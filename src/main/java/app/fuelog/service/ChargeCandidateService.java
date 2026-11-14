package app.fuelog.service;

import app.fuelog.dto.ChargeCandidateDtos.*;
import app.fuelog.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

@Service
public class ChargeCandidateService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final InstallationAuth auth;
    private final InstallationAccess access;
    private final PairingRepository pairing;
    private final VehicleHomeRepository home;
    private final TripUserCandidateRepository members;
    private final ChargingEstimateService estimates;
    private final String coordinateSystem;
    public ChargeCandidateService(JdbcTemplate db,ObjectMapper json,InstallationAuth auth,
            InstallationAccess access,PairingRepository pairing,VehicleHomeRepository home,
            TripUserCandidateRepository members,ChargingEstimateService estimates,
            @Value("${fuelog.charging.pleos-coordinate-system:}") String coordinateSystem) {
        this.db=db;this.json=json;this.auth=auth;this.access=access;this.pairing=pairing;
        this.home=home;this.members=members;this.estimates=estimates;this.coordinateSystem=coordinateSystem;
    }
    @Transactional
    public View sync(String bearer,Sync b) {
        UUID installation=installation(bearer);
        var interval=home.intervalByTripId(installation,b.intervalId());
        if(interval==null || !interval.vehicleId().equals(pairing.linkedVehicle(installation))
                || !b.owner().equals(interval.userId()) || !members.memberForUpdate(b.owner(),interval.vehicleId())) fail(403,"CHARGE_OWNER_MISMATCH");
        if(b.startAt()>b.endAt() || b.startAt()<interval.startedAt().toEpochMilli()
                || b.endAt()>System.currentTimeMillis()+60000
                || interval.endedAt()!=null && b.endAt()>interval.endedAt().toEpochMilli()) fail(400,"INVALID_CHARGE_TIME");
        Row row=find(b.id());
        if(row!=null && (!row.installation.equals(installation) || !row.view.owner().equals(b.owner())
                || !row.view.intervalId().equals(b.intervalId()))) fail(403,"CHARGE_SCOPE_MISMATCH");
        if(row==null) {
            View v=new View(b.id(),b.intervalId(),b.owner(),"CANDIDATE",1,b.startAt(),b.endAt(),
                    b.estimatedKwh(),b.basis(),"LOCATION_UNAVAILABLE",List.of(),null,null,null);
            db.update("INSERT INTO charge_candidates(id,installation_id,vehicle_id,owner_id,interval_id,payload,detected_at,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                b.id().toString(),installation.toString(),interval.vehicleId().toString(),b.owner().toString(),interval.id().toString(),json.writeValueAsString(v),b.endAt());
            return v;
        }
        View p=row.view;
        if(!p.status().equals("CANDIDATE") || b.endAt()<p.endAt()) return p;
        if(b.startAt()!=p.startAt() || !b.basis().equals(p.basis()) || b.estimatedKwh().compareTo(p.estimatedKwh())<0
                || b.endAt()==p.endAt() && b.estimatedKwh().compareTo(p.estimatedKwh())!=0) fail(409,"CHARGE_EVIDENCE_CONFLICT");
        if(b.endAt()==p.endAt()) return p;
        return save(new View(p.id(),p.intervalId(),p.owner(),p.status(),p.revision()+1,p.startAt(),b.endAt(),b.estimatedKwh(),p.basis(),p.estimateStatus(),
            ChargingEstimateService.reprice(p.scenarios(),b.estimatedKwh()),p.correctedKwh(),p.correctedAmount(),p.correctedPlace()));
    }
    @Transactional
    public TripReference tripReference(String bearer, UUID tripId, ReferenceLocation b) {
        UUID installation = installation(bearer);
        var interval = home.intervalByTripId(installation, tripId);
        UUID user = access.currentUser(installation);
        if (interval == null || !interval.vehicleId().equals(pairing.linkedVehicle(installation))
                || user == null || !user.equals(interval.userId())) fail(403, "TRIP_REFERENCE_ACCESS_DENIED");
        Instant now = Instant.now();
        if (!Double.isFinite(b.latitude()) || !Double.isFinite(b.longitude()) || Math.abs(b.latitude()) > 90 || Math.abs(b.longitude()) > 180
                || b.measuredAt().isAfter(b.receivedAt()) || b.receivedAt().isAfter(now.plusSeconds(5))
                || b.measuredAt().isBefore(now.minusSeconds(30))
                || b.receivedAt().isAfter(b.measuredAt().plusSeconds(10))) fail(400, "INVALID_LOCATION_EVIDENCE");
        var scenarios = "WGS84".equals(coordinateSystem)
                ? estimates.estimate(b.latitude(), b.longitude(), java.math.BigDecimal.ONE, now) : List.<Scenario>of();
        return new TripReference(tripId, user, now, !"WGS84".equals(coordinateSystem) ? "COORDINATE_SYSTEM_UNVERIFIED"
                : scenarios.isEmpty() ? "INSUFFICIENT_COMPARABLE_STATIONS" : "CURRENT_LOCATION_REFERENCE", scenarios.stream().map(s -> new ReferenceRate(s.powerBand(), s.basis(), s.averageWonPerKwh(), s.stations())).toList());
    }

    @Transactional
    public View detailDevice(String bearer,UUID id) { return device(bearer,id).view; }
    @Transactional
    public View estimate(String bearer,UUID id,Location b) {
        Row row=device(bearer,id); View p=row.view;
        if(!id.equals(b.candidateId()) || !b.locationOperationId().equals("charge-estimate:"+id)) fail(400,"INVALID_LOCATION_OPERATION");
        if(!p.status().equals("CANDIDATE") || row.operation!=null) return p;
        Instant now=Instant.now();
        if(!Double.isFinite(b.latitude()) || !Double.isFinite(b.longitude()) || Math.abs(b.latitude())>90 || Math.abs(b.longitude())>180
                || b.measuredAt().toEpochMilli()<row.detectedAt || b.measuredAt().toEpochMilli()>row.detectedAt+10000
                || b.receivedAt().isBefore(b.measuredAt()) || b.receivedAt().toEpochMilli()>row.detectedAt+10000
                || b.receivedAt().isAfter(now.plusSeconds(5)) || b.receivedAt().isBefore(now.minusSeconds(120))) fail(400,"INVALID_LOCATION_EVIDENCE");
        var slow="WGS84".equals(coordinateSystem) ? estimates.slowTripEstimate(b.latitude(),b.longitude(),p.estimatedKwh(),Instant.ofEpochMilli(p.startAt()),now) : null;
        var scenarios=slow==null ? List.<Scenario>of() : List.of(slow);
        String status=!"WGS84".equals(coordinateSystem) ? "COORDINATE_SYSTEM_UNVERIFIED" : scenarios.isEmpty() ? "INSUFFICIENT_COMPARABLE_STATIONS" : scenarios.size()==1 ? "REFERENCE_ESTIMATE" : "POWER_BAND_SELECTION_REQUIRED";
        View result=save(new View(p.id(),p.intervalId(),p.owner(),p.status(),p.revision()+1,p.startAt(),p.endAt(),p.estimatedKwh(),p.basis(),status,scenarios,p.correctedKwh(),p.correctedAmount(),p.correctedPlace()));
        // Coordinates are consumed in this transaction and are never persisted or logged.
        db.update("UPDATE charge_candidates SET location_operation_id=? WHERE id=?",b.locationOperationId(),id.toString());
        return result;
    }
    @Transactional
    public View detail(UUID user,UUID id) { return mobile(user,id).view; }
    @Transactional(readOnly=true)
    public List<View> list(UUID user) {
        return db.query("SELECT c.payload FROM charge_candidates c JOIN group_members m ON m.vehicle_id=c.vehicle_id AND m.user_id=c.owner_id WHERE c.owner_id=? ORDER BY c.created_at DESC,c.id LIMIT 100",
                (r,n)->json.readValue(r.getString(1),View.class),user.toString());
    }
    @Transactional
    public View patch(UUID user,UUID id,Patch b) {
        Row row=mobile(user,id); View p=row.view;
        String request=json.writeValueAsString(b);
        var old=db.query("SELECT request_payload,response_payload FROM charge_candidate_operations WHERE candidate_id=? AND operation_id=?",(r,n)->new String[]{r.getString(1),r.getString(2)},id.toString(),b.operationId());
        if(!old.isEmpty()) {
            if(!old.getFirst()[0].equals(request)) fail(409,"OPERATION_ID_REUSED");
            return json.readValue(old.getFirst()[1],View.class);
        }
        if(p.revision()!=b.expectedRevision()) fail(409,"REVISION_CONFLICT");
        View result=save(new View(p.id(),p.intervalId(),p.owner(),b.action().equals("CONFIRM")?"CONFIRMED":"REJECTED",p.revision()+1,p.startAt(),p.endAt(),p.estimatedKwh(),p.basis(),p.estimateStatus(),p.scenarios(),
                b.kwh()==null?p.correctedKwh():b.kwh(),b.amount()==null?p.correctedAmount():b.amount(),b.place()==null?p.correctedPlace():b.place()));
        db.update("INSERT INTO charge_candidate_operations VALUES(?,?,?,?)",id.toString(),b.operationId(),request,json.writeValueAsString(result));
        return result;
    }
    private Row mobile(UUID user,UUID id) {
        var ids=db.query("SELECT installation_id FROM charge_candidates WHERE id=?",(r,n)->UUID.fromString(r.getString(1)),id.toString());
        if(ids.isEmpty()) { fail(404,"CHARGE_NOT_FOUND"); }
        pairing.lockInstallation(ids.getFirst()); Row row=require(id);
        if(!row.view.owner().equals(user) || !members.memberForUpdate(user,row.vehicle)) fail(403,"CHARGE_ACCESS_DENIED");
        return row;
    }
    private Row device(String bearer,UUID id) {
        UUID installation=installation(bearer);Row row=require(id);
        if(!row.installation.equals(installation) || !row.vehicle.equals(pairing.linkedVehicle(installation))
                || !row.view.owner().equals(access.currentUser(installation))) fail(403,"CHARGE_ACCESS_DENIED");
        return row;
    }
    private UUID installation(String bearer) { UUID id=auth.require(bearer).id();pairing.lockInstallation(id);access.requireCollection(id);return id; }
    private View save(View v) { db.update("UPDATE charge_candidates SET payload=? WHERE id=?",json.writeValueAsString(v),v.id().toString());return v; }
    private Row require(UUID id) { Row row=find(id);if(row==null) fail(404,"CHARGE_NOT_FOUND");return row; }
    private Row find(UUID id) {
        var rows=db.query("SELECT * FROM charge_candidates WHERE id=? FOR UPDATE",(r,n)->new Row(UUID.fromString(r.getString("installation_id")),UUID.fromString(r.getString("vehicle_id")),json.readValue(r.getString("payload"),View.class),r.getString("location_operation_id"),r.getLong("detected_at")),id.toString());
        return rows.isEmpty()?null:rows.getFirst();
    }
    private record Row(UUID installation,UUID vehicle,View view,String operation,long detectedAt) {}
    private static void fail(int status,String code) { throw new ResponseStatusException(HttpStatus.valueOf(status),code); }
}
