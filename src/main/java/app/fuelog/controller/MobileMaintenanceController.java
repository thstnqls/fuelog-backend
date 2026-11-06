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
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping(value="/mobile/vehicles/{vehicleId}",produces="application/json")
@Tag(name="M11/M12 정비 관리",description="Supabase 사용자 JWT와 차량 멤버십이 필요합니다. 공동 쓰기는 OWNER/MANAGER, 개인 쓰기는 본인만 가능합니다. 기록 수정·삭제는 작성자만 가능하며 변경과 예정 재계산은 한 트랜잭션입니다.")
@SecurityRequirement(name="supabaseBearer")
public class MobileMaintenanceController {
    private final MaintenanceService service;
    private final PairingActor actor;
    public MobileMaintenanceController(MaintenanceService service,PairingActor actor) { this.service=service; this.actor=actor; }
    private static final String EVENT_INPUT="{\"itemId\":\"11111111-1111-4111-8111-111111111111\",\"serviceDate\":\"2026-01-10\",\"odometerKm\":42000,\"status\":\"CONFIRMED\",\"shared\":true,\"detailsShared\":false,\"cost\":85000,\"currency\":\"KRW\",\"memo\":\"사용자 확인 기록\",\"shop\":null,\"receiptUrl\":null}";
    private static final String EVENT_OUTPUT="{\"id\":\"33333333-3333-4333-8333-333333333333\",\"itemId\":\"11111111-1111-4111-8111-111111111111\",\"serviceDate\":\"2026-01-10\",\"odometerKm\":42000,\"status\":\"CONFIRMED\",\"shared\":true,\"detailsShared\":false,\"revision\":1,\"details\":{\"cost\":85000,\"currency\":\"KRW\",\"memo\":\"사용자 확인 기록\",\"shop\":null,\"receiptUrl\":null}}";
    private static final String PLAN_INPUT="{\"name\":\"엔진오일\",\"shared\":true,\"basis\":\"USER\",\"evidenceId\":null,\"intervalKm\":10000,\"intervalMonths\":12}";
    private static final String PLAN_OUTPUT="{\"id\":\"11111111-1111-4111-8111-111111111111\",\"name\":\"엔진오일\",\"shared\":true,\"active\":true,\"basis\":\"USER\",\"evidence\":null,\"intervalKm\":10000,\"intervalMonths\":12,\"revision\":1,\"lastServiceDate\":null,\"lastServiceKm\":null,\"nextDueDate\":null,\"nextDueKm\":null}";
    @ModelAttribute
    void noStore(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }

    @GetMapping("/maintenance-events")
    @Operation(summary="정비 이력 조회",description="공동 이력과 본인 개인 이력을 UUID cursor로 최대 50건씩 반환합니다. details는 작성자 또는 detailsShared=true일 때만 제공하고 그 외 null입니다. 삭제 기록은 제외합니다.")
    @ApiResponse(responseCode="200",description="이력 페이지",content=@Content(examples=@ExampleObject(value="{\"items\":[],\"nextCursor\":null}")))
    public Events events(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID vehicleId,
            @Parameter(description="이전 페이지 nextCursor. 최초 생략") @RequestParam(required=false) UUID cursor) { return service.events(actor.requireUser(jwt),vehicleId,cursor); }

