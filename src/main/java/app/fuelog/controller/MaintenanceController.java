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
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@SecurityRequirement(name="installationBearer")
@RequestMapping(produces="application/json")
@Tag(name="C06 정비 요약",description="설치 Bearer로 연결된 차량만 조회합니다. 비용·메모·정비소·영수증은 응답에 포함하지 않습니다.")
public class MaintenanceController {
    private final MaintenanceService service;
    public MaintenanceController(MaintenanceService service) { this.service=service; }
    @ModelAttribute
    void noStore(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }

    @GetMapping("/maintenance/summary")
    @Operation(summary="공동 정비 예정 최대 2개 조회",description="차량 ID는 설치 자격에서 결정하며 연결 해제 시 403입니다. 이미 도래한 조건을 우선하고 날짜·거리의 미래 선도달 조건은 예측하지 않습니다. 누적거리가 7일을 넘으면 마지막 값·epoch milliseconds 시각은 유지하되 status=stale, remainingKm·distanceDue=null입니다.")
    @ApiResponse(responseCode="200",description="공동 관리 예정과 마지막 유효 누적거리",content=@Content(schema=@Schema(implementation=Summary.class),examples=@ExampleObject(value="{\"revision\":8,\"odometer\":{\"km\":48200.5,\"observedAt\":1793940000000,\"status\":\"available\"},\"items\":[{\"id\":\"11111111-1111-4111-8111-111111111111\",\"name\":\"엔진오일\",\"lastServiceDate\":\"2026-01-10\",\"lastServiceKm\":42000,\"nextDueDate\":\"2027-01-10\",\"nextDueKm\":52000,\"basis\":\"USER\",\"dateDue\":false,\"distanceDue\":false,\"remainingKm\":3799.5}]}")))
    public Summary summary(@Parameter(description="설치 자격 증명 Bearer",required=true) @RequestHeader(value="Authorization",required=false) String bearer) {
        return service.summary(bearer);
    }

    @PutMapping("/vehicle-odometer/samples/{sampleId}")
    @Operation(summary="차량 원본 누적거리 샘플 멱등 업로드",description="설치별 sampleId UUID를 재시도에도 유지합니다. 같은 ID·같은 내용은 기존 receivedAt, 다른 내용은 409입니다. source=PLEOS_VEHICLE_API, unit=km만 받습니다. VALID는 km 필수, ERROR/UNSUPPORTED는 km=null입니다. 미래 60초 초과·역행·음수는 거부합니다. source 선언은 하드웨어 증명이 아니므로 신뢰 기기 검증은 별도 프로비저닝 대상입니다.")
    @ApiResponse(responseCode="200",description="서버 수신 확인",content=@Content(examples=@ExampleObject(value="{\"sampleId\":\"22222222-2222-4222-8222-222222222222\",\"receivedAt\":\"2026-11-06T01:00:01Z\"}")))
    public OdometerSaved odometer(@RequestHeader(value="Authorization",required=false) String bearer,@PathVariable UUID sampleId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description="observedAt은 UTC epoch milliseconds, km는 원본 누적거리입니다. 구간 증가 거리를 보내지 않습니다.",required=true,
                    content=@Content(examples=@ExampleObject(value="{\"km\":48200.5,\"observedAt\":1793940000000,\"unit\":\"km\",\"source\":\"PLEOS_VEHICLE_API\",\"quality\":\"VALID\"}")))
            @Valid @RequestBody OdometerInput body) { return service.odometer(bearer,sampleId,body); }
}
