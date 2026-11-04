package app.fuelog.controller;

import app.fuelog.dto.VehicleHomeDtos;
import app.fuelog.service.VehicleHomeService;
import app.fuelog.dto.TripUserCandidateDtos;
import app.fuelog.service.TripUserCandidateService;
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
@Tag(name = "M02 운행 사용자 확인", description = "Supabase 로그인 JWT로 C02 QR을 승인합니다. 이름은 인증 근거가 아니며 차량 멤버십을 서버에서 확인합니다.")
@SecurityRequirement(name = "supabaseBearer")
public class MobileTripUserController {
    private final VehicleHomeService service;
    private final TripUserCandidateService candidates;

    public MobileTripUserController(VehicleHomeService service, TripUserCandidateService candidates) {
        this.service = service;
        this.candidates = candidates;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @PostMapping("/mobile/trip-user/approve")
    @Operation(summary = "운행 사용자 QR 승인", description = "앱 내부 스캐너가 읽은 challengeId와 proof, 명시적으로 선택한 vehicleId를 JSON 본문으로 보냅니다. Supabase 사용자와 해당 차량의 멤버십, 설치의 현재 차량, 2분 만료를 확인하고 짧은 설치 범위 세션을 발급합니다. proof는 URL에 넣지 않습니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "승인 완료", content = @Content(mediaType = "application/json", schema = @Schema(implementation = VehicleHomeDtos.MobileApproval.class), examples = @ExampleObject(value = "{\"challengeId\":\"11111111-1111-4111-8111-111111111111\",\"status\":\"APPROVED\",\"sessionVersion\":2}"))), @ApiResponse(responseCode = "401", description = "모바일 JWT 오류"), @ApiResponse(responseCode = "403", description = "proof, 차량 또는 멤버십 불일치"), @ApiResponse(responseCode = "409", description = "이미 승인된 요청"), @ApiResponse(responseCode = "410", description = "만료된 요청")})
    public VehicleHomeDtos.MobileApproval approve(@AuthenticationPrincipal Jwt jwt,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "QR의 challengeId/proof와 로그인 사용자가 선택한 차량 ID", required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = VehicleHomeDtos.MobileApprovalRequest.class), examples = @ExampleObject(value = "{\"challengeId\":\"11111111-1111-4111-8111-111111111111\",\"proof\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"vehicleId\":\"22222222-2222-4222-8222-222222222222\"}")))
            @Valid @RequestBody VehicleHomeDtos.MobileApprovalRequest body) {
        return service.approve(jwt, body);
    }

    @PostMapping("/mobile/trip-user/consent")
    @Operation(summary = "C03 이번 구간 사용자 모바일 동의", description = "M04에서 로그인한 사용자가 현재 차량·설치·구간을 명시적으로 확인한 뒤 QR challengeId/proof를 HTTPS JSON 본문으로 보냅니다. 서버는 멤버십과 현재 구간을 확인하고 challenge를 1회 소비해 5분 후보를 만듭니다. 이 동의만으로 차량 화면 사용자를 변경하지 않습니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "후보 동의 등록", content = @Content(mediaType = "application/json", schema = @Schema(implementation = TripUserCandidateDtos.MobileConsent.class), examples = @ExampleObject(value = "{\"consentId\":\"66666666-6666-4666-8666-666666666666\",\"candidateId\":\"66666666-6666-4666-8666-666666666666\",\"intervalId\":\"55555555-5555-4555-8555-555555555555\",\"status\":\"ACTIVE\",\"expiresAt\":\"2026-11-04T10:05:00Z\"}"))), @ApiResponse(responseCode = "401", description = "모바일 JWT 오류"), @ApiResponse(responseCode = "403", description = "proof·멤버십·차량·설치·구간 불일치"), @ApiResponse(responseCode = "409", description = "소비된 challenge 또는 같은 키에 다른 body"), @ApiResponse(responseCode = "410", description = "challenge 만료")})
    public TripUserCandidateDtos.MobileConsent consent(@AuthenticationPrincipal Jwt jwt,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "차량·설치·구간과 QR의 challengeId/proof, 모바일 재시도 operationId", required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = TripUserCandidateDtos.MobileConsentRequest.class), examples = @ExampleObject(value = "{\"vehicleId\":\"22222222-2222-4222-8222-222222222222\",\"installationId\":\"44444444-4444-4444-8444-444444444444\",\"intervalId\":\"55555555-5555-4555-8555-555555555555\",\"challengeId\":\"11111111-1111-4111-8111-111111111111\",\"proof\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"operationId\":\"mobile-consent-001\"}")))
            @Valid @RequestBody TripUserCandidateDtos.MobileConsentRequest body) {
        return candidates.consent(jwt, body);
    }
}
