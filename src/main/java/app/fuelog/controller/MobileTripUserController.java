package app.fuelog.controller;

import app.fuelog.dto.TripUserCandidateDtos;
import app.fuelog.dto.VehicleHomeDtos;
import app.fuelog.service.TripUserCandidateService;
import app.fuelog.service.VehicleHomeService;

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

/** 모바일에서 개인 홈 사용자 승인 또는 현재 운행 구간 동의를 접수한다. */
@RestController
@Tag(
        name = "M02 운행 사용자 확인",
        description = "Supabase 로그인 JWT로 C02 QR을 승인합니다. 이름은 인증 근거가 아니며 차량 멤버십을 서버에서 확인합니다.")
@SecurityRequirement(name = "supabaseBearer")
public class MobileTripUserController {
    private final VehicleHomeService service;
    private final TripUserCandidateService candidates;

    public MobileTripUserController(
            VehicleHomeService service, TripUserCandidateService candidates) {
        this.service = service;
        this.candidates = candidates;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @PostMapping("/mobile/trip-user/approve")
    @Operation(
            summary = "모바일에서 개인 홈 사용자 승인",
            description =
                    "모바일의 Supabase JWT와 QR의 challengeId, proof, 선택한 vehicleId를 보냅니다. 현재 차량 멤버십을"
                        + " 확인하고 장치용 사용자 세션을 생성합니다.\n\n"
                        + "장치는 POST /trip-user/session에서 세션 토큰을 받습니다. intervalId에 묶인 구간 확인 QR은 이"
                        + " API 대신 POST /mobile/trip-user/consent를 사용하세요.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "승인 완료",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema =
                                        @Schema(
                                                implementation =
                                                        VehicleHomeDtos.MobileApproval.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"challengeId\":\"11111111-1111-4111-8111-111111111111\",\"status\":\"APPROVED\",\"sessionVersion\":2}"))),
        @ApiResponse(responseCode = "401", description = "모바일 JWT 오류"),
        @ApiResponse(responseCode = "403", description = "proof, 차량 또는 멤버십 불일치"),
        @ApiResponse(responseCode = "409", description = "이미 승인된 요청"),
        @ApiResponse(responseCode = "410", description = "만료된 요청")
    })
    public VehicleHomeDtos.MobileApproval approve(
            @AuthenticationPrincipal Jwt jwt,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "QR의 challengeId/proof와 로그인 사용자가 선택한 차량 ID",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema =
                                                    @Schema(
                                                            implementation =
                                                                    VehicleHomeDtos
                                                                            .MobileApprovalRequest
                                                                            .class),
                                            examples =
                                                    @ExampleObject(
                                                            value =
                                                                    "{\"challengeId\":\"11111111-1111-4111-8111-111111111111\",\"proof\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"vehicleId\":\"22222222-2222-4222-8222-222222222222\"}")))
                    @Valid
                    @RequestBody
                    VehicleHomeDtos.MobileApprovalRequest body) {
        return service.approve(jwt, body);
    }

    @PostMapping("/mobile/trip-user/consent")
    @Operation(
            summary = "모바일에서 이번 운행의 사용자 후보 동의",
            description =
                    "모바일의 Supabase JWT와 vehicleId, installationId, intervalId, challengeId, proof,"
                        + " operationId를 보냅니다. 현재 구간에 대한 명시적 동의와 차량 멤버십을 확인해 5분 유효 후보를 만듭니다.\n\n"
                        + "동의 후에도 차량 화면에서 POST /trip-user/verify를 호출해야 합니다. 개인 홈 세션 승인과는 별도 흐름입니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "후보 동의 등록",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema =
                                        @Schema(
                                                implementation =
                                                        TripUserCandidateDtos.MobileConsent.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"consentId\":\"66666666-6666-4666-8666-666666666666\",\"candidateId\":\"66666666-6666-4666-8666-666666666666\",\"intervalId\":\"55555555-5555-4555-8555-555555555555\",\"status\":\"ACTIVE\",\"expiresAt\":\"2026-11-04T10:05:00Z\"}"))),
        @ApiResponse(responseCode = "401", description = "모바일 JWT 오류"),
        @ApiResponse(responseCode = "403", description = "proof·멤버십·차량·설치·구간 불일치"),
        @ApiResponse(responseCode = "409", description = "소비된 challenge 또는 같은 키에 다른 body"),
        @ApiResponse(responseCode = "410", description = "challenge 만료")
    })
    public TripUserCandidateDtos.MobileConsent consent(
            @AuthenticationPrincipal Jwt jwt,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "차량·설치·구간과 QR의 challengeId/proof, 모바일 재시도 operationId",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema =
                                                    @Schema(
                                                            implementation =
                                                                    TripUserCandidateDtos
                                                                            .MobileConsentRequest
                                                                            .class),
                                            examples =
                                                    @ExampleObject(
                                                            value =
                                                                    "{\"vehicleId\":\"22222222-2222-4222-8222-222222222222\",\"installationId\":\"44444444-4444-4444-8444-444444444444\",\"intervalId\":\"55555555-5555-4555-8555-555555555555\",\"challengeId\":\"11111111-1111-4111-8111-111111111111\",\"proof\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"operationId\":\"mobile-consent-001\"}")))
                    @Valid
                    @RequestBody
                    TripUserCandidateDtos.MobileConsentRequest body) {
        return candidates.consent(jwt, body);
    }
}
