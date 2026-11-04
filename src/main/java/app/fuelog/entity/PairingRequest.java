package app.fuelog.entity;

import java.time.Instant;
import java.util.UUID;

/** A pairing row. The proof field contains only SHA-256, never the QR secret. */
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
        String confirmationCode
) {
    public boolean active() {
        return "pending".equals(status) || "awaiting_vehicle_confirmation".equals(status);
    }
}
