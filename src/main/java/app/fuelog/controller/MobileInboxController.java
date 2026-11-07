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

/** 현재 로그인한 수신자의 운행·주유 확인함과 읽음 처리를 제공한다. */
@RestController
@RequestMapping(produces = "application/json")
@Tag(name = "M06 모바일 확인함", description = "Supabase 사용자 로그인 JWT. 현재 운행 소유권과 차량 멤버십을 검사합니다.")
@SecurityRequirement(name = "supabaseBearer")
public class MobileInboxController {
    private final TripSummaryService service;
    private final PairingActor actor;

    public MobileInboxController(TripSummaryService service, PairingActor actor) {
        this.service = service;
        this.actor = actor;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @GetMapping("/mobile/inbox")
    @Operation(
            summary = "내 운행·주유 확인함 조회",
            description =
                    "모바일의 Supabase JWT로 호출합니다. cursor는 이전 응답의 nextCursor이며 최초에는 0입니다. 최대 50건을 ID"
                        + " 오름차순으로 반환합니다.\n\n"
                        + "TRIP_SUMMARY는 전달 당시 운행 요약이고 FUEL_CANDIDATE는 주유 후보 ID와 최신 revision입니다. 주유"
                        + " 상세는 별도 상세 API로 조회하세요. 현재 소유권과 차량 멤버십이 유효한 항목만 표시합니다.")
    @ApiResponse(
            responseCode = "200",
            description = "확인함 페이지",
            content =
                    @Content(
                            schema = @Schema(implementation = Inbox.class),
                            examples =
                                    @ExampleObject(value = "{\"items\":[],\"nextCursor\":null}")))
    public Inbox inbox(
            @AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") long cursor) {
        return service.inbox(actor.requireUser(jwt), cursor);
    }

    @PatchMapping("/mobile/inbox/{itemId}")
    @Operation(
            summary = "내 확인함 항목 읽음 처리",
            description =
                    "모바일의 Supabase JWT와 UTC 시각 readAt을 보냅니다. readAt은 항목 전달 시각 이후여야 합니다.\n\n"
                            + "첫 읽음 시각만 저장하며 재시도는 기존 시각을 반환합니다. 수신자·소유권·차량 멤버십을 검사합니다.")
    @ApiResponse(
            responseCode = "200",
            description = "사용자별 읽음 상태",
            content =
                    @Content(
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"inboxItemId\":1,\"readAt\":\"2026-11-06T02:02:00Z\"}")))
    public ReadResult read(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long itemId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "앱에서 읽은 UTC 시각",
                            required = true,
                            content =
                                    @Content(
                                            examples =
                                                    @ExampleObject(
                                                            value =
                                                                    "{\"readAt\":\"2026-11-06T02:02:00Z\"}")))
                    @Valid
                    @RequestBody
                    Read body) {
        return service.read(actor.requireUser(jwt), itemId, body);
    }
}
