package app.fuelog.repository;

import app.fuelog.dto.FuelCandidateDtos.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** 주유 감지 증거·사용자 보정·재시도 응답을 저장하며 후보 변경 시 행을 잠근다. */
@Repository
public class FuelCandidateRepository {
    private final JdbcTemplate db;

    public FuelCandidateRepository(JdbcTemplate db) {
        this.db = db;
    }

    public record Row(UUID installation, UUID vehicle, UUID interval, View view) {}

    public record Estimate(boolean verified, BigDecimal liters, BigDecimal amount, Long priceAt) {}

    public Row get(UUID id) {
        var rows =
                db.query(
                        "SELECT * FROM fuel_candidates WHERE id=? FOR UPDATE",
                        (r, n) -> {
                            var v =
                                    new View(
                                            id,
                                            UUID.fromString(r.getString("owner_id")),
                                            Status.valueOf(r.getString("status")),
                                            r.getLong("revision"),
                                            r.getBoolean("capacity_verified"),
                                            r.getBigDecimal("estimated_liters"),
                                            r.getBigDecimal("estimated_amount"),
                                            r.getObject("price_at", Long.class),
                                            List.of(),
                                            UUID.fromString(r.getString("interval_id")),
                                            r.getLong("start_at"),
                                            r.getLong("end_at"),
                                            r.getBigDecimal("increase_percent"),
                                            r.getString("corrected_place"),
                                            r.getBigDecimal("corrected_liters"),
                                            r.getBigDecimal("corrected_amount"));
                            return new Row(
                                    UUID.fromString(r.getString("installation_id")),
                                    UUID.fromString(r.getString("vehicle_id")),
                                    v.intervalId(),
                                    v);
                        },
                        id.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void insert(UUID installation, UUID vehicle, UUID interval, Sync b, Estimate e) {
        db.update(
                "INSERT INTO fuel_candidates"
                    + " (id,installation_id,vehicle_id,owner_id,interval_id,start_at,end_at,increase_percent,capacity_verified,estimated_liters,estimated_amount,price_at,created_at,updated_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                b.id().toString(),
                installation.toString(),
                vehicle.toString(),
                b.owner().toString(),
                interval.toString(),
                b.startAt(),
                b.endAt(),
                b.increasePercent(),
                e.verified(),
                e.liters(),
                e.amount(),
                e.priceAt(),
                now(),
                now());
    }

    public void evidence(UUID id, long start, long end, BigDecimal increase, Estimate e) {
        db.update(
                "UPDATE fuel_candidates SET"
                    + " start_at=?,end_at=?,increase_percent=?,capacity_verified=?,estimated_liters=?,estimated_amount=?,price_at=?,revision=revision+1,updated_at=?"
                    + " WHERE id=?",
                start,
                end,
                increase,
                e.verified(),
                e.liters(),
                e.amount(),
                e.priceAt(),
                now(),
                id.toString());
    }

    public void deliver(Row row) {
        db.update(
                "INSERT INTO mobile_trip_inbox (recipient_id,kind,candidate_id,delivered_at) SELECT"
                    + " ?,'FUEL_CANDIDATE',?,? WHERE NOT EXISTS (SELECT 1 FROM mobile_trip_inbox"
                    + " WHERE recipient_id=? AND kind='FUEL_CANDIDATE' AND candidate_id=?)",
                row.view().owner().toString(),
                row.view().id().toString(),
                now(),
                row.view().owner().toString(),
                row.view().id().toString());
    }

    public String[] operation(UUID candidate, String operation) {
        var rows =
                db.query(
                        "SELECT actor_id,request_payload,response_payload FROM"
                            + " fuel_candidate_operations WHERE candidate_id=? AND operation_id=?",
                        (r, n) -> new String[] {r.getString(1), r.getString(2), r.getString(3)},
                        candidate.toString(),
                        operation);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void patch(UUID id, Patch b) {
        db.update(
                "UPDATE fuel_candidates SET"
                    + " status=?,corrected_place=COALESCE(?,corrected_place),corrected_liters=COALESCE(?,corrected_liters),corrected_amount=COALESCE(?,corrected_amount),revision=revision+1,updated_at=?"
                    + " WHERE id=? AND revision=?",
                b.action() == Action.CONFIRM ? "CONFIRMED" : "REJECTED",
                b.place(),
                b.liters(),
                b.amount(),
                now(),
                id.toString(),
                b.expectedRevision());
    }

    public void audit(UUID user, UUID id, Patch b, String request, String response) {
        db.update(
                "INSERT INTO fuel_candidate_operations"
                    + " (candidate_id,operation_id,actor_id,request_payload,response_payload,previous_revision,resulting_revision,created_at)"
                    + " VALUES (?,?,?,?,?,?,?,?)",
                id.toString(),
                b.operationId(),
                user.toString(),
                request,
                response,
                b.expectedRevision(),
                b.expectedRevision() + 1,
                now());
    }

    public static LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }
}
