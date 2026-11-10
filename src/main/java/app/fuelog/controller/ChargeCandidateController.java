package app.fuelog.controller;

import app.fuelog.dto.ChargeCandidateDtos.*;
import app.fuelog.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@Tag(name = "충전 후보·참고요금", description = "장치는 설치 Bearer, 모바일은 Supabase JWT를 사용합니다. 참고요금은 실제 결제금액과 다를 수 있습니다.")
public class ChargeCandidateController {
    private final ChargeCandidateService service;
    private final PairingActor actor;
    public ChargeCandidateController(ChargeCandidateService service,PairingActor actor) { this.service=service;this.actor=actor; }
    @ModelAttribute void noStore(jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    @PostMapping("/charge-candidates/sync")
    @SecurityRequirement(name = "installationBearer")
    @Operation(
            summary = "배터리 증가로 감지한 충전 후보 저장·갱신",
            description = "장치의 설치 Bearer로 id, intervalId, owner, startAt, endAt, estimatedKwh, basis를 보냅니다. "
                    + "운행 구간에 고정된 소유자와 현재 차량 멤버십을 검사합니다. 시각은 UTC epoch milliseconds, 충전량은 0 초과 1000 이하의 kWh입니다.\n\n"
                    + "같은 후보 ID·관측 시각은 중복 저장하지 않습니다. 더 늦은 관측은 충전량과 버전을 갱신하지만 소유자·구간·시작 시각·감지 근거는 바꿀 수 없습니다. "
                    + "확인·기각된 후보는 갱신하지 않으며 잘못된 범위는 403, 감지 내용 충돌은 409입니다.")
    public View sync(@RequestHeader(value="Authorization",required=false) String bearer,@Valid @RequestBody Sync body) { return service.sync(bearer,body); }
    @GetMapping("/charge-candidates/{id}")
    @SecurityRequirement(name = "installationBearer")
    @Operation(
            summary = "장치에서 현재 사용자의 충전 후보 조회",
            description = "장치의 설치 Bearer와 충전 후보 UUID로 조회합니다. 후보가 같은 설치·현재 연결 차량에 속하고 현재 인증 사용자 소유여야 합니다. "
                    + "감지 충전량, 상태·revision, 참고요금 시나리오와 사용자 보정값을 반환합니다. 후보가 없으면 404, 접근 권한이 없으면 403입니다.")
    public View device(@RequestHeader(value="Authorization",required=false) String bearer,@PathVariable UUID id) { return service.detailDevice(bearer,id); }
    @PostMapping("/charge-candidates/{id}/estimate")
    @SecurityRequirement(name = "installationBearer")
    @Operation(
            summary = "충전 후보의 주변 사업자 참고요금 계산",
            description = "장치의 설치 Bearer로 후보 ID와 Pleos 위치 증거를 보냅니다. candidateId는 경로의 id와 같고 locationOperationId는 charge-estimate:{id}여야 합니다. "
                    + "source는 PLEOS_CURRENT_UPDATE, measuredAt·receivedAt은 ISO-8601 시각입니다. 위치는 최초 감지 시각부터 10초 안에 측정·수신되어야 하며 수신 후 2분 이내여야 합니다.\n\n"
                    + "출력 구간별 서로 다른 사업자 2~3곳의 평균 단가와 추정 금액을 반환합니다. 좌표계 미검증은 COORDINATE_SYSTEM_UNVERIFIED, "
                    + "비교 자료 부족은 INSUFFICIENT_COMPARABLE_STATIONS입니다. 여러 출력 구간은 POWER_BAND_SELECTION_REQUIRED로 구분합니다. "
                    + "이미 계산했거나 확인·기각된 후보는 기존 결과를 반환하며 원시 좌표는 저장하지 않습니다.")
    public View estimate(@RequestHeader(value="Authorization",required=false) String bearer,@PathVariable UUID id,@Valid @RequestBody Location body) { return service.estimate(bearer,id,body); }
    @PostMapping("/recording-intervals/{id}/charging-reference")
    @SecurityRequirement(name = "installationBearer")
    @Operation(
            summary = "운행의 현재 조회 위치 기준 충전 참고단가 조회",
            description = "장치의 설치 Bearer로 현재 인증 사용자에게 귀속된 운행의 참고단가를 조회합니다. "
                    + "latitude, longitude, measuredAt, receivedAt, source=PLEOS_CURRENT_UPDATE를 보냅니다. 측정은 최근 30초 이내, 수신은 측정 후 10초 이내여야 합니다.\n\n"
                    + "충전 후보 없이도 조회할 수 있으며 출력 구간별 평균 원/kWh와 비교 충전소를 반환합니다. 이 결과는 현재 위치의 참고단가이며 과거 운행의 확정 결제가격으로 저장하지 않습니다. "
                    + "원시 좌표는 저장·반환하지 않습니다. 정상 참고값은 CURRENT_LOCATION_REFERENCE, 좌표계 미검증·자료 부족은 해당 사유 상태를 반환합니다.")
    public TripReference tripReference(@RequestHeader(value="Authorization",required=false) String bearer,
            @PathVariable UUID id, @Valid @RequestBody ReferenceLocation body) { return service.tripReference(bearer,id,body); }
    @GetMapping("/mobile/charge-candidates")
    @SecurityRequirement(name = "supabaseBearer")
    @Operation(
            summary = "모바일에서 내 충전 후보 목록 조회",
            description = "모바일의 Supabase JWT로 호출합니다. 로그인 사용자 소유이며 현재 차량 멤버십이 유효한 충전 후보를 최신순으로 최대 100건 반환합니다. "
                    + "응답은 View 배열이며 운행·주유 확인함인 /mobile/inbox와 별도 목록입니다.")
    public List<View> list(@AuthenticationPrincipal Jwt jwt) { return service.list(actor.requireUser(jwt)); }
    @GetMapping("/mobile/charge-candidates/{id}")
    @SecurityRequirement(name = "supabaseBearer")
    @Operation(
            summary = "모바일에서 내 충전 후보 상세 조회",
            description = "모바일의 Supabase JWT와 충전 후보 UUID로 호출합니다. 후보 소유자이며 현재 차량 멤버인 사용자만 조회할 수 있습니다. "
                    + "감지 정보·참고요금·사용자 보정값을 반환하며 후보가 없으면 404, 소유권·멤버십이 없으면 403입니다.")
    public View detail(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { return service.detail(actor.requireUser(jwt),id); }
    @PatchMapping("/mobile/charge-candidates/{id}")
    @SecurityRequirement(name = "supabaseBearer")
    @Operation(
            summary = "충전 후보 확인·기각 및 수동 보정",
            description = "모바일의 Supabase JWT로 operationId, expectedRevision, action=CONFIRM 또는 REJECT를 보냅니다. "
                    + "필요하면 kwh, amount, place를 함께 보정합니다. 보정값은 원래 감지량과 참고 추정값을 덮어쓰지 않으며 생략·null이면 기존 보정값을 유지합니다.\n\n"
                    + "후보 소유자와 현재 차량 멤버십을 검사합니다. 같은 operationId·같은 본문은 최초 결과를 반환하며 다른 본문으로 키를 재사용하거나 revision이 다르면 409입니다.")
    public View patch(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody Patch body) { return service.patch(actor.requireUser(jwt),id,body); }
}
