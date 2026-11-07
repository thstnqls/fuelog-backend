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
                    "장치의 설치 Bearer로 연결 차량의 공동 정비 예정 최대 2개를 조회합니다. 이미 도래한 항목을 우선하며 비용·메모·정비소·영수증은"
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
                                                    "{\"revision\":8,\"odometer\":{\"km\":48200.5,\"observedAt\":1793940000000,\"status\":\"available\"},\"items\":[{\"id\":\"11111111-1111-4111-8111-111111111111\",\"name\":\"엔진오일\",\"lastServiceDate\":\"2026-01-10\",\"lastServiceKm\":42000,\"nextDueDate\":\"2027-01-10\",\"nextDueKm\":52000,\"basis\":\"USER\",\"dateDue\":false,\"distanceDue\":false,\"remainingKm\":3799.5}]}")))
    public Summary summary(
            @Parameter(description = "설치 자격 증명 Bearer", required = true)
                    @RequestHeader(value = "Authorization", required = false)
                    String bearer) {
        return service.summary(bearer);
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
