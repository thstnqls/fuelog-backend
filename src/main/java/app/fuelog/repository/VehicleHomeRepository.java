package app.fuelog.repository;

import app.fuelog.common.TimeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 사용자 세션·구간·샘플을 저장하며 시간대 없는 DATETIME을 UTC로 읽고 쓴다. */
@Repository
public class VehicleHomeRepository {
    private final JdbcTemplate db;

    public VehicleHomeRepository(JdbcTemplate db) {
        this.db = db;
    }

    public record Challenge(
            UUID id,
            UUID installationId,
            UUID vehicleId,
            String operationId,
            UUID intervalId,
            byte[] proofHash,
            String status,
            Instant expiresAt,
            UUID approvedUserId,
            String displayName,
            UUID sessionId) {}

    public record Session(
            UUID id,
            UUID installationId,
            UUID vehicleId,
            UUID userId,
            String displayName,
            long version,
            byte[] tokenHash,
            String status,
            Instant expiresAt) {}

    public record Interval(
            UUID id,
            UUID installationId,
            UUID vehicleId,
            UUID userId,
            Long sessionVersion,
            String localIntervalId,
            String source,
            String status,
            Instant startedAt,
            Instant endedAt,
            String finishOperationId,
            Long finishLastSequence,
            long lastAcceptedSequence,
            Instant lastSampleAt,
            Instant lastSyncedAt) {}

    public record Batch(
            byte[] payloadHash, int acceptedCount, long lastAcceptedSequence, Instant receivedAt) {}

    public record Vehicle(String alias, Instant observedAt) {}

    public record Distance(BigDecimal km, Instant observedAt) {}

    public record Fuel(String status, Instant observedAt) {}

    private final RowMapper<Challenge> challengeMapper =
            (rs, n) ->
                    new Challenge(
                            id(rs.getString("id")),
                            id(rs.getString("installation_id")),
                            id(rs.getString("vehicle_id")),
                            rs.getString("operation_id"),
                            id(rs.getString("interval_id")),
                            rs.getBytes("proof_hash"),
                            rs.getString("status"),
                            instant(rs.getObject("expires_at", LocalDateTime.class)),
                            id(rs.getString("approved_user_id")),
                            rs.getString("display_name"),
                            id(rs.getString("session_id")));
    private final RowMapper<Session> sessionMapper =
            (rs, n) ->
                    new Session(
                            id(rs.getString("id")),
                            id(rs.getString("installation_id")),
                            id(rs.getString("vehicle_id")),
                            id(rs.getString("user_id")),
                            rs.getString("display_name"),
                            rs.getLong("version"),
                            rs.getBytes("token_hash"),
                            rs.getString("status"),
                            instant(rs.getObject("expires_at", LocalDateTime.class)));
    private final RowMapper<Interval> intervalMapper =
            (rs, n) ->
                    new Interval(
                            id(rs.getString("id")),
                            id(rs.getString("installation_id")),
                            id(rs.getString("vehicle_id")),
                            id(rs.getString("user_id")),
                            rs.getObject("session_version", Long.class),
                            rs.getString("local_interval_id"),
                            rs.getString("source"),
                            rs.getString("status"),
                            instant(rs.getObject("started_at", LocalDateTime.class)),
                            instant(rs.getObject("ended_at", LocalDateTime.class)),
                            rs.getString("finish_operation_id"),
                            rs.getObject("finish_last_sequence", Long.class),
                            rs.getLong("last_accepted_sequence"),
                            instant(rs.getObject("last_sample_at", LocalDateTime.class)),
                            instant(rs.getObject("last_synced_at", LocalDateTime.class)));

    public long sessionVersion(UUID installation) {
        Long value =
                db.queryForObject(
                        "SELECT session_version FROM vehicle_installations WHERE id=?",
                        Long.class,
                        installation.toString());
        return value == null ? 0 : value;
    }

