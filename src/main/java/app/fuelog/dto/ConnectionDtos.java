package app.fuelog.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** 설치 Bearer 연결 QR 발급·검사·승인의 요청과 응답 형식. */
public final class ConnectionDtos {
    private ConnectionDtos() {}

    @Schema(
            name = "ConnectionRequestBody",
            description = "차량 앱 요청 발급 또는 재시도. 최초 requestId는 null입니다.")
    public record RequestBody(
            @NotBlank
                    @Size(max = 100)
                    @Schema(
                            description = "동일 요청 재시도에 유지하는 고유 키. 다른 내용의 요청에는 새 값을 사용합니다.",
                            example = "a5e76c6b-5d72-4430-b470-31b56efa6110")
                    String operationId,
            @Schema(
                            description = "최초 요청은 빈 문자열 또는 null",
                            example = "8b31a39f-770f-4ae8-98f8-28166e030093",
                            nullable = true)
                    String requestId) {}

    @Schema(name = "ConnectionStatusBody", description = "차량 앱 상태 조회")
    public record StatusBody(
            @NotBlank
                    @Size(max = 100)
                    @Schema(
                            description = "동일 요청 재시도에 유지하는 고유 키. 다른 내용의 요청에는 새 값을 사용합니다.",
                            example = "a5e76c6b-5d72-4430-b470-31b56efa6110")
                    String operationId,
            @NotBlank
                    @Schema(
                            description = "차량 연결 QR 요청 UUID.",
                            example = "8b31a39f-770f-4ae8-98f8-28166e030093")
                    String requestId) {}

    @Schema(
            name = "ConnectionResponse",
            description = "차량 앱의 연결 상태. WAITING일 때만 nonce와 remainingSeconds가 있습니다.")
    public record Response(
            @Schema(
                            description = "차량 연결 QR 요청 UUID.",
                            example = "8b31a39f-770f-4ae8-98f8-28166e030093")
                    UUID requestId,
            @Schema(
                            allowableValues = {
                                "WAITING",
                                "EXPIRED",
                                "REVOKED",
                                "COMPLETED",
                                "ALREADY_LINKED"
                            },
                            example = "WAITING")
                    String status,
            @Schema(description = "서버 만료 시각 기준 남은 유효 시간(초).", example = "120", nullable = true)
                    Integer remainingSeconds,
            @Schema(
                            description = "QR에 넣을 일회용 proof",
                            example = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                            nullable = true)
                    String nonce) {}

    @Schema(name = "ConnectionEnvelope", description = "모바일 내부 스캐너가 읽은 QR 원문")
    public record Envelope(
            @NotBlank @Schema(example = "fuelog.vehicle-registration") String type,
            @NotNull @Schema(example = "1") Integer version,
            @NotNull
                    @Schema(
                            description = "차량 연결 QR 요청 UUID.",
                            example = "8b31a39f-770f-4ae8-98f8-28166e030093")
                    UUID requestId,
            @NotBlank
                    @Schema(
                            description = "차량 연결 QR의 일회용 비밀값.",
                            example = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                    String nonce) {}

    @Schema(name = "ConnectionApproveBody", description = "승인할 기존 차량과 멱등 키")
    public record ApproveBody(
            @NotNull
                    @Schema(
                            description = "QR의 요청 ID",
                            example = "8b31a39f-770f-4ae8-98f8-28166e030093")
                    UUID requestId,
            @NotBlank
                    @Schema(
                            description = "QR의 일회용 nonce",
                            example = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                    String nonce,
            @NotNull
                    @Schema(
                            description = "로그인 사용자가 관리하는 기존 차량 ID",
                            example = "22222222-2222-4222-8222-222222222222")
                    UUID vehicleId,
            @NotBlank
                    @Size(max = 100)
                    @Schema(
                            description = "같은 승인 재시도에 동일하게 사용하는 고유 키",
                            example = "approve-20261104-001")
                    String idempotencyKey) {}

    @Schema(name = "ConnectionInspection", description = "QR 검증 결과")
    public record Inspection(
            @Schema(description = "차량 연결 QR 요청 UUID.") UUID requestId,
            @Schema(description = "서버 만료 시각 기준 남은 유효 시간(초).") int remainingSeconds) {}

    @Schema(name = "ConnectionApproval", description = "모바일 승인 결과")
    public record Approval(
            @Schema(description = "차량 연결 QR 요청 UUID.") UUID requestId,
            @Schema(description = "차량 UUID.") UUID vehicleId,
            String status) {}

    @Schema(
            name = "ConnectionResetBody",
            description =
                    "연결 초기화 요청. 휴대폰의 최근 OTP 인증으로 발급한 Supabase JWT를 X-User-Authorization에 보냅니다.")
    public record ResetBody(
            @NotNull
                    @Schema(
                            description = "차량 UUID.",
                            example = "22222222-2222-4222-8222-222222222222")
                    UUID vehicleId) {}

    @Schema(name = "ConnectionResetResult", description = "연결 초기화 결과")
    public record ResetResult(
            @Schema(description = "장치 앱 설치 UUID.") UUID installationId, String status) {}
}
