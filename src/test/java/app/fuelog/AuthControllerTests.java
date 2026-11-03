package app.fuelog;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;

    @Test
    void returnsOnlyVerifiedTokenClaims() throws Exception {
        Instant issuedAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plusSeconds(3600);
        Jwt jwt = Jwt.withTokenValue("valid-access-token")
                .header("alg", "RS256")
                .subject("33333333-3333-4333-8333-333333333333")
                .claim("aud", List.of("fuelog-api"))
                .claim("email", "user@example.com")
                .claim("phone", "+821012345678")
                .claim("role", "authenticated")
                .claim("is_anonymous", false)
                .claim("session_id", "55555555-5555-4555-8555-555555555555")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        when(decoder.decode("valid-access-token")).thenReturn(jwt);

        mvc.perform(get("/auth/me").header("Authorization", "Bearer valid-access-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value("33333333-3333-4333-8333-333333333333"))
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.phone").value("+821012345678"))
                .andExpect(jsonPath("$.role").value("authenticated"))
                .andExpect(jsonPath("$.is_anonymous").value(false))
                .andExpect(jsonPath("$.session_id").value("55555555-5555-4555-8555-555555555555"))
                .andExpect(jsonPath("$.issued_at").value(issuedAt.toString()))
                .andExpect(jsonPath("$.expires_at").value(expiresAt.toString()))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void rejectsMissingOrInvalidBearerToken() throws Exception {
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("Invalid token"));
        mvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/me").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }
}
