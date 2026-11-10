package app.fuelog.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** 모바일 사용자의 소유·참여 차량 목록 DTO */
public final class MobileVehicleDtos {
    private MobileVehicleDtos() {}

    @Schema(name = "MobileVehicleItem", description = "모바일 사용자가 소유 또는 참여 중인 차량 정보")
    public record VehicleItem(
            @Schema(description = "차량 UUID", example = "28017757-d498-4e4c-a40b-d0a7e81bbda4")
            UUID id,
            @Schema(description = "차량 UUID (id와 동일)", example = "28017757-d498-4e4c-a40b-d0a7e81bbda4")
            UUID vehicleId,
            @Schema(description = "차량 이름", example = "12가 3456 (휘발유)")
            String name,
            @Schema(description = "차량 이름 (name과 동일)", example = "12가 3456 (휘발유)")
            String vehicleName,
            @Schema(description = "사용자의 차량 권한", example = "OWNER", allowableValues = {"OWNER", "MANAGER", "MEMBER"})
            String role,
            @Schema(description = "차량 등록일시(UTC)", example = "2026-11-07T09:45:08.630475Z")
            Instant createdAt,
            @Schema(description = "차량 단말기 연결 여부", example = "true")
            boolean paired,
            @Schema(description = "연결된 단말기 UUID (미연결 시 null)", example = "44507ead-8e80-448b-b9fa-f92001a35ebe", nullable = true)
            UUID installationId
    ) {
        public static VehicleItem of(UUID id, String name, String role, Instant createdAt, UUID installationId) {
            return new VehicleItem(id, id, name, name, role, createdAt, installationId != null, installationId);
        }
    }
}
