package app.fuelog;

import app.fuelog.dto.ConnectionDtos;
import app.fuelog.dto.ConnectionSettingsDtos;
import app.fuelog.dto.PairingDtos;
import app.fuelog.service.ConnectionService;
import app.fuelog.service.ConnectionSettingsService;
import app.fuelog.service.PairingService;
import app.fuelog.service.VehicleHomeService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ConnectionAliasTests {
    @Autowired JdbcTemplate db;
    @Autowired PairingService pairing;
    @Autowired ConnectionService connection;
    @Autowired ConnectionSettingsService settings;
    @Autowired VehicleHomeService home;
    UUID installation, vehicle, user;
    String bearer;

    @BeforeEach
    void setup() {
        TestSchema.create(db);
        var issued = pairing.createInstallation();
        installation = issued.installation_id();
        bearer = "Bearer " + issued.installation_secret();
        user = UUID.randomUUID();
        var qr = pairing.issue(installation, "initial");
        var intent = pairing.intent(user, qr.id(), "initial-intent",
                new PairingDtos.IntentRequest(qr.qr().proof(), null, "  내 출퇴근 차  "));
        vehicle = pairing.confirm(installation, qr.id(), intent.confirmation_code()).vehicle_id();
        db.update("UPDATE vehicles SET name='아반떼' WHERE id=?", vehicle.toString());
    }

    @Test
    void homeUsesOnlyTheCompletedAliasOfTheCurrentUser() {
        var initial = home.home(bearer, null);
        assertEquals("아반떼", initial.vehicleAlias());
        assertEquals("내 출퇴근 차", initial.connectionAlias());

        settings.disconnect(bearer, new ConnectionSettingsDtos.Disconnect(ConnectionSettingsDtos.Kind.USER, "disconnect"));
        assertNull(home.home(bearer, null).connectionAlias());

        UUID next = UUID.randomUUID();
        db.update("INSERT INTO group_members (vehicle_id,user_id,role,created_at) VALUES (?,?,'MANAGER',CURRENT_TIMESTAMP)",
                vehicle.toString(), next.toString());
        var qr = pairing.issue(installation, "next");
        var intent = pairing.intent(next, qr.id(), "next-intent",
                new PairingDtos.IntentRequest(qr.qr().proof(), null, "주말 나들이 차"));
        assertNull(home.home(bearer, null).connectionAlias());
        pairing.confirm(installation, qr.id(), intent.confirmation_code());
        assertEquals("주말 나들이 차", home.home(bearer, null).connectionAlias());
        assertEquals("아반떼", home.home(bearer, null).vehicleAlias());

        db.update("DELETE FROM group_members WHERE vehicle_id=? AND user_id=?", vehicle.toString(), next.toString());
        assertNull(home.home(bearer, null).connectionAlias());
    }

    @Test
    void connectionWithoutAnInputAliasUsesVehicleNameInsteadOfThePreviousUsersAlias() {
        settings.disconnect(bearer, new ConnectionSettingsDtos.Disconnect(ConnectionSettingsDtos.Kind.USER, "disconnect"));
        var qr = connection.issue(bearer, new ConnectionDtos.RequestBody("bearer-qr", null));
        connection.approve(user, new ConnectionDtos.ApproveBody(qr.requestId(), qr.nonce(), vehicle, "approve"));

        assertEquals("아반떼", home.home(bearer, null).connectionAlias());
    }
}
