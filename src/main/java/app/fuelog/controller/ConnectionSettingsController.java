package app.fuelog.controller;

import app.fuelog.dto.ConnectionSettingsDtos.*;
import app.fuelog.service.ConnectionSettingsService;
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

/** 장치 설정과 모바일 관리자의 연결 초기화 승인 API를 제공한다. */
@RestController
@RequestMapping(produces = "application/json")
@Tag(name = "C07 연결·설정 / M14 초기화 승인")
public class ConnectionSettingsController {
    private final ConnectionSettingsService service;
    private final PairingActor actor;

    public ConnectionSettingsController(ConnectionSettingsService service, PairingActor actor) {
        this.service = service;
        this.actor = actor;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @PostMapping("/connection/settings")
    @SecurityRequirement(name = "installationBearer")
    @Operation(
            summary = "장치 연결·기능·추천 설정 조회",
            description =
                    "장치의 설치 Bearer로 호출합니다. 요청의 kind와 operationId는 기존 클라이언트 요청 형식이며 설정 변경에는 사용하지"
                        + " 않습니다.\n\n"
                        + "장치 별칭, 동기화 상태, 기능 상태, 추천 설정을 반환합니다. 기능 보고가 없거나 60초보다 오래되었거나 서버가 큐 완료를"
                        + " 확인하지 못하면 해당 상태는 UNKNOWN입니다.")
    public Settings settings(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @Valid @RequestBody Envelope b) {
        return service.settings(bearer, b);
    }

    @PostMapping("/connection/disconnect")
    @SecurityRequirement(name = "installationBearer")
    @Operation(
            summary = "사용자 연결 해제 또는 차량 초기화 요청",
            description =
                    "장치의 설치 Bearer로 kind와 operationId를 보냅니다. USER는 사용자 권한을 즉시 폐기하고 COMPLETED,"
                        + " VEHICLE은 사용자 권한을 차단하고 관리자 승인 대기인 AWAITING_ADMIN을 반환합니다.\n\n"
                        + "같은 operationId는 최초 요청 대상에 고정되며 다른 kind로 재사용하면 409입니다. 운행·주유·정비 기록의 소유권과"
                        + " 설치 비밀값은 보존합니다.")
    public OperationResult disconnect(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @Valid @RequestBody Disconnect b) {
        return service.disconnect(bearer, b);
    }

    @PutMapping("/installation/capabilities")
    @SecurityRequirement(name = "installationBearer")
    @Operation(
            summary = "장치 SDK의 기능 사용 가능 상태 보고",
            description =
                    "장치의 설치 Bearer로 연료·거리·위치 기능 상태를 보고합니다. observedAt은 UTC 기준 밀리초입니다.\n\n"
                        + "AVAILABLE은 사용 가능, UNSUPPORTED는 미지원, PERMISSION_REQUIRED는 권한 필요,"
                        + " TEMPORARY_FAILURE는 일시 오류, UNKNOWN은 미확인입니다. 과거 또는 같은 시각의 보고는 최신 값을 덮어쓰지"
                        + " 않습니다. 추천 기능 활성화의 서버 검증은 별도입니다.")
    public Features capabilities(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @Valid @RequestBody Capabilities b) {
        return service.capabilities(bearer, b);
    }

    @GetMapping("/installation/sync-status")
    @SecurityRequirement(name = "installationBearer")
    @Operation(
            summary = "현재 사용자의 서버 큐 확인 상태 조회",
            description =
                    "장치의 설치 Bearer로 호출합니다. 현재 확인된 사용자에 대한 큐 상태만 반환합니다.\n\n"
                        + "PENDING은 대기 항목 있음, SYNCED는 서버가 완료 확인, UNKNOWN은 완료 여부 미확인입니다. 로컬 업로드 큐가"
                        + " 비었다는 의미로 해석하지 마세요. 운행 샘플의 마지막 수신 번호는 GET /sync/status에서 조회합니다.")
    public SyncStatus sync(
            @RequestHeader(value = "Authorization", required = false) String bearer) {
        return service.syncStatus(bearer);
    }

    @GetMapping("/mobile/connection-operations")
    @SecurityRequirement(name = "supabaseBearer")
    @Operation(
            summary = "관리 차량의 초기화 승인 대기 목록 조회",
            description =
                    "모바일의 Supabase JWT로 호출합니다. 현재 연결된 차량에서 OWNER 또는 MANAGER 권한이 있는 사용자의 승인 대기 요청을"
                        + " 최대 100건 반환합니다.\n\n"
                        + "승인·기각 요청에는 목록의 operationId, installationId, registrationVersion을 사용하세요.")
    public Operations pending(@AuthenticationPrincipal Jwt jwt) {
        return service.pending(actor.requireUser(jwt));
    }

    @PostMapping("/mobile/connection-operations/{operationId}/approve")
    @SecurityRequirement(name = "supabaseBearer")
    @Operation(
            summary = "관리자가 차량 초기화 승인",
            description =
                    "모바일의 Supabase JWT와 목록의 installationId, registrationVersion을 보냅니다."
                        + " pendingRecordsPolicy는 RETAIN_ORIGINAL_OWNER로 지정해 기존 기록 소유권 보존에 동의해야"
                        + " 합니다.\n\n"
                        + "reauthenticationProof는 같은 관리자의 최근 5분 내 OTP/TOTP 인증 기록이 있는 Supabase JWT"
                        + " 원문입니다. 같은 인증 사건은 다른 초기화에 재사용할 수 없습니다. 승인하면 차량 연결과 사용자 권한을 해제합니다.")
    public OperationResult approve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String operationId,
            @Valid @RequestBody Approve b) {
        return service.approve(jwt, operationId, b);
    }

    @PostMapping("/mobile/connection-operations/{operationId}/reject")
    @SecurityRequirement(name = "supabaseBearer")
    @Operation(
            summary = "관리자가 차량 초기화 기각",
            description =
                    "모바일의 Supabase JWT와 목록의 installationId, registrationVersion을 보냅니다. 현재 관리자 권한과"
                            + " 최초 요청의 차량 등록 버전을 다시 검사합니다.\n\n"
                            + "기각해도 초기화 요청 시 폐기한 사용자 권한과 개인 화면 접근은 자동 복구하지 않습니다.")
    public OperationResult reject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String operationId,
            @Valid @RequestBody Reject b) {
        return service.reject(actor.requireUser(jwt), operationId, b);
    }

    @PutMapping("/mobile/vehicles/{vehicleId}/recommendation")
    @SecurityRequirement(name = "supabaseBearer")
    @Operation(
            summary = "사용자 추천 기능 동의·활성화 변경",
            description =
                    "모바일의 Supabase JWT로 호출합니다. consented=false는 동의 철회이며 enabled=true에는 동의가"
                        + " 필요합니다.\n\n"
                        + "동의·활성화에는 서버의 추천 기능 등록·검증과 현재 운행에서 확인된 사용자 일치가 필요합니다. 추천 설정은 운행 기록의 소유자를"
                        + " 변경하지 않습니다.")
    public RecommendationResult recommendation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID vehicleId,
            @Valid @RequestBody Recommendation b) {
        return service.setRecommendation(actor.requireUser(jwt), vehicleId, b);
    }
}
