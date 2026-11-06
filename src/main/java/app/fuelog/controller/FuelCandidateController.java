package app.fuelog.controller;

import app.fuelog.dto.FuelCandidateDtos.*;
import app.fuelog.service.FuelCandidateService;
import app.fuelog.service.PairingActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(produces="application/json")
@Tag(name="C05 주유 후보 / M07 모바일 확인")
public class FuelCandidateController {
    private final FuelCandidateService service;
    private final PairingActor actor;
    public FuelCandidateController(FuelCandidateService service,PairingActor actor) { this.service=service; this.actor=actor; }
    @ModelAttribute
    void noStore(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }

    @PostMapping("/fuel-candidates/sync")
    @SecurityRequirement(name="installationBearer")
    @Operation(summary="주유 증가 후보 동기화", description="설치 Bearer와 현재 구간의 검증된 사용자 동의를 검사합니다. status/revision/표시 메타데이터는 캐시로 무시합니다. queued=true는 확인함 전달 의도입니다. 시각은 epoch milliseconds입니다.")
    public View sync(@RequestHeader(value="Authorization",required=false) String bearer,@Valid @RequestBody Sync body) {
        return service.sync(bearer,body);
    }
    @GetMapping("/mobile/fuel-candidates/{id}")
    @SecurityRequirement(name="supabaseBearer")
    @Operation(summary="내 주유 후보 상세 조회")
    public View detail(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { return service.detail(actor.requireUser(jwt),id); }

    @PatchMapping("/mobile/fuel-candidates/{id}")
    @SecurityRequirement(name="supabaseBearer")
    @Operation(summary="주유 후보 확인/기각 및 수동 보정",description="operationId 재시도는 저장한 응답을 반환하며 다른 요청으로 재사용하면 409입니다. expectedRevision 불일치는 409입니다. 보정값은 감지 증거 및 추정값과 별도 저장합니다.")
    public View patch(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody Patch body) {
        return service.patch(actor.requireUser(jwt),id,body);
    }
    @PostMapping("/fuel-candidates/{id}/station-search")
    @SecurityRequirement(name="installationBearer")
    @Operation(summary="주유소 후보 조회",description="현재 오피넷 공급자 미연동으로 빈 후보 목록을 반환합니다. 위치 권한/신선도가 없거나 공급자가 없어도 원본 증거와 추정량은 보존합니다.")
    public View stationSearch(@RequestHeader(value="Authorization",required=false) String bearer,@PathVariable UUID id,
                              @Valid @RequestBody StationSearch body) { return service.stationSearch(bearer,id,body); }
}
