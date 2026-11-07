package app.fuelog.controller;

import app.fuelog.dto.ConnectionDtos;
import app.fuelog.service.ConnectionService;
import app.fuelog.service.PairingActor;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 장치 QR 발급·상태 조회와 사용 중단된 초기화 경로를 제공한다. */
@RestController
@Tag(
        name = "C01 차량 연결",
        description =
                "차량 Pleos 앱 전용. Authorization: Bearer에는 설치별 256비트 이상 무작위 자격 증명을 넣습니다. Supabase 사용자"
                        + " JWT는 사용하지 않습니다.")
public class ConnectionController {
    private final ConnectionService service;
    private final JwtDecoder jwtDecoder;
    private final PairingActor actor;

    public ConnectionController(
            ConnectionService service, JwtDecoder jwtDecoder, PairingActor actor) {
        this.service = service;
        this.jwtDecoder = jwtDecoder;
        this.actor = actor;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @PostMapping("/connection/requests")
    @SecurityRequirement(name = "installationBearer")
    @Operation(
            summary = "차량 연결 QR 발급·재발급",
            description =
                    "장치가 설치 비밀값을 Bearer로 보내 호출합니다. 처음에는 requestId를 생략하거나 null로 보내세요.\n\n"
                        + "같은 operationId로 재시도하면 같은 QR 정보를 반환합니다. 새 operationId는 이전 QR을 폐기합니다. QR에는"
                        + " type=fuelog.vehicle-registration, version=1, requestId, nonce를 넣으며"
                        + " 유효기간은 최대 120초입니다. 차량이 이미 연결되었으면 ALREADY_LINKED를 반환합니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "WAITING 또는 ALREADY_LINKED",
                content =
                        @Content(
                                schema = @Schema(implementation = ConnectionDtos.Response.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"requestId\":\"8b31a39f-770f-4ae8-98f8-28166e030093\",\"status\":\"WAITING\",\"remainingSeconds\":120,\"nonce\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}"))),
        @ApiResponse(responseCode = "401", description = "설치 자격 증명 오류"),
        @ApiResponse(responseCode = "409", description = "requestId 불일치 또는 기존 차량 연결 충돌"),
        @ApiResponse(responseCode = "429", description = "운영 게이트웨이 요청 제한")
    })
    public ConnectionDtos.Response issue(
            @Parameter(
                            name = "Authorization",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "설치별 32바이트 이상 자격 증명의 base64url Bearer",
                            example = "Bearer BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB")
                    @RequestHeader(value = "Authorization", required = false)
                    String bearer,
            @Valid @RequestBody ConnectionDtos.RequestBody body) {
        return service.issue(bearer, body);
    }

    @PostMapping("/connection/requests/status")
    @SecurityRequirement(name = "installationBearer")
    @Operation(
            summary = "차량 연결 승인 상태 조회",
            description =
                    "QR을 발급한 장치의 설치 Bearer와 같은 operationId, requestId로 호출합니다.\n\n"
                            + "WAITING은 모바일 승인 대기, COMPLETED는 연결 완료, EXPIRED는 만료, REVOKED는 폐기입니다."
                            + " WAITING일 때만 nonce와 남은 초를 반환하며 1초 간격으로 조회할 수 있습니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "WAITING / EXPIRED / REVOKED / COMPLETED",
                content =
                        @Content(
                                schema = @Schema(implementation = ConnectionDtos.Response.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"requestId\":\"8b31a39f-770f-4ae8-98f8-28166e030093\",\"status\":\"COMPLETED\",\"remainingSeconds\":null,\"nonce\":null}"))),
        @ApiResponse(responseCode = "401", description = "설치 자격 증명 오류"),
        @ApiResponse(responseCode = "403", description = "다른 설치의 요청"),
        @ApiResponse(responseCode = "409", description = "operationId 불일치"),
        @ApiResponse(responseCode = "429", description = "운영 게이트웨이 요청 제한")
    })
    public ConnectionDtos.Response status(
            @Parameter(
                            name = "Authorization",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "요청을 발급한 설치의 Bearer 자격 증명",
                            example = "Bearer BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB")
                    @RequestHeader(value = "Authorization", required = false)
                    String bearer,
            @Valid @RequestBody ConnectionDtos.StatusBody body) {
        return service.status(bearer, body);
    }

    @PostMapping("/connection/reset")
    @Operation(
            summary = "이전 차량 초기화 API — 사용 중단",
            description =
                    "이 경로는 초기화를 수행하지 않습니다. 사용자 JWT와 최근 5분 내 OTP/TOTP 인증을 확인한 뒤 409"
                        + " USE_CONNECTION_OPERATIONS를 반환합니다.\n\n"
                        + "장치는 POST /connection/disconnect에 kind=VEHICLE을 보내고, 관리자는 모바일 초기화 승인 API를"
                        + " 사용하세요.",
            deprecated = true)
    @ApiResponses({
        @ApiResponse(
                responseCode = "401",
                description = "사용자 JWT가 없거나 유효하지 않음",
                content = @Content),
        @ApiResponse(
                responseCode = "403",
                description = "최근 OTP/TOTP 인증 또는 로그인 사용자 조건 불충족",
                content = @Content),
        @ApiResponse(
                responseCode = "409",
                description = "USE_CONNECTION_OPERATIONS: 새 연결 해제·승인 API 사용 필요",
                content = @Content)
    })
    public ConnectionDtos.ResetResult reset(
            @Parameter(
                            name = "Authorization",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "설치 Bearer 자격 증명",
                            example = "Bearer BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB")
                    @RequestHeader(value = "Authorization", required = false)
                    String bearer,
            @Parameter(
                            name = "X-User-Authorization",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "최근 5분 이내 OTP/TOTP 인증 기록이 있는 Supabase 사용자 JWT",
                            example = "Bearer <supabase-access-token>")
                    @RequestHeader(value = "X-User-Authorization", required = false)
                    String userBearer,
            @Valid @RequestBody ConnectionDtos.ResetBody body) {
        if (userBearer == null || !userBearer.startsWith("Bearer "))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "USER_TOKEN_REQUIRED");
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(userBearer.substring(7));
        } catch (JwtException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_USER_TOKEN");
        }
        if (!recentOtp(jwt))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "RECENT_REAUTH_REQUIRED");
        actor.requireUser(jwt);
        throw new ResponseStatusException(HttpStatus.CONFLICT, "USE_CONNECTION_OPERATIONS");
    }

    private static boolean recentOtp(Jwt jwt) {
        Object claim = jwt.getClaim("amr");
        if (!(claim instanceof List<?> entries)) return false;
        Instant now = Instant.now();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> amr)
                    || !(amr.get("timestamp") instanceof Number stamp)) continue;
            Object method = amr.get("method");
            if (!("otp".equals(method) || "totp".equals(method))) continue;
            Instant authenticated = Instant.ofEpochSecond(stamp.longValue());
            if (!authenticated.isAfter(now) && authenticated.isAfter(now.minusSeconds(300)))
                return true;
        }
        return false;
    }
}
