package app.fuelog.repository;

import app.fuelog.dto.MaintenanceDtos.*;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 정비 계획·이력·감사 기록·누적거리를 저장한다. 변경은 차량 잠금 아래 수행한다. */
@Repository
public class MaintenanceRepository {
    private final JdbcTemplate db;

    public MaintenanceRepository(JdbcTemplate db) {
        this.db = db;
    }

    public record PlanRow(
            UUID id,
            UUID vehicle,
            UUID owner,
            String name,
            boolean shared,
            boolean active,
            Basis basis,
            UUID evidence,
            BigDecimal km,
            Integer months,
            long revision,
            LocalDate lastDate,
            BigDecimal lastKm,
            LocalDate dueDate,
            BigDecimal dueKm,
            Instant completedAt,
            Instant deletedAt,
            UUID completionEvent,
            UUID nextPlan) {}

    public record EventRow(
            UUID id,
            UUID vehicle,
            UUID item,
            UUID author,
            String payload,
            long revision,
            String createResponse,
            byte[] createHash,
            Instant createdAt,
            Instant deletedAt) {}

    public record Sample(
            UUID vehicle, BigDecimal km, Instant observedAt, Instant receivedAt, byte[] hash) {}

    private final RowMapper<PlanRow> planMapper =
            (r, n) ->
                    new PlanRow(
                            id(r.getString("id")),
                            id(r.getString("vehicle_id")),
                            id(r.getString("owner_user_id")),
                            r.getString("name"),
                            r.getBoolean("shared"),
                            r.getBoolean("active"),
                            Basis.valueOf(r.getString("basis")),
                            id(r.getString("evidence_id")),
                            r.getBigDecimal("interval_km"),
                            r.getObject("interval_months", Integer.class),
                            r.getLong("revision"),
                            r.getObject("last_service_date", LocalDate.class),
                            r.getBigDecimal("last_service_km"),
                            r.getObject("next_due_date", LocalDate.class),
                            r.getBigDecimal("next_due_km"),
                            instant(r.getObject("completed_at", LocalDateTime.class)),
                            instant(r.getObject("deleted_at", LocalDateTime.class)),
                            id(r.getString("completion_event_id")),
                            id(r.getString("next_plan_id")));
    private final RowMapper<EventRow> eventMapper =
            (r, n) ->
                    new EventRow(
                            id(r.getString("id")),
                            id(r.getString("vehicle_id")),
                            id(r.getString("item_id")),
                            id(r.getString("author_user_id")),
                            r.getString("payload"),
                            r.getLong("revision"),
                            r.getString("create_response"),
                            r.getBytes("create_hash"),
                            instant(r.getObject("created_at", LocalDateTime.class)),
                            instant(r.getObject("deleted_at", LocalDateTime.class)));
    private final RowMapper<Sample> sampleMapper =
            (r, n) ->
                    new Sample(
                            id(r.getString("vehicle_id")),
                            r.getBigDecimal("km"),
                            instant(r.getObject("observed_at", LocalDateTime.class)),
                            instant(r.getObject("received_at", LocalDateTime.class)),
                            r.getBytes("payload_hash"));

    public void lockVehicle(UUID vehicle) {
        if (db.query(
                        "SELECT id FROM vehicles WHERE id=? FOR UPDATE",
                        (r, n) -> r.getString(1),
                        vehicle.toString())
                .isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "VEHICLE_NOT_FOUND");
    }