    public long incrementSessionVersion(UUID installation) {
        db.update(
                "UPDATE vehicle_installations SET session_version=session_version+1 WHERE id=?",
                installation.toString());
        return sessionVersion(installation);
    }

    public boolean member(UUID user, UUID vehicle) {
        return !db.query(
                        "SELECT user_id FROM group_members WHERE vehicle_id=? AND user_id=?",
                        (rs, n) -> rs.getString(1),
                        vehicle.toString(),
                        user.toString())
                .isEmpty();
    }

    public Vehicle vehicle(UUID vehicle) {
        List<Vehicle> rows =
                db.query(
                        "SELECT name,updated_at FROM vehicles WHERE id=?",
                        (rs, n) ->
                                new Vehicle(
                                        rs.getString(1),
                                        instant(rs.getObject(2, LocalDateTime.class))),
                        vehicle.toString());
        if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "VEHICLE_NOT_FOUND");
        return rows.getFirst();
    }

    public Challenge challengeForOperation(UUID installation, String operationId) {
        List<Challenge> rows =
                db.query(
                        "SELECT * FROM trip_user_challenges WHERE installation_id=? AND"
                                + " operation_id=?",
                        challengeMapper,
                        installation.toString(),
                        operationId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Challenge challenge(UUID id, boolean lock) {
        List<Challenge> rows =
                db.query(
                        "SELECT * FROM trip_user_challenges WHERE id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        challengeMapper,
                        id.toString());
        if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CHALLENGE_NOT_FOUND");
        return rows.getFirst();
    }

    public void revokePendingChallenges(UUID installation, Instant now) {
        db.update(
                "UPDATE trip_user_challenges SET status='revoked', active_installation_id=NULL,"
                    + " proof_hash=NULL, updated_at=? WHERE installation_id=? AND status='pending'",
                time(now),
                installation.toString());
    }

    public void createChallenge(
            UUID id,
            UUID installation,
            UUID vehicle,
            UUID interval,
            String operationId,
            byte[] proofHash,
            Instant expires,
            Instant now) {
        db.update(
                "INSERT INTO trip_user_challenges"
                    + " (id,installation_id,vehicle_id,interval_id,operation_id,proof_hash,status,expires_at,active_installation_id,created_at,updated_at)"
                    + " VALUES (?,?,?,?,?,?,'pending',?,?,?,?)",
                id.toString(),
                installation.toString(),
                vehicle.toString(),
                interval == null ? null : interval.toString(),
                operationId,
                proofHash,
                time(expires),
                installation.toString(),
                time(now),
                time(now));
    }

    public void expireChallenge(UUID id, Instant now) {
        db.update(
                "UPDATE trip_user_challenges SET status='expired', active_installation_id=NULL,"
                        + " proof_hash=NULL, updated_at=? WHERE id=? AND status='pending'",
                time(now),
                id.toString());
    }

    public void approveChallenge(
            UUID id, UUID user, String displayName, UUID session, Instant now) {
        db.update(
                "UPDATE trip_user_challenges SET status='approved', active_installation_id=NULL,"
                        + " proof_hash=NULL, approved_user_id=?, display_name=?, session_id=?,"
                        + " updated_at=? WHERE id=? AND status='pending'",
                user.toString(),
                displayName,
                session.toString(),
                time(now),
                id.toString());
    }

    public Session session(UUID id) {
        List<Session> rows =
                db.query(
                        "SELECT * FROM trip_user_sessions WHERE id=?",
                        sessionMapper,
                        id.toString());
        if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND");
        return rows.getFirst();
    }

    public Session sessionByTokenHash(byte[] hash) {
        List<Session> rows =
                db.query(
                        "SELECT * FROM trip_user_sessions WHERE token_hash=?", sessionMapper, hash);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void revokeActiveSessions(UUID installation, Instant now) {
        db.update(
                "UPDATE trip_user_sessions SET status='revoked', active_installation_id=NULL,"
                        + " revoked_at=? WHERE installation_id=? AND status='active'",
                time(now),
                installation.toString());
    }

    public void createSession(
            UUID id,
            UUID installation,
            UUID vehicle,
            UUID user,
            String displayName,
            long version,
            Instant expires,
            Instant now) {
        db.update(
                "INSERT INTO trip_user_sessions"
                    + " (id,installation_id,vehicle_id,user_id,display_name,version,token_hash,status,expires_at,active_installation_id,created_at)"
                    + " VALUES (?,?,?,?,?,?,?,'active',?,?,?)",
                id.toString(),
                installation.toString(),
                vehicle.toString(),
                user.toString(),
                displayName,
                version,
                null,
                time(expires),
                installation.toString(),
                time(now));
    }

    public void replaceSessionTokenHash(UUID id, byte[] hash) {
        db.update(
                "UPDATE trip_user_sessions SET token_hash=? WHERE id=? AND status='active'",
                hash,
                id.toString());
    }

    public Interval intervalByLocalId(UUID installation, String localId) {
        List<Interval> rows =
                db.query(
                        "SELECT * FROM recording_intervals WHERE installation_id=? AND"
                                + " local_interval_id=?",
                        intervalMapper,
                        installation.toString(),
                        localId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Interval intervalByTripId(UUID installation, UUID tripId) {
        var rows =
                db.query(
                        "SELECT * FROM recording_intervals WHERE installation_id=? AND (id=? OR"
                                + " local_interval_id=?) FOR UPDATE",
                        intervalMapper,
                        installation.toString(),
                        tripId.toString(),
                        tripId.toString());
        if (rows.size() > 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AMBIGUOUS_TRIP_ID");
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Interval interval(UUID id, boolean lock) {
        List<Interval> rows =
                db.query(
                        "SELECT * FROM recording_intervals WHERE id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        intervalMapper,
                        id.toString());
        if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "INTERVAL_NOT_FOUND");
        return rows.getFirst();
    }

    public Interval latestInterval(UUID installation, UUID vehicle) {
        List<Interval> rows =
                db.query(
                        "SELECT * FROM recording_intervals WHERE installation_id=? AND vehicle_id=?"
                                + " ORDER BY started_at DESC, created_at DESC LIMIT 1",
                        intervalMapper,
                        installation.toString(),
                        vehicle.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Interval latestIntervalForUser(UUID installation, UUID vehicle, UUID user) {
        String userPredicate = user == null ? "user_id IS NULL" : "user_id=?";
        String sql =
                "SELECT * FROM recording_intervals WHERE installation_id=? AND vehicle_id=? AND "
                        + userPredicate
                        + " ORDER BY started_at DESC, created_at DESC LIMIT 1";
        List<Interval> rows =
                user == null
                        ? db.query(sql, intervalMapper, installation.toString(), vehicle.toString())
                        : db.query(
                                sql,
                                intervalMapper,
                                installation.toString(),
                                vehicle.toString(),
                                user.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void createInterval(
            UUID id,
            UUID installation,
            UUID vehicle,
            UUID user,
            Long sessionVersion,
            String localId,
            Instant startedAt,
            Instant now) {
        db.update(
                "INSERT INTO recording_intervals"
                    + " (id,installation_id,vehicle_id,user_id,session_version,local_interval_id,source,status,started_at,created_at,updated_at)"
                    + " VALUES (?,?,?,?,?,?,'manual_app','recording',?,?,?)",
                id.toString(),
                installation.toString(),
                vehicle.toString(),
                user == null ? null : user.toString(),
                sessionVersion,
                localId,
                time(startedAt),
                time(now),
                time(now));
    }

    public Batch batch(UUID interval, String batchId) {
        List<Batch> rows =
                db.query(
                        "SELECT payload_hash,accepted_count,last_accepted_sequence,received_at FROM"
                                + " recording_batches WHERE interval_id=? AND batch_id=?",
                        (rs, n) ->
                                new Batch(
                                        rs.getBytes(1),
                                        rs.getInt(2),
                                        rs.getLong(3),
                                        instant(rs.getObject(4, LocalDateTime.class))),
                        interval.toString(),
                        batchId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void insertSample(
            UUID interval,
            String batch,
            long sequence,
            Instant observedAt,
            BigDecimal distance,
            String missingReason,
            BigDecimal fuel,
            String fuelStatus,
            UUID user,
            UUID candidate,
            Instant now) {
        db.update(
                "INSERT INTO recording_samples"
                    + " (interval_id,sequence,batch_id,observed_at,distance_km,missing_reason,fuel_liters,fuel_status,received_at,user_id,verified_candidate_id)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                interval.toString(),
                sequence,
                batch,
                time(observedAt),
                distance,
                missingReason,
                fuel,
                fuelStatus,
                time(now),
                user == null ? null : user.toString(),
                candidate == null ? null : candidate.toString());
    }

    public void insertBatch(
            UUID interval, String batch, byte[] hash, int count, long lastSequence, Instant now) {
        db.update(
                "INSERT INTO recording_batches"
                    + " (interval_id,batch_id,payload_hash,accepted_count,last_accepted_sequence,received_at)"
                    + " VALUES (?,?,?,?,?,?)",
                interval.toString(),
                batch,
                hash,
                count,
                lastSequence,
                time(now));
    }

    public void updateIntervalAfterSamples(
            UUID interval, long sequence, Instant lastObservedAt, Instant now) {
        db.update(
                "UPDATE recording_intervals SET"
                    + " last_accepted_sequence=GREATEST(last_accepted_sequence,?),"
                    + " last_sample_at=CASE WHEN last_sample_at IS NULL OR last_sample_at<? THEN ?"
                    + " ELSE last_sample_at END, last_synced_at=?, updated_at=? WHERE id=?",
                sequence,
                time(lastObservedAt),
                time(lastObservedAt),
                time(now),
                time(now),
                interval.toString());
    }

    public void finishInterval(
            UUID interval, String operationId, Instant endedAt, long lastSequence, Instant now) {
        db.update(
                "UPDATE recording_intervals SET status='finished', ended_at=?,"
                    + " finish_operation_id=?, finish_last_sequence=?, updated_at=? WHERE id=? AND"
                    + " status='recording'",
                time(endedAt),
                operationId,
                lastSequence,
                time(now),
                interval.toString());
    }

    public Distance distanceForUser(UUID vehicle, UUID user) {
        List<Distance> rows =
                db.query(
                        "SELECT SUM(s.distance_km), MAX(s.observed_at) FROM recording_samples s"
                                + " JOIN recording_intervals i ON i.id=s.interval_id WHERE"
                                + " i.vehicle_id=? AND COALESCE(s.user_id,i.user_id)=?",
                        (rs, n) ->
                                new Distance(
                                        rs.getBigDecimal(1),
                                        instant(rs.getObject(2, LocalDateTime.class))),
                        vehicle.toString(),
                        user.toString());
        return rows.getFirst();
    }

    public Fuel latestFuelForUser(UUID vehicle, UUID user) {
        List<Fuel> rows =
                db.query(
                        "SELECT s.fuel_status,s.observed_at FROM recording_samples s JOIN"
                            + " recording_intervals i ON i.id=s.interval_id WHERE i.vehicle_id=?"
                            + " AND COALESCE(s.user_id,i.user_id)=? ORDER BY s.observed_at"
                            + " DESC,s.sequence DESC LIMIT 1",
                        (rs, n) ->
                                new Fuel(
                                        rs.getString(1),
                                        instant(rs.getObject(2, LocalDateTime.class))),
                        vehicle.toString(),
                        user.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static UUID id(String text) {
        return text == null ? null : UUID.fromString(text);
    }

    private static LocalDateTime time(Instant value) {
        return TimeUtils.toLocalDateTime(value);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : TimeUtils.toInstant(value);
    }
}
