package app.fuelog.controller;

import app.fuelog.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@RestController
public class InstallationUserController {
    private final InstallationAuth auth;
    private final PairingService pairing;
    private final SupabaseUserService users;
    public InstallationUserController(InstallationAuth auth,PairingService pairing,SupabaseUserService users) {
        this.auth=auth;this.pairing=pairing;this.users=users;
    }
    @GetMapping("/installation/users/{userId}")
    @SecurityRequirement(name="installationBearer")
    @Operation(summary="현재 연결된 사용자 이메일 조회",description="설치 Bearer로 인증하고 현재 QR 인증 사용자 ID와 일치할 때만 Supabase Auth의 최신 이메일을 반환합니다. 다른 사용자 ID는 403입니다.")
    public ResponseEntity<SupabaseUserService.User> get(@RequestHeader(value="Authorization",required=false) String bearer,
            @PathVariable UUID userId) {
        UUID installation=auth.require(bearer).id();
        requireUser(installation,userId);
        var user=users.find(userId);
        requireUser(installation,userId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(user);
    }
    private void requireUser(UUID installation,UUID userId) {
        var current=pairing.installation(installation);
        if (!current.paired() || !userId.equals(current.authenticated_user_id()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"CONNECTED_USER_REQUIRED");
    }
}
