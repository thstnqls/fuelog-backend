package app.fuelog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class ConnectionDtos {
    private ConnectionDtos() {}

    @Schema(description = "차량 앱 요청 발급 또는 재시도. 최초 requestId는 null입니다.")
    public record RequestBody(
            @NotBlank @Size(max = 100) @Schema(example = "a5e76c6b-5d72-4430-b470-31b56efa6110") String operationId,
            @Schema(description = "최초 요청은 빈 문자열 또는 null", example = "8b31a39f-770f-4ae8-98f8-28166e030093", nullable = true) String requestId) {}

    @Schema(description = "차량 앱 상태 조회")
    public record StatusBody(
            @NotBlank @Size(max = 100) @Schema(example = "a5e76c6b-5d72-4430-b470-31b56efa6110") String operationId,
            @NotBlank @Schema(example = "8b31a39f-770f-4ae8-98f8-28166e030093") String requestId) {}

    @Schema(description = "차량 앱의 연결 상태. WAITING일 때만 nonce와 remainingSeconds가 있습니다.")
    public record Response(
            @Schema(example = "8b31a39f-770f-4ae8-98f8-28166e030093") UUID requestId,
            @Schema(allowableValues = {"WAITING", "EXPIRED", "REVOKED", "COMPLETED", "ALREADY_LINKED"}, example = "WAITING") String status,
            @Schema(example = "120", nullable = true) Integer remainingSeconds,
            @Schema(description = "QR에 넣을 일회용 proof", example = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", nullable = true) String nonce) {}

    @Schema(description = "모바일 내부 스캐너가 읽은 QR 원문")
    public record Envelope(
            @NotBlank @Schema(example = "fuelog.vehicle-registration") String type,
            @NotNull @Schema(example = "1") Integer version,
            @NotNull @Schema(example = "8b31a39f-770f-4ae8-98f8-28166e030093") UUID requestId,
            @NotBlank @Schema(example = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA") String nonce) {}

    @Schema(description = "승인할 기존 차량과 멱등 키")
    public record ApproveBody(
            @NotNull @Schema(description = "QR의 요청 ID", example = "8b31a39f-770f-4ae8-98f8-28166e030093") UUID requestId,
            @NotBlank @Schema(description = "QR의 일회용 nonce", example = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA") String nonce,
            @NotNull @Schema(description = "로그인 사용자가 관리하는 기존 차량 ID", example = "22222222-2222-4222-8222-222222222222") UUID vehicleId,
            @NotBlank @Size(max = 100) @Schema(description = "같은 승인 재시도에 동일하게 사용하는 고유 키", example = "approve-20261104-001") String idempotencyKey) {}

    @Schema(description = "QR 검증 결과")
    public record Inspection(UUID requestId, int remainingSeconds) {}

    @Schema(description = "모바일 승인 결과")
    public record Approval(UUID requestId, UUID vehicleId, String status) {}

    @Schema(description = "연결 초기화 요청. 휴대폰의 최근 OTP 인증으로 발급한 Supabase JWT를 X-User-Authorization에 보냅니다.")
    public record ResetBody(@NotNull @Schema(example = "22222222-2222-4222-8222-222222222222") UUID vehicleId) {}

    @Schema(description = "연결 초기화 결과")
    public record ResetResult(UUID installationId, String status) {}
}
