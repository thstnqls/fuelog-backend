package app.fuelog.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** 연결 해제 후 개인 수집을 차단한다. 호출자는 트랜잭션에서 설치 행을 먼저 잠가야 한다. */
@Component
public class InstallationAccess {
    private final JdbcTemplate db;

    public InstallationAccess(JdbcTemplate db) {
        this.db = db;
    }

    public long registrationVersion(UUID id) {
        return db.queryForObject(
                "SELECT registration_version FROM vehicle_installations WHERE id=?",
                Long.class,
                id.toString());
    }

    public boolean blocked(UUID id) {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        "SELECT personal_access_blocked FROM vehicle_installations WHERE id=?",
                        Boolean.class,
                        id.toString()));
    }

    public void requireCollection(UUID id) {
        if (blocked(id)) fail("USER_VERIFICATION_REQUIRED");
    }

    public void requireChallenge(UUID id) {
        Integer count =
                db.queryForObject(
                        "SELECT COUNT(*) FROM connection_operations o JOIN vehicle_installations i"
                                + " ON i.id=o.installation_id AND"
                                + " i.registration_version=o.registration_version WHERE"
                                + " o.installation_id=? AND o.kind='VEHICLE' AND"
                                + " o.status='AWAITING_ADMIN'",
                        Integer.class,
                        id.toString());
        if (count != null && count > 0) fail("VEHICLE_RESET_PENDING");
    }

    public void verified(UUID installation, UUID challenge) {
        requireChallenge(installation);
        var boundary =
                db.queryForObject(
                        "SELECT access_blocked_at FROM vehicle_installations WHERE id=?",
                        LocalDateTime.class,
                        installation.toString());
        var created =
                db.queryForObject(
                        "SELECT created_at FROM trip_user_challenges WHERE id=?",
                        LocalDateTime.class,
                        challenge.toString());
        if (boundary != null && (created == null || !created.isAfter(boundary)))
            fail("NEW_USER_PROOF_REQUIRED");
        db.update(
                "UPDATE vehicle_installations SET personal_access_blocked=FALSE WHERE id=?",
                installation.toString());
    }

    public void block(UUID installation) {
        db.update(
                "UPDATE vehicle_installations SET personal_access_blocked=TRUE,access_blocked_at=?"
                        + " WHERE id=?",
                LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC),
                installation.toString());
        db.update(
                "UPDATE installation_sync_checkpoints SET verified_at=NULL WHERE installation_id=?",
                installation.toString());
    }

    public record AuthenticatedUser(UUID subjectId, String displayName) {}

    public AuthenticatedUser authenticatedUser(UUID installation) {
        if (blocked(installation)) return null;
        // Only successful QR approvals for the installation's current vehicle can supply an owner.
        var users = db.query("""
                SELECT latest.user_id, latest.display_name FROM (
                SELECT q.user_id, q.display_name, q.vehicle_id FROM (
                    SELECT p.approved_user_id AS user_id, 'QR 인증 사용자' AS display_name,
                           p.updated_at AS authenticated_at, p.selected_vehicle_id AS vehicle_id
                    FROM pairing_requests p WHERE p.installation_id=? AND p.status='completed'
                    UNION ALL
                    SELECT c.approved_user_id AS user_id, c.display_name,
                           c.updated_at AS authenticated_at, c.vehicle_id
                    FROM trip_user_challenges c WHERE c.installation_id=?
                        AND c.status IN ('approved','consented')
                ) q JOIN vehicle_installations i ON i.id=? AND i.vehicle_id=q.vehicle_id
                ORDER BY q.authenticated_at DESC LIMIT 1
                ) latest JOIN group_members m ON m.vehicle_id=latest.vehicle_id AND m.user_id=latest.user_id
                """, (row, n) -> new AuthenticatedUser(UUID.fromString(row.getString(1)),
                        row.getString(2)), installation.toString(), installation.toString(), installation.toString());
        return users.isEmpty() ? null : users.getFirst();
    }

    public UUID currentUser(UUID installation) {
        var user = authenticatedUser(installation);
        return user == null ? null : user.subjectId();
    }

    private static void fail(String code) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, code);
    }
}
