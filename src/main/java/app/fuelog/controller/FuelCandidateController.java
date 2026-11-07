package app.fuelog.controller;

import app.fuelog.dto.FuelCandidateDtos.*;
import app.fuelog.service.FuelCandidateService;
import app.fuelog.service.PairingActor;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 장치의 주유 후보 동기화와 모바일 소유자의 확인 API를 제공한다. */
@RestController
@RequestMapping(produces = "application/json")
@Tag(name = "C05 주유 후보 / M07 모바일 확인")
public class FuelCandidateController {
    private final FuelCandidateService service;
    private final PairingActor actor;

    public FuelCandidateController(FuelCandidateService service, PairingActor actor) {
        this.service = service;
        this.actor = actor;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @PostMapping("/fuel-candidates/sync")
    @SecurityRequirement(name = "installationBearer")
    @Operation(
            summary = "주유로 추정되는 연료 증가 후보 저장",
            description =
                    "장치의 설치 Bearer로 호출합니다. 현재 운행 구간과 확인된 사용자 동의가 필요합니다. startAt/endAt은 UTC 기준"
                        + " 밀리초입니다.\n\n"
                        + "queued=true이면 사용자 모바일 확인함에 전달합니다. status, revision, 추정량 등 클라이언트 캐시는 저장"
                        + " 명령으로 사용하지 않으며 서버가 권한과 추정 근거를 확인합니다.")
    public View sync(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @Valid @RequestBody Sync body) {
        return service.sync(bearer, body);
    }

    @GetMapping("/mobile/fuel-candidates/{id}")
    @SecurityRequirement(name = "supabaseBearer")
    @Operation(
            summary = "내 주유 후보 상세 조회",
            description =
                    "모바일의 Supabase JWT가 필요합니다. 후보 소유자이며 현재 차량 멤버인 사용자만 조회할 수 있습니다. 감지 정보, 서버 추정값,"
                            + " 사용자 보정값을 반환합니다.")
    public View detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.detail(actor.requireUser(jwt), id);
    }

    @PatchMapping("/mobile/fuel-candidates/{id}")
    @SecurityRequirement(name = "supabaseBearer")
    @Operation(
            summary = "주유 후보 확인·기각 및 보정",
            description =
                    "모바일의 Supabase JWT로 action=CONFIRM 또는 REJECT를 보냅니다. 필요하면 place, liters, amount를"
                        + " 함께 보내세요.\n\n"
                        + "expectedRevision은 현재 후보 revision입니다. 같은 operationId와 같은 본문은 저장된 응답을 반환하며"
                        + " 키 재사용 또는 revision 불일치는 409입니다. 보정값은 원래 감지·추정값과 별도로 저장합니다.")
    public View patch(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody Patch body) {
        return service.patch(actor.requireUser(jwt), id, body);
    }

    @PostMapping("/fuel-candidates/{id}/station-search")
    @SecurityRequirement(name = "installationBearer")
    @Operation(
            summary = "주유소 후보 조회 — 외부 공급자 미연동",
            description =
                    "장치의 설치 Bearer로 호출합니다. locationPermission과 선택적인 좌표·관측 시각을 받습니다.\n\n"
                        + "현재 외부 주유소 공급자가 연결되지 않아 stationCandidates는 빈 목록입니다. 기존 감지 정보와 추정량은 변경하지"
                        + " 않습니다.")
    public View stationSearch(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @PathVariable UUID id,
            @Valid @RequestBody StationSearch body) {
        return service.stationSearch(bearer, id, body);
    }
}
