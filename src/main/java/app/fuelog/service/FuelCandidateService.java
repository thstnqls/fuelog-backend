package app.fuelog.service;

import app.fuelog.dto.FuelCandidateDtos.*;
import app.fuelog.repository.*;
import app.fuelog.repository.FuelCandidateRepository.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class FuelCandidateService {
    private final InstallationAccess access;
    private final InstallationAuth auth;
    private final PairingRepository pairing;
    private final VehicleHomeRepository home;
    private final TripUserCandidateRepository consents;
    private final FuelCandidateRepository repository;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public FuelCandidateService(InstallationAuth auth, PairingRepository pairing, VehicleHomeRepository home,
            TripUserCandidateRepository consents, FuelCandidateRepository repository, JdbcTemplate db, ObjectMapper json, InstallationAccess access) {
        this.access=access;
        this.auth=auth; this.pairing=pairing; this.home=home; this.consents=consents;
        this.repository=repository; this.db=db; this.json=json;
    }

    @Transactional
    public View sync(String bearer, Sync b) {
        UUID installation = installation(bearer);
        var interval = home.intervalByTripId(installation, b.intervalId());
        if (interval == null) fail(HttpStatus.FORBIDDEN, "INTERVAL_SCOPE_MISMATCH");
        vehicleAccess(installation, interval, b.owner(), b.startAt(), b.endAt());
        Row row = repository.get(b.id());
        if (row != null && (!row.installation().equals(installation) || !row.interval().equals(interval.id())
                || !row.view().owner().equals(b.owner()) || !row.vehicle().equals(interval.vehicleId())))
            fail(HttpStatus.FORBIDDEN, "CANDIDATE_SCOPE_MISMATCH");
        if (row == null) repository.insert(installation, interval.vehicleId(), interval.id(), b,
                estimate(interval.vehicleId(), b.increasePercent(), b.endAt()));
        else {
            View prior = row.view();
            boolean newer = b.endAt() > prior.endAt();
            // Equal timestamps are retries; only a later observation may replace detection evidence.
            BigDecimal increase = newer ? b.increasePercent() : prior.increasePercent();
            long end = newer ? b.endAt() : prior.endAt();
            Estimate e = estimate(interval.vehicleId(), increase, end);
            if (newer || prior.capacityVerified() != e.verified() || !equal(prior.estimatedLiters(),e.liters())
                    || !equal(prior.estimatedAmount(),e.amount()) || !Objects.equals(prior.priceAt(),e.priceAt()))
                repository.evidence(b.id(), newer ? b.startAt() : prior.startAt(), end, increase, e);
        }
        row = repository.get(b.id());
        if (b.queued()) repository.deliver(row);
        return row.view();
    }

    @Transactional
    public View detail(UUID user, UUID id) { return mobileAccess(user, id).view(); }

    @Transactional
    public View patch(UUID user, UUID id, Patch b) {
        Row row = mobileAccess(user, id);
        String request = json.writeValueAsString(b);
        String[] prior = repository.operation(id, b.operationId());
        if (prior != null) {
            if (!prior[0].equals(user.toString()) || !prior[1].equals(request)) fail(HttpStatus.CONFLICT,"OPERATION_ID_REUSED");
            return json.readValue(prior[2], View.class);
        }
        if (row.view().revision() != b.expectedRevision()) fail(HttpStatus.CONFLICT,"REVISION_CONFLICT");
        repository.patch(id,b);
        View result = repository.get(id).view();
        repository.audit(user,id,b,request,json.writeValueAsString(result));
        return result;
    }

    @Transactional
    public View stationSearch(String bearer, UUID id, StationSearch b) {
        UUID installation = installation(bearer);
        Row row = require(id);
        if (!row.installation().equals(installation)) fail(HttpStatus.FORBIDDEN,"CANDIDATE_SCOPE_MISMATCH");
        vehicleAccess(installation,home.interval(row.interval(),false),row.view().owner(),row.view().startAt(),row.view().endAt());
        // No location or Opinet provider is configured. Do not infer a station, or alter evidence/estimates.
        // Even a granted location is usable only with complete coordinates and a recent timestamp.
        if (!b.locationPermission() || b.latitude()==null || b.longitude()==null || b.observedAt()==null
                || b.observedAt() > System.currentTimeMillis() || b.observedAt() < System.currentTimeMillis()-60_000)
            return row.view();
        return row.view();
    }

    private void vehicleAccess(UUID installation, VehicleHomeRepository.Interval interval, UUID owner, long start, long end) {
        if (!interval.installationId().equals(installation) || !interval.vehicleId().equals(pairing.linkedVehicle(installation)))
            fail(HttpStatus.FORBIDDEN,"INTERVAL_SCOPE_MISMATCH");
        var latest = home.latestInterval(installation, interval.vehicleId());
        if (latest == null || !latest.id().equals(interval.id())) fail(HttpStatus.FORBIDDEN,"CURRENT_INTERVAL_REQUIRED");
        if (start > end || end > System.currentTimeMillis()+60_000 || start < interval.startedAt().toEpochMilli()
                || interval.endedAt()!=null && end > interval.endedAt().toEpochMilli()) fail(HttpStatus.BAD_REQUEST,"INVALID_CANDIDATE_TIME");
        var grants = db.query("SELECT id,updated_at FROM trip_user_candidates WHERE installation_id=? AND verified_operation_id IS NOT NULL ORDER BY updated_at DESC,id DESC LIMIT 1 FOR UPDATE",
                (r,n) -> new Object[]{UUID.fromString(r.getString(1)),r.getObject(2,LocalDateTime.class).toInstant(ZoneOffset.UTC)}, installation.toString());
        if (grants.isEmpty()) fail(HttpStatus.FORBIDDEN,"VERIFIED_USER_REQUIRED");
        var grant = consents.get((UUID)grants.getFirst()[0],true);
        if (!"verified".equals(grant.status())) fail(HttpStatus.FORBIDDEN,"VERIFIED_USER_REQUIRED");
        if (!grant.intervalId().equals(interval.id()) || !grant.vehicleId().equals(interval.vehicleId())
                || !grant.userId().equals(owner) || interval.userId()!=null && !interval.userId().equals(owner))
            fail(HttpStatus.FORBIDDEN,"CANDIDATE_OWNER_MISMATCH");
        if (grant.verificationExpiresAt()==null || !Instant.now().isBefore(grant.verificationExpiresAt()))
            fail(HttpStatus.UNAUTHORIZED,"USER_CONSENT_EXPIRED");
        if (start < ((Instant)grants.getFirst()[1]).toEpochMilli()) fail(HttpStatus.FORBIDDEN,"EVIDENCE_PRECEDES_VERIFICATION");
        if (!consents.memberForUpdate(owner,interval.vehicleId())) fail(HttpStatus.FORBIDDEN,"VEHICLE_MEMBERSHIP_REQUIRED");
    }
    private Row mobileAccess(UUID user, UUID id) {
        // Use installation lock first, consistently with vehicle sync and membership changes.
        var installations = db.query("SELECT installation_id FROM fuel_candidates WHERE id=?", (r,n)->UUID.fromString(r.getString(1)),id.toString());
        if (installations.isEmpty()) fail(HttpStatus.NOT_FOUND,"FUEL_CANDIDATE_NOT_FOUND");
        pairing.lockInstallation(installations.getFirst());
        Row row = require(id);
        if (!row.view().owner().equals(user) || !consents.memberForUpdate(user,row.vehicle()))
            fail(HttpStatus.FORBIDDEN,"CANDIDATE_ACCESS_DENIED");
        return row;
    }
    private Estimate estimate(UUID vehicle, BigDecimal percent, long end) {
        var values = db.query("SELECT * FROM vehicle_fuel_metadata WHERE vehicle_id=?", (r,n) -> {
            BigDecimal capacity=r.getBigDecimal("capacity_liters");
            boolean verified=capacity!=null && capacity.signum()>0 && r.getObject("capacity_verified_at")!=null
                    && r.getString("capacity_source")!=null && !r.getString("capacity_source").isBlank();
            if (!verified) return new Estimate(false,null,null,null);
            BigDecimal liters=capacity.multiply(percent).movePointLeft(2).setScale(3,RoundingMode.HALF_UP);
            BigDecimal price=r.getBigDecimal("unit_price");
            Long at=r.getObject("price_at",Long.class), until=r.getObject("price_valid_until",Long.class);
            String source=r.getString("price_source");
            boolean valid=price!=null && price.signum()>0 && at!=null && at>=0 && at<=end && until!=null && until>=end
                    && source!=null && !source.isBlank();
            return new Estimate(true,liters,valid ? liters.multiply(price).setScale(2,RoundingMode.HALF_UP):null,valid?at:null);
        },vehicle.toString());
        return values.isEmpty()?new Estimate(false,null,null,null):values.getFirst();
    }
    private UUID installation(String bearer) { UUID id=auth.require(bearer).id(); pairing.lockInstallation(id); access.requireCollection(id); return id; }
    private Row require(UUID id) { Row row=repository.get(id); if(row==null) fail(HttpStatus.NOT_FOUND,"FUEL_CANDIDATE_NOT_FOUND"); return row; }
    private static boolean equal(BigDecimal a,BigDecimal b) { return a==null?b==null:b!=null && a.compareTo(b)==0; }
    private static void fail(HttpStatus status,String code) { throw new ResponseStatusException(status,code); }
}
