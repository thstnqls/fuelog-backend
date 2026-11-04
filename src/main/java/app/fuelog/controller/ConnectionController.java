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
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Tag(name = "C01 차량 연결", description = "차량 Pleos 앱 전용. Authorization: Bearer에는 설치별 256비트 이상 무작위 자격 증명을 넣습니다. Supabase 사용자 JWT는 사용하지 않습니다.")
public class ConnectionController {
    private final ConnectionService service;
    private final JwtDecoder jwtDecoder;
    private final PairingActor actor;

    public ConnectionController(ConnectionService service, JwtDecoder jwtDecoder, PairingActor actor) {
        this.service = service;
        this.jwtDecoder = jwtDecoder;
        this.actor = actor;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @PostMapping("/connection/requests")
    @Operation(summary = "C01 요청 발급 또는 재시도", description = "최초 requestId는 null. 서버가 설치 자격 증명의 SHA-256 해시로 설치를 등록·식별합니다. 같은 operationId는 같은 requestId와 nonce를 반환합니다. 새 operationId는 기존 QR을 원자적으로 폐기합니다. QR JSON은 {type: fuelog.vehicle-registration, version: 1, requestId, nonce}로 구성하세요.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "WAITING 또는 ALREADY_LINKED", content = @Content(schema = @Schema(implementation = ConnectionDtos.Response.class), examples = @ExampleObject(value = "{\"requestId\":\"8b31a39f-770f-4ae8-98f8-28166e030093\",\"status\":\"WAITING\",\"remainingSeconds\":120,\"nonce\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}"))),
            @ApiResponse(responseCode = "401", description = "설치 자격 증명 오류"),
            @ApiResponse(responseCode = "409", description = "requestId 불일치 또는 기존 차량 연결 충돌"),
            @ApiResponse(responseCode = "429", description = "운영 게이트웨이 요청 제한")
    })
    public ConnectionDtos.Response issue(
                                         @Parameter(name = "Authorization", in = ParameterIn.HEADER, required = true, description = "설치별 32바이트 이상 자격 증명의 base64url Bearer", example = "Bearer BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB")
                                         @RequestHeader(value = "Authorization", required = false) String bearer,
                                         @Valid @RequestBody ConnectionDtos.RequestBody body) {
        return service.issue(bearer, body);
    }

    @PostMapping("/connection/requests/status")
    @Operation(summary = "C01 요청 상태 조회", description = "설치 자격 증명, operationId, requestId가 모두 일치해야 합니다. WAITING에는 nonce와 서버 만료 시각 기준 remainingSeconds를 반환합니다. EXPIRED/REVOKED는 정상 응답입니다. 1초 폴링에 사용할 수 있습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "WAITING / EXPIRED / REVOKED / COMPLETED", content = @Content(schema = @Schema(implementation = ConnectionDtos.Response.class), examples = @ExampleObject(value = "{\"requestId\":\"8b31a39f-770f-4ae8-98f8-28166e030093\",\"status\":\"COMPLETED\",\"remainingSeconds\":null,\"nonce\":null}"))),
            @ApiResponse(responseCode = "401", description = "설치 자격 증명 오류"),
            @ApiResponse(responseCode = "403", description = "다른 설치의 요청"),
            @ApiResponse(responseCode = "409", description = "operationId 불일치"),
            @ApiResponse(responseCode = "429", description = "운영 게이트웨이 요청 제한")
    })
    public ConnectionDtos.Response status(
                                          @Parameter(name = "Authorization", in = ParameterIn.HEADER, required = true, description = "요청을 발급한 설치의 Bearer 자격 증명", example = "Bearer BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB")
                                          @RequestHeader(value = "Authorization", required = false) String bearer,
                                          @Valid @RequestBody ConnectionDtos.StatusBody body) {
        return service.status(bearer, body);
    }

    @PostMapping("/connection/reset")
    @Operation(summary = "C07 차량 연결 초기화", description = "설치 Bearer 자격 증명과 X-User-Authorization: Bearer <Supabase JWT>를 함께 보냅니다. 사용자 JWT의 최근 5분 이내 OTP/TOTP 인증 기록과 해당 차량 OWNER/MANAGER 권한을 확인합니다. 설치 연결을 해제하고 해당 설치 요청을 REVOKED로 바꾸며 운행·주유·정비 기록은 유지합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "초기화 완료", content = @Content(schema = @Schema(implementation = ConnectionDtos.ResetResult.class), examples = @ExampleObject(value = "{\"installationId\":\"44444444-4444-4444-8444-444444444444\",\"status\":\"RESET\"}"))),
            @ApiResponse(responseCode = "401", description = "설치 또는 사용자 토큰 오류"),
            @ApiResponse(responseCode = "403", description = "최근 OTP 인증이나 차량 관리 권한 없음"),
            @ApiResponse(responseCode = "409", description = "연결 차량 ID 불일치")
    })
    public ConnectionDtos.ResetResult reset(
            @Parameter(name = "Authorization", in = ParameterIn.HEADER, required = true, description = "설치 Bearer 자격 증명", example = "Bearer BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB")
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @Parameter(name = "X-User-Authorization", in = ParameterIn.HEADER, required = true, description = "최근 5분 이내 OTP/TOTP 인증 기록이 있는 Supabase 사용자 JWT", example = "Bearer <supabase-access-token>")
            @RequestHeader(value = "X-User-Authorization", required = false) String userBearer,
            @Valid @RequestBody ConnectionDtos.ResetBody body) {
        if (userBearer == null || !userBearer.startsWith("Bearer "))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "USER_TOKEN_REQUIRED");
        Jwt jwt;
        try { jwt = jwtDecoder.decode(userBearer.substring(7)); }
        catch (JwtException ex) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_USER_TOKEN"); }
        if (!recentOtp(jwt)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "RECENT_REAUTH_REQUIRED");
        return service.reset(bearer, actor.requireUser(jwt), body.vehicleId());
    }

    private static boolean recentOtp(Jwt jwt) {
        Object claim = jwt.getClaim("amr");
        if (!(claim instanceof List<?> entries)) return false;
        Instant now = Instant.now();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> amr) || !(amr.get("timestamp") instanceof Number stamp)) continue;
            Object method = amr.get("method");
            if (!("otp".equals(method) || "totp".equals(method))) continue;
            Instant authenticated = Instant.ofEpochSecond(stamp.longValue());
            if (!authenticated.isAfter(now) && authenticated.isAfter(now.minusSeconds(300))) return true;
        }
        return false;
    }
}
