package app.fuelog;

import app.fuelog.dto.PairingDtos;
import app.fuelog.dto.VehicleHomeDtos;
import app.fuelog.dto.TripSummaryDtos;
import app.fuelog.dto.ConnectionSettingsDtos;
import app.fuelog.service.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class PersistentPairingUserTests {
    @Autowired JdbcTemplate db;
    @Autowired PairingService pairing;
    @Autowired VehicleHomeService home;
    @Autowired ConnectionSettingsService settings;
    @Autowired TripSummaryService summaries;
    @Autowired MaintenanceService maintenance;
    @Autowired ObjectMapper json;
    UUID installation, vehicle, user;
    String bearer;

    @BeforeEach void setup() {
        TestSchema.create(db);
        var issued = pairing.createInstallation();
        installation = issued.installation_id();
        bearer = "Bearer " + issued.installation_secret();
        user = UUID.randomUUID();
        vehicle = pair(user);
    }

    private UUID pair(UUID owner) {
        UUID known = pairing.installation(installation).vehicle_id();
        if (known != null) db.update("INSERT INTO group_members (vehicle_id,user_id,role,created_at) VALUES (?,?,'MANAGER',CURRENT_TIMESTAMP)", known.toString(), owner.toString());
        var issued = pairing.issue(installation, "issue-" + UUID.randomUUID());
        var intent = pairing.intent(owner, issued.id(), "intent-" + UUID.randomUUID(),
                new PairingDtos.IntentRequest(issued.qr().proof(), known, known == null ? "Car" : null));
        return pairing.confirm(installation, issued.id(), intent.confirmation_code()).vehicle_id();
    }

    @Test void restartAndExpiredQrKeepUserAndSummaryNeedsNoIntervalConsent() {
        db.update("UPDATE pairing_requests SET expires_at=TIMESTAMP '2000-01-01 00:00:00'");
        assertEquals(user, pairing.installation(installation).authenticated_user_id());
        Instant start = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.SECONDS);
        var request = new VehicleHomeDtos.IntervalStartRequest("owned", start, "manual_app", user);
        var interval = home.start(bearer, null, request);
        assertEquals(user, interval.subjectId());
        assertEquals(interval.id(), home.start(bearer, null, request).id());
        var samples = json.readValue("{\"batchId\":\"batch\",\"samples\":[{\"sequence\":1,\"observedAt\":\"" + start.plusSeconds(1) + "\",\"distanceKm\":1.0}]}", VehicleHomeDtos.SamplesRequest.class);
        home.samples(bearer, interval.id(), null, samples);
        assertEquals(user.toString(), db.queryForObject("SELECT user_id FROM recording_samples", String.class));
        Instant end = start.plusSeconds(5);
        home.finish(bearer, interval.id(), new VehicleHomeDtos.FinishRequest("finish", end, 1L));
        var upload = json.readValue("{\"tripId\":\"" + interval.id() + "\",\"revision\":1,\"source\":\"manual_app\",\"startedAt\":\"" + start + "\",\"endedAt\":\"" + end + "\",\"endEstimated\":false,\"distanceKm\":1.0,\"distanceValid\":true,\"fuelEndpointsValid\":false,\"missing\":false,\"refueled\":false,\"ownerChanged\":false,\"priceValid\":false}", TripSummaryDtos.Upload.class);
        summaries.upload(bearer, interval.id(), upload);
        summaries.summary(bearer, interval.id(), null);
        summaries.deliver(bearer, interval.id(), null, new TripSummaryDtos.Deliver("mobile-inbox:" + interval.id(), 1L));
        assertEquals(user.toString(), db.queryForObject("SELECT user_id FROM trip_summaries", String.class));
        assertEquals(user.toString(), db.queryForObject("SELECT recipient_id FROM mobile_trip_inbox", String.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM trip_user_challenges", Integer.class));
        settings.disconnect(bearer, new ConnectionSettingsDtos.Disconnect(ConnectionSettingsDtos.Kind.USER, "switch"));
        pair(UUID.randomUUID());
        assertThrows(ResponseStatusException.class, () -> summaries.summary(bearer, interval.id(), null));
        assertEquals(user.toString(), db.queryForObject("SELECT user_id FROM trip_summaries", String.class));
    }

    @Test void disconnectAndRePairKeepOldOwnerAndRejectAnOldQueuedStart() {
        var old = home.start(bearer, null, new VehicleHomeDtos.IntervalStartRequest("old", Instant.now(), "manual_app", user));
        settings.disconnect(bearer, new ConnectionSettingsDtos.Disconnect(ConnectionSettingsDtos.Kind.USER, "disconnect"));
        assertFalse(pairing.installation(installation).paired());
        assertNull(pairing.installation(installation).authenticated_user_id());
        assertEquals(vehicle, pairing.installation(installation).vehicle_id());
        assertNotNull(maintenance.summary(bearer));
        UUID next = UUID.randomUUID();
        pair(next);
        assertEquals(next, pairing.installation(installation).authenticated_user_id());
        assertEquals(user.toString(), db.queryForObject("SELECT user_id FROM recording_intervals WHERE id=?", String.class, old.id().toString()));
        assertThrows(ResponseStatusException.class, () -> home.start(bearer, null,
                new VehicleHomeDtos.IntervalStartRequest("queued-before-change", Instant.now(), "manual_app", user)));
        var current = home.start(bearer, null, new VehicleHomeDtos.IntervalStartRequest("next", Instant.now(), "manual_app", next));
        assertEquals(next, current.subjectId());
        settings.disconnect(bearer, new ConnectionSettingsDtos.Disconnect(ConnectionSettingsDtos.Kind.USER, "disconnect"));
        assertTrue(pairing.installation(installation).paired());
    }

    @Test void removedMembershipCannotFallBackToAnotherUser() {
        db.update("DELETE FROM group_members WHERE vehicle_id=? AND user_id=?", vehicle.toString(), user.toString());
        assertNull(pairing.installation(installation).authenticated_user_id());
        assertThrows(ResponseStatusException.class, () -> home.start(bearer, null,
                new VehicleHomeDtos.IntervalStartRequest("revoked", Instant.now(), "manual_app", user)));
    }
}