    @PostMapping("/maintenance-events")
    @Operation(summary="확정 정비 또는 방문 추정 등록",description="활성 plan의 itemId가 필요합니다. CONFIRMED만 예정 계산에 쓰며 VISIT_ESTIMATE는 제외합니다. 날짜·누적거리 중 하나는 필수입니다. 동일 Idempotency-Key와 같은 본문은 최초 응답, 다른 본문은 409입니다.")
    @ApiResponse(responseCode="200",description="등록 및 재계산 완료, ETag는 기록 revision",content=@Content(examples=@ExampleObject(value=EVENT_OUTPUT)))
    public Event create(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID vehicleId,
            @Parameter(description="재시도에 유지할 최대 100자 키",required=true,example="maintenance-create-001") @RequestHeader(value="Idempotency-Key",required=false) String key,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description="개인 상세 공개는 공동 항목 공개와 독립된 detailsShared로 지정합니다.",required=true,content=@Content(examples=@ExampleObject(value=EVENT_INPUT)))
            @Valid @RequestBody EventInput body,HttpServletResponse response) {
        Event result=service.create(actor.requireUser(jwt),vehicleId,key,body); etag(response,result.revision()); return result;
    }

    @PatchMapping("/maintenance-events/{eventId}")
    @Operation(summary="정비 기록 일부 수정·예정 재계산",description="작성자만 수정합니다. 생략은 유지, null은 선택 필드 제거입니다. itemId 변경 시 이전·새 항목 모두 재계산합니다. 현재 revision을 If-Match로 전달하며 누락 428, 불일치 412입니다.")
    @ApiResponse(responseCode="200",description="변경 결과와 새 ETag",content=@Content(examples=@ExampleObject(value=EVENT_OUTPUT)))
    public Event patch(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID vehicleId,@PathVariable UUID eventId,
            @Parameter(description="현재 기록 revision",required=true,example="\"1\"") @RequestHeader(value="If-Match",required=false) String ifMatch,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description="수정할 필드만 보냅니다.",required=true,content=@Content(examples=@ExampleObject(value="{\"serviceDate\":\"2026-02-10\",\"odometerKm\":43000}")))
            @RequestBody JsonNode patch,HttpServletResponse response) {
        Event result=service.patch(actor.requireUser(jwt),vehicleId,eventId,ifMatch,patch); etag(response,result.revision()); return result;
    }

    @DeleteMapping("/maintenance-events/{eventId}")
    @Operation(summary="정비 기록 논리 삭제",description="작성자·If-Match를 검사합니다. 감사 이력을 보존하고 마지막 유효 확정 기록으로 다음 예정을 재계산합니다. 동일 삭제 재시도는 기존 결과입니다.")
    @ApiResponse(responseCode="200",description="삭제·재계산 완료",content=@Content(examples=@ExampleObject(value="{\"id\":\"33333333-3333-4333-8333-333333333333\",\"revision\":2,\"deleted\":true}")))
    public Deleted deleteEvent(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID vehicleId,@PathVariable UUID eventId,
            @Parameter(required=true,description="현재 기록 revision",example="\"1\"") @RequestHeader(value="If-Match",required=false) String ifMatch) { return service.deleteEvent(actor.requireUser(jwt),vehicleId,eventId,ifMatch); }

    @GetMapping("/maintenance-plans")
    @Operation(summary="정비 주기·근거·공개 범위 조회",description="공동 계획과 본인 개인 계획을 반환합니다. 비활성 계획도 과거 이력 연결을 위해 반환합니다. 제조사 근거는 문서·차종·연식·범위와 현재 검증 상태를 포함합니다.")
    @ApiResponse(responseCode="200",description="계획 목록",content=@Content(examples=@ExampleObject(value="{\"items\":[]}")))
    public Plans plans(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID vehicleId) { return service.plans(actor.requireUser(jwt),vehicleId); }

    @PutMapping("/maintenance-plans/{itemId}")
    @Operation(summary="정비 주기 생성·수정",description="새 itemId UUID로 최초 PUT하며 기존 변경·재활성화는 If-Match가 필요합니다. 거리 또는 개월 주기 중 하나가 필요합니다. MANUFACTURER는 검증 evidenceId와 차종·연식·주기가 정확히 일치해야 하고 미검증은 422입니다.")
    @ApiResponse(responseCode="200",description="계획·예정과 설정 revision ETag",content=@Content(examples=@ExampleObject(value=PLAN_OUTPUT)))
    public Plan putPlan(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID vehicleId,@PathVariable UUID itemId,
            @Parameter(description="기존 계획 revision. 최초 생략 또는 0",example="\"1\"") @RequestHeader(value="If-Match",required=false) String ifMatch,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description="intervalMonths는 달력 개월 수입니다.",required=true,content=@Content(examples=@ExampleObject(value=PLAN_INPUT)))
            @Valid @RequestBody PlanInput body,HttpServletResponse response) {
        Plan result=service.putPlan(actor.requireUser(jwt),vehicleId,itemId,ifMatch,body); etag(response,result.revision()); return result;
    }

    @DeleteMapping("/maintenance-plans/{itemId}")
    @Operation(summary="정비 주기 비활성화",description="If-Match로 동시 변경을 보호합니다. 과거 정비 이력은 보존하고 차량 요약에서 예정만 제외합니다.")
    @ApiResponse(responseCode="200",description="비활성화 결과",content=@Content(examples=@ExampleObject(value="{\"id\":\"11111111-1111-4111-8111-111111111111\",\"revision\":2,\"deleted\":true}")))
    public Deleted deletePlan(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID vehicleId,@PathVariable UUID itemId,
            @Parameter(required=true,description="현재 계획 revision",example="\"1\"") @RequestHeader(value="If-Match",required=false) String ifMatch) { return service.deletePlan(actor.requireUser(jwt),vehicleId,itemId,ifMatch); }
    private static void etag(HttpServletResponse response,long revision) { response.setHeader("ETag","\""+revision+"\""); }
}
