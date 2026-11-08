package app.fuelog;

import app.fuelog.dto.ConnectionDtos;
import app.fuelog.dto.ConnectionSettingsDtos;
import app.fuelog.dto.PairingDtos;
import app.fuelog.service.ConnectionService;
import app.fuelog.service.ConnectionSettingsService;
import app.fuelog.service.InstallationAccess;
import app.fuelog.service.PairingService;
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
class QrRescanTests {
    @Autowired JdbcTemplate db;
    @Autowired PairingService pairing;
    @Autowired ConnectionService connection;
    @Autowired ConnectionSettingsService settings;
    @Autowired InstallationAccess access;

    @BeforeEach
    void setup() {
        TestSchema.create(db);
    }

    @Test
    void completedLegacyQrReturnsExistingLoginEvenAfterQrExpiry() {
        var installation = pairing.createInstallation();
        UUID user = UUID.randomUUID();
        var qr = pairing.issue(installation.installation_id(), "issue");
        var input = new PairingDtos.PreviewRequest(qr.id(), qr.qr().proof());
        assertEquals("pending", pairing.preview(user, input).status());
        assertNull(pairing.preview(user, input).vehicle_id());
        var intent = pairing.intent(user, qr.id(), "intent",
                new PairingDtos.IntentRequest(qr.qr().proof(), null, "My car"));
        UUID vehicle = pairing.confirm(installation.installation_id(), qr.id(), intent.confirmation_code()).vehicle_id();
        db.update("UPDATE pairing_requests SET expires_at=TIMESTAMP '2000-01-01 00:00:00' WHERE id=?", qr.id().toString());

        var first = pairing.preview(user, input);
        assertEquals("completed", first.status());
        assertEquals(vehicle, first.vehicle_id());
        assertEquals(first, pairing.preview(user, input));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM pairing_requests", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM vehicles", Integer.class));

        assertThrows(ResponseStatusException.class, () -> pairing.preview(UUID.randomUUID(), input));
        db.update("DELETE FROM group_members WHERE vehicle_id=? AND user_id=?", vehicle.toString(), user.toString());
        assertThrows(ResponseStatusException.class, () -> pairing.preview(user, input));
    }

    @Test
    void completedBearerQrCannotRestoreBlockedUserOrAuthenticateAnotherAccount() {
        var installation = pairing.createInstallation();
        UUID user = UUID.randomUUID();
        var first = pairing.issue(installation.installation_id(), "initial");
        var intent = pairing.intent(user, first.id(), "intent",
                new PairingDtos.IntentRequest(first.qr().proof(), null, "My car"));
        UUID vehicle = pairing.confirm(installation.installation_id(), first.id(), intent.confirmation_code()).vehicle_id();
        settings.disconnect("Bearer " + installation.installation_secret(),
                new ConnectionSettingsDtos.Disconnect(ConnectionSettingsDtos.Kind.USER, "disconnect"));
        String bearer = "Bearer " + installation.installation_secret();
        // 차단 후에는 신규 QR 승인으로 다시 로그인한다. 이 요청의 재스캔만 완료 상태로 반환한다.
        var qr = connection.issue(bearer, new ConnectionDtos.RequestBody("again", null));
        var input = new ConnectionDtos.Envelope("fuelog.vehicle-registration", 1, qr.requestId(), qr.nonce());
        assertEquals("WAITING", connection.inspect(user, input).status());
        connection.approve(user, new ConnectionDtos.ApproveBody(qr.requestId(), qr.nonce(), vehicle, "approve"));
        db.update("UPDATE pairing_requests SET expires_at=TIMESTAMP '2000-01-01 00:00:00' WHERE id=?", qr.requestId().toString());

        var result = connection.inspect(user, input);
        assertEquals("COMPLETED", result.status());
        assertEquals(vehicle, result.vehicleId());
        assertEquals(0, result.remainingSeconds());
        assertEquals(result, connection.inspect(user, input));
        assertThrows(ResponseStatusException.class, () -> connection.inspect(UUID.randomUUID(), input));
        access.block(installation.installation_id());
        assertThrows(ResponseStatusException.class, () -> connection.inspect(user, input));
        assertNull(access.currentUser(installation.installation_id()));
    }
}
