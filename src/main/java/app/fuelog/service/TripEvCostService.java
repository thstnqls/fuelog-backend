package app.fuelog.service;

import app.fuelog.dto.TripSummaryDtos.*;
import app.fuelog.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.math.*;
import java.util.*;

/** A successful estimate is an immutable snapshot; coordinates are consumed without persistence. */
@Service
public class TripEvCostService {
    private final InstallationAuth auth; private final InstallationAccess access;
    private final PairingRepository pairing; private final VehicleHomeRepository home;
    private final TripSummaryRepository trips; private final ChargingEstimateService prices;
    private final JdbcTemplate db; private final ObjectMapper json; private final String coordinates;
    public TripEvCostService(InstallationAuth auth,InstallationAccess access,PairingRepository pairing,
            VehicleHomeRepository home,TripSummaryRepository trips,ChargingEstimateService prices,JdbcTemplate db,
            ObjectMapper json,@Value("${fuelog.charging.pleos-coordinate-system:}") String coordinates) {
        this.auth=auth;this.access=access;this.pairing=pairing;this.home=home;this.trips=trips;
        this.prices=prices;this.db=db;this.json=json;this.coordinates=coordinates;
    }
    @Transactional
    public EvCostResult get(String bearer,UUID trip) {
        var row=authorized(bearer,trip);
        SavedEvCost cost=preserveExisting(row);
        return new EvCostResult(trip,row.user(),cost==null?"LOCATION_REQUIRED":"SAVED",cost);
    }
    @Transactional
    public EvCostResult calculate(String bearer,UUID trip,EvCostLocation b) {
        var row=authorized(bearer,trip);
        SavedEvCost cost=preserveExisting(row);
        if(cost!=null) return new EvCostResult(trip,row.user(),"SAVED",cost);
        Upload upload=json.readValue(row.payload(),Upload.class);
        var energy=TripSummaryService.calculate(upload,row.syncedAt()).energyConsumedKwh().value();
        if(energy==null || energy.signum()<=0) return new EvCostResult(trip,row.user(),"NO_VALID_CONSUMPTION",null);
        Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        if(b==null || b.latitude()==null || b.longitude()==null || !Double.isFinite(b.latitude()) || !Double.isFinite(b.longitude())
                || Math.abs(b.latitude())>90 || Math.abs(b.longitude())>180 || b.measuredAt()==null || b.receivedAt()==null
                || !"PLEOS_CURRENT_UPDATE".equals(b.source()) || (b.locationBasis()==null || !Set.of("TRIP_END","CONFIRMATION").contains(b.locationBasis()))
                || b.measuredAt().isBefore(now.minusSeconds(30)) || b.measuredAt().isAfter(b.receivedAt())
                || b.receivedAt().isAfter(now.plusSeconds(5)) || b.receivedAt().isAfter(b.measuredAt().plusSeconds(10))) fail(400,"INVALID_LOCATION_EVIDENCE");
        if("TRIP_END".equals(b.locationBasis()) && (b.measuredAt().isBefore(upload.endedAt())
                || b.measuredAt().isAfter(upload.endedAt().plusSeconds(10)))) fail(400,"INVALID_TRIP_END_LOCATION");
        if(!"WGS84".equals(coordinates)) return new EvCostResult(trip,row.user(),"COORDINATE_SYSTEM_UNVERIFIED",null);
        var scenario=prices.slowTripEstimate(b.latitude(),b.longitude(),energy,upload.endedAt(),now);
        if(scenario==null) return new EvCostResult(trip,row.user(),"INSUFFICIENT_HISTORICAL_SLOW_RATES",null);
        int sourceCount=scenario.stations().size();
        cost=new SavedEvCost(scenario.estimatedAmount(),"KRW",scenario.averageWonPerKwh(),energy,
                upload.endedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate(),now,"SLOW_OPERATOR_MEAN",sourceCount,b.locationBasis());
        insert(row.id(),cost,json.writeValueAsString(scenario.stations()));
        return new EvCostResult(trip,row.user(),"SAVED",cost);
    }
    private TripSummaryRepository.Trip authorized(String bearer,UUID trip) {
        UUID installation=auth.require(bearer).id();pairing.lockInstallation(installation);access.requireCollection(installation);
        var interval=home.intervalByTripId(installation,trip);
        var row=trips.trip(installation,interval==null?trip:interval.id());
        if(row==null) row=trips.trip(installation,trip);
        if(row==null) {fail(404,"TRIP_NOT_SAVED");}
        UUID user=access.currentUser(installation);
        if(user==null || !user.equals(row.user()) || !row.vehicle().equals(pairing.linkedVehicle(installation))) fail(403,"TRIP_COST_ACCESS_DENIED");
        return row;
    }
    public SavedEvCost stored(long summaryId) {
        var rows=db.query("SELECT payload FROM trip_ev_costs WHERE summary_id=?",(r,n)->json.readValue(r.getString(1),SavedEvCost.class),summaryId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public SavedEvCost preserveExisting(TripSummaryRepository.Trip row) {
        SavedEvCost saved=stored(row.id());if(saved!=null) return saved;
        if(!legacyEligible(row.id())) return null;
        Upload upload=json.readValue(row.payload(),Upload.class);
        var summary=TripSummaryService.calculate(upload,row.syncedAt());
        if(upload.ev()!=null && upload.ev().detected() && summary.estimatedCost().value()!=null) {
            saved=new SavedEvCost(summary.estimatedCost().value(),summary.estimatedCost().currency(),upload.unitPrice(),
                    summary.energyConsumedKwh().value(),upload.endedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate(),
                    row.syncedAt(),"LEGACY_SAVED_ESTIMATE",0,"LEGACY");
            insert(row.id(),saved,"[]");
        }
        db.update("UPDATE trip_summaries SET legacy_ev_cost_eligible=FALSE WHERE id=?",row.id());
        return saved;
    }
    public boolean legacyEligible(long id) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT legacy_ev_cost_eligible FROM trip_summaries WHERE id=?",Boolean.class,id));
    }
    private void insert(long id,SavedEvCost cost,String evidence) {
        db.update("INSERT INTO trip_ev_costs(summary_id,payload,price_evidence,calculated_at) VALUES(?,?,?,?)",
                id,json.writeValueAsString(cost),evidence,LocalDateTime.ofInstant(cost.calculatedAt(),ZoneOffset.UTC));
    }
    private static void fail(int status,String code) {throw new ResponseStatusException(HttpStatus.valueOf(status),code);}
}
