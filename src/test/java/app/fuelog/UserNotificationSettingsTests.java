package app.fuelog;

import app.fuelog.dto.PairingDtos;
import app.fuelog.service.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class UserNotificationSettingsTests {
    @Autowired JdbcTemplate db;
    @Autowired PairingService pairing;
    @Autowired UserNotificationSettingsService settings;
    UUID user; String bearer;
    @BeforeEach void setup() {
        TestSchema.create(db);
        db.execute("CREATE TABLE IF NOT EXISTS user_notification_settings(user_id CHAR(36) PRIMARY KEY,charge_complete_enabled BOOLEAN NOT NULL,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        var installation=pairing.createInstallation();
        bearer="Bearer "+installation.installation_secret(); user=UUID.randomUUID();
        var qr=pairing.issue(installation.installation_id(),"settings-test");
        var intent=pairing.intent(user,qr.id(),"settings-intent",new PairingDtos.IntentRequest(qr.qr().proof(),null,"설정 테스트"));
        pairing.confirm(installation.installation_id(),qr.id(),intent.confirmation_code());
    }
    @Test void preferencesPersistAndOtherUsersAreRejected() {
        assertFalse(settings.get(bearer,user).chargeCompleteEnabled());
        settings.save(bearer,user,true);
        assertTrue(settings.get(bearer,user).chargeCompleteEnabled());
        assertThrows(ResponseStatusException.class,()->settings.get(bearer,UUID.randomUUID()));
        assertThrows(ResponseStatusException.class,()->settings.save(bearer,UUID.randomUUID(),false));
        assertTrue(settings.get(bearer,user).chargeCompleteEnabled());
        settings.save(bearer,user,false);
        assertFalse(settings.get(bearer,user).chargeCompleteEnabled());
        assertThrows(ResponseStatusException.class,()->settings.get(null,user));
    }
}
