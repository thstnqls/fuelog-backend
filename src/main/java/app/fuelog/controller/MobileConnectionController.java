package app.fuelog.controller;

import app.fuelog.dto.ConnectionDtos;
import app.fuelog.service.ConnectionService;
import app.fuelog.service.PairingActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "M02 모바일 연결", description = "Supabase 로그인 JWT로 QR을 확인하고 기존 차량을 선택합니다. nonce는 HTTPS JSON 본문에만 보냅니다.")
@SecurityRequirement(name = "supabaseBearer")
public class MobileConnectionController {
    private final ConnectionService service;
    private final PairingActor actor;

    public MobileConnectionController(ConnectionService service, PairingActor actor) {
        this.service = service;
        this.actor = actor;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @PostMapping("/mobile/connection/inspect")
    @Operation(summary = "QR 검증", description = "앱 내부 스캐너에서 읽은 type/version/requestId/nonce를 JSON 본문으로 보냅니다. 미사용·미만료 QR인지 확인하고 최소 정보만 반환합니다. 이 단계는 nonce를 소비하지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "유효한 QR", content = @Content(schema = @Schema(implementation = ConnectionDtos.Inspection.class), examples = @ExampleObject(value = "{\"requestId\":\"8b31a39f-770f-4ae8-98f8-28166e030093\",\"remainingSeconds\":97}"))),
            @ApiResponse(responseCode = "401", description = "사용자 JWT 오류"),
            @ApiResponse(responseCode = "403", description = "nonce 오류"),
            @ApiResponse(responseCode = "410", description = "만료·폐기")
    })
    public ConnectionDtos.Inspection inspect(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ConnectionDtos.Envelope body) {
        return service.inspect(actor.requireUser(jwt), body);
    }

    @PostMapping("/mobile/connection/approve")
    @Operation(summary = "기존 차량 연결 승인", description = "requestId/nonce/vehicleId/idempotencyKey를 JSON 본문으로 보냅니다. 기존 차량 OWNER/MANAGER 권한을 검사하고 nonce 소비와 설치 연결을 한 트랜잭션으로 완료합니다. 같은 키·차량 재시도는 같은 결과를 반환합니다. 새 차량 생성은 이 API에서 지원하지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "연결 완료", content = @Content(schema = @Schema(implementation = ConnectionDtos.Approval.class), examples = @ExampleObject(value = "{\"requestId\":\"8b31a39f-770f-4ae8-98f8-28166e030093\",\"vehicleId\":\"22222222-2222-4222-8222-222222222222\",\"status\":\"COMPLETED\"}"))),
            @ApiResponse(responseCode = "401", description = "사용자 JWT 오류"),
            @ApiResponse(responseCode = "403", description = "nonce 또는 차량 권한 오류"),
            @ApiResponse(responseCode = "409", description = "이미 소비·연결된 요청/차량"),
            @ApiResponse(responseCode = "410", description = "만료·폐기")
    })
    public ConnectionDtos.Approval approve(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ConnectionDtos.ApproveBody body) {
        return service.approve(actor.requireUser(jwt), body);
    }
}
