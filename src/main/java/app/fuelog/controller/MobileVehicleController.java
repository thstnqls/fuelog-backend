package app.fuelog.controller;

import app.fuelog.dto.MobileVehicleDtos.VehicleItem;
import app.fuelog.service.MobileVehicleService;
import app.fuelog.service.PairingActor;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(value = "/mobile/vehicles", produces = "application/json")
@Tag(
        name = "M03 모바일 차량 목록",
        description = "현재 로그인한 모바일 사용자가 소유(OWNER)·관리(MANAGER)·참여(MEMBER) 중인 전체 차량 목록을 조회합니다.")
@SecurityRequirement(name = "supabaseBearer")
public class MobileVehicleController {
    private final MobileVehicleService service;
    private final PairingActor actor;

    public MobileVehicleController(MobileVehicleService service, PairingActor actor) {
        this.service = service;
        this.actor = actor;
    }

    @ModelAttribute
    void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @GetMapping
    @Operation(
            summary = "내 차량 전체 목록 조회",
            description =
                    "모바일 앱의 Supabase JWT를 사용하여 현재 사용자가 속한 모든 차량 목록을 생성일 최신순으로 반환합니다. "
                            + "모바일 앱 메인 홈의 차량 전환 팝업(showVehicleSwitcherDialog)에서 전체 차량을 불러올 때 사용합니다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "차량 목록 반환 성공",
                content =
                        @Content(
                                mediaType = "application/json",
                                array = @ArraySchema(schema = @Schema(implementation = VehicleItem.class)),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "[{\"id\":\"28017757-d498-4e4c-a40b-d0a7e81bbda4\",\"vehicleId\":\"28017757-d498-4e4c-a40b-d0a7e81bbda4\",\"name\":\"12가 3456 (휘발유)\",\"vehicleName\":\"12가 3456 (휘발유)\",\"role\":\"OWNER\",\"createdAt\":\"2026-11-07T09:45:08.630475Z\",\"paired\":true,\"installationId\":\"44507ead-8e80-448b-b9fa-f92001a35ebe\"}]"))),
        @ApiResponse(responseCode = "401", description = "인증 실패 (유효한 Supabase Bearer JWT 필요)")
    })
    public List<VehicleItem> list(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return service.listVehicles(actor.requireUser(jwt));
    }
}
