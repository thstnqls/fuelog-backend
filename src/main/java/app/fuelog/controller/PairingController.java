package app.fuelog.controller;

import app.fuelog.dto.PairingDtos;
import app.fuelog.service.PairingActor;
import app.fuelog.service.PairingService;

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

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** 설치 등록과 차량 화면 최종 확인이 필요한 기존 페어링 API를 제공한다. */
@RestController
@Tag(
        name = "차량 페어링",
        description =
                "차량 앱 설치 등록, 일회용 QR 발급, 모바일 차량 선택, 차량 화면 최종 확인. 차량 앱은 설치 헤더를, 모바일은 Supabase Bearer"
                        + " JWT를 사용합니다.")
public class PairingController {
    private final PairingService service;
    private final PairingActor actor;

    public PairingController(PairingService service, PairingActor actor) {
        this.service = service;
        this.actor = actor;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @PostMapping("/installations")
    @Operation(
            summary = "차량 앱 최초 설치 등록",
            description =
                    "호출 주체: 차량 Pleos 앱. 요청 본문은 없습니다. 서버가 설치 UUID와 32바이트 설치 비밀값을 한 번 발급합니다. 비밀값은 기기에"
                            + " 안전하게 보관하고 이후 차량 API 헤더에 사용하며 QR에는 넣지 않습니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "설치 ID와 비밀값 발급",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema =
                                        @Schema(
                                                implementation =
                                                        PairingDtos.InstallationIssued.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"installation_id\":\"44444444-4444-4444-8444-444444444444\",\"installation_secret\":\"BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB\"}")))
    })
    public ResponseEntity<PairingDtos.InstallationIssued> createInstallation() {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createInstallation());
    }

    @PostMapping("/pairing-requests")
    @Operation(
            summary = "차량 앱 QR 요청 발급·재발급",
            description =
                    "호출 주체: 설치된 차량 앱. 새 요청의 QR JSON과 서버 기준 2분 만료 시각을 반환합니다. 기존 활성 요청은 같은 트랜잭션에서 즉시"
                            + " 무효화됩니다. 동일 Idempotency-Key 재시도는 proof를 재노출할 수 없어 409를 반환합니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "QR 페이로드 발급",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = PairingDtos.Issued.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"id\":\"11111111-1111-4111-8111-111111111111\",\"expires_at\":\"2026-11-03T03:02:00Z\",\"qr\":{\"v\":1,\"type\":\"fuelog.vehicle_pairing\",\"request_id\":\"11111111-1111-4111-8111-111111111111\",\"proof\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}}"))),
        @ApiResponse(responseCode = "400", description = "Idempotency-Key 형식 오류"),
        @ApiResponse(responseCode = "401", description = "설치 ID 또는 비밀값이 없거나 틀림"),
        @ApiResponse(responseCode = "409", description = "이미 연결된 설치 또는 사용한 Idempotency-Key")
    })
    public ResponseEntity<PairingDtos.Issued> issue(
            @Parameter(
                            name = "X-Installation-Id",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "POST /installations에서 발급한 설치 UUID",
                            example = "44444444-4444-4444-8444-444444444444")
                    @RequestHeader(value = "X-Installation-Id", required = false)
                    UUID installationId,
            @Parameter(
                            name = "X-Installation-Secret",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "최초 등록 때 받은 설치 비밀값. QR·URL에 넣지 않습니다.",
                            example = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB")
                    @RequestHeader(value = "X-Installation-Secret", required = false)
                    String secret,
            @Parameter(
                            name = "Idempotency-Key",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "요청마다 새로 만든 최대 100자 키. 중복 전송으로 새 QR이 여러 개 생기는 것을 막습니다.",
                            example = "issue-20261103-001")
                    @RequestHeader("Idempotency-Key")
                    String key,
            @Valid @RequestBody(required = false) PairingDtos.IssueRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.issue(actor.requireInstallation(installationId, secret), key,
                        body == null ? null : body.vehicle_name()));
    }

    @GetMapping("/pairing-requests/current")
    @Operation(
            summary = "차량 앱 현재 페어링 요청 조회",
            description =
                    "호출 주체: 설치된 차량 앱. 화면 회전·재시작 뒤 최신 요청 상태와 만료 시각을 복원합니다. QR proof는 다시 반환하지 않습니다."
                            + " 만료된 요청은 expired로 갱신됩니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "현재 요청 상태",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = PairingDtos.Status.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"id\":\"11111111-1111-4111-8111-111111111111\",\"status\":\"pending\",\"expires_at\":\"2026-11-03T03:02:00Z\",\"vehicle_id\":null,\"approved_user_id\":null,\"selected_vehicle_name\":null,\"confirmation_code\":null}"))),
        @ApiResponse(responseCode = "401", description = "설치 인증 실패"),
        @ApiResponse(responseCode = "404", description = "발급된 페어링 요청 없음")
    })
    public PairingDtos.Status current(
            @Parameter(
                            name = "X-Installation-Id",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "설치 UUID",
                            example = "44444444-4444-4444-8444-444444444444")
                    @RequestHeader(value = "X-Installation-Id", required = false)
                    UUID installationId,
            @Parameter(
                            name = "X-Installation-Secret",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "설치 비밀값",
                            example = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB")
                    @RequestHeader(value = "X-Installation-Secret", required = false)
                    String secret) {
        return service.current(actor.requireInstallation(installationId, secret));
    }

    @PostMapping("/pairing-requests/preview")
    @SecurityRequirement(name = "supabaseBearer")
    @Operation(
            summary = "모바일 QR 미리보기·검증",
            description =
                    "호출 주체: Supabase에 로그인한 휴대폰 사용자(익명 계정 제외). QR의 request_id와 proof를 HTTPS 요청 본문으로"
                            + " 보내 유효성을 확인합니다. 미승인 요청은 status=pending을 반환하며, 해당 설치에 이미 등록된 차량이 있다면 vehicle_id와 vehicle_name을 함께 반환합니다.\n\n"
                            + "현재 연결된 동일 계정·설치·차량의 완료 요청이면 status=completed와 vehicle_id를 반환합니다. 모바일은 추가 승인 화면 없이 로그인 완료로 이동하세요. 완료된 QR의 만료 시각과 무관하게 기존 로그인 상태만 확인하며, 해제·사용자 변경·멤버십 철회된 권한은 복구하지 않습니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "유효한 QR",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = PairingDtos.Preview.class),
                                examples = {
                                    @ExampleObject(
                                            name = "신규 설치",
                                            value =
                                                    "{\"request_id\":\"11111111-1111-4111-8111-111111111111\",\"expires_at\":\"2026-11-03T03:02:00Z\",\"status\":\"pending\",\"vehicle_id\":null,\"vehicle_name\":null}"),
                                    @ExampleObject(
                                            name = "기존 차량 매핑 단말",
                                            value =
                                                    "{\"request_id\":\"11111111-1111-4111-8111-111111111111\",\"expires_at\":\"2026-11-03T03:02:00Z\",\"status\":\"pending\",\"vehicle_id\":\"22222222-2222-4222-8222-222222222222\",\"vehicle_name\":\"내 차\"}")
                                })),
        @ApiResponse(responseCode = "400", description = "요청 본문 형식 오류"),
        @ApiResponse(responseCode = "401", description = "Supabase 로그인 토큰 없음·무효"),
        @ApiResponse(responseCode = "403", description = "proof 불일치 또는 익명 사용자"),
        @ApiResponse(responseCode = "404", description = "요청 ID 없음"),
        @ApiResponse(responseCode = "409", description = "차량 확인 대기 또는 현재 사용자와 일치하지 않는 완료 요청"),
        @ApiResponse(responseCode = "410", description = "만료·취소된 요청")
    })
    public PairingDtos.Preview preview(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "QR에서 파싱한 request_id와 proof. URL 경로·query에 proof를 넣지 않습니다.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema =
                                                    @Schema(
                                                            implementation =
                                                                    PairingDtos.PreviewRequest
                                                                            .class),
                                            examples =
                                                    @ExampleObject(
                                                            value =
                                                                    "{\"request_id\":\"11111111-1111-4111-8111-111111111111\",\"proof\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}")))
                    @Valid
                    @RequestBody
                    PairingDtos.PreviewRequest body) {
        return service.preview(actor.requireUser(jwt), body);
    }

    @PostMapping("/pairing-requests/{id}/intent")
    @SecurityRequirement(name = "supabaseBearer")
    @Operation(
            summary = "모바일 차량 선택·연결 의사 제출",
            description =
                    "호출 주체: Supabase에 로그인한 휴대폰 사용자. 기존 vehicle_id 또는 새 차량 이름 중 하나를 제출합니다. proof는"
                        + " 여기서 1회 소비되고 요청은 awaiting_vehicle_confirmation이 됩니다. 확인 코드는 이 intent에 묶여"
                        + " 새로 생성됩니다. 기존 차량 선택은 사용자 관리 권한을 검사합니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "차량 화면 최종 확인 대기",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = PairingDtos.Status.class),
                                examples =
                                        @ExampleObject(
                                                name = "새 차량 선택",
                                                value =
                                                        "{\"id\":\"11111111-1111-4111-8111-111111111111\",\"status\":\"awaiting_vehicle_confirmation\",\"expires_at\":\"2026-11-03T03:02:00Z\",\"vehicle_id\":null,\"approved_user_id\":\"33333333-3333-4333-8333-333333333333\",\"selected_vehicle_name\":\"내"
                                                            + " 차\",\"confirmation_code\":\"583204\"}"))),
        @ApiResponse(responseCode = "400", description = "차량 선택·Idempotency-Key 형식 오류"),
        @ApiResponse(responseCode = "401", description = "Supabase 로그인 토큰 없음·무효"),
        @ApiResponse(responseCode = "403", description = "proof 불일치 또는 기존 차량 관리 권한 없음"),
        @ApiResponse(responseCode = "404", description = "요청 ID 없음"),
        @ApiResponse(responseCode = "409", description = "이미 다른 intent가 접수되거나 완료된 요청"),
        @ApiResponse(responseCode = "410", description = "만료·취소된 요청")
    })
    public PairingDtos.Status intent(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @Parameter(
                            name = "id",
                            in = ParameterIn.PATH,
                            required = true,
                            description = "QR의 request_id UUID",
                            example = "11111111-1111-4111-8111-111111111111")
                    @PathVariable
                    UUID id,
            @Parameter(
                            name = "Idempotency-Key",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "같은 intent 재시도에 동일하게 사용하는 최대 100자 키",
                            example = "intent-20261103-001")
                    @RequestHeader("Idempotency-Key")
                    String key,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "proof와 차량 선택. vehicle_id 또는 new_vehicle_name 중 하나만 보내세요.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema =
                                                    @Schema(
                                                            implementation =
                                                                    PairingDtos.IntentRequest
                                                                            .class),
                                            examples = {
                                                @ExampleObject(
                                                        name = "새 차량",
                                                        value =
                                                                "{\"proof\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"new_vehicle_name\":\"내"
                                                                    + " 차\"}"),
                                                @ExampleObject(
                                                        name = "기존 차량",
                                                        value =
                                                                "{\"proof\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"vehicle_id\":\"22222222-2222-4222-8222-222222222222\"}")
                                            }))
                    @Valid
                    @RequestBody
                    PairingDtos.IntentRequest body) {
        return service.intent(actor.requireUser(jwt), id, key, body);
    }

    @PostMapping("/pairing-requests/{id}/confirm")
    @SecurityRequirement(name = "supabaseBearer")
    @Operation(
            summary = "모바일 또는 차량 화면에서 연결 최종 확인",
            description =
                    "호출 주체: 모바일 앱(Supabase Bearer JWT) 또는 설치된 차량 앱(X-Installation 헤더). 모바일에서 6자리 확인 코드를 입력하거나 차량 화면에서 확인 코드를 직접 확인한 뒤 호출합니다. 확인 코드가 일치하면 즉시 completed로 확정합니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "차량·설치 연결 완료",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = PairingDtos.Status.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"id\":\"11111111-1111-4111-8111-111111111111\",\"status\":\"completed\",\"expires_at\":\"2026-11-03T03:02:00Z\",\"vehicle_id\":\"22222222-2222-4222-8222-222222222222\",\"approved_user_id\":\"33333333-3333-4333-8333-333333333333\",\"selected_vehicle_name\":\"내"
                                                            + " 차\",\"confirmation_code\":\"583204\"}"))),
        @ApiResponse(responseCode = "400", description = "확인 코드 본문 형식 오류"),
        @ApiResponse(responseCode = "401", description = "인증 실패 (설치 헤더 또는 Supabase Bearer 토큰 필요)"),
        @ApiResponse(responseCode = "403", description = "확인 코드 불일치, 다른 설치의 요청, 사용자 차량 권한 없음"),
        @ApiResponse(responseCode = "404", description = "요청 ID 없음"),
        @ApiResponse(responseCode = "409", description = "대기 중이 아니거나 이미 연결된 요청·차량"),
        @ApiResponse(responseCode = "410", description = "만료된 요청")
    })
    public PairingDtos.Status confirm(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @Parameter(
                            name = "id",
                            in = ParameterIn.PATH,
                            required = true,
                            description = "최종 확인할 페어링 요청 UUID",
                            example = "11111111-1111-4111-8111-111111111111")
                    @PathVariable
                    UUID id,
            @Parameter(
                            name = "X-Installation-Id",
                            in = ParameterIn.HEADER,
                            description = "차량 앱 호출 시 필요. 모바일은 Bearer JWT를 사용합니다.",
                            example = "44444444-4444-4444-8444-444444444444")
                    @RequestHeader(value = "X-Installation-Id", required = false)
                    UUID installationId,
            @Parameter(
                            name = "X-Installation-Secret",
                            in = ParameterIn.HEADER,
                            description = "차량 앱 호출 시 필요",
                            example = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB")
                    @RequestHeader(value = "X-Installation-Secret", required = false)
                    String secret,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "모바일 또는 차량 화면에서 확인한 6자리 비교 코드",
                            required = true,
                            content =
                                     @Content(
                                            mediaType = "application/json",
                                            schema =
                                                    @Schema(
                                                            implementation =
                                                                    PairingDtos.ConfirmRequest
                                                                            .class),
                                            examples =
                                                    @ExampleObject(
                                                            value =
                                                                    "{\"confirmation_code\":\"583204\"}")))
                    @Valid
                    @RequestBody
                    PairingDtos.ConfirmRequest body) {
        if (installationId != null || secret != null) {
            return service.confirm(
                    actor.requireInstallation(installationId, secret), id, body.confirmation_code());
        }
        return service.confirmByUser(actor.requireUser(jwt), id, body.confirmation_code());
    }

    @GetMapping("/pairing-requests/{id}")
    @Operation(
            summary = "페어링 요청 상태 조회",
            description =
                    "호출 주체: 요청을 발급한 차량 앱 또는 intent를 제출한 모바일 사용자. 차량 앱은 설치 헤더 두 개를, 모바일은 Supabase"
                        + " Bearer JWT를 사용합니다. pending / awaiting_vehicle_confirmation / completed"
                        + " / expired / revoked를 반환합니다. 만료 시 서버 상태도 갱신합니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "요청 상태",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = PairingDtos.Status.class),
                                examples = {
                                    @ExampleObject(
                                            name = "확인 대기",
                                            value =
                                                    "{\"id\":\"11111111-1111-4111-8111-111111111111\",\"status\":\"awaiting_vehicle_confirmation\",\"expires_at\":\"2026-11-03T03:02:00Z\",\"vehicle_id\":null,\"approved_user_id\":\"33333333-3333-4333-8333-333333333333\",\"selected_vehicle_name\":\"내"
                                                        + " 차\",\"confirmation_code\":\"583204\"}"),
                                    @ExampleObject(
                                            name = "만료",
                                            value =
                                                    "{\"id\":\"11111111-1111-4111-8111-111111111111\",\"status\":\"expired\",\"expires_at\":\"2026-11-03T03:02:00Z\",\"vehicle_id\":null,\"approved_user_id\":null,\"selected_vehicle_name\":null,\"confirmation_code\":null}")
                                })),
        @ApiResponse(responseCode = "401", description = "설치 비밀값 또는 Supabase 토큰 없음·무효"),
        @ApiResponse(responseCode = "403", description = "해당 설치 또는 승인 사용자의 요청이 아님"),
        @ApiResponse(responseCode = "404", description = "요청 ID 없음")
    })
    public PairingDtos.Status status(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @Parameter(
                            name = "id",
                            in = ParameterIn.PATH,
                            required = true,
                            description = "조회할 페어링 요청 UUID",
                            example = "11111111-1111-4111-8111-111111111111")
                    @PathVariable
                    UUID id,
            @Parameter(
                            name = "X-Installation-Id",
                            in = ParameterIn.HEADER,
                            description = "차량 앱 호출 시 필요. 모바일은 Bearer JWT를 사용합니다.",
                            example = "44444444-4444-4444-8444-444444444444")
                    @RequestHeader(value = "X-Installation-Id", required = false)
                    UUID installationId,
            @Parameter(
                            name = "X-Installation-Secret",
                            in = ParameterIn.HEADER,
                            description = "차량 앱 호출 시 필요",
                            example = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB")
                    @RequestHeader(value = "X-Installation-Secret", required = false)
                    String secret) {
        if (installationId != null || secret != null)
            return service.status(
                    actor.requireInstallation(installationId, secret), "installation", id);
        return service.status(actor.requireUser(jwt), "user", id);
    }

    @PostMapping("/pairing-requests/{id}/cancel")
    @Operation(
            summary = "차량 앱 페어링 요청 취소",
            description =
                    "호출 주체: 요청을 발급한 차량 앱. 화면 종료나 연결 취소 시 활성 요청을 revoked로 바꾸고 proof를 폐기합니다. 완료된 차량"
                            + " 연결이나 과거 운행·주유 기록은 삭제하지 않습니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "취소 후 상태. 이미 완료·만료된 요청은 현재 상태를 반환합니다.",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = PairingDtos.Status.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"id\":\"11111111-1111-4111-8111-111111111111\",\"status\":\"revoked\",\"expires_at\":\"2026-11-03T03:02:00Z\",\"vehicle_id\":null,\"approved_user_id\":null,\"selected_vehicle_name\":null,\"confirmation_code\":null}"))),
        @ApiResponse(responseCode = "401", description = "설치 인증 실패"),
        @ApiResponse(responseCode = "403", description = "다른 설치의 요청"),
        @ApiResponse(responseCode = "404", description = "요청 ID 없음")
    })
    public PairingDtos.Status cancel(
            @Parameter(
                            name = "id",
                            in = ParameterIn.PATH,
                            required = true,
                            description = "취소할 페어링 요청 UUID",
                            example = "11111111-1111-4111-8111-111111111111")
                    @PathVariable
                    UUID id,
            @Parameter(
                            name = "X-Installation-Id",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "설치 UUID",
                            example = "44444444-4444-4444-8444-444444444444")
                    @RequestHeader(value = "X-Installation-Id", required = false)
                    UUID installationId,
            @Parameter(
                            name = "X-Installation-Secret",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "설치 비밀값",
                            example = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB")
                    @RequestHeader(value = "X-Installation-Secret", required = false)
                    String secret) {
        return service.cancel(actor.requireInstallation(installationId, secret), id);
    }

    @GetMapping("/installations/me")
    @Operation(
            summary = "차량 앱 설치 연결 상태 조회",
            description =
                    "호출 주체: 설치된 차량 앱. 저장된 설치 ID·비밀값으로 현재 차량과 마지막 QR 인증 사용자를 확인합니다. vehicle_id는 사용자 연결 해제 후에도 유지되며, paired와 authenticated_user_id가 사용자 연결 여부를 나타냅니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "설치 연결 상태",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = PairingDtos.Installation.class),
                                examples = {
                                    @ExampleObject(
                                            name = "연결 전",
                                            value =
                                                    "{\"installation_id\":\"44444444-4444-4444-8444-444444444444\",\"vehicle_id\":null,\"paired\":false}"),
                                    @ExampleObject(
                                            name = "연결 완료",
                                            value =
                                                    "{\"installation_id\":\"44444444-4444-4444-8444-444444444444\",\"vehicle_id\":\"22222222-2222-4222-8222-222222222222\",\"paired\":true}")
                                })),
        @ApiResponse(responseCode = "401", description = "설치 인증 실패")
    })
    public PairingDtos.Installation installation(
            @Parameter(
                            name = "X-Installation-Id",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "설치 UUID",
                            example = "44444444-4444-4444-8444-444444444444")
                    @RequestHeader(value = "X-Installation-Id", required = false)
                    UUID installationId,
            @Parameter(
                            name = "X-Installation-Secret",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "설치 비밀값",
                            example = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB")
                    @RequestHeader(value = "X-Installation-Secret", required = false)
                    String secret) {
        return service.installation(actor.requireInstallation(installationId, secret));
    }
}
