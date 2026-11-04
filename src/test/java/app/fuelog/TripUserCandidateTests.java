package app.fuelog;

import app.fuelog.dto.VehicleHomeDtos;
import app.fuelog.service.PairingService;
import app.fuelog.service.VehicleHomeService;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
class TripUserCandidateTests {
    @Autowired JdbcTemplate db;
    @Autowired PairingService pairing;
    @Autowired VehicleHomeService home;
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;

    private UUID installation;
    private UUID vehicle;
    private UUID interval;
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
                vehicle.toString(), "Vehicle", user.toString());
        db.update("INSERT INTO group_members (vehicle_id,user_id,role,created_at) VALUES (?,?,?,CURRENT_TIMESTAMP)",
                vehicle.toString(), user.toString(), "MEMBER");
        db.update("UPDATE vehicle_installations SET vehicle_id=? WHERE id=?", vehicle.toString(), installation.toString());
        when(decoder.decode("mobile-user")).thenReturn(jwt(user));
        when(decoder.decode("mobile.user.token")).thenReturn(jwt(user));
        interval = home.start(bearer, null, new VehicleHomeDtos.IntervalStartRequest(
                "c03-local", Instant.now().minusSeconds(20).truncatedTo(ChronoUnit.SECONDS), "manual_app")).id();
    }

    @Test
    void consentVerifyUploadAndRevokeKeepPriorSamplesUnchanged() throws Exception {
        String challenge = challenge(interval);
        String challengeId = JsonPath.read(challenge, "$.challengeId");
        String proof = JsonPath.read(challenge, "$.proof");
        assertEquals("PENDING", JsonPath.read(challenge, "$.status"));
        mvc.perform(post("/mobile/trip-user/approve").header("Authorization", "Bearer mobile-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + challengeId + "\",\"proof\":\"" + proof
                                + "\",\"vehicleId\":\"" + vehicle + "\"}"))
                .andExpect(status().isConflict());

        String consent = consent(challengeId, proof, interval, vehicle, installation, "mobile-op-1", "mobile-user");
        String candidateId = JsonPath.read(consent, "$.candidateId");
        assertEquals(candidateId, JsonPath.read(consent, "$.consentId"));
        assertEquals(candidateId, JsonPath.read(consent(challengeId, proof, interval, vehicle,
                installation, "mobile-op-1", "mobile-user"), "$.candidateId"));
        mvc.perform(post("/mobile/trip-user/consent").header("Authorization", "Bearer mobile-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consentBody(challengeId, proof, interval, vehicle, installation, "different-op")))
                .andExpect(status().isConflict());

        String candidates = candidates(interval);
        assertEquals(candidateId, JsonPath.read(candidates, "$.candidates[0].candidateId"));
        assertEquals("Driver One", JsonPath.read(candidates, "$.candidates[0].displayName"));
        assertEquals(true, JsonPath.read(candidates, "$.candidates[0].vehicleMember"));
        assertEquals(true, JsonPath.read(candidates, "$.candidates[0].mobileConsented"));
        int remaining = ((Number) JsonPath.read(candidates, "$.candidates[0].remainingSeconds")).intValue();
        assertTrue(remaining >= 1 && remaining <= 300);

        String events = mvc.perform(get("/trip-user/events").header("Authorization", bearer).param("cursor", "0"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals("candidates_changed", JsonPath.read(events, "$.events[0].type"));
        assertFalse(events.contains("Driver One"));
        assertFalse(events.contains(candidateId));
        long cursor = ((Number) JsonPath.read(events, "$.nextCursor")).longValue();

        sample(1, null);
        String verified = verify(interval, candidateId, "verify-1");
        assertEquals(user.toString(), JsonPath.read(verified, "$.subjectId"));
        assertEquals(true, JsonPath.read(verified, "$.vehicleMember"));
        assertEquals(true, JsonPath.read(verified, "$.mobileConsented"));
        assertTrue(((Number) JsonPath.read(verified, "$.expiresAtEpochMillis")).longValue() > Instant.now().toEpochMilli());
        assertEquals(verified, verify(interval, candidateId, "verify-1"));
        mvc.perform(post("/trip-user/verify").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody(interval, candidateId, "verify-2")))
                .andExpect(status().isConflict());
        mvc.perform(post("/trip-user/verify").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody(interval, UUID.randomUUID().toString(), "verify-1")))
                .andExpect(status().isConflict());
        assertEquals(0, ((List<?>) JsonPath.read(candidates(interval), "$.candidates")).size());

        sample(2, candidateId);
        assertNull(db.queryForObject("SELECT user_id FROM recording_samples WHERE interval_id=? AND sequence=1", String.class, interval.toString()));
        assertEquals(user.toString(), db.queryForObject("SELECT user_id FROM recording_samples WHERE interval_id=? AND sequence=2", String.class, interval.toString()));
        assertNull(db.queryForObject("SELECT user_id FROM recording_intervals WHERE id=?", String.class, interval.toString()));

        mvc.perform(post("/trip-user/revoke").header("Authorization", "Bearer mobile.user.token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consentId\":\"" + candidateId + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/recording-intervals/" + interval + "/samples")
                        .header("Authorization", bearer).header("X-Trip-User-Candidate", candidateId)
                        .contentType(MediaType.APPLICATION_JSON).content(sampleBody(3)))
                .andExpect(status().isGone());
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM recording_samples WHERE interval_id=?", Integer.class, interval.toString()));
        String changed = mvc.perform(get("/trip-user/events").header("Authorization", bearer)
                        .param("cursor", Long.toString(cursor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(((List<?>) JsonPath.read(changed, "$.events")).size() >= 1);
        assertFalse(changed.contains("Driver One"));
    }

    @Test
    void rejectsWrongScopeExpiredProofAndRemovedMembership() throws Exception {
        String challenge = challenge(interval);
        String id = JsonPath.read(challenge, "$.challengeId");
        String proof = JsonPath.read(challenge, "$.proof");
        mvc.perform(post("/mobile/trip-user/consent").header("Authorization", "Bearer mobile-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consentBody(id, proof, interval, UUID.randomUUID(), installation, "wrong-vehicle")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/mobile/trip-user/consent").header("Authorization", "Bearer mobile-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consentBody(id, proof, interval, vehicle, UUID.randomUUID(), "wrong-installation")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/mobile/trip-user/consent").header("Authorization", "Bearer mobile-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consentBody(id, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                                interval, vehicle, installation, "bad-proof")))
                .andExpect(status().isForbidden());
        String candidate = JsonPath.read(consent(id, proof, interval, vehicle, installation,
                "valid", "mobile-user"), "$.candidateId");

        UUID otherInterval = home.start(bearer, null, new VehicleHomeDtos.IntervalStartRequest(
                "other-local", Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.SECONDS), "manual_app")).id();
        mvc.perform(post("/trip-user/verify").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody(otherInterval, candidate, "wrong-interval")))
                .andExpect(status().isForbidden());
        db.update("DELETE FROM group_members WHERE vehicle_id=? AND user_id=?", vehicle.toString(), user.toString());
        assertEquals(0, ((List<?>) JsonPath.read(candidates(interval), "$.candidates")).size());
        mvc.perform(post("/trip-user/verify").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody(interval, candidate, "after-removal")))
                .andExpect(status().isForbidden());
        db.update("INSERT INTO group_members (vehicle_id,user_id,role,created_at) VALUES (?,?,?,CURRENT_TIMESTAMP)",
                vehicle.toString(), user.toString(), "MEMBER");
        db.update("UPDATE trip_user_candidates SET expires_at=? WHERE id=?",
                java.time.LocalDateTime.ofInstant(Instant.now().minusSeconds(1), java.time.ZoneOffset.UTC), candidate);
        mvc.perform(post("/trip-user/verify").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody(interval, candidate, "after-expiry")))
                .andExpect(status().isGone());
    }

    @Test
    void membershipRemovalBlocksVerifiedSampleUpload() throws Exception {
        String challenge = challenge(interval);
        String candidate = JsonPath.read(consent(JsonPath.read(challenge, "$.challengeId"),
                JsonPath.read(challenge, "$.proof"), interval, vehicle, installation,
                "consent-removed", "mobile-user"), "$.candidateId");
        verify(interval, candidate, "verify-removed");
        db.update("DELETE FROM group_members WHERE vehicle_id=? AND user_id=?", vehicle.toString(), user.toString());
        mvc.perform(post("/recording-intervals/" + interval + "/samples")
                        .header("Authorization", bearer).header("X-Trip-User-Candidate", candidate)
                        .contentType(MediaType.APPLICATION_JSON).content(sampleBody(1)))
                .andExpect(status().isForbidden());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM recording_samples WHERE interval_id=?",
                Integer.class, interval.toString()));
    }

    @Test
    void candidateCannotBeVerifiedByAnotherInstallation() throws Exception {
        String challenge = challenge(interval);
        String candidate = JsonPath.read(consent(JsonPath.read(challenge, "$.challengeId"),
                JsonPath.read(challenge, "$.proof"), interval, vehicle, installation,
                "consent-cross-installation", "mobile-user"), "$.candidateId");
        var other = pairing.createInstallation();
        UUID otherVehicle = UUID.randomUUID();
        db.update("INSERT INTO vehicles (id,name,created_by,created_at,updated_at) VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                otherVehicle.toString(), "Other", user.toString());
        db.update("UPDATE vehicle_installations SET vehicle_id=? WHERE id=?", otherVehicle.toString(),
                other.installation_id().toString());
        mvc.perform(post("/trip-user/verify")
                        .header("Authorization", "Bearer " + other.installation_secret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody(interval, candidate, "cross-installation")))
                .andExpect(status().isForbidden());
    }

    private String challenge(UUID intervalId) throws Exception {
        return mvc.perform(post("/trip-user/challenges").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intervalId\":\"" + intervalId + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String consent(String challengeId, String proof, UUID intervalId, UUID vehicleId,
                           UUID installationId, String operation, String token) throws Exception {
        return mvc.perform(post("/mobile/trip-user/consent").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consentBody(challengeId, proof, intervalId, vehicleId, installationId, operation)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String consentBody(String challengeId, String proof, UUID intervalId, UUID vehicleId,
                                      UUID installationId, String operation) {
        return "{\"vehicleId\":\"" + vehicleId + "\",\"installationId\":\"" + installationId
                + "\",\"intervalId\":\"" + intervalId + "\",\"challengeId\":\"" + challengeId
                + "\",\"proof\":\"" + proof + "\",\"operationId\":\"" + operation + "\"}";
    }

    private String candidates(UUID intervalId) throws Exception {
        return mvc.perform(post("/trip-user/candidates").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intervalId\":\"" + intervalId + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String verify(UUID intervalId, String candidateId, String operation) throws Exception {
        return mvc.perform(post("/trip-user/verify").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody(intervalId, candidateId, operation)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String verifyBody(UUID intervalId, String candidateId, String operation) {
        return "{\"intervalId\":\"" + intervalId + "\",\"candidateId\":\"" + candidateId
                + "\",\"operationId\":\"" + operation + "\"}";
    }

    private void sample(int sequence, String candidateId) throws Exception {
        var request = post("/recording-intervals/" + interval + "/samples")
                .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content(sampleBody(sequence));
        if (candidateId != null) request.header("X-Trip-User-Candidate", candidateId);
        mvc.perform(request).andExpect(status().isOk());
    }

    private static String sampleBody(int sequence) {
        return "{\"batchId\":\"batch-" + sequence + "\",\"samples\":[{\"sequence\":" + sequence
                + ",\"observedAt\":\"" + Instant.now().truncatedTo(ChronoUnit.SECONDS)
                + "\",\"distanceKm\":0.100}]}";
    }

    private static Jwt jwt(UUID subject) {
        return Jwt.withTokenValue("mobile-user").header("alg", "ES256")
                .subject(subject.toString()).claim("role", "authenticated")
                .claim("is_anonymous", false).claim("aud", List.of("authenticated"))
                .claim("user_metadata", Map.of("full_name", "Driver One"))
                .issuedAt(Instant.now().minusSeconds(5)).expiresAt(Instant.now().plusSeconds(300)).build();
    }
}
