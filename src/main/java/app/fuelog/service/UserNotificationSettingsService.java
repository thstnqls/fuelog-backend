package app.fuelog.service;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserNotificationSettingsService {
    public record Settings(UUID user_id, boolean chargeCompleteEnabled) {}
    private final JdbcTemplate db;
    private final InstallationAuth auth;
    private final PairingService pairing;
    public UserNotificationSettingsService(JdbcTemplate db, InstallationAuth auth, PairingService pairing) {
        this.db=db; this.auth=auth; this.pairing=pairing;
    }
    private void requireUser(UUID installation, UUID user) {
        var current=pairing.installation(installation);
        if (!current.paired() || !user.equals(current.authenticated_user_id()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"CONNECTED_USER_REQUIRED");
    }
    public Settings get(String bearer, UUID user) {
        UUID installation=auth.require(bearer).id();
        requireUser(installation,user);
        var values=db.query("SELECT charge_complete_enabled FROM user_notification_settings WHERE user_id=?",
                (row,index)->row.getBoolean(1),user.toString());
        requireUser(installation,user);
        return new Settings(user,!values.isEmpty() && values.getFirst());
    }
    @Transactional
    public Settings save(String bearer, UUID user, boolean enabled) {
        UUID installation=auth.require(bearer).id();
        db.queryForList("SELECT id FROM vehicle_installations WHERE id=? FOR UPDATE",installation.toString());
        requireUser(installation,user);
        db.update("INSERT INTO user_notification_settings(user_id,charge_complete_enabled) VALUES (?,?) "
                +"ON DUPLICATE KEY UPDATE charge_complete_enabled=VALUES(charge_complete_enabled),updated_at=CURRENT_TIMESTAMP",
                user.toString(),enabled);
        return new Settings(user,enabled);
    }
}
