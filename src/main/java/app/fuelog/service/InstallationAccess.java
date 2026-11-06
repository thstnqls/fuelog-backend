package app.fuelog.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Called while the installation row is locked by the surrounding transaction. */
@Component
public class InstallationAccess {
    private final JdbcTemplate db;
    public InstallationAccess(JdbcTemplate db) { this.db=db; }
    public long registrationVersion(UUID id) {
        return db.queryForObject("SELECT registration_version FROM vehicle_installations WHERE id=?",Long.class,id.toString());
    }
    public boolean blocked(UUID id) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT personal_access_blocked FROM vehicle_installations WHERE id=?",Boolean.class,id.toString()));
    }
    public void requireCollection(UUID id) {
        if (blocked(id)) fail("USER_VERIFICATION_REQUIRED");
    }
    public void requireChallenge(UUID id) {
        Integer count=db.queryForObject("SELECT COUNT(*) FROM connection_operations o JOIN vehicle_installations i ON i.id=o.installation_id AND i.registration_version=o.registration_version WHERE o.installation_id=? AND o.kind='VEHICLE' AND o.status='AWAITING_ADMIN'",Integer.class,id.toString());
        if (count!=null && count>0) fail("VEHICLE_RESET_PENDING");
    }
    public void verified(UUID installation,UUID challenge) {
        requireChallenge(installation);
        var boundary=db.queryForObject("SELECT access_blocked_at FROM vehicle_installations WHERE id=?",LocalDateTime.class,installation.toString());
        var created=db.queryForObject("SELECT created_at FROM trip_user_challenges WHERE id=?",LocalDateTime.class,challenge.toString());
        if (boundary!=null && (created==null || !created.isAfter(boundary))) fail("NEW_USER_PROOF_REQUIRED");
        db.update("UPDATE vehicle_installations SET personal_access_blocked=FALSE WHERE id=?",installation.toString());
    }
    public void block(UUID installation) {
        db.update("UPDATE vehicle_installations SET personal_access_blocked=TRUE,access_blocked_at=? WHERE id=?",
                LocalDateTime.ofInstant(Instant.now(),ZoneOffset.UTC),installation.toString());
        db.update("UPDATE installation_sync_checkpoints SET verified_at=NULL WHERE installation_id=?",installation.toString());
    }
    public UUID currentUser(UUID installation) {
        if (blocked(installation)) return null;
        var users=db.query("SELECT s.user_id FROM trip_user_sessions s JOIN vehicle_installations i ON i.id=s.installation_id JOIN group_members m ON m.vehicle_id=s.vehicle_id AND m.user_id=s.user_id WHERE s.installation_id=? AND s.vehicle_id=i.vehicle_id AND s.status='active' AND s.version=i.session_version AND s.expires_at>?",
                (r,n)->UUID.fromString(r.getString(1)),installation.toString(),LocalDateTime.now(ZoneOffset.UTC));
        if (!users.isEmpty()) return users.getFirst();
        var grants=db.query("SELECT c.user_id FROM trip_user_candidates c JOIN vehicle_installations i ON i.id=c.installation_id JOIN group_members m ON m.vehicle_id=c.vehicle_id AND m.user_id=c.user_id WHERE c.installation_id=? AND c.vehicle_id=i.vehicle_id AND c.status='verified' AND c.verification_expires_at>? AND c.verified_operation_id IS NOT NULL ORDER BY c.updated_at DESC,c.id DESC LIMIT 1",
                (r,n)->UUID.fromString(r.getString(1)),installation.toString(),LocalDateTime.now(ZoneOffset.UTC));
        return grants.isEmpty()?null:grants.getFirst();
    }
    private static void fail(String code) { throw new ResponseStatusException(HttpStatus.FORBIDDEN,code); }
}