    public String role(UUID vehicle, UUID user) {
        var rows =
                db.query(
                        "SELECT role FROM group_members WHERE vehicle_id=? AND user_id=? FOR"
                                + " UPDATE",
                        (r, n) -> r.getString(1),
                        vehicle.toString(),
                        user.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public long revision(UUID vehicle) {
        var rows =
                db.query(
                        "SELECT revision FROM vehicle_maintenance_state WHERE vehicle_id=?",
                        (r, n) -> r.getLong(1),
                        vehicle.toString());
        return rows.isEmpty() ? 0 : rows.getFirst();
    }

    public void bump(UUID vehicle) {
        db.update(
                "INSERT INTO vehicle_maintenance_state (vehicle_id,revision) VALUES (?,1) ON"
                        + " DUPLICATE KEY UPDATE revision=revision+1",
                vehicle.toString());
    }

    public List<PlanRow> plans(UUID vehicle) {
        return db.query(
                "SELECT * FROM maintenance_plans WHERE vehicle_id=? ORDER BY name,id",
                planMapper,
                vehicle.toString());
    }

    public PlanRow plan(UUID vehicle, UUID item) {
        var rows =
                db.query(
                        "SELECT * FROM maintenance_plans WHERE vehicle_id=? AND id=?",
                        planMapper,
                        vehicle.toString(),
                        item.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void savePlan(
            UUID vehicle,
            UUID item,
            UUID user,
            PlanInput b,
            long revision,
            boolean insert,
            Instant now) {
        if (insert)
            db.update(
                    "INSERT INTO maintenance_plans"
                        + " (id,vehicle_id,owner_user_id,name,shared,active,basis,evidence_id,interval_km,interval_months,revision,created_at,updated_at)"
                        + " VALUES (?,?,?,?,?,TRUE,?,?,?,?,?,?,?)",
                    item.toString(),
                    vehicle.toString(),
                    user.toString(),
                    b.name(),
                    b.shared(),
                    b.basis().name(),
                    str(b.evidenceId()),
                    b.intervalKm(),
                    b.intervalMonths(),
                    revision,
                    time(now),
                    time(now));
        else
            db.update(
                    "UPDATE maintenance_plans SET"
                        + " name=?,shared=?,active=TRUE,basis=?,evidence_id=?,interval_km=?,interval_months=?,revision=?,updated_at=?"
                        + " WHERE id=? AND vehicle_id=?",
                    b.name(),
                    b.shared(),
                    b.basis().name(),
                    str(b.evidenceId()),
                    b.intervalKm(),
                    b.intervalMonths(),
                    revision,
                    time(now),
                    item.toString(),
                    vehicle.toString());
    }

    public void disablePlan(UUID item, long revision, Instant now) {
        db.update(
                "UPDATE maintenance_plans SET active=FALSE,revision=?,updated_at=? WHERE id=?",
                revision,
                time(now),
                item.toString());
    }

    public void due(
            UUID item,
            LocalDate lastDate,
            BigDecimal lastKm,
            LocalDate nextDate,
            BigDecimal nextKm) {
        db.update(
                "UPDATE maintenance_plans SET"
                    + " last_service_date=?,last_service_km=?,next_due_date=?,next_due_km=? WHERE"
                    + " id=?",
                lastDate,
                lastKm,
                nextDate,
                nextKm,
                item.toString());
    }

    public void completePlan(UUID item, UUID event, UUID next, Instant now) {
        db.update("UPDATE maintenance_plans SET active=FALSE,completed_at=?,deleted_at=?,"
                        + "completion_event_id=?,next_plan_id=?,revision=revision+1,updated_at=? WHERE id=?",
                time(now), time(now), event.toString(), next.toString(), time(now), item.toString());
    }

    public boolean completionEvent(UUID event) {
        return !db.query("SELECT id FROM maintenance_plans WHERE completion_event_id=?",
                (r, n) -> r.getString(1), event.toString()).isEmpty();
    }

    public List<ManufacturerDefault> manufacturerDefaults(UUID vehicle) {
        var rows=db.query("SELECT id,service_name,interval_km,interval_months FROM maintenance_manufacturer_evidence WHERE revoked_at IS NULL ORDER BY service_name,id",
                (r,n)->new ManufacturerDefault(UUID.fromString(r.getString("id")),r.getString("service_name"),r.getBigDecimal("interval_km"),r.getObject("interval_months",Integer.class),null,null));
        var plans=plans(vehicle);
        return rows.stream().map(row->new ManufacturerDefault(row.id(),row.name(),row.intervalKm(),row.intervalMonths(),
                evidence(vehicle,row.id(),row.name(),row.intervalKm(),row.intervalMonths()),
                plans.stream().filter(p->p.active() && p.shared() && row.id().equals(p.evidence())).map(PlanRow::id).findFirst().orElse(null))).toList();
    }

    public List<CompletedService> completedServices(UUID vehicle) {
        return db.query("SELECT e.id,p.name,e.service_date,e.odometer_km,p.next_plan_id FROM maintenance_plans p JOIN maintenance_events e ON e.id=p.completion_event_id WHERE p.vehicle_id=? AND p.shared=TRUE AND p.completed_at IS NOT NULL AND e.deleted_at IS NULL ORDER BY p.completed_at DESC,p.id DESC LIMIT 50",
                (r,n)->new CompletedService(UUID.fromString(r.getString("id")),r.getString("name"),r.getObject("service_date",LocalDate.class),r.getBigDecimal("odometer_km"),UUID.fromString(r.getString("next_plan_id"))),vehicle.toString());
    }

    public Evidence evidence(
            UUID vehicle, UUID evidence, String name, BigDecimal km, Integer months) {
        if (evidence == null) return null;
        var rows =
                db.query(
                        "SELECT e.* FROM maintenance_manufacturer_evidence e WHERE e.id=?",
                        (r, n) -> {
                            boolean valid =
                                    r.getObject("revoked_at") == null
                                            && r.getObject("verified_at") != null
                                            && name.equals(r.getString("service_name"))
                                            && !r.getString("scope_text").isBlank()
                                            && !r.getString("document_title").isBlank()
                                            && r.getString("document_url").startsWith("https://")
                                            && equal(km, r.getBigDecimal("interval_km"))
                                            && Objects.equals(
                                                    months,
                                                    r.getObject("interval_months", Integer.class));
                            return new Evidence(
                                    evidence,
                                    r.getString("document_url"),
                                    r.getString("document_title"),
                                    r.getString("manufacturer"),
                                    r.getString("model"),
                                    r.getInt("model_year_from"),
                                    r.getInt("model_year_to"),
                                    r.getString("scope_text"),
                                    valid);
                        },
                        evidence.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public EventRow event(UUID vehicle, UUID event) {
        var rows =
                db.query(
                        "SELECT * FROM maintenance_events WHERE vehicle_id=? AND id=?",
                        eventMapper,
                        vehicle.toString(),
                        event.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public EventRow forKey(UUID vehicle, UUID user, String key) {
        var rows =
                db.query(
                        "SELECT * FROM maintenance_events WHERE vehicle_id=? AND author_user_id=?"
                                + " AND create_key=?",
                        eventMapper,
                        vehicle.toString(),
                        user.toString(),
                        key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<EventRow> events(UUID vehicle, UUID user, UUID cursor) {
        return db.query(
                "SELECT * FROM maintenance_events WHERE vehicle_id=? AND deleted_at IS NULL AND"
                        + " (shared=TRUE OR author_user_id=?) AND id>? ORDER BY id LIMIT 51",
                eventMapper,
                vehicle.toString(),
                user.toString(),
                cursor == null ? "" : cursor.toString());
    }

    public List<EventRow> itemEvents(UUID vehicle, UUID item) {
        return db.query(
                "SELECT * FROM maintenance_events WHERE vehicle_id=? AND (item_id=? OR id IN"
                        + " (SELECT completion_event_id FROM maintenance_plans WHERE next_plan_id=?))"
                        + " AND deleted_at IS NULL AND status='CONFIRMED'",
                eventMapper,
                vehicle.toString(),
                item.toString(),
                item.toString());
    }

    public void createEvent(
            UUID id,
            UUID vehicle,
            UUID user,
            EventInput b,
            String payload,
            String key,
            byte[] hash,
            String response,
            Instant now) {
        db.update(
                "INSERT INTO maintenance_events"
                    + " (id,vehicle_id,item_id,author_user_id,shared,status,service_date,odometer_km,payload,revision,create_key,create_hash,create_response,created_at,updated_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,1,?,?,?,?,?)",
                id.toString(),
                vehicle.toString(),
                b.itemId().toString(),
                user.toString(),
                b.shared(),
                b.status().name(),
                b.serviceDate(),
                b.odometerKm(),
                payload,
                key,
                hash,
                response,
                time(now),
                time(now));
    }

    public void updateEvent(UUID id, EventInput b, String payload, long revision, Instant now) {
        db.update(
                "UPDATE maintenance_events SET"
                    + " item_id=?,shared=?,status=?,service_date=?,odometer_km=?,payload=?,revision=?,updated_at=?"
                    + " WHERE id=?",
                b.itemId().toString(),
                b.shared(),
                b.status().name(),
                b.serviceDate(),
                b.odometerKm(),
                payload,
                revision,
                time(now),
                id.toString());
    }

    public void deleteEvent(UUID id, long revision, Instant now) {
        db.update(
                "UPDATE maintenance_events SET deleted_at=?,revision=?,updated_at=? WHERE id=?",
                time(now),
                revision,
                time(now),
                id.toString());
    }

    public void audit(
            UUID event,
            UUID actor,
            String action,
            long revision,
            String before,
            String after,
            Instant now) {
        db.update(
                "INSERT INTO maintenance_event_audit"
                    + " (event_id,actor_user_id,action,revision,before_payload,after_payload,created_at)"
                    + " VALUES (?,?,?,?,?,?,?)",
                event.toString(),
                actor.toString(),
                action,
                revision,
                before,
                after,
                time(now));
    }

    public Sample sample(UUID installation, UUID sample) {
        var rows =
                db.query(
                        "SELECT * FROM vehicle_odometer_samples WHERE installation_id=? AND"
                                + " sample_id=?",
                        sampleMapper,
                        installation.toString(),
                        sample.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Sample latest(UUID vehicle) {
        var rows =
                db.query(
                        "SELECT * FROM vehicle_odometer_samples WHERE vehicle_id=? AND"
                                + " quality='VALID' ORDER BY observed_at DESC,id DESC LIMIT 1",
                        sampleMapper,
                        vehicle.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public boolean monotonic(UUID vehicle, BigDecimal km, Instant at) {
        Integer violations =
                db.queryForObject(
                        "SELECT COUNT(*) FROM vehicle_odometer_samples WHERE vehicle_id=? AND"
                            + " quality='VALID' AND ((observed_at<=? AND km>?) OR (observed_at>=?"
                            + " AND km<?))",
                        Integer.class,
                        vehicle.toString(),
                        time(at),
                        km,
                        time(at),
                        km);
        return violations != null && violations == 0;
    }

    public void saveSample(
            UUID installation,
            UUID vehicle,
            UUID sample,
            OdometerInput b,
            byte[] hash,
            Instant now) {
        db.update(
                "INSERT INTO vehicle_odometer_samples"
                    + " (sample_id,installation_id,vehicle_id,km,observed_at,unit,source,quality,payload_hash,received_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                sample.toString(),
                installation.toString(),
                vehicle.toString(),
                b.km(),
                time(Instant.ofEpochMilli(b.observedAt())),
                b.unit(),
                b.source(),
                b.quality().name(),
                hash,
                time(now));
    }

    private static UUID id(String s) {
        return s == null ? null : UUID.fromString(s);
    }

    private static String str(UUID id) {
        return id == null ? null : id.toString();
    }

    private static LocalDateTime time(Instant t) {
        return LocalDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }

    private static boolean equal(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }
}
