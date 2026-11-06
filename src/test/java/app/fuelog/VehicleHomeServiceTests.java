package app.fuelog;

import app.fuelog.service.PairingService;
import app.fuelog.service.ConnectionService;
import app.fuelog.service.VehicleHomeService;
import app.fuelog.dto.VehicleHomeDtos;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VehicleHomeServiceTests {
    @Autowired JdbcTemplate db;
    @Autowired PairingService pairing;
    @Autowired app.fuelog.service.ConnectionSettingsService settings;
    @Autowired VehicleHomeService homeService;
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;

    private UUID installation;
    private UUID vehicle;
    private UUID user;
    private String bearer;

    @BeforeEach
    void setup() {
        TestSchema.create(db);
        var issued = pairing.createInstallation();
        installation = issued.installation_id();
        bearer = "Bearer " + issued.installation_secret();
        vehicle = UUID.randomUUID();
        user = UUID.randomUUID();
        db.update("INSERT INTO vehicles (id,name,created_by,created_at,updated_at) VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                vehicle.toString(), "My vehicle", user.toString());
        db.update("INSERT INTO group_members (vehicle_id,user_id,role,created_at) VALUES (?,?,?,CURRENT_TIMESTAMP)",
                vehicle.toString(), user.toString(), "MEMBER");
        db.update("UPDATE vehicle_installations SET vehicle_id=? WHERE id=?", vehicle.toString(), installation.toString());
        when(decoder.decode("mobile-user")).thenReturn(jwt(user));
    }

    @Test
    void privateHomeRequiresProofAndAnonymousIntervalStaysAnonymous() throws Exception {
        String initial = mvc.perform(get("/vehicle/home").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        assertEquals("My vehicle", JsonPath.read(initial, "$.vehicleAlias"));
        assertEquals("needs_user_proof", JsonPath.read(initial, "$.distanceStatus.state"));
        assertNull(JsonPath.read(initial, "$.distanceKm"));

        Instant started = Instant.now().minusSeconds(15).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String anonymous = startInterval("local-anon", started, null);
        String anonymousId = JsonPath.read(anonymous, "$.id");
        assertNull(JsonPath.read(anonymous, "$.subjectId"));
        String batch = "{\"batchId\":\"batch-anon\",\"samples\":[{\"sequence\":1,\"observedAt\":\""
                + started.plusSeconds(3) + "\",\"distanceKm\":0.250,\"fuelLiters\":-1.0}]}";
        mvc.perform(post("/recording-intervals/" + anonymousId + "/samples")
                        .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isOk());
        assertEquals("error", db.queryForObject("SELECT fuel_status FROM recording_samples WHERE interval_id=?", String.class, anonymousId));
        assertEquals("0.250", db.queryForObject("SELECT distance_km FROM recording_samples WHERE interval_id=?", java.math.BigDecimal.class, anonymousId).toPlainString());
        finish(anonymousId, "finish-anon", started.plusSeconds(10), 2);

        String challenge = challenge("challenge-one");
        String challengeId = JsonPath.read(challenge, "$.challengeId");
        String proof = JsonPath.read(challenge, "$.proof");
        String retry = challenge("challenge-one");
        assertEquals(challengeId, JsonPath.read(retry, "$.challengeId"));
        assertEquals(proof, JsonPath.read(retry, "$.proof"));
        String approval = "{\"challengeId\":\"" + challengeId + "\",\"proof\":\"" + proof
                + "\",\"vehicleId\":\"" + vehicle + "\"}";
        mvc.perform(post("/mobile/trip-user/approve").contentType(MediaType.APPLICATION_JSON).content(approval))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/mobile/trip-user/approve").header("Authorization", "Bearer mobile-user")
                        .contentType(MediaType.APPLICATION_JSON).content(approval))
                .andExpect(status().isOk());
        mvc.perform(post("/mobile/trip-user/approve").header("Authorization", "Bearer mobile-user")
                        .contentType(MediaType.APPLICATION_JSON).content(approval))
                .andExpect(status().isConflict());

        String session = mvc.perform(post("/trip-user/session").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + challengeId + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals("ACTIVE", JsonPath.read(session, "$.status"));
        assertEquals("Driver One", JsonPath.read(session, "$.displayName"));
        assertEquals(user.toString(), JsonPath.read(session, "$.subjectId"));
        String scoped = "Bearer " + JsonPath.read(session, "$.scopedToken");
        String sessionRetry = mvc.perform(post("/trip-user/session").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + challengeId + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals((String) JsonPath.read(session, "$.scopedToken"),
                (String) JsonPath.read(sessionRetry, "$.scopedToken"));

        String afterApproval = mvc.perform(get("/vehicle/home").header("Authorization", bearer)
                        .header("X-Trip-User-Session", scoped))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertNull(JsonPath.read(afterApproval, "$.distanceKm"));
        assertEquals("unsupported", JsonPath.read(afterApproval, "$.distanceStatus.state"));

        String named = startInterval("local-user", started.plusSeconds(20), scoped);
        String namedId = JsonPath.read(named, "$.id");
        assertEquals(user.toString(), JsonPath.read(named, "$.subjectId"));
        String namedBatch = "{\"batchId\":\"batch-user\",\"samples\":["
                + "{\"sequence\":1,\"observedAt\":\"" + started.plusSeconds(23) + "\",\"distanceKm\":0.250},"
                + "{\"sequence\":2,\"observedAt\":\"" + started.plusSeconds(26) + "\",\"missingReason\":\"odometer_reset\"},"
                + "{\"sequence\":3,\"observedAt\":\"" + started.plusSeconds(29) + "\",\"distanceKm\":0.150,\"fuelLiters\":-1.0}]}";
        mvc.perform(post("/recording-intervals/" + namedId + "/samples")
                        .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(namedBatch))
                .andExpect(status().isOk()).andExpect(jsonPath("$.acceptedCount").value(3));
        mvc.perform(post("/recording-intervals/" + namedId + "/samples")
                        .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(namedBatch))
                .andExpect(status().isOk()).andExpect(jsonPath("$.acceptedCount").value(3));
        assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM recording_samples WHERE interval_id=?", Integer.class, namedId));
        mvc.perform(post("/recording-intervals/" + namedId + "/samples")
                        .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchId\":\"invalid-distance\",\"samples\":[{\"sequence\":4,\"observedAt\":\""
                                + started.plusSeconds(32) + "\",\"distanceKm\":-0.1}]}"))
                .andExpect(status().isBadRequest());
        assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM recording_samples WHERE interval_id=?", Integer.class, namedId));
        finish(namedId, "finish-user", started.plusSeconds(40), 5);
        finish(namedId, "finish-user", started.plusSeconds(40), 5);
        mvc.perform(post("/recording-intervals/" + namedId + "/samples")
                        .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchId\":\"late\",\"samples\":[{\"sequence\":6,\"observedAt\":\""
                                + started.plusSeconds(42) + "\",\"distanceKm\":0.010}]}"))
                .andExpect(status().isConflict());
        String home = mvc.perform(get("/vehicle/home").header("Authorization", bearer)
                        .header("X-Trip-User-Session", scoped))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(0, new java.math.BigDecimal(JsonPath.read(home, "$.distanceKm").toString())
                .compareTo(new java.math.BigDecimal("0.400")));
        assertEquals("error", JsonPath.read(home, "$.fuelStatus.state"));
        assertEquals("error", JsonPath.read(home, "$.costStatus.state"));
        assertNull(JsonPath.read(home, "$.estimatedCost"));
        assertNull(JsonPath.read(home, "$.fuelReviewCount"));
        String sync = mvc.perform(get("/sync/status").header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(3, ((Number) JsonPath.read(sync, "$.lastAcceptedSequence")).intValue());
        assertNull(JsonPath.read(sync, "$.pendingCount"));

        mvc.perform(delete("/trip-user/session").header("Authorization", bearer)
                        .header("X-Trip-User-Session", scoped))
                .andExpect(status().isOk());
        mvc.perform(get("/vehicle/home").header("Authorization", bearer)
                        .header("X-Trip-User-Session", scoped))
                .andExpect(status().isUnauthorized());
        String privateHidden = mvc.perform(get("/vehicle/home").header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals("needs_user_proof", JsonPath.read(privateHidden, "$.distanceStatus.state"));
        assertNull(JsonPath.read(privateHidden, "$.distanceKm"));
    }

    @Test
    void challengeExpiryAndReissueRejectOldProof() throws Exception {
        String old = challenge("one");
        String oldId = JsonPath.read(old, "$.challengeId");
        String oldProof = JsonPath.read(old, "$.proof");
        String next = challenge("two");
        assertNotEquals(oldId, JsonPath.read(next, "$.challengeId"));
        mvc.perform(post("/mobile/trip-user/approve").header("Authorization", "Bearer mobile-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + oldId + "\",\"proof\":\"" + oldProof
                                + "\",\"vehicleId\":\"" + vehicle + "\"}"))
                .andExpect(status().isGone());
        String nextId = JsonPath.read(next, "$.challengeId");
        db.update("UPDATE trip_user_challenges SET expires_at=? WHERE id=?",
                java.time.LocalDateTime.ofInstant(Instant.now().minusSeconds(1), java.time.ZoneOffset.UTC), nextId);
        mvc.perform(post("/mobile/trip-user/approve").header("Authorization", "Bearer mobile-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + nextId + "\",\"proof\":\""
                                + JsonPath.read(next, "$.proof") + "\",\"vehicleId\":\"" + vehicle + "\"}"))
                .andExpect(status().isGone());
    }

    @Test
    void switchingUserRevokesOldSessionAndDoesNotReturnTheirDistance() throws Exception {
        String first = challenge("first-user");
        String firstId = JsonPath.read(first, "$.challengeId");
        approveChallenge(first, "mobile-user");
        String firstSession = session(firstId);
        String oldScoped = "Bearer " + JsonPath.read(firstSession, "$.scopedToken");
        Instant started = Instant.now().minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String intervalId = JsonPath.read(startInterval("old-user-trip", started, oldScoped), "$.id");
        mvc.perform(post("/recording-intervals/" + intervalId + "/samples")
                        .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchId\":\"old-user-batch\",\"samples\":[{\"sequence\":1,\"observedAt\":\""
                                + started.plusSeconds(3) + "\",\"distanceKm\":0.500}]}"))
                .andExpect(status().isOk());

        String second = challenge("second-user");
        String secondId = JsonPath.read(second, "$.challengeId");
        mvc.perform(get("/vehicle/home").header("Authorization", bearer)
                        .header("X-Trip-User-Session", oldScoped))
                .andExpect(status().isUnauthorized());
        assertEquals("REVOKED", JsonPath.read(session(firstId), "$.status"));

        UUID secondUser = UUID.randomUUID();
        db.update("INSERT INTO group_members (vehicle_id,user_id,role,created_at) VALUES (?,?,?,CURRENT_TIMESTAMP)",
                vehicle.toString(), secondUser.toString(), "MEMBER");
        when(decoder.decode("mobile-second")).thenReturn(jwt("mobile-second", secondUser, "Driver Two"));
        approveChallenge(second, "mobile-second");
        String secondSession = session(secondId);
        assertTrue(((Number) JsonPath.read(secondSession, "$.sessionVersion")).longValue()
                > ((Number) JsonPath.read(firstSession, "$.sessionVersion")).longValue());
        String secondScoped = "Bearer " + JsonPath.read(secondSession, "$.scopedToken");
        String newHome = mvc.perform(get("/vehicle/home").header("Authorization", bearer)
                        .header("X-Trip-User-Session", secondScoped))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertNull(JsonPath.read(newHome, "$.distanceKm"));
        assertEquals("unsupported", JsonPath.read(newHome, "$.distanceStatus.state"));
    }

    @Test
    void userDisconnectRevokesTripSessionAndKeepsRecordingHistory() throws Exception {
        String challenge = challenge("before-reset");
        approveChallenge(challenge, "mobile-user");
        String active = session(JsonPath.read(challenge, "$.challengeId"));
        String scoped = "Bearer " + JsonPath.read(active, "$.scopedToken");
        Instant started = Instant.now().minusSeconds(5).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String intervalId = JsonPath.read(startInterval("retained-trip", started, scoped), "$.id");

        db.update("UPDATE group_members SET role='OWNER' WHERE vehicle_id=? AND user_id=?",
                vehicle.toString(), user.toString());
        settings.disconnect(bearer,new app.fuelog.dto.ConnectionSettingsDtos.Disconnect(
                app.fuelog.dto.ConnectionSettingsDtos.Kind.USER,"preserve-recording"));
        mvc.perform(get("/vehicle/home").header("Authorization", bearer)
                        .header("X-Trip-User-Session", scoped))
                .andExpect(status().isUnauthorized());
        assertEquals("revoked", db.queryForObject("SELECT status FROM trip_user_sessions", String.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM recording_intervals WHERE id=?", Integer.class, intervalId));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM vehicles WHERE id=?", Integer.class, vehicle.toString()));
    }

    @Test
    void outOfOrderOfflineBatchesKeepHighestAcceptedSequence() throws Exception {
        Instant started = Instant.now().minusSeconds(20).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String intervalId = JsonPath.read(startInterval("offline-order", started, null), "$.id");
        String later = "{\"batchId\":\"later\",\"samples\":[{\"sequence\":10,\"observedAt\":\""
                + started.plusSeconds(15) + "\",\"distanceKm\":0.100}]}";
        String earlier = "{\"batchId\":\"earlier\",\"samples\":[{\"sequence\":2,\"observedAt\":\""
                + started.plusSeconds(3) + "\",\"distanceKm\":0.050}]}";
        mvc.perform(post("/recording-intervals/" + intervalId + "/samples")
                        .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(later))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lastAcceptedSequence").value(10));
        mvc.perform(post("/recording-intervals/" + intervalId + "/samples")
                        .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(earlier))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lastAcceptedSequence").value(10));
        mvc.perform(get("/sync/status").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lastAcceptedSequence").value(10));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM recording_samples WHERE interval_id=?", Integer.class, intervalId));
    }

    @Test
    void concurrentMobileApprovalsIssueOnlyOneSession() throws Exception {
        UUID otherUser = UUID.randomUUID();
        db.update("INSERT INTO group_members (vehicle_id,user_id,role,created_at) VALUES (?,?,?,CURRENT_TIMESTAMP)",
                vehicle.toString(), otherUser.toString(), "MEMBER");
        String challenge = challenge("concurrent-approval");
        var body = new VehicleHomeDtos.MobileApprovalRequest(
                UUID.fromString(JsonPath.read(challenge, "$.challengeId")),
                JsonPath.read(challenge, "$.proof"), vehicle);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> approveAfterLatch(start, jwt(user), body));
            var second = pool.submit(() -> approveAfterLatch(start,
                    jwt("other", otherUser, "Other"), body));
            start.countDown();
            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
        }
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM trip_user_sessions WHERE status='active'", Integer.class));
    }

    private boolean approveAfterLatch(java.util.concurrent.CountDownLatch start, Jwt jwt,
            VehicleHomeDtos.MobileApprovalRequest body) throws InterruptedException {
        start.await();
        try {
            homeService.approve(jwt, body);
            return true;
        } catch (org.springframework.web.server.ResponseStatusException ex) {
            assertEquals(409, ex.getStatusCode().value());
            return false;
        }
    }

    private void approveChallenge(String challengeJson, String token) throws Exception {
        mvc.perform(post("/mobile/trip-user/approve").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + JsonPath.read(challengeJson, "$.challengeId")
                                + "\",\"proof\":\"" + JsonPath.read(challengeJson, "$.proof")
                                + "\",\"vehicleId\":\"" + vehicle + "\"}"))
                .andExpect(status().isOk());
    }

    private String session(String challengeId) throws Exception {
        return mvc.perform(post("/trip-user/session").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + challengeId + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String challenge(String operation) throws Exception {
        return mvc.perform(post("/trip-user/challenges").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"installationId\":\"" + installation + "\",\"operationId\":\"" + operation + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String startInterval(String localId, Instant started, String scoped) throws Exception {
        var request = post("/recording-intervals").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"localIntervalId\":\"" + localId + "\",\"startedAt\":\"" + started
                        + "\",\"source\":\"manual_app\"}");
        if (scoped != null) request.header("X-Trip-User-Session", scoped);
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void finish(String intervalId, String operation, Instant ended, long lastSequence) throws Exception {
        mvc.perform(post("/recording-intervals/" + intervalId + "/finish")
                        .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operationId\":\"" + operation + "\",\"endedAt\":\"" + ended
                                + "\",\"lastSequence\":" + lastSequence + "}"))
                .andExpect(status().isOk());
    }

    private static Jwt jwt(UUID subject) {
        return jwt("mobile-user", subject, "Driver One");
    }

    private static Jwt jwt(String token, UUID subject, String name) {
        return Jwt.withTokenValue(token).header("alg", "ES256")
                .subject(subject.toString()).claim("role", "authenticated")
                .claim("is_anonymous", false).claim("aud", List.of("authenticated"))
                .claim("email", "driver@example.com")
                .claim("user_metadata", Map.of("full_name", name))
                .issuedAt(Instant.now().minusSeconds(5)).expiresAt(Instant.now().plusSeconds(300)).build();
    }
}
