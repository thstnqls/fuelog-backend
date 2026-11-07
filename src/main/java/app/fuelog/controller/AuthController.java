package app.fuelog.controller;

import app.fuelog.dto.AuthDtos;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** 검증된 모바일 JWT에서 현재 사용자 정보를 읽는 HTTP 진입점. */
@RestController
@Tag(name = "인증", description = "Supabase access token으로 확인한 현재 사용자 정보")
public class AuthController {
    @GetMapping("/auth/me")
    @SecurityRequirement(name = "supabaseBearer")
    @Operation(
            summary = "로그인한 사용자와 토큰 정보 조회",
            description =
                    "모바일의 Supabase access token을 Bearer로 보냅니다. 서명·발급자·대상·만료를 검증한 뒤 사용자 ID, 이메일, 역할과"
                            + " 세션 시각을 반환합니다.\n\n"
                            + "정보는 토큰 발급 당시 값이며 Auth 서버를 다시 조회하지 않습니다. 토큰 원문이나 refresh token은 반환하지"
                            + " 않습니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "검증된 토큰의 사용자·세션 정보",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = AuthDtos.Me.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"user_id\":\"33333333-3333-4333-8333-333333333333\",\"email\":\"user@example.com\",\"phone\":null,\"role\":\"authenticated\",\"is_anonymous\":false,\"session_id\":\"55555555-5555-4555-8555-555555555555\",\"issued_at\":\"2026-11-03T03:00:00Z\",\"expires_at\":\"2026-11-03T04:00:00Z\"}"))),
        @ApiResponse(responseCode = "401", description = "Bearer 토큰이 없거나 서명·발급자·대상·만료 검증 실패")
    })
    public AuthDtos.Me me(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid user ID in token");
        }
        return new AuthDtos.Me(
                userId,
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("phone"),
                jwt.getClaimAsString("role"),
                Boolean.TRUE.equals(jwt.getClaim("is_anonymous")),
                jwt.getClaimAsString("session_id"),
                jwt.getIssuedAt(),
                jwt.getExpiresAt());
    }
}
