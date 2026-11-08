package app.fuelog.controller;

import app.fuelog.dto.ChargeCandidateDtos.*;
import app.fuelog.service.*;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class ChargeCandidateController {
    private final ChargeCandidateService service;
    private final PairingActor actor;
    public ChargeCandidateController(ChargeCandidateService service,PairingActor actor) { this.service=service;this.actor=actor; }
    @ModelAttribute void noStore(jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    @PostMapping("/charge-candidates/sync")
    public View sync(@RequestHeader(value="Authorization",required=false) String bearer,@Valid @RequestBody Sync body) { return service.sync(bearer,body); }
    @GetMapping("/charge-candidates/{id}")
    public View device(@RequestHeader(value="Authorization",required=false) String bearer,@PathVariable UUID id) { return service.detailDevice(bearer,id); }
    @PostMapping("/charge-candidates/{id}/estimate")
    public View estimate(@RequestHeader(value="Authorization",required=false) String bearer,@PathVariable UUID id,@Valid @RequestBody Location body) { return service.estimate(bearer,id,body); }
    @GetMapping("/mobile/charge-candidates")
    public List<View> list(@AuthenticationPrincipal Jwt jwt) { return service.list(actor.requireUser(jwt)); }
    @GetMapping("/mobile/charge-candidates/{id}")
    public View detail(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { return service.detail(actor.requireUser(jwt),id); }
    @PatchMapping("/mobile/charge-candidates/{id}")
    public View patch(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody Patch body) { return service.patch(actor.requireUser(jwt),id,body); }
}
