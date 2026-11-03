package app.fuelog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() {}

    @Schema(description = "검증된 Supabase access token에서 읽은 현재 사용자·세션 정보. Auth 서버나 사용자 DB를 재조회하지 않습니다.")
    public record Me(
            @Schema(description = "JWT sub: 현재 사용자 UUID", example = "33333333-3333-4333-8333-333333333333") UUID user_id,
            @Schema(description = "JWT email. 없는 경우 null", example = "user@example.com", nullable = true) String email,
            @Schema(description = "JWT phone. 없는 경우 null", example = "+821012345678", nullable = true) String phone,
            @Schema(description = "JWT role", example = "authenticated") String role,
            @Schema(description = "Supabase 익명 로그인 여부", example = "false") boolean is_anonymous,
            @Schema(description = "JWT session_id. 없는 경우 null", example = "55555555-5555-4555-8555-555555555555", nullable = true) String session_id,
            @Schema(description = "JWT iat: 발급 시각(UTC)", example = "2026-11-03T03:00:00Z") Instant issued_at,
            @Schema(description = "JWT exp: 만료 시각(UTC)", example = "2026-11-03T04:00:00Z") Instant expires_at
    ) {}
}
