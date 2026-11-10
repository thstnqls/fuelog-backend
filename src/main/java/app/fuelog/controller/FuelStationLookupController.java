package app.fuelog.controller;

import app.fuelog.service.*;
import app.fuelog.repository.PairingRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@Tag(name = "주변 주유소 조회", description = "장치의 현재 위치로 오피넷 주유소·주소·게시가격을 조회합니다. 실제 주유 기록은 변경하지 않습니다.")
@SecurityRequirement(name = "installationBearer")
public class FuelStationLookupController {
    private final InstallationAuth auth;private final InstallationAccess access;private final PairingRepository pairing;private final OpinetLookupService opinet;
    public FuelStationLookupController(InstallationAuth auth,InstallationAccess access,PairingRepository pairing,OpinetLookupService opinet) {
        this.auth=auth;this.access=access;this.pairing=pairing;this.opinet=opinet;
    }
    @PostMapping("/fuel-candidates/nearby-stations")
    @Operation(
            summary = "현재 차량 위치 주변 주유소·게시가격 조회",
            description = "장치의 설치 Bearer와 현재 인증 사용자가 필요합니다. latitude, longitude, measuredAt, receivedAt, "
                    + "source=PLEOS_CURRENT_UPDATE, fuelType을 보냅니다. 측정 시각은 최근 30초 이내, 수신은 측정 후 10초 이내여야 합니다. "
                    + "유종 코드는 B034=고급휘발유, B027=보통휘발유, D047=경유, C004=등유, K015=LPG입니다.\n\n"
                    + "1km 안을 검색하고 결과가 없으면 3km로 확대해 거리순 최대 3곳의 이름·주소·게시가격을 반환합니다. "
                    + "오래되거나 해석할 수 없는 가격은 숨기되 주유소 정보는 표시합니다. COMPLETED는 조회 완료, NO_STATIONS는 결과 없음입니다. "
                    + "INVALID_LOCATION_EVIDENCE, OPINET_NOT_CONFIGURED, COORDINATE_SYSTEM_UNVERIFIED, OPINET_UNAVAILABLE이면 해당 사유와 빈 목록을 반환합니다. "
                    + "주유 후보·기록은 생성하지 않으며 원시 좌표를 저장하거나 응답에 포함하지 않습니다.")
    public OpinetLookupService.Result lookup(@RequestHeader(value="Authorization",required=false) String bearer,
            @Valid @RequestBody OpinetLookupService.Request request,jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        var installation=auth.require(bearer).id();access.requireCollection(installation);
        var user=access.currentUser(installation);var vehicle=pairing.linkedVehicle(installation);
        if(user==null || vehicle==null) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"USER_VERIFICATION_REQUIRED");
        var result=opinet.lookup(request);
        access.requireCollection(installation);
        if(!user.equals(access.currentUser(installation)) || !vehicle.equals(pairing.linkedVehicle(installation)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"USER_VERIFICATION_REQUIRED");
        return result;
    }
}
