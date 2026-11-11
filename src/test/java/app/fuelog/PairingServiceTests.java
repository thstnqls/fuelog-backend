package app.fuelog;

import app.fuelog.dto.PairingDtos;
import app.fuelog.dto.ConnectionDtos;
import app.fuelog.service.ConnectionService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PairingServiceTests {
    @Autowired JdbcTemplate db;
    @Autowired PairingService service;
    @Autowired ConnectionService connectionService;
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;

    @BeforeEach
    void schema() {
        TestSchema.create(db);
    }

    @Test
    void vehicleAliasFromQrBecomesRegisteredVehicleName() {
        UUID installation = service.createInstallation().installation_id();
        UUID user = UUID.randomUUID();
        assertThrows(Exception.class, () -> service.issue(installation, "empty-name", "  "));
        var issued = service.issue(installation, "alias-issue", "  출퇴근 차량  ");
        var intent = service.intent(user, issued.id(), "alias-intent",
                new PairingDtos.IntentRequest(issued.qr().proof(), null, "Mobile name"));
        var completed = service.confirm(installation, issued.id(), intent.confirmation_code());
        assertEquals("출퇴근 차량", db.queryForObject("SELECT name FROM vehicles WHERE id=?", String.class, completed.vehicle_id().toString()));
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
                app.fuelog.common.TimeUtils.toLocalDateTime(Instant.now().minusSeconds(1)),
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
        // 인증 헤더(토큰 또는 단말 시크릿)가 없으면 401
        mvc.perform(post("/pairing-requests/" + id + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmation_code\":\"" + code + "\"}"))
                .andExpect(status().isUnauthorized());

        // 다른 사용자의 토큰이면 403
        UUID otherUser = UUID.randomUUID();
        when(decoder.decode("other-token")).thenReturn(jwt(otherUser));
        mvc.perform(post("/pairing-requests/" + id + "/confirm").header("Authorization", "Bearer other-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmation_code\":\"" + code + "\"}"))
                .andExpect(status().isForbidden());

        // 잘못된 설치 비밀값이면 401
        mvc.perform(post("/pairing-requests/" + id + "/confirm")
                        .header("X-Installation-Id", installationId)
                        .header("X-Installation-Secret", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmation_code\":\"" + code + "\"}"))
                .andExpect(status().isUnauthorized());

        // 잘못된 확인 코드이면 403
        mvc.perform(post("/pairing-requests/" + id + "/confirm").header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmation_code\":\"000000\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/pairing-requests/" + id).header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk());

        // 모바일 사용자 토큰과 올바른 6자리 확인 코드로 즉시 completed 승인
        mvc.perform(post("/pairing-requests/" + id + "/confirm").header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmation_code\":\"" + code + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void androidContractRetriesRevokesAndApprovesExistingVehicleOnce() throws Exception {
        UUID firstOperation = UUID.randomUUID();
        UUID secondOperation = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        UUID approvalKey = UUID.randomUUID();
        byte[] credentialBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(credentialBytes);
        String bearer = "Bearer " + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(credentialBytes);
        when(decoder.decode("user-token")).thenReturn(jwt(user));

        String firstBody = "{\"operationId\":\"" + firstOperation + "\",\"requestId\":\"\"}";
        mvc.perform(post("/connection/requests").contentType(MediaType.APPLICATION_JSON).content(firstBody))
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("INVALID_INSTALLATION_CREDENTIAL"));
        String first = mvc.perform(post("/connection/requests").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(firstBody))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        String firstId = JsonPath.read(first, "$.requestId");
        String firstNonce = JsonPath.read(first, "$.nonce");
        assertEquals("WAITING", JsonPath.read(first, "$.status"));
        assertEquals(43, firstNonce.length());
        mvc.perform(post("/mobile/connection/inspect")
                        .contentType(MediaType.APPLICATION_JSON).content(envelope(firstId, firstNonce)))
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("INVALID_USER_TOKEN"));
        String retry = mvc.perform(post("/connection/requests").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(firstBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(firstId, JsonPath.read(retry, "$.requestId"));
        assertEquals(firstNonce, JsonPath.read(retry, "$.nonce"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM vehicle_installations", Integer.class));

        String secondBody = "{\"operationId\":\"" + secondOperation + "\",\"requestId\":\"" + firstId + "\"}";
        String second = mvc.perform(post("/connection/requests").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(secondBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String secondId = JsonPath.read(second, "$.requestId");
        String secondNonce = JsonPath.read(second, "$.nonce");
        String oldStatus = mvc.perform(post("/connection/requests/status").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operationId\":\"" + firstOperation + "\",\"requestId\":\"" + firstId + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals("REVOKED", JsonPath.read(oldStatus, "$.status"));
        mvc.perform(post("/mobile/connection/inspect").header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON).content(envelope(firstId, firstNonce)))
                .andExpect(status().isGone());

        mvc.perform(post("/mobile/connection/inspect").header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON).content(envelope(secondId, secondNonce)))
                .andExpect(status().isOk());
        String approval = "{\"requestId\":\"" + secondId + "\",\"nonce\":\"" + secondNonce
                + "\",\"vehicleId\":\"" + vehicle + "\",\"idempotencyKey\":\"" + approvalKey + "\"}";
        mvc.perform(post("/mobile/connection/approve").header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON).content(approval))
                .andExpect(status().isForbidden());
        db.update("INSERT INTO vehicles (id,name,created_by,created_at,updated_at) VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                vehicle.toString(), "Existing", user.toString());
        db.update("INSERT INTO group_members (vehicle_id,user_id,role,created_at) VALUES (?,?,?,CURRENT_TIMESTAMP)",
                vehicle.toString(), user.toString(), "OWNER");
        mvc.perform(post("/mobile/connection/approve").header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON).content(approval))
                .andExpect(status().isOk());
        mvc.perform(post("/mobile/connection/approve").header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approval.replace(approvalKey.toString(), UUID.randomUUID().toString())))
                .andExpect(status().isConflict());
        mvc.perform(post("/mobile/connection/approve").header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON).content(approval))
                .andExpect(status().isOk());
        mvc.perform(post("/mobile/connection/inspect").header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON).content(envelope(secondId, secondNonce)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.vehicleId").value(vehicle.toString()));
        String done = mvc.perform(post("/connection/requests/status").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operationId\":\"" + secondOperation + "\",\"requestId\":\"" + secondId + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals("COMPLETED", JsonPath.read(done, "$.status"));
        assertNull(JsonPath.read(done, "$.nonce"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM vehicles", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM vehicle_installations WHERE vehicle_id IS NOT NULL", Integer.class));
        assertNull(db.queryForObject("SELECT proof_hash FROM pairing_requests WHERE id=?", byte[].class, secondId));

        mvc.perform(post("/connection/reset").header("Authorization", bearer)
                        .header("X-User-Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleId\":\"" + vehicle + "\"}"))
                .andExpect(status().isForbidden());
        when(decoder.decode("fresh-user-token")).thenReturn(recentOtpJwt(user));
        mvc.perform(post("/connection/reset").header("Authorization", bearer)
                        .header("X-User-Authorization", "Bearer fresh-user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleId\":\"" + vehicle + "\"}"))
                .andExpect(status().isConflict());
        // C07 requires an operation with an explicit original-owner retention policy.
        mvc.perform(post("/connection/disconnect").header("Authorization",bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"VEHICLE\",\"operationId\":\"reset-c07\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AWAITING_ADMIN"));
        String installationId=db.queryForObject("SELECT id FROM vehicle_installations WHERE vehicle_id=?",String.class,vehicle.toString());
        Long registrationVersion=db.queryForObject("SELECT registration_version FROM vehicle_installations WHERE id=?",Long.class,installationId);
        mvc.perform(post("/mobile/connection-operations/reset-c07/approve").header("Authorization","Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"installationId\":\""+installationId+"\",\"registrationVersion\":"+registrationVersion+",\"pendingRecordsPolicy\":\"RETAIN_ORIGINAL_OWNER\",\"reauthenticationProof\":\"fresh-user-token\"}"))
                .andExpect(status().isOk());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM vehicle_installations WHERE vehicle_id IS NOT NULL", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM vehicles", Integer.class));
        mvc.perform(post("/mobile/connection/approve").header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON).content(approval))
                .andExpect(status().isGone());
        String resetStatus = mvc.perform(post("/connection/requests/status").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operationId\":\"" + secondOperation + "\",\"requestId\":\"" + secondId + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals("REVOKED", JsonPath.read(resetStatus, "$.status"));
    }

    @Test
    void androidContractRejectsExpiredQrAndOtherInstallation() throws Exception {
        String credentialOne = randomBearer();
        String credentialTwo = randomBearer();
        UUID operationOne = UUID.randomUUID();
        UUID operationTwo = UUID.randomUUID();
        String one = issueConnection(credentialOne, operationOne);
        String two = issueConnection(credentialTwo, operationTwo);
        String firstId = JsonPath.read(one, "$.requestId");
        String nonce = JsonPath.read(one, "$.nonce");
        mvc.perform(post("/connection/requests/status").header("Authorization", credentialTwo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operationId\":\"" + operationOne + "\",\"requestId\":\"" + firstId + "\"}"))
                .andExpect(status().isForbidden());
        assertNotEquals(firstId, JsonPath.read(two, "$.requestId"));
        db.update("UPDATE pairing_requests SET expires_at=? WHERE id=?",
                app.fuelog.common.TimeUtils.toLocalDateTime(Instant.now().minusSeconds(1)), firstId);
        when(decoder.decode("user-token")).thenReturn(jwt(UUID.randomUUID()));
        mvc.perform(post("/mobile/connection/inspect").header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON).content(envelope(firstId, nonce)))
                .andExpect(status().isGone());
        String expired = mvc.perform(post("/connection/requests/status").header("Authorization", credentialOne)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operationId\":\"" + operationOne + "\",\"requestId\":\"" + firstId + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals("EXPIRED", JsonPath.read(expired, "$.status"));
    }

    @Test
    void concurrentFirstRequestWithSameOperationReturnsOneQr() throws Exception {
        String bearer = randomBearer();
        var body = new ConnectionDtos.RequestBody(UUID.randomUUID().toString(), "");
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<ConnectionDtos.Response> call = () -> {
                start.await();
                return connectionService.issue(bearer, body);
            };
            var first = pool.submit(call);
            var second = pool.submit(call);
            start.countDown();
            var a = first.get();
            var b = second.get();
            assertEquals(a.requestId(), b.requestId());
            assertEquals(a.nonce(), b.nonce());
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM pairing_requests", Integer.class));
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM vehicle_installations", Integer.class));
        }
    }

    @Test
    void concurrentApprovalsCreateOnlyOneConnection() throws Exception {
        UUID user = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        db.update("INSERT INTO vehicles (id,name,created_by,created_at,updated_at) VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                vehicle.toString(), "Existing", user.toString());
        db.update("INSERT INTO group_members (vehicle_id,user_id,role,created_at) VALUES (?,?,?,CURRENT_TIMESTAMP)",
                vehicle.toString(), user.toString(), "OWNER");
        var issued = connectionService.issue(randomBearer(),
                new ConnectionDtos.RequestBody(UUID.randomUUID().toString(), ""));
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> first = () -> approveAfterLatch(start, user, issued, vehicle);
            java.util.concurrent.Callable<Boolean> second = () -> approveAfterLatch(start, user, issued, vehicle);
            var a = pool.submit(first);
            var b = pool.submit(second);
            start.countDown();
            assertEquals(1, (a.get() ? 1 : 0) + (b.get() ? 1 : 0));
        }
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM vehicle_installations WHERE vehicle_id IS NOT NULL", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM pairing_requests WHERE status='completed'", Integer.class));
    }

    private boolean approveAfterLatch(java.util.concurrent.CountDownLatch start, UUID user,
            ConnectionDtos.Response issued, UUID vehicle) throws InterruptedException {
        start.await();
        try {
            connectionService.approve(user, new ConnectionDtos.ApproveBody(
                    issued.requestId(), issued.nonce(), vehicle, UUID.randomUUID().toString()));
            return true;
        } catch (org.springframework.web.server.ResponseStatusException ex) {
            assertEquals(409, ex.getStatusCode().value());
            return false;
        }
    }

    private String issueConnection(String bearer, UUID operation) throws Exception {
        return mvc.perform(post("/connection/requests").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operationId\":\"" + operation + "\",\"requestId\":null}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String randomBearer() {
        byte[] value = new byte[32];
        new java.security.SecureRandom().nextBytes(value);
        return "Bearer " + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static Jwt recentOtpJwt(UUID subject) {
        return Jwt.withTokenValue("fresh-user-token")
                .header("alg", "ES256")
                .subject(subject.toString())
                .claim("role", "authenticated")
                .claim("is_anonymous", false)
                .claim("amr", List.of(java.util.Map.of("method", "otp", "timestamp", Instant.now().getEpochSecond())))
                .issuedAt(Instant.now().minusSeconds(5))
                .expiresAt(Instant.now().plusSeconds(300)).build();
    }

    private static String envelope(String id, String nonce) {
        return "{\"type\":\"fuelog.vehicle-registration\",\"version\":1,\"requestId\":\"" + id
                + "\",\"nonce\":\"" + nonce + "\"}";
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
