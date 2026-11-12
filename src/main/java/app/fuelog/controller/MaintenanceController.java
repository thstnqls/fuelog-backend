package app.fuelog.controller;

import app.fuelog.dto.MaintenanceDtos.*;
import app.fuelog.service.MaintenanceService;

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

/** 장치에 공동 정비 요약을 제공하고 차량 원본 누적거리를 수신한다. */
@RestController
@SecurityRequirement(name = "installationBearer")
@RequestMapping(produces = "application/json")
@Tag(name = "C06 정비 요약", description = "설치 Bearer로 연결된 차량만 조회합니다. 비용·메모·정비소·영수증은 응답에 포함하지 않습니다.")
public class MaintenanceController {
    private final MaintenanceService service;

    public MaintenanceController(MaintenanceService service) {
        this.service = service;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @GetMapping("/maintenance/summary")
    @Operation(
            summary = "차량의 공동 정비 예정 조회",
            description =
                    "사용자 페어링 없이 장치의 설치 Bearer로 연결 차량의 공동 정비 주기·예정 전체를 조회합니다. 제조사 기준을 먼저, 각 기준 안에서는 위험·도래 항목을 우선하며 비용·메모·정비소·영수증은"
                        + " 포함하지 않습니다.\n\n"
                        + "누적거리가 7일보다 오래되면 status=stale이며 remainingKm와 distanceDue는 null입니다. 마지막"
                        + " 누적거리와 관측 시각은 유지합니다.")
    @ApiResponse(
            responseCode = "200",
            description = "공동 관리 예정과 마지막 유효 누적거리",
            content =
                    @Content(
                            schema = @Schema(implementation = Summary.class),
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"revision\":8,\"odometer\":{\"km\":48200.5,\"observedAt\":1793940000000,\"status\":\"available\"},\"items\":[{\"id\":\"11111111-1111-4111-8111-111111111111\",\"name\":\"엔진오일\",\"lastServiceDate\":\"2026-01-10\",\"lastServiceKm\":42000,\"nextDueDate\":\"2027-01-10\",\"nextDueKm\":52000,\"basis\":\"USER\",\"dateDue\":false,\"distanceDue\":false,\"remainingKm\":3799.5,\"intervalKm\":10000,\"intervalMonths\":12}]}")))
    public Summary summary(
            @Parameter(description = "설치 자격 증명 Bearer", required = true)
                    @RequestHeader(value = "Authorization", required = false)
                    String bearer) {
        return service.summary(bearer);
    }

    @GetMapping("/maintenance/plans")
    @Operation(
            summary = "차량 오너의 공용 교체주기 관리 목록",
            description = "장치의 설치 Bearer로 호출합니다. 현재 인증된 사용자가 연결 차량의 OWNER여야 하며 활성화된 공동 정비 계획을 반환합니다. "
                    + "개인 계획과 비활성 계획은 제외합니다. 사용자 미확인·연결 해제·오너 권한 없음은 403입니다.")
    public Plans plans(@RequestHeader(value="Authorization",required=false) String bearer) {
        return service.vehiclePlans(bearer);
    }

    @PutMapping("/maintenance/plans/{itemId}")
    @Operation(
            summary = "차량 오너의 공용 교체주기와 마지막 교체 기록 저장",
            description = "장치의 설치 Bearer로 인증된 차량 OWNER만 호출할 수 있습니다. plan.shared=true인 정비 계획과 operationId를 보내고 "
                    + "필요하면 lastServiceDate(YYYY-MM-DD), lastServiceKm(차량 누적 km)를 추가합니다.\n\n"
                    + "새 계획은 새 itemId UUID로 생성합니다. 기존 계획 변경은 현재 revision을 If-Match 헤더로 보내며 응답 ETag에 새 revision을 반환합니다. "
                    + "마지막 정비값이 달라지면 확정 공동 정비 이력을 추가하고 다음 예정을 재계산합니다. 이전 날짜로 되돌리는 수정은 모바일 정비 이력 API를 사용하세요. "
                    + "기존 계획의 If-Match 누락은 428, 버전 불일치는 412입니다.")
    public Plan putPlan(@RequestHeader(value="Authorization",required=false) String bearer,
            @PathVariable UUID itemId, @RequestHeader(value="If-Match",required=false) String revision,
            @Valid @RequestBody VehiclePlanInput body, HttpServletResponse response) {
        Plan result=service.putVehiclePlan(bearer,itemId,revision,body);
        response.setHeader("ETag", "\""+result.revision()+"\"");
        return result;
    }

