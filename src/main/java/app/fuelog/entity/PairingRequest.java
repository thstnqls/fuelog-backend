package app.fuelog.entity;

import java.time.Instant;
import java.util.UUID;

/** 연결 요청의 저장 상태. proofHash에는 QR 비밀값 원문 대신 SHA-256만 보관한다. */
public record PairingRequest(
        UUID id,
        UUID installationId,
        byte[] proofHash,
        String status,
        Instant expiresAt,
        UUID selectedVehicleId,
        String newVehicleName,
        UUID approvedUserId,
        String issueKey,
        String intentKey,
        String confirmationCode) {
    public boolean active() {
        return "pending".equals(status) || "awaiting_vehicle_confirmation".equals(status);
    }
}
