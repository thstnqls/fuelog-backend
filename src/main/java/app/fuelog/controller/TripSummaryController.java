package app.fuelog.controller;

import app.fuelog.dto.TripSummaryDtos.*;
import app.fuelog.service.TripSummaryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 장치의 운행 요약 저장·조회·모바일 확인함 전달 API를 제공한다. */
@RestController
@SecurityRequirement(name = "installationBearer")
@RequestMapping(produces = "application/json")
@Tag(
        name = "C04 운행 요약",
        description =
                "설치 Bearer 인증. 사용자 귀속 운행의 조회·확인함 전송에는 X-Trip-User-Candidate에 같은 C03 검증 후보 ID가"
                        + " 필요합니다.")
public class TripSummaryController {
    private final TripSummaryService service;

    public TripSummaryController(TripSummaryService service) {
        this.service = service;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @PutMapping("/recording-intervals/{tripId}")
    @Operation(
            summary = "오프라인 운행 요약 저장·갱신",
            description =
                    "장치의 설치 Bearer로 호출합니다. 경로와 본문의 tripId는 같아야 하며, revision은 1부터 증가시키세요. 같은"
                        + " revision·같은 본문 재시도는 같은 syncedAt을 반환하고 낮은 revision이나 본문 충돌은 409입니다.\n\n"
                        + "userConsentId에는 같은 구간에서 확인한 candidateId를 넣습니다. null이면 익명이며 모바일 확인함 전송이"
                        + " 불가합니다. 기존 소유자와 운행 시작·종료 시각은 변경할 수 없습니다. 연료·단가를 모르면 값은 null, 유효 여부는"
                        + " false로 보내세요.")
    @ApiResponse(
            responseCode = "200",
            description = "서버 수신 확인",
            content =
                    @Content(
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"tripId\":\"77777777-7777-4777-8777-777777777777\",\"revision\":1,\"syncedAt\":\"2026-11-06T02:00:01Z\"}")))
    public Synced upload(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @PathVariable UUID tripId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "UTC ISO-8601 시각, km·L 단위. 연료값은 동일 연속 구간에서 수집해야 합니다. 서버는 계산 조건과"
                                            + " 경계를 검사하고 요약 조회 시 비용을 다시 계산합니다.",
                            required = true,
                            content =
                                    @Content(
                                            schema = @Schema(implementation = Upload.class),
                                            examples =
                                                    @ExampleObject(
                                                            value =
                                                                    "{\"tripId\":\"77777777-7777-4777-8777-777777777777\",\"revision\":1,\"source\":\"manual_app\",\"userConsentId\":null,\"startedAt\":\"2026-11-06T01:00:00Z\",\"endedAt\":\"2026-11-06T02:00:00Z\",\"endEstimated\":false,\"distanceKm\":60,\"distanceValid\":true,\"startFuelLiters\":40,\"endFuelLiters\":36,\"fuelEndpointsValid\":true,\"missing\":false,\"refueled\":false,\"ownerChanged\":false,\"unitPrice\":1700,\"currency\":\"KRW\",\"priceSource\":\"사용자가"
                                                                        + " 확인한 주유"
                                                                        + " 단가\",\"priceObservedAt\":\"2026-11-06T00:00:00Z\",\"priceValid\":true}")))
                    @Valid
                    @RequestBody
                    Upload body) {
        return service.upload(bearer, tripId, body);
    }

    @GetMapping("/recording-intervals/{tripId}/summary")
    @Operation(
            summary = "저장된 운행 요약 조회",
            description =
                    "장치의 설치 Bearer로 거리, 연료 소모량, 연비, 추정 비용을 조회합니다. 사용자 귀속 운행은 X-Trip-User-Candidate에"
                        + " 저장 당시 userConsentId와 같은 candidateId가 필요합니다.\n\n"
                        + "계산할 수 없는 항목은 value=null, status=unavailable과 reason을 반환합니다. 조회만 수행하며 운행을"
                        + " 새로 만들지 않습니다.")
    @ApiResponse(
            responseCode = "200",
            description = "거리·소모량·연비·비용·품질과 revision/syncedAt",
            content =
                    @Content(
                            schema = @Schema(implementation = Summary.class),
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"tripId\":\"77777777-7777-4777-8777-777777777777\",\"revision\":1,\"distanceKm\":{\"value\":60,\"status\":\"available\",\"reason\":null},\"fuelConsumedLiters\":{\"value\":4,\"status\":\"available\",\"reason\":null},\"fuelEfficiencyKmPerLiter\":{\"value\":15,\"status\":\"available\",\"reason\":null},\"estimatedCost\":{\"value\":6800,\"currency\":\"KRW\",\"status\":\"available\",\"reason\":null}}")))
    public Summary summary(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @PathVariable UUID tripId,
            @Parameter(description = "userConsentId와 같은 C03 검증 candidateId")
                    @RequestHeader(value = "X-Trip-User-Candidate", required = false)
                    UUID consent) {
        return service.summary(bearer, tripId, consent);
    }

    @PutMapping("/mobile-inbox/trips/{tripId}")
    @Operation(
            summary = "운행 요약을 사용자 모바일 확인함에 전달",
            description =
                    "장치가 요약 업로드 성공 후 설치 Bearer와 X-Trip-User-Candidate로 호출합니다. operationId는"
                        + " mobile-inbox:{tripId}, tripRevision은 업로드 성공 revision으로 보내세요.\n\n"
                        + "수신자는 서버가 운행 소유자로 결정합니다. 같은 revision 재시도는 같은 항목을 반환하며 높은 revision은 기존 항목을"
                        + " 갱신합니다. 익명 운행은 409입니다.")
    @ApiResponse(
            responseCode = "200",
            description = "서버 확인함 저장 완료",
            content =
                    @Content(
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"inboxItemId\":1,\"tripId\":\"77777777-7777-4777-8777-777777777777\",\"deliveredAt\":\"2026-11-06T02:01:00Z\"}")))
    public Delivered deliver(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @PathVariable UUID tripId,
            @RequestHeader(value = "X-Trip-User-Candidate", required = false) UUID consent,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "서버 업로드 성공 revision과 고정 operationId",
                            required = true,
                            content =
                                    @Content(
                                            examples =
                                                    @ExampleObject(
                                                            value =
                                                                    "{\"operationId\":\"mobile-inbox:77777777-7777-4777-8777-777777777777\",\"tripRevision\":1}")))
                    @Valid
                    @RequestBody
                    Deliver body) {
        return service.deliver(bearer, tripId, consent, body);
    }
}
