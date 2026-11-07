package app.fuelog.repository;

import app.fuelog.dto.TripUserCandidateDtos;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** 구간별 사용자 동의·확인 상태와 개인정보 없는 변경 이벤트를 저장한다. */
@Repository
public class TripUserCandidateRepository {
    private final JdbcTemplate db;

    public TripUserCandidateRepository(JdbcTemplate db) {
        this.db = db;
    }

    public record Candidate(
            UUID id,
            UUID challengeId,
            UUID installationId,
            UUID vehicleId,
            UUID intervalId,
            UUID userId,
            String displayName,
            String consentOperationId,
            byte[] proofFingerprint,
            String verifiedOperationId,
            String status,
            Instant expiresAt,
            Instant verificationExpiresAt) {}

    private final RowMapper<Candidate> mapper =
            (rs, n) ->
                    new Candidate(
                            id(rs.getString("id")),
                            id(rs.getString("challenge_id")),
                            id(rs.getString("installation_id")),
                            id(rs.getString("vehicle_id")),
                            id(rs.getString("interval_id")),
                            id(rs.getString("user_id")),
                            rs.getString("display_name"),
                            rs.getString("consent_operation_id"),
                            rs.getBytes("proof_fingerprint"),
                            rs.getString("verified_operation_id"),
                            rs.getString("status"),
                            instant(rs.getObject("expires_at", LocalDateTime.class)),
                            instant(rs.getObject("verification_expires_at", LocalDateTime.class)));

    public Candidate get(UUID id, boolean lock) {
        List<Candidate> rows =
                db.query(
                        "SELECT * FROM trip_user_candidates WHERE id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        mapper,
                        id.toString());
        if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CANDIDATE_NOT_FOUND");
        return rows.getFirst();
    }

    public Candidate byChallenge(UUID challenge) {
        List<Candidate> rows =
                db.query(
                        "SELECT * FROM trip_user_candidates WHERE challenge_id=?",
                        mapper,
                        challenge.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Candidate byConsentOperation(UUID installation, String operation) {
        List<Candidate> rows =
                db.query(
                        "SELECT * FROM trip_user_candidates WHERE installation_id=? AND"
                                + " consent_operation_id=?",
                        mapper,
                        installation.toString(),
                        operation);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Candidate byVerifyOperation(UUID installation, String operation) {
        List<Candidate> rows =
                db.query(
                        "SELECT * FROM trip_user_candidates WHERE installation_id=? AND"
                                + " verified_operation_id=?",
                        mapper,
                        installation.toString(),
                        operation);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<Candidate> activeCandidates(
            UUID installation, UUID vehicle, UUID interval, Instant now) {
        return db.query(
                "SELECT c.* FROM trip_user_candidates c JOIN group_members m ON"
                    + " m.vehicle_id=c.vehicle_id AND m.user_id=c.user_id WHERE c.installation_id=?"
                    + " AND c.vehicle_id=? AND c.interval_id=? AND c.status='active' AND"
                    + " c.expires_at>? ORDER BY c.created_at, c.id",
                mapper,
                installation.toString(),
                vehicle.toString(),
                interval.toString(),
                time(now));
    }

    public boolean memberForUpdate(UUID user, UUID vehicle) {
        return !db.query(
                        "SELECT user_id FROM group_members WHERE vehicle_id=? AND user_id=? FOR"
                                + " UPDATE",
                        (rs, n) -> rs.getString(1),
                        vehicle.toString(),
                        user.toString())
                .isEmpty();
    }

    public void insert(Candidate value, Instant now) {
        db.update(
                "INSERT INTO trip_user_candidates"
                    + " (id,challenge_id,installation_id,vehicle_id,interval_id,user_id,display_name,consent_operation_id,proof_fingerprint,status,expires_at,created_at,updated_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,'active',?,?,?)",
                value.id().toString(),
                value.challengeId().toString(),
                value.installationId().toString(),
                value.vehicleId().toString(),
                value.intervalId().toString(),
                value.userId().toString(),
                value.displayName(),
                value.consentOperationId(),
                value.proofFingerprint(),
                time(value.expiresAt()),
                time(now),
                time(now));
    }

    public void markChallengeConsented(UUID id, UUID user, String displayName, Instant now) {
        db.update(
                "UPDATE trip_user_challenges SET status='approved', active_installation_id=NULL,"
                        + " proof_hash=NULL, approved_user_id=?, display_name=?, updated_at=? WHERE"
                        + " id=? AND status='pending'",
                user.toString(),
                displayName,
                time(now),
                id.toString());
    }

    public void markVerified(UUID id, String operation, Instant expiresAt, Instant now) {
        db.update(
                "UPDATE trip_user_candidates SET status='verified', verified_operation_id=?,"
                        + " verification_expires_at=?, updated_at=? WHERE id=? AND status='active'",
                operation,
                time(expiresAt),
                time(now),
                id.toString());
    }

    public void revoke(UUID id, Instant now) {
        db.update(
                "UPDATE trip_user_candidates SET status='revoked', updated_at=? WHERE id=? AND"
                        + " status IN ('active','verified','expired')",
                time(now),
                id.toString());
    }

    public int expireActive(UUID installation, Instant now) {
        return db.update(
                "UPDATE trip_user_candidates SET status='expired', updated_at=? WHERE"
                        + " installation_id=? AND status='active' AND expires_at<=?",
                time(now),
                installation.toString(),
                time(now));
    }

    public int revokeForInstallation(UUID installation, Instant now) {
        return db.update(
                "UPDATE trip_user_candidates SET status='revoked', updated_at=? WHERE"
                        + " installation_id=? AND status IN ('active','verified')",
                time(now),
                installation.toString());
    }

    public long addEvent(UUID installation, String type, Instant now) {
        db.update(
                "INSERT INTO trip_user_event_state (installation_id,revision) VALUES (?,1) ON"
                        + " DUPLICATE KEY UPDATE revision=revision+1",
                installation.toString());
        long revision = revision(installation);
        db.update(
                "INSERT INTO trip_user_events (installation_id,revision,event_type,created_at)"
                        + " VALUES (?,?,?,?)",
                installation.toString(),
                revision,
                type,
                time(now));
        return revision;
    }

    public long revision(UUID installation) {
        List<Long> rows =
                db.query(
                        "SELECT revision FROM trip_user_event_state WHERE installation_id=?",
                        (rs, n) -> rs.getLong(1),
                        installation.toString());
        return rows.isEmpty() ? 0 : rows.getFirst();
    }

    public List<TripUserCandidateDtos.Event> events(UUID installation, long after, int limit) {
        return db.query(
                "SELECT revision,event_type FROM trip_user_events WHERE installation_id=? AND"
                        + " revision>? ORDER BY revision LIMIT ?",
                (rs, n) -> new TripUserCandidateDtos.Event(rs.getLong(1), rs.getString(2)),
                installation.toString(),
                after,
                limit);
    }

    private static UUID id(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static LocalDateTime time(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
