package app.fuelog.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** 설치 ID·비밀값을 사용하는 기존 페어링 계약. 필드 이름은 기존 snake_case를 유지한다. */
public final class PairingDtos {
    public record IssueRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 100) String vehicle_name) {}

    private PairingDtos() {}

    @Schema(
            name = "PairingQrPayload",
            description = "차량 화면에 QR로 인코딩할 앱 전용 JSON. URL이나 인증 토큰을 넣지 않습니다.")
    public record QrPayload(
            @Schema(description = "QR 페이로드 버전", example = "1") int v,
            @Schema(
                            description = "페이로드 종류. 이 값과 버전을 확인한 뒤 파싱합니다.",
                            example = "fuelog.vehicle_pairing")
                    String type,
            @Schema(
                            description = "서버가 발급한 페어링 요청 UUID",
                            example = "11111111-1111-4111-8111-111111111111")
                    UUID request_id,
            @Schema(
                            description =
                                    "32바이트 일회용 난수의 패딩 없는 base64url. 2분 안에 모바일 요청 본문으로만 전송합니다.",
                            example = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                    String proof) {}

    @Schema(name = "PairingIssued", description = "차량 앱의 새 QR 발급 결과. proof는 이 응답에서만 받을 수 있습니다.")
    public record Issued(
            @Schema(
                            description = "요청 UUID. 조회·취소·최종 확인 경로의 {id}로 사용합니다.",
                            example = "11111111-1111-4111-8111-111111111111")
                    UUID id,
            @Schema(description = "서버 기준 QR 만료 시각(UTC)", example = "2026-11-03T03:02:00Z")
                    Instant expires_at,
            @Schema(description = "QR로 표시할 JSON 페이로드") QrPayload qr) {}

    @Schema(name = "PairingStatus", description = "페어링 요청의 현재 상태. 확인 코드는 모바일 intent 접수 후에만 표시됩니다.")
    public record Status(
            @Schema(description = "페어링 요청 UUID", example = "11111111-1111-4111-8111-111111111111")
                    UUID id,
            @Schema(
                            description = "요청 상태",
                            allowableValues = {
                                "pending",
                                "awaiting_vehicle_confirmation",
                                "completed",
                                "expired",
                                "revoked"
                            },
                            example = "awaiting_vehicle_confirmation")
                    String status,
            @Schema(description = "서버 기준 요청 만료 시각(UTC)", example = "2026-11-03T03:02:00Z")
                    Instant expires_at,
            @Schema(
                            description = "선택한 기존 차량 또는 완료 후 생성된 차량 UUID. 새 차량은 완료 전까지 null입니다.",
                            example = "22222222-2222-4222-8222-222222222222",
                            nullable = true)
                    UUID vehicle_id,
            @Schema(
                            description = "intent를 제출한 모바일 사용자 UUID. 차량 화면에서 요청 식별에 사용합니다.",
                            example = "33333333-3333-4333-8333-333333333333",
                            nullable = true)
                    UUID approved_user_id,
            @Schema(description = "선택하거나 새로 입력한 차량 이름", example = "내 차", nullable = true)
                    String selected_vehicle_name,
            @Schema(
                            description = "intent 접수 때 생성한 6자리 비교 코드. 차량과 휴대폰 화면에서 대조합니다.",
                            example = "583204",
                            nullable = true)
                    String confirmation_code) {}

    @Schema(name = "PairingPreviewRequest", description = "휴대폰이 QR에서 읽은 요청 ID와 proof")
    public record PreviewRequest(
            @NotNull
                    @Schema(
                            description = "QR의 request_id UUID",
                            example = "11111111-1111-4111-8111-111111111111",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    UUID request_id,
            @NotBlank
                    @Schema(
                            description = "QR의 일회용 proof. URL이나 query에 넣지 않습니다.",
                            example = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    String proof) {}

    @Schema(name = "PairingPreview", description = "QR 확인 결과. 현재 연결된 동일 사용자이면 completed로 바로 로그인 완료 처리합니다.")
    public record Preview(
            @Schema(description = "검증된 요청 UUID", example = "11111111-1111-4111-8111-111111111111")
                    UUID request_id,
            @Schema(description = "서버 기준 요청 만료 시각(UTC)", example = "2026-11-03T03:02:00Z")
                    Instant expires_at,
            @Schema(description = "pending이면 차량 선택·확인 진행, completed이면 기존 로그인 유지 중.", allowableValues = {"pending", "completed"}) String status,
            @Schema(description = "completed 또는 단말에 이미 매핑된 차량이 있을 때 차량 UUID.", example = "22222222-2222-4222-8222-222222222222") UUID vehicle_id,
            @Schema(description = "단말에 이미 매핑된 차량 이름", example = "내 차") String vehicle_name) {
        public Preview(UUID request_id, Instant expires_at, String status, UUID vehicle_id) {
            this(request_id, expires_at, status, vehicle_id, null);
        }
    }

    @Schema(
            name = "PairingIntentRequest",
            description = "모바일의 차량 선택. vehicle_id와 new_vehicle_name 중 정확히 하나만 전달합니다.")
    public record IntentRequest(
            @NotBlank
                    @Schema(
                            description = "QR에서 읽은 proof. 이 요청에서 소비되며 다시 사용할 수 없습니다.",
                            example = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    String proof,
            @Schema(
                            description =
                                    "기존 차량 선택 시 해당 차량 UUID. 로그인 사용자의 OWNER 또는 MANAGER 권한을 검사합니다.",
                            example = "22222222-2222-4222-8222-222222222222",
                            nullable = true)
                    UUID vehicle_id,
            @Size(max = 100)
                    @Schema(
                            description = "새 차량 선택 시 이름(최대 100자). 실제 차량은 차량 화면 최종 확인 때 한 건만 생성됩니다.",
                            example = "내 차",
                            nullable = true)
                    String new_vehicle_name) {}

    @Schema(name = "PairingConfirmRequest", description = "차량 화면에서 수동 확인 후 제출하는 비교 코드")
    public record ConfirmRequest(
            @NotBlank
                    @Schema(
                            description = "모바일 intent 결과와 차량 화면에 같은 값으로 표시된 6자리 코드",
                            example = "583204",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    String confirmation_code) {}

    @Schema(
            name = "PairingInstallationIssued",
            description = "최초 설치 등록 결과. installation_secret은 재조회할 수 없으므로 기기에 안전하게 저장합니다.")
    public record InstallationIssued(
            @Schema(
                            description = "서버가 발급한 설치 UUID. 이후 X-Installation-Id 헤더 값입니다.",
                            example = "44444444-4444-4444-8444-444444444444")
                    UUID installation_id,
            @Schema(
                            description =
                                    "설치별 32바이트 난수의 base64url 문자열. 장치 Bearer 또는 기존"
                                        + " X-Installation-Secret 헤더에 사용합니다. QR에 넣지 않습니다.",
                            example = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB")
                    String installation_secret) {}

    @Schema(
            name = "PairingInstallation",
            description = "설치의 연결 여부. paired가 true이면 차량 앱은 C07로 이동합니다.")
    public record Installation(
            @Schema(description = "현재 설치 UUID", example = "44444444-4444-4444-8444-444444444444")
                    UUID installation_id,
            @Schema(
                            description = "연결된 차량 UUID. 아직 연결 전이면 null입니다.",
                            example = "22222222-2222-4222-8222-222222222222",
                            nullable = true)
                    UUID vehicle_id,
            @Schema(description = "차량 연결 완료 여부", example = "true") boolean paired,
            @Schema(description = "마지막 QR 인증 사용자. 미인증·해제·멤버십 철회 시 null") UUID authenticated_user_id,
            String authenticated_user_name, String vehicle_name) {}
}
