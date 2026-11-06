package app.fuelog.controller;

import app.fuelog.dto.TripSummaryDtos.*;
import app.fuelog.service.PairingActor;
import app.fuelog.service.TripSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(produces = "application/json")
@Tag(name = "M06 모바일 확인함", description = "Supabase 사용자 로그인 JWT. 현재 운행 소유권과 차량 멤버십을 검사합니다.")
@SecurityRequirement(name = "supabaseBearer")
public class MobileInboxController {
    private final TripSummaryService service;
    private final PairingActor actor;
    public MobileInboxController(TripSummaryService service, PairingActor actor) { this.service = service; this.actor = actor; }
    @ModelAttribute
    void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @GetMapping("/mobile/inbox")
    @Operation(summary = "내 운행·주유 확인함 조회", description = "cursor는 이전 응답 nextCursor(숫자 ID), 최초 0입니다. 최대 50건을 ID 오름차순으로 반환합니다. 현재 로그인한 소유자의 항목만 제공하고 멤버십이 해제되면 숨깁니다. TRIP_SUMMARY는 전달된 revision의 요약 스냅샷입니다. FUEL_CANDIDATE는 candidateId와 최신 candidateRevision을 제공하며 상세 API로 조회합니다.")
    @ApiResponse(responseCode = "200", description = "확인함 페이지", content = @Content(schema = @Schema(implementation = Inbox.class), examples = @ExampleObject(value = "{\"items\":[],\"nextCursor\":null}")))
    public Inbox inbox(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") long cursor) {
        return service.inbox(actor.requireUser(jwt), cursor);
    }

    @PatchMapping("/mobile/inbox/{itemId}")
    @Operation(summary = "내 확인함 항목 읽음 처리", description = "수신자·소유권·멤버십을 검사합니다. 첫 readAt만 저장하며 같은 항목 재시도는 기존 읽음 시각을 반환합니다. UTC 시각은 전달 시각 이후여야 합니다.")
    @ApiResponse(responseCode = "200", description = "사용자별 읽음 상태", content = @Content(examples = @ExampleObject(value = "{\"inboxItemId\":1,\"readAt\":\"2026-11-06T02:02:00Z\"}")))
    public ReadResult read(@AuthenticationPrincipal Jwt jwt, @PathVariable long itemId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "앱에서 읽은 UTC 시각", required = true,
                    content = @Content(examples = @ExampleObject(value = "{\"readAt\":\"2026-11-06T02:02:00Z\"}")))
            @Valid @RequestBody Read body) { return service.read(actor.requireUser(jwt), itemId, body); }
}
