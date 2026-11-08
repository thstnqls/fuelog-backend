package app.fuelog.controller;

import app.fuelog.dto.VehicleHomeDtos;
import app.fuelog.service.VehicleHomeService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 장치의 홈·개인 세션·수동 기록 구간·샘플 수신 API를 제공한다. */
@RestController
@SecurityRequirement(name = "installationBearer")
@Tag(
        name = "C02 차량 홈",
        description =
                "설치 자격 증명 Bearer를 사용합니다. 개인 데이터는 X-Trip-User-Session Bearer가 유효할 때만 조회합니다. 모든 시각은"
                        + " UTC, 거리는 km입니다.")
public class VehicleHomeController {
    private final VehicleHomeService service;

    public VehicleHomeController(VehicleHomeService service) {
        this.service = service;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @GetMapping("/vehicle/home")
    @Operation(
            summary = "차량 홈 조회",
            description =
                    "장치의 설치 Bearer로 마지막 QR 인증 사용자의 개인 홈을 조회합니다. 추가 구간 QR은 필요하지 않습니다. 인증된 사용자가 없으면 개인 값은 null, 상태는 needs_user_proof입니다. 세션이 무효해 401/403이 반환되면"
                        + " 앱의 개인 캐시를 삭제하세요. 거리 단위는 km, 문자열 시각은 UTC입니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "공개 상태와 검증된 사용자 데이터",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = VehicleHomeDtos.Home.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"vehicleAlias\":\"내"
                                                            + " 차\",\"vehicleAliasStatus\":{\"state\":\"available\",\"observedAt\":\"2026-11-04T10:00:00Z\"},\"userSessionVersion\":0,\"recordingStatus\":\"unknown\",\"recordingDataStatus\":{\"state\":\"unsupported\",\"observedAt\":null},\"lastSampleAt\":null,\"distanceKm\":null,\"distanceStatus\":{\"state\":\"needs_user_proof\",\"observedAt\":null},\"fuelStatus\":{\"state\":\"needs_user_proof\",\"observedAt\":null},\"costStatus\":{\"state\":\"needs_user_proof\",\"observedAt\":null},\"estimatedCost\":null,\"syncStatus\":{\"state\":\"unsupported\",\"observedAt\":null},\"fuelReviewCount\":null,\"fuelReviewStatus\":{\"state\":\"needs_user_proof\",\"observedAt\":null},\"maintenanceDueCount\":null,\"maintenanceStatus\":{\"state\":\"needs_user_proof\",\"observedAt\":null},\"generatedAt\":\"2026-11-04T10:00:00Z\"}"))),
        @ApiResponse(responseCode = "401", description = "설치 또는 사용자 세션 오류"),
        @ApiResponse(responseCode = "403", description = "설치·차량·사용자 범위 불일치")
    })
    public VehicleHomeDtos.Home home(
            @Parameter(
                            name = "Authorization",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "설치 자격 증명",
                            example = "Bearer BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB")
                    @RequestHeader(value = "Authorization", required = false)
                    String bearer,
            @Parameter(
                            name = "X-Trip-User-Session",
                            in = ParameterIn.HEADER,
                            description = "이전 클라이언트용 scopedToken. 생략 시 마지막 QR 인증 사용자 사용",
                            example = "Bearer AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                    @RequestHeader(value = "X-Trip-User-Session", required = false)
                    String scopedBearer) {
        return service.home(bearer, scopedBearer);
    }

    @PostMapping("/trip-user/challenges")
    @Operation(
            summary = "운행 사용자 확인 QR 발급",
            description =
                    "장치의 설치 Bearer로 호출합니다. 개인 홈 확인은 installationId와 operationId를, 현재 운행의 사용자 후보 확인은"
                        + " intervalId를 보내세요. 구간 확인에도 operationId를 주면 재시도 시 유지해야 합니다.\n\n"
                        + "QR에는 응답의 type, version, challengeId, proof를 넣습니다. 유효기간은 2분입니다. 새 요청은 이전"
                        + " QR과 사용자 세션을 폐기하며, 같은 operationId는 기존 요청을 반환합니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "QR 요청",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = VehicleHomeDtos.Challenge.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"challengeId\":\"11111111-1111-4111-8111-111111111111\",\"status\":\"PENDING\",\"expiresAt\":\"2026-11-04T10:02:00Z\",\"proof\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"type\":\"fuelog.trip-user\",\"version\":1}"))),
        @ApiResponse(responseCode = "403", description = "installationId 불일치"),
        @ApiResponse(responseCode = "409", description = "차량 미연결")
    })
    public VehicleHomeDtos.Challenge challenge(
            @Parameter(
                            name = "Authorization",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "설치 Bearer 자격 증명")
                    @RequestHeader(value = "Authorization", required = false)
                    String bearer,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "C02는 설치 ID와 고정 operationId, C03은 현재 intervalId. C03에서도"
                                            + " operationId를 주면 재시도 시 유지해야 합니다.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema =
                                                    @Schema(
                                                            implementation =
                                                                    VehicleHomeDtos.ChallengeRequest
                                                                            .class),
                                            examples = {
                                                @ExampleObject(
                                                        name = "C02 개인 홈",
                                                        value =
                                                                "{\"installationId\":\"44444444-4444-4444-8444-444444444444\",\"operationId\":\"trip-user-20261104-001\"}"),
                                                @ExampleObject(
                                                        name = "C03 현재 구간",
                                                        value =
                                                                "{\"intervalId\":\"55555555-5555-4555-8555-555555555555\",\"operationId\":\"c03-001\"}")
                                            }))
                    @Valid
                    @RequestBody
                    VehicleHomeDtos.ChallengeRequest body) {
        return service.challenge(bearer, body);
    }

    @PostMapping("/trip-user/session")
    @Operation(
            summary = "사용자 승인 상태와 장치용 세션 토큰 조회",
            description =
                    "장치의 설치 Bearer와 challengeId로 모바일 승인 결과를 조회합니다.\n\n"
                        + "ACTIVE이면 표시명, 사용자 ID, 세션 버전, 만료 시각, scopedToken을 반환합니다. 이 토큰은 Supabase"
                        + " JWT가 아니라 장치·차량·사용자에 묶인 30분 세션입니다. 개인 API의 X-Trip-User-Session 헤더에"
                        + " Bearer로 보내세요. PENDING/EXPIRED/REVOKED에는 개인 정보와 토큰이 없습니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "PENDING 또는 ACTIVE 등",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = VehicleHomeDtos.Session.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"status\":\"ACTIVE\",\"challengeId\":\"11111111-1111-4111-8111-111111111111\",\"displayName\":\"운전자\",\"subjectId\":\"33333333-3333-4333-8333-333333333333\",\"sessionVersion\":2,\"expiresAt\":\"2026-11-04T10:30:00Z\",\"scopedToken\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}"))),
        @ApiResponse(responseCode = "403", description = "다른 설치의 challenge")
    })
    public VehicleHomeDtos.Session session(
            @Parameter(
                            name = "Authorization",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "설치 Bearer 자격 증명")
                    @RequestHeader(value = "Authorization", required = false)
                    String bearer,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "차량 화면의 확인 요청 ID",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema =
                                                    @Schema(
                                                            implementation =
                                                                    VehicleHomeDtos.SessionRequest
                                                                            .class),
                                            examples =
                                                    @ExampleObject(
                                                            value =
                                                                    "{\"challengeId\":\"11111111-1111-4111-8111-111111111111\"}")))
                    @Valid
                    @RequestBody
                    VehicleHomeDtos.SessionRequest body) {
        return service.session(bearer, body);
    }

    @DeleteMapping("/trip-user/session")
    @Operation(
            summary = "현재 운행 사용자 로그아웃",
            description =
                    "장치의 설치 Bearer와 현재 X-Trip-User-Session Bearer가 모두 필요합니다.\n\n"
                            + "사용자 세션을 폐기하고 세션 버전을 증가시킵니다. 앱은 호출할 때 개인 캐시도 즉시 삭제해야 합니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "폐기 완료",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema =
                                        @Schema(
                                                implementation =
                                                        VehicleHomeDtos.SessionDeleted.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"sessionVersion\":3,\"status\":\"REVOKED\"}"))),
        @ApiResponse(responseCode = "401", description = "세션 만료·무효")
    })
    public VehicleHomeDtos.SessionDeleted logout(
            @Parameter(
                            name = "Authorization",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "설치 Bearer 자격 증명")
                    @RequestHeader(value = "Authorization", required = false)
                    String bearer,
            @Parameter(
                            name = "X-Trip-User-Session",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "현재 운행 사용자 scopedToken")
                    @RequestHeader(value = "X-Trip-User-Session", required = false)
                    String scopedBearer) {
        return service.logout(bearer, scopedBearer);
    }

    @PostMapping("/recording-intervals")
    @Operation(
            summary = "수동 운행 기록 시작",
            description =
                    "장치의 설치 Bearer로 호출하며 source는 manual_app만 허용합니다. localIntervalId는 재시도에도 같은 값을"
                        + " 유지하세요.\n\n"
                        + "시작 시 subjectId에 installations/me의 authenticated_user_id를 보내세요. 현재 인증 사용자와 일치할 때만 구간에 고정합니다. 기존 익명 구간은 재귀속하지 않습니다. 차량 시동에 따른 자동 기록 시작은 수행하지 않습니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "시작 또는 동일 키 재시도",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = VehicleHomeDtos.Interval.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"id\":\"55555555-5555-4555-8555-555555555555\",\"vehicleId\":\"22222222-2222-4222-8222-222222222222\",\"localIntervalId\":\"local-001\",\"status\":\"recording\",\"startedAt\":\"2026-11-04T10:00:00Z\",\"endedAt\":null,\"subjectId\":null,\"lastAcceptedSequence\":0,\"lastSampleAt\":null}"))),
        @ApiResponse(responseCode = "409", description = "localIntervalId 재사용 충돌")
    })
    public VehicleHomeDtos.Interval start(
            @Parameter(
                            name = "Authorization",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "설치 Bearer 자격 증명")
                    @RequestHeader(value = "Authorization", required = false)
                    String bearer,
            @Parameter(
                            name = "X-Trip-User-Session",
                            in = ParameterIn.HEADER,
                            description = "있으면 시작 시점 사용자에 구간을 귀속")
                    @RequestHeader(value = "X-Trip-User-Session", required = false)
                    String scopedBearer,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "앱 수동 기록 구간. 최초 요청과 재시도에 같은 localIntervalId를 사용합니다.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema =
                                                    @Schema(
                                                            implementation =
                                                                    VehicleHomeDtos
                                                                            .IntervalStartRequest
                                                                            .class),
                                            examples =
                                                    @ExampleObject(
                                                            value =
                                                                    "{\"localIntervalId\":\"local-interval-001\",\"startedAt\":\"2026-11-04T10:00:00Z\",\"source\":\"manual_app\"}")))
                    @Valid
                    @RequestBody
                    VehicleHomeDtos.IntervalStartRequest body) {
        return service.start(bearer, scopedBearer, body);
    }

    @PostMapping("/recording-intervals/{id}/samples")
    @Operation(
            summary = "거리·연료 샘플 묶음 업로드",
            description =
                    "장치의 설치 Bearer와 batchId, 1~500개 samples를 보냅니다. distanceKm는 해당 샘플에서 증가한 거리이며, 거리"
                        + " 누락 시 missingReason을 보내세요.\n\n"
                        + "운행 사용자 후보 확인을 마쳤다면 X-Trip-User-Candidate에 candidateId를 보내 신규 샘플을 해당"
                        + " 사용자에게 귀속합니다. 같은 batchId와 같은 본문은 중복 저장하지 않습니다. 잘못된 연료값은 거리 수신을 취소하지 않고"
                        + " 연료 상태만 error로 저장합니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "수신 결과",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema =
                                        @Schema(
                                                implementation =
                                                        VehicleHomeDtos.SamplesAccepted.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"intervalId\":\"55555555-5555-4555-8555-555555555555\",\"batchId\":\"batch-001\",\"acceptedCount\":2,\"lastAcceptedSequence\":2,\"lastSyncedAt\":\"2026-11-04T10:00:06Z\"}"))),
        @ApiResponse(responseCode = "409", description = "batchId 충돌 또는 종료된 구간")
    })
    public VehicleHomeDtos.SamplesAccepted samples(
            @Parameter(
                            name = "Authorization",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "설치 Bearer 자격 증명")
                    @RequestHeader(value = "Authorization", required = false)
                    String bearer,
            @Parameter(
                            name = "X-Trip-User-Candidate",
                            in = ParameterIn.HEADER,
                            description = "C03 verify가 반환한 candidateId. 신규 샘플의 사용자 결합에 사용",
                            example = "66666666-6666-4666-8666-666666666666")
                    @RequestHeader(value = "X-Trip-User-Candidate", required = false)
                    UUID candidateId,
            @PathVariable UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "배치 ID와 1~500개의 거리 또는 누락 샘플",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema =
                                                    @Schema(
                                                            implementation =
                                                                    VehicleHomeDtos.SamplesRequest
                                                                            .class),
                                            examples =
                                                    @ExampleObject(
                                                            value =
                                                                    "{\"batchId\":\"batch-001\",\"samples\":[{\"sequence\":1,\"observedAt\":\"2026-11-04T10:00:03Z\",\"distanceKm\":0.125},{\"sequence\":2,\"observedAt\":\"2026-11-04T10:00:06Z\",\"missingReason\":\"odometer_reset\"}]}")))
                    @Valid
                    @RequestBody
                    VehicleHomeDtos.SamplesRequest body) {
        return service.samples(bearer, id, candidateId, body);
    }

    @PostMapping("/recording-intervals/{id}/finish")
    @Operation(
            summary = "수동 운행 기록 종료",
            description =
                    "장치의 설치 Bearer와 operationId, endedAt, lastSequence를 보냅니다. endedAt은 UTC 시각,"
                            + " lastSequence는 앱이 기록한 마지막 샘플 번호입니다.\n\n"
                            + "같은 본문 재시도는 기존 결과를 반환합니다. 종료 후 신규 샘플 업로드는 거부하며 누락 샘플 번호는 보존합니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "종료 결과",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = VehicleHomeDtos.Interval.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"id\":\"55555555-5555-4555-8555-555555555555\",\"vehicleId\":\"22222222-2222-4222-8222-222222222222\",\"localIntervalId\":\"local-001\",\"status\":\"finished\",\"startedAt\":\"2026-11-04T10:00:00Z\",\"endedAt\":\"2026-11-04T10:01:00Z\",\"subjectId\":null,\"lastAcceptedSequence\":2,\"lastSampleAt\":\"2026-11-04T10:00:06Z\"}"))),
        @ApiResponse(responseCode = "409", description = "다른 종료 요청 또는 차량 변경")
    })
    public VehicleHomeDtos.Interval finish(
            @Parameter(
                            name = "Authorization",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "설치 Bearer 자격 증명")
                    @RequestHeader(value = "Authorization", required = false)
                    String bearer,
            @PathVariable UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "종료 멱등 키, UTC 종료 시각, 로컬 마지막 sequence",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema =
                                                    @Schema(
                                                            implementation =
                                                                    VehicleHomeDtos.FinishRequest
                                                                            .class),
                                            examples =
                                                    @ExampleObject(
                                                            value =
                                                                    "{\"operationId\":\"finish-001\",\"endedAt\":\"2026-11-04T10:01:00Z\",\"lastSequence\":5}")))
                    @Valid
                    @RequestBody
                    VehicleHomeDtos.FinishRequest body) {
        return service.finish(bearer, id, body);
    }

    @GetMapping("/sync/status")
    @Operation(
            summary = "최신 운행의 서버 수신 상태 조회",
            description =
                    "장치의 설치 Bearer로 현재 연결 차량의 최신 기록 구간을 조회합니다. 서버가 수락한 마지막 샘플 번호와 수신 시각을 반환합니다.\n\n"
                            + "pendingCount는 서버가 기기의 로컬 업로드 큐를 알 수 없어 null입니다. 사용자별 확인함 큐 상태는 GET"
                            + " /installation/sync-status를 사용하세요.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "서버 동기화 상태",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = VehicleHomeDtos.SyncStatus.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"intervalId\":\"55555555-5555-4555-8555-555555555555\",\"lastAcceptedSequence\":2,\"lastSyncedAt\":\"2026-11-04T10:00:06Z\",\"pendingCount\":null,\"retryAfter\":null,\"state\":{\"state\":\"available\",\"observedAt\":\"2026-11-04T10:00:06Z\"}}")))
    })
    public VehicleHomeDtos.SyncStatus sync(
            @Parameter(
                            name = "Authorization",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "설치 Bearer 자격 증명")
                    @RequestHeader(value = "Authorization", required = false)
                    String bearer) {
        return service.sync(bearer);
    }
}
