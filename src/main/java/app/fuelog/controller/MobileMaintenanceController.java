package app.fuelog.controller;

import app.fuelog.dto.MaintenanceDtos.*;
import app.fuelog.service.MaintenanceService;
import app.fuelog.service.PairingActor;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

/** 모바일 사용자의 정비 계획·이력을 관리하고 ETag로 동시 수정을 보호한다. */
@RestController
@RequestMapping(value = "/mobile/vehicles/{vehicleId}", produces = "application/json")
@Tag(
        name = "M11/M12 정비 관리",
        description =
                "Supabase 사용자 JWT와 차량 멤버십이 필요합니다. 공동 쓰기는 OWNER/MANAGER, 개인 쓰기는 본인만 가능합니다. 기록 수정·삭제는"
                        + " 작성자만 가능하며 변경과 예정 재계산은 한 트랜잭션입니다.")
@SecurityRequirement(name = "supabaseBearer")
public class MobileMaintenanceController {
    private final MaintenanceService service;
    private final PairingActor actor;

    public MobileMaintenanceController(MaintenanceService service, PairingActor actor) {
        this.service = service;
        this.actor = actor;
    }

    private static final String EVENT_INPUT =
            "{\"itemId\":\"11111111-1111-4111-8111-111111111111\",\"serviceDate\":\"2026-01-10\",\"odometerKm\":42000,\"status\":\"CONFIRMED\",\"shared\":true,\"detailsShared\":false,\"cost\":85000,\"currency\":\"KRW\",\"memo\":\"사용자"
                + " 확인 기록\",\"shop\":null,\"receiptUrl\":null}";
    private static final String EVENT_OUTPUT =
            "{\"id\":\"33333333-3333-4333-8333-333333333333\",\"itemId\":\"11111111-1111-4111-8111-111111111111\",\"serviceDate\":\"2026-01-10\",\"odometerKm\":42000,\"status\":\"CONFIRMED\",\"shared\":true,\"detailsShared\":false,\"revision\":1,\"details\":{\"cost\":85000,\"currency\":\"KRW\",\"memo\":\"사용자"
                + " 확인 기록\",\"shop\":null,\"receiptUrl\":null}}";
    private static final String PLAN_INPUT =
            "{\"name\":\"엔진오일\",\"shared\":true,\"basis\":\"USER\",\"evidenceId\":null,\"intervalKm\":10000,\"intervalMonths\":12}";
    private static final String PLAN_OUTPUT =
            "{\"id\":\"11111111-1111-4111-8111-111111111111\",\"name\":\"엔진오일\",\"shared\":true,\"active\":true,\"basis\":\"USER\",\"evidence\":null,\"intervalKm\":10000,\"intervalMonths\":12,\"revision\":1,\"lastServiceDate\":null,\"lastServiceKm\":null,\"nextDueDate\":null,\"nextDueKm\":null}";

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @GetMapping("/maintenance-events")
    @Operation(
            summary = "정비 이력 목록 조회",
            description =
                    "모바일의 Supabase JWT와 차량 멤버십이 필요합니다. 공동 이력과 본인 개인 이력을 최대 50건 반환하며 cursor는 이전 응답의"
                        + " nextCursor입니다.\n\n"
                        + "비용·메모 등 details는 작성자 또는 detailsShared=true인 경우에만 제공하고 나머지는 null입니다. 삭제된"
                        + " 기록은 제외합니다.")
    @ApiResponse(
            responseCode = "200",
            description = "이력 페이지",
            content =
                    @Content(
                            examples =
                                    @ExampleObject(value = "{\"items\":[],\"nextCursor\":null}")))
    public Events events(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID vehicleId,
            @Parameter(description = "이전 페이지 nextCursor. 최초 생략") @RequestParam(required = false)
                    UUID cursor) {
        return service.events(actor.requireUser(jwt), vehicleId, cursor);
    }

