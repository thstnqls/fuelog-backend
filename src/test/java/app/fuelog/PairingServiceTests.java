package app.fuelog;

import app.fuelog.dto.PairingDtos;
import app.fuelog.service.PairingService;
import java.util.UUID;
import java.util.List;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.jayway.jsonpath.JsonPath;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PairingServiceTests {
    @Autowired JdbcTemplate db;
    @Autowired PairingService service;
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;

    @BeforeEach
    void schema() {
        db.execute("DROP TABLE IF EXISTS group_members");
        db.execute("DROP TABLE IF EXISTS pairing_requests");
        db.execute("DROP TABLE IF EXISTS vehicle_installations");
        db.execute("DROP TABLE IF EXISTS vehicles");
        db.execute("CREATE TABLE vehicles (id VARCHAR(36) PRIMARY KEY, name VARCHAR(100), created_by VARCHAR(36), source_request_id VARCHAR(36) UNIQUE, created_at TIMESTAMP, updated_at TIMESTAMP)");
        db.execute("CREATE TABLE vehicle_installations (id VARCHAR(36) PRIMARY KEY, vehicle_id VARCHAR(36) UNIQUE, credential_hash BINARY(32), created_at TIMESTAMP, updated_at TIMESTAMP)");
        db.execute("CREATE TABLE pairing_requests (id VARCHAR(36) PRIMARY KEY, installation_id VARCHAR(36), active_installation_id VARCHAR(36) UNIQUE, proof_hash BINARY(32), status VARCHAR(40), expires_at TIMESTAMP, selected_vehicle_id VARCHAR(36), new_vehicle_name VARCHAR(100), approved_user_id VARCHAR(36), issue_key VARCHAR(100), intent_key VARCHAR(100), confirmation_code VARCHAR(6), created_at TIMESTAMP, updated_at TIMESTAMP, UNIQUE(installation_id, issue_key))");
        db.execute("CREATE TABLE group_members (vehicle_id VARCHAR(36), user_id VARCHAR(36), role VARCHAR(20), created_at TIMESTAMP, PRIMARY KEY(vehicle_id,user_id))");
    }

    @Test
    void createsVehicleOnceAfterVehicleConfirmationAndRejectsReusedProof() {
        UUID installation = service.createInstallation().installation_id();
        UUID user = UUID.randomUUID();
        var issued = service.issue(installation, "issue-1");
        assertEquals(43, issued.qr().proof().length());
        assertEquals("fuelog.vehicle_pairing", issued.qr().type());
        assertEquals(issued.id(), service.preview(user,
                new PairingDtos.PreviewRequest(issued.id(), issued.qr().proof())).request_id());
        assertNull(service.current(installation).confirmation_code());
        var intent = service.intent(user, issued.id(), "intent-1",
                new PairingDtos.IntentRequest(issued.qr().proof(), null, "My car"));
        assertEquals("awaiting_vehicle_confirmation", intent.status());
        assertEquals("My car", intent.selected_vehicle_name());
        assertEquals(user, intent.approved_user_id());
        assertNotNull(intent.confirmation_code());
        assertEquals(intent, service.intent(user, issued.id(), "intent-1",
                new PairingDtos.IntentRequest(issued.qr().proof(), null, "My car")));
        assertThrows(Exception.class, () -> service.preview(user,
                new PairingDtos.PreviewRequest(issued.id(), issued.qr().proof())));
        var completed = service.confirm(installation, issued.id(), intent.confirmation_code());
        assertEquals("completed", completed.status());
        assertNotNull(completed.vehicle_id());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM vehicles", Integer.class));
        assertEquals(completed.vehicle_id(), service.installation(installation).vehicle_id());
        assertThrows(Exception.class, () -> service.confirm(installation, issued.id(), intent.confirmation_code()));
    }

    @Test
    void reissueRevokesOldQr() {
        UUID installation = service.createInstallation().installation_id();
        UUID user = UUID.randomUUID();
        var first = service.issue(installation, "first");
        var second = service.issue(installation, "second");
        assertEquals("revoked", service.status(installation, "installation", first.id()).status());
        assertThrows(Exception.class, () -> service.preview(user,
                new PairingDtos.PreviewRequest(first.id(), first.qr().proof())));
        assertEquals("pending", service.current(installation).status());
        assertEquals(second.id(), service.current(installation).id());
    }

    @Test
    void expiresAtServerTimeAndDoesNotAllowConfirmation() {
        UUID installation = service.createInstallation().installation_id();
        UUID user = UUID.randomUUID();
        var issued = service.issue(installation, "first");
        db.update("UPDATE pairing_requests SET expires_at=? WHERE id=?",
                java.time.LocalDateTime.ofInstant(Instant.now().minusSeconds(1), java.time.ZoneOffset.UTC),
                issued.id().toString());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.preview(user, new PairingDtos.PreviewRequest(issued.id(), issued.qr().proof())));
        assertEquals("expired", service.current(installation).status());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.confirm(installation, issued.id(), "000000"));
    }

    @Test
    void existingVehiclePermissionIsCheckedAgainAtConfirm() {
        UUID installation = service.createInstallation().installation_id();
        UUID user = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        var issued = service.issue(installation, "first");
        db.update("INSERT INTO vehicles (id,name,created_by,created_at,updated_at) VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                vehicle.toString(), "Existing", user.toString());
        var body = new PairingDtos.IntentRequest(issued.qr().proof(), vehicle, null);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.intent(user, issued.id(), "intent", body));
        db.update("INSERT INTO group_members (vehicle_id,user_id,role,created_at) VALUES (?,?,?,CURRENT_TIMESTAMP)",
                vehicle.toString(), user.toString(), "MANAGER");
        var intent = service.intent(user, issued.id(), "intent", body);
        assertEquals("awaiting_vehicle_confirmation", intent.status());
        db.update("DELETE FROM group_members WHERE vehicle_id=? AND user_id=?", vehicle.toString(), user.toString());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.confirm(installation, issued.id(), intent.confirmation_code()));
        assertFalse(service.installation(installation).paired());
    }

    @Test
    void photoAloneCannotConfirmWithoutInstallationSecretAndVehicleCode() throws Exception {
        UUID user = UUID.randomUUID();
        when(decoder.decode("user-token")).thenReturn(jwt(user));

        mvc.perform(post("/pairing-requests").header("Authorization", "Bearer user-token")
                        .header("Idempotency-Key", "first"))
                .andExpect(status().isUnauthorized());

        String installationResponse = mvc.perform(post("/installations"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String installationId = JsonPath.read(installationResponse, "$.installation_id");
        String secret = JsonPath.read(installationResponse, "$.installation_secret");

        String response = mvc.perform(post("/pairing-requests")
                        .header("X-Installation-Id", installationId)
                        .header("X-Installation-Secret", secret)
                        .header("Idempotency-Key", "first"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(response, "$.qr.request_id");
        String proof = JsonPath.read(response, "$.qr.proof");
        assertFalse(response.contains("confirmation_code"));

        mvc.perform(post("/pairing-requests/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"request_id\":\"" + id + "\",\"proof\":\"" + proof + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/pairing-requests/preview").header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"request_id\":\"" + id + "\",\"proof\":\"" + proof + "\"}"))
                .andExpect(status().isOk());
        String intentResponse = mvc.perform(post("/pairing-requests/" + id + "/intent").header("Authorization", "Bearer user-token")
                        .header("Idempotency-Key", "mobile-first").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proof\":\"" + proof + "\",\"new_vehicle_name\":\"My car\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(intentResponse, "$.confirmation_code");
        mvc.perform(post("/pairing-requests/" + id + "/confirm").header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmation_code\":\"" + code + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/pairing-requests/" + id + "/confirm")
                        .header("X-Installation-Id", installationId)
                        .header("X-Installation-Secret", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmation_code\":\"" + code + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/pairing-requests/" + id + "/confirm")
                        .header("X-Installation-Id", installationId)
                        .header("X-Installation-Secret", secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmation_code\":\"000000\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/pairing-requests/" + id).header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk());
        mvc.perform(post("/pairing-requests/" + id + "/confirm")
                        .header("X-Installation-Id", installationId)
                        .header("X-Installation-Secret", secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmation_code\":\"" + code + "\"}"))
                .andExpect(status().isOk());
    }

    private static Jwt jwt(UUID subject) {
        return Jwt.withTokenValue("user-token")
                .header("alg", "RS256")
                .subject(subject.toString())
                .claim("role", "authenticated")
                .claim("is_anonymous", false)
                .claim("aud", List.of("authenticated"))
                .issuedAt(Instant.now().minusSeconds(5))
                .expiresAt(Instant.now().plusSeconds(300)).build();
    }
}