    @PostMapping("/maintenance/plans/{itemId}/complete")
    @Operation(summary = "차량 오너의 정비 완료 확인 및 다음 회차 생성",
            description = "설치 Bearer로 인증된 현재 차량 OWNER만 공동 계획을 완료할 수 있습니다. "
                    + "If-Match에 현재 계획 revision, Idempotency-Key에 재시도 키를 보내세요. "
                    + "본문은 {confirmed:true,serviceDate:YYYY-MM-DD,odometerKm:정비 당시 누적거리}입니다. 거리 주기가 있으면 누적거리 필수입니다. "
                    + "기존 계획을 완료·논리 삭제하고 동일 이름·주기의 새 계획을 원자적으로 생성합니다. event, completedPlan, nextPlan을 반환합니다. "
                    + "같은 키·본문 재시도는 같은 완료 이력과 다음 계획 ID를 반환하며 완료 확인 이력의 수정·삭제는 409입니다.")
    public Completion complete(@RequestHeader(value = "Authorization", required = false) String bearer,
            @PathVariable UUID itemId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CompletionInput body) {
        return service.completeVehiclePlan(bearer, itemId, ifMatch, key, body);
    }

    @DeleteMapping("/maintenance/plans/{itemId}")
    @Operation(
            summary = "차량 오너의 공용 교체주기 비활성화",
            description = "장치의 설치 Bearer로 인증된 차량 OWNER만 호출할 수 있습니다. 현재 계획 revision을 If-Match 헤더로 보내세요. "
                    + "공동 계획만 비활성화할 수 있으며 개인 계획은 이 경로에서 변경할 수 없습니다. 과거 정비 이력은 보존하고 차량 요약에서 해당 예정만 제외합니다. "
                    + "If-Match 누락은 428, 버전 불일치는 412이며 id, revision, deleted를 반환합니다.")
    public Deleted deletePlan(@RequestHeader(value="Authorization",required=false) String bearer,
            @PathVariable UUID itemId, @RequestHeader(value="If-Match",required=false) String revision) {
        return service.deleteVehiclePlan(bearer,itemId,revision);
    }

    @PutMapping("/vehicle-odometer/samples/{sampleId}")
    @Operation(
            summary = "차량 원본 누적거리 업로드",
            description =
                    "장치의 설치 Bearer로 호출하며 재시도에도 같은 sampleId를 유지합니다. km는 차량 전체 누적거리, observedAt은 UTC"
                        + " 기준 밀리초입니다. unit=km, source=PLEOS_VEHICLE_API로 보내세요.\n\n"
                        + "quality=VALID이면 km가 필요하고 ERROR/UNSUPPORTED이면 km=null이어야 합니다. 같은 ID·같은"
                        + " 본문은 기존 receivedAt을 반환합니다. 내용 충돌, 누적거리 역행, 미래 60초 초과 시각은 거부합니다. source"
                        + " 문자열만으로 실제 차량 하드웨어를 증명하지는 않습니다.")
    @ApiResponse(
            responseCode = "200",
            description = "서버 수신 확인",
            content =
                    @Content(
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"sampleId\":\"22222222-2222-4222-8222-222222222222\",\"receivedAt\":\"2026-11-06T01:00:01Z\"}")))
    public OdometerSaved odometer(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @PathVariable UUID sampleId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "observedAt은 UTC epoch milliseconds, km는 원본 누적거리입니다. 구간 증가 거리를"
                                            + " 보내지 않습니다.",
                            required = true,
                            content =
                                    @Content(
                                            examples =
                                                    @ExampleObject(
                                                            value =
                                                                    "{\"km\":48200.5,\"observedAt\":1793940000000,\"unit\":\"km\",\"source\":\"PLEOS_VEHICLE_API\",\"quality\":\"VALID\"}")))
                    @Valid
                    @RequestBody
                    OdometerInput body) {
        return service.odometer(bearer, sampleId, body);
    }
}
