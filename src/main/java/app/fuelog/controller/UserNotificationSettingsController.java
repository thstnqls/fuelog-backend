package app.fuelog.controller;

import app.fuelog.service.UserNotificationSettingsService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/installation/users/{userId}/notification-settings")
@SecurityRequirement(name="installationBearer")
public class UserNotificationSettingsController {
    public record Update(@NotNull Boolean chargeCompleteEnabled) {}
    private final UserNotificationSettingsService service;
    public UserNotificationSettingsController(UserNotificationSettingsService service) {this.service=service;}
    @GetMapping
    public ResponseEntity<UserNotificationSettingsService.Settings> get(
            @RequestHeader(value="Authorization",required=false) String bearer,@PathVariable UUID userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(bearer,userId));
    }
    @PutMapping
    public ResponseEntity<UserNotificationSettingsService.Settings> put(
            @RequestHeader(value="Authorization",required=false) String bearer,@PathVariable UUID userId,
            @Valid @RequestBody Update update) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.save(bearer,userId,update.chargeCompleteEnabled()));
    }
}
