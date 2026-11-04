package app.fuelog.controller;

import app.fuelog.dto.TripUserCandidateDtos;
import app.fuelog.service.PairingActor;
import app.fuelog.service.TripUserCandidateService;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Tag(name = "C03 이번 운행 사용자 확인", description = "후보는 현재 설치·차량·기록 구간에 대한 모바일 동의가 있을 때만 노출합니다. 차량 앱의 설치 Bearer로 조회·검증하고, revoke는 설치 Bearer 또는 해당 모바일 사용자 JWT를 받습니다.")
public class TripUserCandidateController {
    private final TripUserCandidateService service;
    private final JwtDecoder jwtDecoder;
    private final PairingActor actor;

    public TripUserCandidateController(TripUserCandidateService service, JwtDecoder jwtDecoder, PairingActor actor) {
        this.service = service;
        this.jwtDecoder = jwtDecoder;
        this.actor = actor;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @PostMapping("/trip-user/candidates")
    @Operation(summary = "현재 구간의 유효 후보 조회", description = "설치 Bearer와 intervalId가 필요합니다. 모바일이 명시적으로 동의하고 현재 차량 멤버십이 남아 있으며 5분 유효기간이 지나지 않은 후보만 반환합니다. 한 명이어도 차량 화면에서 선택·확인해야 합니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "후보 목록", content = @Content(mediaType = "application/json", schema = @Schema(implementation = TripUserCandidateDtos.Candidates.class), examples = @ExampleObject(value = "{\"intervalId\":\"55555555-5555-4555-8555-555555555555\",\"candidates\":[{\"candidateId\":\"66666666-6666-4666-8666-666666666666\",\"displayName\":\"운전자\",\"vehicleMember\":true,\"mobileConsented\":true,\"remainingSeconds\":240}]}"))), @ApiResponse(responseCode = "403", description = "다른 설치·차량의 구간"), @ApiResponse(responseCode = "409", description = "종료된 구간")})
    public TripUserCandidateDtos.Candidates candidates(
            @Parameter(name = "Authorization", in = ParameterIn.HEADER, required = true, description = "설치 Bearer 자격 증명") @RequestHeader(value = "Authorization", required = false) String bearer,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "조회할 현재 수동 기록 구간", required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = TripUserCandidateDtos.CandidatesRequest.class), examples = @ExampleObject(value = "{\"intervalId\":\"55555555-5555-4555-8555-555555555555\"}")))
            @Valid @RequestBody TripUserCandidateDtos.CandidatesRequest body) {
        return service.candidates(bearer, body);
    }

    @PostMapping("/trip-user/verify")
    @Operation(summary = "차량 화면에서 후보 최종 확인", description = "선택한 candidateId가 같은 설치·차량·구간의 모바일 동의인지 재검사하고 일회용 후보를 소비합니다. 같은 operationId와 같은 body 재시도만 같은 검증 결과를 반환합니다. 기존 구간이나 이미 저장된 샘플의 소유자는 변경하지 않습니다. 신규 샘플에 X-Trip-User-Candidate를 전달하면 서버가 다시 확인합니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "이번 구간 신규 기록용 검증 결과", content = @Content(mediaType = "application/json", schema = @Schema(implementation = TripUserCandidateDtos.Verified.class), examples = @ExampleObject(value = "{\"intervalId\":\"55555555-5555-4555-8555-555555555555\",\"candidateId\":\"66666666-6666-4666-8666-666666666666\",\"subjectId\":\"33333333-3333-4333-8333-333333333333\",\"vehicleMember\":true,\"mobileConsented\":true,\"expiresAtEpochMillis\":1793786700000}"))), @ApiResponse(responseCode = "403", description = "후보·차량·구간·설치 범위 또는 멤버십 불일치"), @ApiResponse(responseCode = "409", description = "다른 키로 재사용하거나 같은 키에 다른 body"), @ApiResponse(responseCode = "410", description = "만료·폐기된 후보")})
    public TripUserCandidateDtos.Verified verify(
            @Parameter(name = "Authorization", in = ParameterIn.HEADER, required = true, description = "설치 Bearer 자격 증명") @RequestHeader(value = "Authorization", required = false) String bearer,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "구간 ID, 목록의 후보 ID, 재시도에 고정할 operationId", required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = TripUserCandidateDtos.VerifyRequest.class), examples = @ExampleObject(value = "{\"intervalId\":\"55555555-5555-4555-8555-555555555555\",\"candidateId\":\"66666666-6666-4666-8666-666666666666\",\"operationId\":\"verify-001\"}")))
            @Valid @RequestBody TripUserCandidateDtos.VerifyRequest body) {
        return service.verify(bearer, body);
    }

    @PostMapping("/trip-user/revoke")
    @Operation(summary = "후보·향후 사용 권한 폐기", description = "설치 Bearer 또는 해당 후보를 동의한 모바일 사용자의 Supabase Bearer JWT로 호출합니다. 후보를 목록에서 제거하고 이후 신규 샘플 귀속을 막습니다. 이미 저장된 샘플과 구간 소유자는 변경하지 않습니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "폐기 완료", content = @Content(mediaType = "application/json", schema = @Schema(implementation = TripUserCandidateDtos.Revoked.class), examples = @ExampleObject(value = "{\"consentId\":\"66666666-6666-4666-8666-666666666666\",\"status\":\"REVOKED\"}"))), @ApiResponse(responseCode = "401", description = "인증 실패"), @ApiResponse(responseCode = "403", description = "다른 설치 또는 모바일 사용자")})
    public TripUserCandidateDtos.Revoked revoke(
            @Parameter(name = "Authorization", in = ParameterIn.HEADER, required = true, description = "설치 Bearer 또는 해당 모바일 사용자의 Supabase JWT") @RequestHeader(value = "Authorization", required = false) String bearer,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "모바일 동의 응답의 consentId", required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = TripUserCandidateDtos.RevokeRequest.class), examples = @ExampleObject(value = "{\"consentId\":\"66666666-6666-4666-8666-666666666666\"}")))
            @Valid @RequestBody TripUserCandidateDtos.RevokeRequest body) {
        if (bearer != null && bearer.startsWith("Bearer ") && bearer.substring(7).contains(".")) {
            Jwt jwt;
            try { jwt = jwtDecoder.decode(bearer.substring(7)); }
            catch (JwtException ex) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_USER_TOKEN"); }
            return service.revokeByUser(actor.requireUser(jwt), body.consentId());
        }
        return service.revokeByInstallation(bearer, body.consentId());
    }

    @GetMapping("/trip-user/events")
    @Operation(summary = "후보 변경 이벤트 조회", description = "설치 Bearer로 cursor 이후 최대 100개의 revision과 변경 종류만 받습니다. 이름·subjectId·candidateId를 이벤트에 넣지 않습니다. 변경 시 C03에서 candidates를 재조회하세요.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "개인정보 없는 변경 이벤트", content = @Content(mediaType = "application/json", schema = @Schema(implementation = TripUserCandidateDtos.Events.class), examples = @ExampleObject(value = "{\"revision\":3,\"nextCursor\":3,\"events\":[{\"revision\":3,\"type\":\"candidates_changed\"}]}"))), @ApiResponse(responseCode = "400", description = "음수 cursor"), @ApiResponse(responseCode = "401", description = "설치 인증 실패")})
    public TripUserCandidateDtos.Events events(
            @Parameter(name = "Authorization", in = ParameterIn.HEADER, required = true, description = "설치 Bearer 자격 증명") @RequestHeader(value = "Authorization", required = false) String bearer,
            @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "마지막으로 처리한 revision. 최초 0", example = "0") @RequestParam(value = "cursor", required = false) Long cursor) {
        return service.events(bearer, cursor);
    }
}
