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

/** 모바일 JWT로 QR을 검사하고 기존 차량에 장치를 연결한다. */
@RestController
@Tag(
        name = "M02 모바일 연결",
        description = "Supabase 로그인 JWT로 QR을 확인하고 기존 차량을 선택합니다. nonce는 HTTPS JSON 본문에만 보냅니다.")
@SecurityRequirement(name = "supabaseBearer")
public class MobileConnectionController {
    private final ConnectionService service;
    private final PairingActor actor;

    public MobileConnectionController(ConnectionService service, PairingActor actor) {
        this.service = service;
        this.actor = actor;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @PostMapping("/mobile/connection/inspect")
    @Operation(
            summary = "모바일에서 차량 연결 QR 확인",
            description =
                    "모바일의 Supabase JWT와 QR의 type, version, requestId, nonce를 보냅니다.\n\n"
                            + "QR의 종류·비밀값·유효기간을 검사하고 남은 초를 반환합니다. 이 호출만으로 차량 연결은 완료되지 않습니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "유효한 QR",
                content =
                        @Content(
                                schema = @Schema(implementation = ConnectionDtos.Inspection.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"requestId\":\"8b31a39f-770f-4ae8-98f8-28166e030093\",\"remainingSeconds\":97}"))),
        @ApiResponse(responseCode = "401", description = "사용자 JWT 오류"),
        @ApiResponse(responseCode = "403", description = "nonce 오류"),
        @ApiResponse(responseCode = "410", description = "만료·폐기")
    })
    public ConnectionDtos.Inspection inspect(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ConnectionDtos.Envelope body) {
        return service.inspect(actor.requireUser(jwt), body);
    }

    @PostMapping("/mobile/connection/approve")
    @Operation(
            summary = "모바일에서 기존 차량 연결 승인",
            description =
                    "모바일의 Supabase JWT와 requestId, nonce, vehicleId, idempotencyKey를 보냅니다. 로그인 사용자가"
                            + " 해당 기존 차량의 OWNER 또는 MANAGER여야 합니다.\n\n"
                            + "같은 사용자·차량·승인 키 재시도는 COMPLETED를 반환합니다. 이 API는 새 차량을 생성하지 않습니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "연결 완료",
                content =
                        @Content(
                                schema = @Schema(implementation = ConnectionDtos.Approval.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"requestId\":\"8b31a39f-770f-4ae8-98f8-28166e030093\",\"vehicleId\":\"22222222-2222-4222-8222-222222222222\",\"status\":\"COMPLETED\"}"))),
        @ApiResponse(responseCode = "401", description = "사용자 JWT 오류"),
        @ApiResponse(responseCode = "403", description = "nonce 또는 차량 권한 오류"),
        @ApiResponse(responseCode = "409", description = "이미 소비·연결된 요청/차량"),
        @ApiResponse(responseCode = "410", description = "만료·폐기")
    })
    public ConnectionDtos.Approval approve(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ConnectionDtos.ApproveBody body) {
        return service.approve(actor.requireUser(jwt), body);
    }
}