    @PostMapping("/maintenance-events")
    @Operation(
            summary = "정비 기록 등록",
            description =
                    "모바일의 Supabase JWT와 재시도에 유지할 Idempotency-Key 헤더가 필요합니다. 활성 정비 계획의 itemId와"
                        + " serviceDate 또는 odometerKm를 보내세요.\n\n"
                        + "CONFIRMED는 확정 기록으로 다음 정비 예정 계산에 쓰이며 VISIT_ESTIMATE는 방문 추정으로 계산에서 제외됩니다."
                        + " 같은 키·같은 본문은 최초 응답, 다른 본문은 409입니다. 응답 ETag는 기록 revision입니다.")
    @ApiResponse(
            responseCode = "200",
            description = "등록 및 재계산 완료, ETag는 기록 revision",
            content = @Content(examples = @ExampleObject(value = EVENT_OUTPUT)))
    public Event create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID vehicleId,
            @Parameter(
                            description = "재시도에 유지할 최대 100자 키",
                            required = true,
                            example = "maintenance-create-001")
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String key,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "개인 상세 공개는 공동 항목 공개와 독립된 detailsShared로 지정합니다.",
                            required = true,
                            content = @Content(examples = @ExampleObject(value = EVENT_INPUT)))
                    @Valid
                    @RequestBody
                    EventInput body,
            HttpServletResponse response) {
        Event result = service.create(actor.requireUser(jwt), vehicleId, key, body);
        etag(response, result.revision());
        return result;
    }

    @PostMapping("/maintenance-plans/{itemId}/complete")
    @Operation(summary = "사용자 정비 완료 확인 및 다음 회차 생성",
            description = "실제 정비를 사용자가 확인한 경우 confirmed=true, serviceDate를 보냅니다. 거리 주기가 있으면 odometerKm도 필수입니다. "
                    + "현재 계획 revision의 If-Match와 재시도에 유지할 Idempotency-Key가 필요합니다. 공동 계획은 OWNER, 개인 계획은 소유자만 완료합니다. "
                    + "기존 계획은 completedAt/deletedAt을 기록해 비활성화하고 동일 이름·주기의 새 UUID 계획을 생성합니다. "
                    + "완료 확인 이력은 수정·삭제할 수 없습니다(409). 같은 키·본문 재시도는 같은 완료 이력과 다음 계획 ID를 반환합니다.")
    public Completion complete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID vehicleId,
            @PathVariable UUID itemId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CompletionInput body) {
        return service.complete(actor.requireUser(jwt), vehicleId, itemId, ifMatch, key, body);
    }

    @PatchMapping("/maintenance-events/{eventId}")
    @Operation(
            summary = "내 정비 기록 일부 수정",
            description =
                    "작성자의 Supabase JWT와 현재 revision을 담은 If-Match 헤더가 필요합니다. 예: If-Match: \"1\". 수정할"
                        + " 필드만 보내며 생략은 유지, null은 선택값 제거입니다.\n\n"
                        + "변경 후 다음 정비 예정을 다시 계산하고 새 ETag를 반환합니다. If-Match 누락은 428, revision 불일치는"
                        + " 412입니다.")
    @ApiResponse(
            responseCode = "200",
            description = "변경 결과와 새 ETag",
            content = @Content(examples = @ExampleObject(value = EVENT_OUTPUT)))
    public Event patch(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID vehicleId,
            @PathVariable UUID eventId,
            @Parameter(description = "현재 기록 revision", required = true, example = "\"1\"")
                    @RequestHeader(value = "If-Match", required = false)
                    String ifMatch,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "수정할 필드만 보냅니다.",
                            required = true,
                            content =
                                    @Content(
                                            examples =
                                                    @ExampleObject(
                                                            value =
                                                                    "{\"serviceDate\":\"2026-02-10\",\"odometerKm\":43000}")))
                    @RequestBody
                    JsonNode patch,
            HttpServletResponse response) {
        Event result = service.patch(actor.requireUser(jwt), vehicleId, eventId, ifMatch, patch);
        etag(response, result.revision());
        return result;
    }

    @DeleteMapping("/maintenance-events/{eventId}")
    @Operation(
            summary = "내 정비 기록 삭제",
            description =
                    "작성자의 Supabase JWT와 현재 revision을 담은 If-Match 헤더가 필요합니다.\n\n"
                        + "기록을 논리 삭제하고 마지막 유효 확정 기록으로 정비 예정을 다시 계산합니다. 감사 이력은 보존하며 동일 삭제 재시도는 기존"
                        + " 결과를 반환합니다.")
    @ApiResponse(
            responseCode = "200",
            description = "삭제·재계산 완료",
            content =
                    @Content(
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"id\":\"33333333-3333-4333-8333-333333333333\",\"revision\":2,\"deleted\":true}")))
    public Deleted deleteEvent(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID vehicleId,
            @PathVariable UUID eventId,
            @Parameter(required = true, description = "현재 기록 revision", example = "\"1\"")
                    @RequestHeader(value = "If-Match", required = false)
                    String ifMatch) {
        return service.deleteEvent(actor.requireUser(jwt), vehicleId, eventId, ifMatch);
    }

    @GetMapping("/maintenance-plans")
    @Operation(
            summary = "정비 주기·근거 목록 조회",
            description =
                    "모바일의 Supabase JWT와 차량 멤버십이 필요합니다. 공동 계획과 본인 개인 계획을 반환합니다.\n\n"
                        + "일반 비활성 계획도 포함하되 완료 후 논리 삭제된 계획은 제외합니다. 제조사 기준 계획은 근거 문서·차종·연식·적용 범위와 현재 검증 여부를 함께"
                        + " 반환합니다.")
    @ApiResponse(
            responseCode = "200",
            description = "계획 목록",
            content = @Content(examples = @ExampleObject(value = "{\"items\":[]}")))
    public Plans plans(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID vehicleId) {
        return service.plans(actor.requireUser(jwt), vehicleId);
    }

    @PutMapping("/maintenance-plans/{itemId}")
    @Operation(
            summary = "정비 주기 생성·수정",
            description =
                    "모바일의 Supabase JWT가 필요합니다. 생성 시 새 itemId UUID를 사용하고, 기존 계획 변경·재활성화 시 현재"
                        + " revision을 If-Match로 보내세요. 공동 계획 쓰기는 OWNER/MANAGER만 가능합니다.\n\n"
                        + "intervalKm 또는 intervalMonths가 필요합니다. basis=USER는 사용자 기준, MANUFACTURER는"
                        + " 제조사 기준입니다. 제조사 기준은 검증된 evidenceId와 항목명·주기가 일치해야 하며 검증 실패는 422입니다. 차종·연식 일치 검사는 생략합니다.")
    @ApiResponse(
            responseCode = "200",
            description = "계획·예정과 설정 revision ETag",
            content = @Content(examples = @ExampleObject(value = PLAN_OUTPUT)))
    public Plan putPlan(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID vehicleId,
            @PathVariable UUID itemId,
            @Parameter(description = "기존 계획 revision. 최초 생략 또는 0", example = "\"1\"")
                    @RequestHeader(value = "If-Match", required = false)
                    String ifMatch,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "intervalMonths는 달력 개월 수입니다.",
                            required = true,
                            content = @Content(examples = @ExampleObject(value = PLAN_INPUT)))
                    @Valid
                    @RequestBody
                    PlanInput body,
            HttpServletResponse response) {
        Plan result = service.putPlan(actor.requireUser(jwt), vehicleId, itemId, ifMatch, body);
        etag(response, result.revision());
        return result;
    }

    @DeleteMapping("/maintenance-plans/{itemId}")
    @Operation(
            summary = "정비 주기 비활성화",
            description =
                    "모바일의 Supabase JWT와 현재 계획 revision을 담은 If-Match 헤더가 필요합니다. 공동 계획은"
                            + " OWNER/MANAGER, 개인 계획은 소유자만 변경할 수 있습니다.\n\n"
                            + "과거 정비 이력은 보존하고 차량 정비 요약에서 해당 예정만 제외합니다.")
    @ApiResponse(
            responseCode = "200",
            description = "비활성화 결과",
            content =
                    @Content(
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"id\":\"11111111-1111-4111-8111-111111111111\",\"revision\":2,\"deleted\":true}")))
    public Deleted deletePlan(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID vehicleId,
            @PathVariable UUID itemId,
            @Parameter(required = true, description = "현재 계획 revision", example = "\"1\"")
                    @RequestHeader(value = "If-Match", required = false)
                    String ifMatch) {
        return service.deletePlan(actor.requireUser(jwt), vehicleId, itemId, ifMatch);
    }

    private static void etag(HttpServletResponse response, long revision) {
        response.setHeader("ETag", "\"" + revision + "\"");
    }
}
