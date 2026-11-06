package app.fuelog.controller;

import app.fuelog.dto.ConnectionSettingsDtos.*;
import app.fuelog.service.ConnectionSettingsService;
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
@Tag(name="C07 연결·설정 / M14 초기화 승인")
public class ConnectionSettingsController {
    private final ConnectionSettingsService service;
    private final PairingActor actor;
    public ConnectionSettingsController(ConnectionSettingsService service,PairingActor actor) { this.service=service;this.actor=actor; }
    @ModelAttribute void noStore(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }

    @PostMapping("/connection/settings")
    @SecurityRequirement(name="installationBearer")
    @Operation(summary="설치 연결 설정 조회",description="kind/operationId envelope는 조회에만 사용합니다. 미보고·60초 초과 기능 상태 및 검증하지 못한 큐는 UNKNOWN입니다.")
    public Settings settings(@RequestHeader(value="Authorization",required=false) String bearer,@Valid @RequestBody Envelope b) { return service.settings(bearer,b); }
    @PostMapping("/connection/disconnect")
    @SecurityRequirement(name="installationBearer")
    @Operation(summary="사용자 해제 또는 차량 초기화 요청",description="USER는 기존 사용자 권한을 폐기하고 COMPLETED를 반환합니다. VEHICLE은 AWAITING_ADMIN입니다. 같은 설치·operationId는 최초 대상/등록 버전에 고정되고 다른 kind 재사용은 409입니다. 기록 소유권과 설치 인증 비밀값을 보존합니다.")
    public OperationResult disconnect(@RequestHeader(value="Authorization",required=false) String bearer,@Valid @RequestBody Disconnect b) { return service.disconnect(bearer,b); }
    @PutMapping("/installation/capabilities")
    @SecurityRequirement(name="installationBearer")
    @Operation(summary="SDK 기능 상태 보고",description="observedAt은 epoch milliseconds. 오래된/동일 시각 보고는 최신 결과를 덮어쓰지 않습니다. 이 보고는 추천 기능의 서버 검증을 대체하지 않습니다.")
    public Features capabilities(@RequestHeader(value="Authorization",required=false) String bearer,@Valid @RequestBody Capabilities b) { return service.capabilities(bearer,b); }
    @GetMapping("/installation/sync-status")
    @SecurityRequirement(name="installationBearer")
    @Operation(summary="현재 검증된 소유자의 큐 확인 상태",description="현재 사용자만 반환하고 다른 사용자 데이터/금액은 노출하지 않습니다. 서버 큐 조정 작업의 검증된 체크포인트가 없으면 UNKNOWN이며 로컬 큐가 비었다고 추정하지 않습니다.")
    public SyncStatus sync(@RequestHeader(value="Authorization",required=false) String bearer) { return service.syncStatus(bearer); }
    @GetMapping("/mobile/connection-operations")
    @SecurityRequirement(name="supabaseBearer")
    @Operation(summary="관리 중인 차량의 초기화 승인 대기 목록",description="OWNER/MANAGER인 현재 등록의 요청만 반환합니다. 최대 100건입니다.")
    public Operations pending(@AuthenticationPrincipal Jwt jwt) { return service.pending(actor.requireUser(jwt)); }
    @PostMapping("/mobile/connection-operations/{operationId}/approve")
    @SecurityRequirement(name="supabaseBearer")
    @Operation(summary="차량 초기화 승인",description="installationId와 최초 registrationVersion 및 RETAIN_ORIGINAL_OWNER 정책이 필수입니다. reauthenticationProof는 같은 관리자의 최근 5분 내 OTP/TOTP 인증 기록이 있는 Supabase JWT입니다. 인증 사건은 한 번만 소비합니다.")
    public OperationResult approve(@AuthenticationPrincipal Jwt jwt,@PathVariable String operationId,@Valid @RequestBody Approve b) { return service.approve(jwt,operationId,b); }
    @PostMapping("/mobile/connection-operations/{operationId}/reject")
    @SecurityRequirement(name="supabaseBearer")
    @Operation(summary="차량 초기화 기각",description="관리 권한과 최초 등록 버전을 재검사합니다. 기존 사용자 권한이나 개인 화면 잠금을 복구하지 않습니다.")
    public OperationResult reject(@AuthenticationPrincipal Jwt jwt,@PathVariable String operationId,@Valid @RequestBody Reject b) { return service.reject(actor.requireUser(jwt),operationId,b); }
    @PutMapping("/mobile/vehicles/{vehicleId}/recommendation")
    @SecurityRequirement(name="supabaseBearer")
    @Operation(summary="추천 동의와 활성화 변경",description="consented=false는 철회입니다. 동의/활성화에는 등록·서버 기능 검증 및 현재 C03 사용자 일치가 필요합니다. 추천은 운행 소유자를 변경하지 않습니다.")
    public RecommendationResult recommendation(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID vehicleId,@Valid @RequestBody Recommendation b) { return service.setRecommendation(actor.requireUser(jwt),vehicleId,b); }
}
