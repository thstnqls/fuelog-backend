package app.fuelog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MobileVehicleControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate db;

    @BeforeEach
    void setup() {
        TestSchema.create(db);
    }

    private static Jwt jwt(UUID user) {
        return Jwt.withTokenValue("token-" + user)
                .header("alg", "RS256")
                .subject(user.toString())
                .claim("aud", List.of("fuelog-api"))
                .claim("role", "authenticated")
                .claim("is_anonymous", false)
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/mobile/vehicles"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsUserVehiclesSortedByCreatedAtDesc() throws Exception {
        UUID user = UUID.randomUUID();
        when(decoder.decode("valid-token")).thenReturn(jwt(user));

        UUID vehicle1 = UUID.randomUUID();
        UUID vehicle2 = UUID.randomUUID();
        UUID installation1 = UUID.randomUUID();

        // 차량 1 등록 (연결된 단말기 있음)
        db.update("INSERT INTO vehicles (id, name, created_by, created_at, updated_at) VALUES (?, ?, ?, TIMESTAMP '2026-11-01 10:00:00', TIMESTAMP '2026-11-01 10:00:00')",
                vehicle1.toString(), "내 차 1", user.toString());
        db.update("INSERT INTO group_members (vehicle_id, user_id, role, created_at) VALUES (?, ?, 'OWNER', TIMESTAMP '2026-11-01 10:00:00')",
                vehicle1.toString(), user.toString());
        db.update("INSERT INTO vehicle_installations (id, vehicle_id, created_at, updated_at) VALUES (?, ?, TIMESTAMP '2026-11-01 10:00:00', TIMESTAMP '2026-11-01 10:00:00')",
                installation1.toString(), vehicle1.toString());

        // 차량 2 등록 (단말기 미연결, 최신 생성)
        db.update("INSERT INTO vehicles (id, name, created_by, created_at, updated_at) VALUES (?, ?, ?, TIMESTAMP '2026-11-02 12:00:00', TIMESTAMP '2026-11-02 12:00:00')",
                vehicle2.toString(), "내 차 2", user.toString());
        db.update("INSERT INTO group_members (vehicle_id, user_id, role, created_at) VALUES (?, ?, 'MEMBER', TIMESTAMP '2026-11-02 12:00:00')",
                vehicle2.toString(), user.toString());

        mvc.perform(get("/mobile/vehicles").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                // 최신순이므로 차량 2가 먼저
                .andExpect(jsonPath("$[0].id").value(vehicle2.toString()))
                .andExpect(jsonPath("$[0].vehicleId").value(vehicle2.toString()))
                .andExpect(jsonPath("$[0].name").value("내 차 2"))
                .andExpect(jsonPath("$[0].vehicleName").value("내 차 2"))
                .andExpect(jsonPath("$[0].role").value("MEMBER"))
                .andExpect(jsonPath("$[0].paired").value(false))
                .andExpect(jsonPath("$[0].installationId").isEmpty())
                // 차량 1
                .andExpect(jsonPath("$[1].id").value(vehicle1.toString()))
                .andExpect(jsonPath("$[1].vehicleId").value(vehicle1.toString()))
                .andExpect(jsonPath("$[1].name").value("내 차 1"))
                .andExpect(jsonPath("$[1].role").value("OWNER"))
                .andExpect(jsonPath("$[1].paired").value(true))
                .andExpect(jsonPath("$[1].installationId").value(installation1.toString()));
    }
}
