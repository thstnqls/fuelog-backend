package app.fuelog.service;

import app.fuelog.dto.TripSummaryDtos.*;
import app.fuelog.repository.*;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/** 오프라인 운행 요약을 중복 없이 저장하고 계산 결과를 소유자의 확인함에 전달한다. */
@Service
public class TripSummaryService {
    private final InstallationAccess access;
    private final InstallationAuth auth;
    private final PairingRepository pairing;
    private final VehicleHomeRepository home;
    private final TripUserCandidateRepository candidates;
    private final TripSummaryRepository repository;
    private final ObjectMapper json;
    private final JdbcTemplate db;

    public TripSummaryService(
            InstallationAuth auth,
            PairingRepository pairing,
            VehicleHomeRepository home,
            TripUserCandidateRepository candidates,
            TripSummaryRepository repository,
            ObjectMapper json,
            JdbcTemplate db,
            InstallationAccess access) {
        this.access = access;
        this.auth = auth;
        this.pairing = pairing;
        this.home = home;
        this.candidates = candidates;
        this.repository = repository;
        this.json = json;
        this.db = db;
    }

    @Transactional
    public Synced upload(String bearer, UUID tripId, Upload input) {
        UUID installation = installation(bearer);
        UUID vehicle = pairing.linkedVehicle(installation);
        if (vehicle == null) fail(HttpStatus.CONFLICT, "INSTALLATION_NOT_LINKED");
        Upload body = canonical(input);
        if (!tripId.equals(body.tripId())) fail(HttpStatus.CONFLICT, "TRIP_ID_MISMATCH");
        validate(body);
        var interval = home.intervalByTripId(installation, tripId);
        if (interval != null) {
            if (!vehicle.equals(interval.vehicleId()))
                fail(HttpStatus.FORBIDDEN, "VEHICLE_MISMATCH");
            if (body.startedAt().isBefore(interval.startedAt())
                    || body.userConsentId() == null
                            && !interval.startedAt().equals(body.startedAt())
                    || interval.endedAt() != null && !interval.endedAt().equals(body.endedAt())
                    || interval.lastSampleAt() != null
                            && body.endedAt().isBefore(interval.lastSampleAt()))
                fail(HttpStatus.CONFLICT, "INTERVAL_BOUNDARY_MISMATCH");
            Integer outside =
                    db.queryForObject(
                            "SELECT COUNT(*) FROM recording_samples WHERE interval_id=? AND"
                                    + " (observed_at<? OR observed_at>?)",
                            Integer.class,
                            interval.id().toString(),
                            java.time.LocalDateTime.ofInstant(
                                    body.startedAt(), java.time.ZoneOffset.UTC),
                            java.time.LocalDateTime.ofInstant(
                                    body.endedAt(), java.time.ZoneOffset.UTC));
            if (outside != null && outside > 0)
                fail(HttpStatus.CONFLICT, "INTERVAL_BOUNDARY_MISMATCH");
            Integer missingSamples =
                    db.queryForObject(
                            "SELECT COUNT(*) FROM recording_samples WHERE interval_id=? AND"
                                    + " missing_reason IS NOT NULL",
                            Integer.class,
                            interval.id().toString());
            if (missingSamples != null && missingSamples > 0 && !body.missing())
                fail(HttpStatus.CONFLICT, "RECORDING_QUALITY_MISMATCH");
        }
        UUID user = null;
        if (body.userConsentId() != null) {
            if (interval == null) fail(HttpStatus.FORBIDDEN, "CONSENT_INTERVAL_REQUIRED");
            var candidate =
                    proof(
                            body.userConsentId(),
                            installation,
                            vehicle,
                            interval.id(),
                            body.startedAt());
            user = candidate.userId();
            if (body.ownerChanged() || interval.userId() != null && !interval.userId().equals(user))
                fail(HttpStatus.FORBIDDEN, "INTERVAL_OWNER_BOUNDARY");
            Integer incompatible =
                    db.queryForObject(
                            "SELECT COUNT(*) FROM recording_samples WHERE interval_id=? AND"
                                    + " (COALESCE(user_id,?) IS NULL OR COALESCE(user_id,?)<>?)",
                            Integer.class,
                            interval.id().toString(),
                            string(interval.userId()),
                            string(interval.userId()),
                            user.toString());
            if (incompatible != null && incompatible > 0)
                fail(HttpStatus.FORBIDDEN, "INTERVAL_OWNER_BOUNDARY");
        } else if (interval != null && interval.userId() != null) {
            // The server-fixed interval owner also covers records queued before a later user switch.
            user = interval.userId();
            if (!candidates.memberForUpdate(user, vehicle))
                fail(HttpStatus.FORBIDDEN, "VEHICLE_MEMBERSHIP_REQUIRED");
        }

        var previous = repository.trip(installation, tripId);
        String payload = json.writeValueAsString(body);
        byte[] hash = InstallationAuth.sha256(payload.getBytes(StandardCharsets.UTF_8));
        if (previous != null) {
            if (!Objects.equals(previous.user(), user)
                    || !Objects.equals(previous.consent(), body.userConsentId())
                    || !previous.vehicle().equals(vehicle))
                fail(HttpStatus.CONFLICT, "TRIP_OWNER_IMMUTABLE");
            Upload prior = parse(previous.payload());
            if (!prior.startedAt().equals(body.startedAt())
                    || !prior.endedAt().equals(body.endedAt()))
                fail(HttpStatus.CONFLICT, "TRIP_BOUNDARY_IMMUTABLE");
            if (body.revision() < previous.revision()) fail(HttpStatus.CONFLICT, "STALE_REVISION");
            if (body.revision() == previous.revision()) {
                if (!MessageDigest.isEqual(hash, previous.hash()))
                    fail(HttpStatus.CONFLICT, "REVISION_CONFLICT");
                return new Synced(tripId, previous.revision(), previous.syncedAt());
            }
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (interval != null && "recording".equals(interval.status()))
            home.finishInterval(
                    interval.id(),
                    "c04:" + tripId,
                    body.endedAt(),
                    interval.lastAcceptedSequence(),
                    now);
        repository.save(
                installation,
                tripId,
                vehicle,
                user,
                body.userConsentId(),
                body.revision(),
                hash,
                payload,
                now,
                previous == null);
        return new Synced(tripId, body.revision(), now);
    }

    @Transactional
    public Summary summary(String bearer, UUID trip, UUID consent) {
        var row = requireTrip(installation(bearer), trip);
        authorize(row, consent);
        return calculate(parse(row.payload()), row.syncedAt());
    }

    @Transactional
    public Delivered deliver(String bearer, UUID tripId, UUID consent, Deliver body) {
        var row = requireTrip(installation(bearer), tripId);
        if (row.user() == null) fail(HttpStatus.CONFLICT, "TRIP_UNASSIGNED");
        authorize(row, consent);
        if (!body.operationId().equals("mobile-inbox:" + tripId))
            fail(HttpStatus.CONFLICT, "OPERATION_ID_MISMATCH");
        if (body.tripRevision() != row.revision())
            fail(HttpStatus.CONFLICT, "TRIP_REVISION_MISMATCH");
        var prior = repository.item(row.user(), tripId);
        if (prior != null && prior.summaryId() != row.id())
            fail(HttpStatus.CONFLICT, "TRIP_ID_COLLISION");
        if (prior == null || prior.revision() < row.revision()) {
            repository.deliver(
                    row,
                    json.writeValueAsString(calculate(parse(row.payload()), row.syncedAt())),
                    Instant.now().truncatedTo(ChronoUnit.MICROS),
                    prior);
            prior = repository.item(row.user(), tripId);
        }
        return new Delivered(prior.id(), tripId, prior.deliveredAt());
    }

    @Transactional(readOnly = true)
    public Inbox inbox(UUID user, long cursor) {
        if (cursor < 0) fail(HttpStatus.BAD_REQUEST, "INVALID_CURSOR");
        var rows = repository.inbox(user, cursor);
        boolean more = rows.size() > 50;
        var page =
                rows.stream()
                        .limit(50)
                        .map(
                                r ->
                                        new InboxItem(
                                                r.id(),
                                                r.trip(),
                                                r.revision(),
                                                r.deliveredAt(),
                                                r.readAt(),
                                                r.snapshot() == null
                                                        ? null
                                                        : json.readValue(
                                                                r.snapshot(), Summary.class),
                                                r.kind(),
                                                r.candidateId(),
                                                r.candidateRevision()))
                        .toList();
        return new Inbox(page, more ? page.getLast().inboxItemId() : null);
    }

    @Transactional
    public ReadResult read(UUID user, long itemId, Read body) {
        var row = repository.readable(user, itemId);
        if (row == null) fail(HttpStatus.FORBIDDEN, "INBOX_ACCESS_DENIED");
        if (body.readAt().isAfter(Instant.now().plusSeconds(60))
                || body.readAt().isBefore(row.deliveredAt()))
            fail(HttpStatus.BAD_REQUEST, "INVALID_READ_TIME");
        Instant at =
                row.readAt() == null ? body.readAt().truncatedTo(ChronoUnit.MICROS) : row.readAt();
        repository.read(itemId, at);
        return new ReadResult(itemId, at);
    }

    private UUID installation(String bearer) {
        UUID id = auth.require(bearer).id();
        pairing.lockInstallation(id);
        access.requireCollection(id);
        return id;
    }

    private TripSummaryRepository.Trip requireTrip(UUID installation, UUID trip) {
        var row = repository.trip(installation, trip);
        if (row == null) fail(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND");
        if (!row.vehicle().equals(pairing.linkedVehicle(installation)))
            fail(HttpStatus.FORBIDDEN, "VEHICLE_MISMATCH");
        return row;
    }

    private void authorize(TripSummaryRepository.Trip row, UUID consent) {
        if (row.user() == null) return;
        if (!Objects.equals(row.consent(), consent))
            fail(HttpStatus.FORBIDDEN, "USER_PROOF_REQUIRED");
        if (consent == null) {
            if (!row.user().equals(access.currentUser(row.installation())))
                fail(HttpStatus.FORBIDDEN, "AUTHENTICATED_USER_CHANGED");
            return;
        }
        var candidate = candidates.get(consent, true);
        proof(
                consent,
                row.installation(),
                row.vehicle(),
                candidate.intervalId(),
                parse(row.payload()).startedAt());
    }

    private TripUserCandidateRepository.Candidate proof(
            UUID consent, UUID installation, UUID vehicle, UUID interval, Instant started) {
        var c = candidates.get(consent, true);
        if (!c.installationId().equals(installation)
                || !c.vehicleId().equals(vehicle)
                || !c.intervalId().equals(interval))
            fail(HttpStatus.FORBIDDEN, "CONSENT_SCOPE_MISMATCH");
        if (!"verified".equals(c.status())
                || c.verifiedOperationId() == null
                || c.verificationExpiresAt() == null)
            fail(HttpStatus.FORBIDDEN, "CONSENT_NOT_VERIFIED");
        // 오프라인 기록은 동의 만료 후에도 허용하되 시작 시각이 당시 확인 권한의 유효 구간 안이어야 한다.
        if (!started.isBefore(c.verificationExpiresAt()))
            fail(HttpStatus.GONE, "CONSENT_EXPIRED_FOR_TRIP");
        var verifiedAt =
                db.queryForObject(
                        "SELECT updated_at FROM trip_user_candidates WHERE id=?",
                        java.time.LocalDateTime.class,
                        consent.toString());
        if (verifiedAt == null || started.isBefore(verifiedAt.toInstant(java.time.ZoneOffset.UTC)))
            fail(HttpStatus.FORBIDDEN, "TRIP_PRECEDES_USER_VERIFICATION");
        if (!candidates.memberForUpdate(c.userId(), vehicle))
            fail(HttpStatus.FORBIDDEN, "VEHICLE_MEMBERSHIP_REQUIRED");
        return c;
    }

    private Upload parse(String payload) {
        return json.readValue(payload, Upload.class);
    }

    private static String string(UUID id) {
        return id == null ? null : id.toString();
    }

    private static BigDecimal number(BigDecimal n) {
        return n == null ? null : n.stripTrailingZeros();
    }

    private static Upload canonical(Upload b) {
        return new Upload(
                b.tripId(),
                b.revision(),
                b.source(),
                b.userConsentId(),
                b.startedAt(),
                b.endedAt(),
                b.endEstimated(),
                number(b.distanceKm()),
                b.distanceValid(),
                number(b.startFuelLiters()),
                number(b.endFuelLiters()),
                b.fuelEndpointsValid(),
                b.missing(),
                b.refueled(),
                b.ownerChanged(),
                number(b.unitPrice()),
                b.currency(),
                b.priceSource(),
                b.priceObservedAt(),
                b.priceValid());
    }

    private static void validate(Upload b) {
        if (b.endedAt().isBefore(b.startedAt())
                || b.endedAt().isAfter(Instant.now().plusSeconds(60))
                || b.startedAt().getNano() % 1000 != 0
                || b.endedAt().getNano() % 1000 != 0)
            fail(HttpStatus.BAD_REQUEST, "INVALID_TRIP_TIME");
        if (b.currency() != null) {
            try {
                Currency.getInstance(b.currency());
            } catch (IllegalArgumentException ex) {
                fail(HttpStatus.BAD_REQUEST, "INVALID_CURRENCY");
            }
        }
    }

    /** 누락·주유·사용자 변경이 있으면 연비·비용을 추측하지 않고 계산 불가 사유를 반환한다. */
    public static Summary calculate(Upload b, Instant syncedAt) {
        boolean distanceOk =
                b.distanceValid() && b.distanceKm() != null && b.distanceKm().signum() >= 0;
        Metric distance =
                new Metric(
                        distanceOk ? b.distanceKm() : null,
                        distanceOk ? "available" : "unavailable",
                        distanceOk
                                ? (b.missing() || b.endEstimated() ? "partial_recording" : null)
                                : "invalid_or_missing_distance");
        String reason =
                b.ownerChanged()
                        ? "owner_changed"
                        : b.refueled()
                                ? "refueled"
                                : b.missing() || b.endEstimated()
                                        ? "missing_recording"
                                        : !b.fuelEndpointsValid()
                                                        || b.startFuelLiters() == null
                                                        || b.endFuelLiters() == null
                                                ? "invalid_or_missing_fuel_endpoints"
                                                : null;
        BigDecimal consumed =
                reason == null ? b.startFuelLiters().subtract(b.endFuelLiters()) : null;
        if (consumed != null && consumed.signum() <= 0) {
            reason = "non_positive_consumption";
            consumed = null;
        }
        Metric fuel = new Metric(consumed, consumed == null ? "unavailable" : "available", reason);
        BigDecimal efficiency =
                consumed != null && distanceOk
                        ? b.distanceKm().divide(consumed, 3, RoundingMode.HALF_UP)
                        : null;
        Metric economy =
                new Metric(
                        efficiency,
                        efficiency == null ? "unavailable" : "available",
                        efficiency == null
                                ? (reason == null ? "invalid_or_missing_distance" : reason)
                                : null);
        String costReason = reason;
        if (costReason == null && !distanceOk) costReason = "invalid_or_missing_distance";
        if (costReason == null
                && (!b.priceValid()
                        || b.unitPrice() == null
                        || b.unitPrice().signum() <= 0
                        || b.currency() == null
                        || b.priceSource() == null
                        || b.priceSource().isBlank()
                        || b.priceObservedAt() == null
                        || b.priceObservedAt().isAfter(b.endedAt())))
            costReason = "invalid_or_missing_price";
        BigDecimal cost =
                costReason == null
                        ? consumed.multiply(b.unitPrice()).setScale(2, RoundingMode.HALF_UP)
                        : null;
        return new Summary(
                b.tripId(),
                b.revision(),
                syncedAt,
                b.startedAt(),
                b.endedAt(),
                distance,
                fuel,
                economy,
                new Money(
                        cost, b.currency(), cost == null ? "unavailable" : "available", costReason),
                b.priceSource(),
                b.priceObservedAt(),
                new Quality(
                        b.missing() || b.endEstimated(),
                        b.endEstimated(),
                        b.refueled(),
                        b.ownerChanged(),
                        "client_reported"));
    }

    private static void fail(HttpStatus status, String code) {
        throw new ResponseStatusException(status, code);
    }
}
