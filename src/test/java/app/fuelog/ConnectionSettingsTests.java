package app.fuelog;

import app.fuelog.dto.ConnectionSettingsDtos.*;
import app.fuelog.dto.ConnectionDtos;
import app.fuelog.dto.VehicleHomeDtos;
import app.fuelog.dto.TripUserCandidateDtos;
import app.fuelog.service.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConnectionSettingsTests {
    @Autowired JdbcTemplate db;
    @Autowired PairingService pairing;
    @Autowired ConnectionService connection;
    @Autowired ConnectionSettingsService service;
    @Autowired InstallationAccess access;
    @Autowired VehicleHomeService home;
    @Autowired TripUserCandidateService consents;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean JwtDecoder decoder;
    UUID installation,vehicle,user,other,interval;
    String bearer,scoped;
    long registration;

    @BeforeEach void setup() {
        TestSchema.create(db);
        var issued=pairing.createInstallation();installation=issued.installation_id();bearer="Bearer "+issued.installation_secret();
        vehicle=UUID.randomUUID();user=UUID.randomUUID();other=UUID.randomUUID();registration=1;
        db.update("INSERT INTO vehicles (id,name,created_by,created_at,updated_at) VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",vehicle.toString(),"C07 car",user.toString());
        db.update("INSERT INTO group_members (vehicle_id,user_id,role,created_at) VALUES (?,?,'OWNER',CURRENT_TIMESTAMP)",vehicle.toString(),user.toString());
        db.update("INSERT INTO group_members (vehicle_id,user_id,role,created_at) VALUES (?,?,'MEMBER',CURRENT_TIMESTAMP)",vehicle.toString(),other.toString());
        db.update("UPDATE vehicle_installations SET vehicle_id=?,registration_version=1,device_alias='Test device' WHERE id=?",vehicle.toString(),installation.toString());
        scoped=login(user);
        interval=home.start(bearer,scoped,new VehicleHomeDtos.IntervalStartRequest("original-trip",Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.SECONDS),"manual_app")).id();
        when(decoder.decode("owner")).thenReturn(jwt(user));when(decoder.decode("member")).thenReturn(jwt(other));
        when(decoder.decode("proof")).thenReturn(reauth(user,Instant.now().getEpochSecond()));
    }
    @Test void settingsAreReadOnlyAndMissingCapabilitiesAndQueueAreUnknown() {
        var first=service.settings(bearer,new Envelope("","settings-key"));
        assertEquals("Test device",first.deviceAlias());assertEquals("UNKNOWN",first.syncStatus());
        assertEquals(Feature.UNKNOWN,first.features().fuel());assertFalse(first.recommendationEnabled());
        assertEquals(first,service.settings(bearer,new Envelope("VEHICLE","settings-key")));
        assertEquals(0,count("connection_operations"));
    }
    @Test void capabilityFreshnessAndOutOfOrderReportsCannotOverwriteNewerResults() {
        long now=System.currentTimeMillis();
        var features=new Features(Feature.AVAILABLE,Feature.PERMISSION_REQUIRED,Feature.TEMPORARY_FAILURE);
        assertEquals(features,service.capabilities(bearer,new Capabilities(now,features)));
        service.capabilities(bearer,new Capabilities(now-1000,new Features(Feature.UNSUPPORTED,Feature.UNSUPPORTED,Feature.UNSUPPORTED)));
        assertEquals(features,settings().features());
        db.update("UPDATE installation_capabilities SET observed_at=?",now-61_000);
        assertEquals(Feature.UNKNOWN,settings().features().fuel());
        assertStatus(400,()->service.capabilities(bearer,new Capabilities(now+120_000,features)));
        db.update("UPDATE vehicle_installations SET registration_version=2");
        assertEquals(Feature.UNKNOWN,settings().features().distance());
    }
    @Test void userDisconnectUnlinksAndPreservesOwnersAndRetryCannotDisconnectNewPairing() {
        var result=service.disconnect(bearer,new Disconnect(Kind.USER,"logout"));
        assertEquals("COMPLETED",result.status());assertTrue(access.blocked(installation));
        assertEquals(vehicle.toString(),db.queryForObject("SELECT vehicle_id FROM vehicle_installations",String.class));
        assertEquals(user.toString(),db.queryForObject("SELECT user_id FROM recording_intervals WHERE id=?",String.class,interval.toString()));
        assertEquals("finished",db.queryForObject("SELECT status FROM recording_intervals WHERE id=?",String.class,interval.toString()));
        assertStatus(401,()->home.home(bearer,scoped));
        rePair(other);
        assertFalse(access.blocked(installation));
        assertEquals(result,service.disconnect(bearer,new Disconnect(Kind.USER,"logout")));
        home.home(bearer,null);
        assertEquals(other,access.currentUser(installation));
        assertEquals(user.toString(),db.queryForObject("SELECT user_id FROM connection_operations WHERE operation_id='logout'",String.class));
        assertStatus(409,()->service.disconnect(bearer,new Disconnect(Kind.VEHICLE,"logout")));
        assertEquals(1,count("connection_operations"));
    }
    @Test void userDisconnectRequiresNewPairingAndKeepsOriginalOwner() {
        service.disconnect(bearer,new Disconnect(Kind.USER,"logout"));
        assertStatus(403,()->home.start(bearer,null,new VehicleHomeDtos.IntervalStartRequest("new-trip",Instant.now(),"manual_app",other)));
        assertNull(pairing.installation(installation).authenticated_user_id());
        rePair(other);
        assertFalse(access.blocked(installation));assertEquals(other,access.currentUser(installation));
        UUID prepared=home.start(bearer,null,new VehicleHomeDtos.IntervalStartRequest("new-trip",Instant.now(),"manual_app",other)).id();
        assertEquals(other.toString(),db.queryForObject("SELECT user_id FROM recording_intervals WHERE id=?",String.class,prepared.toString()));
        assertEquals(user.toString(),db.queryForObject("SELECT user_id FROM recording_intervals WHERE id=?",String.class,interval.toString()));
    }
    @Test void vehicleResetRequiresManagerProofVersionAndRetentionPolicyThenKeepsCredentialForC01() {
        var waiting=service.disconnect(bearer,new Disconnect(Kind.VEHICLE,"reset"));
        assertEquals("AWAITING_ADMIN",waiting.status());
        assertEquals(1,service.pending(user).items().size());assertTrue(service.pending(other).items().isEmpty());
        assertNotNull(db.queryForObject("SELECT vehicle_id FROM vehicle_installations",String.class));
        assertStatus(403,()->home.challenge(bearer,new VehicleHomeDtos.ChallengeRequest(installation,"blocked-challenge",null)));
        assertStatus(403,()->service.approve(jwt(other),"reset",approval("proof",registration)));
        assertStatus(409,()->service.approve(jwt(user),"reset",approval("proof",registration+1)));
        assertStatus(400,()->service.approve(jwt(user),"reset",new Approve(installation,registration,"DELETE","proof")));
        var done=service.approve(jwt(user),"reset",approval("proof",registration));
        assertEquals("COMPLETED",done.status());assertNull(db.queryForObject("SELECT vehicle_id FROM vehicle_installations",String.class));
        assertTrue(access.blocked(installation));assertEquals(2,access.registrationVersion(installation));
        assertEquals(done,service.approve(jwt(user),"reset",approval("unused",registration)));
        assertEquals(done,service.disconnect(bearer,new Disconnect(Kind.VEHICLE,"reset")));
        assertEquals(1,count("recording_intervals"));assertEquals(1,count("connection_reauth_uses"));
        var request=connection.issue(bearer,new ConnectionDtos.RequestBody("new-registration",null));
        assertEquals("WAITING",request.status());
    }
    @Test void staleExpiredWrongActorAndReplayedReauthenticationNeverReset() {
        service.disconnect(bearer,new Disconnect(Kind.VEHICLE,"reset"));
        when(decoder.decode("stale")).thenReturn(reauth(user,Instant.now().minusSeconds(301).getEpochSecond()));
        when(decoder.decode("wrong")).thenReturn(reauth(other,Instant.now().getEpochSecond()));
        when(decoder.decode("expired")).thenReturn(Jwt.withTokenValue("expired").header("alg","ES256").subject(user.toString()).claim("role","authenticated").claim("is_anonymous",false).expiresAt(Instant.now().minusSeconds(1)).build());
        assertStatus(403,()->service.approve(jwt(user),"reset",approval("stale",registration)));
        assertStatus(403,()->service.approve(jwt(user),"reset",approval("wrong",registration)));
        assertStatus(401,()->service.approve(jwt(user),"reset",approval("expired",registration)));
        service.approve(jwt(user),"reset",approval("proof",registration));
        // New registration: the same authentication event remains consumed, including a refreshed JWT.
        db.update("UPDATE vehicle_installations SET vehicle_id=? WHERE id=?",vehicle.toString(),installation.toString());
        service.disconnect(bearer,new Disconnect(Kind.VEHICLE,"second"));
        when(decoder.decode("refreshed")).thenReturn(reauth(user,Instant.now().getEpochSecond()));
        // Reuse the exact signed authentication event from the original proof.
        Jwt originalProof=decoder.decode("proof");
        when(decoder.decode("refreshed")).thenReturn(originalProof);
        assertStatus(409,()->service.approve(jwt(user),"second",approval("refreshed",2)));
        assertNotNull(db.queryForObject("SELECT vehicle_id FROM vehicle_installations",String.class));
    }
    @Test void managerMembershipAndRegistrationAreRecheckedAtDecisionTime() {
        service.disconnect(bearer,new Disconnect(Kind.VEHICLE,"reset"));
        db.update("UPDATE group_members SET role='MEMBER' WHERE user_id=?",user.toString());
        assertStatus(403,()->service.approve(jwt(user),"reset",approval("proof",registration)));
        db.update("UPDATE group_members SET role='OWNER' WHERE user_id=?",user.toString());
        db.update("UPDATE vehicle_installations SET registration_version=2");
        assertStatus(409,()->service.approve(jwt(user),"reset",approval("proof",registration)));
        assertEquals(0,count("connection_reauth_uses"));
    }
    @Test void rejectionIsIdempotentAndDoesNotRestorePersonalAccess() {
        service.disconnect(bearer,new Disconnect(Kind.VEHICLE,"reset"));
        var rejected=service.reject(user,"reset",new Reject(installation,registration));
        assertEquals("REJECTED",rejected.status());assertEquals(rejected,service.reject(user,"reset",new Reject(installation,registration)));
        assertEquals(rejected,service.disconnect(bearer,new Disconnect(Kind.VEHICLE,"reset")));
        assertTrue(access.blocked(installation));assertStatus(401,()->home.home(bearer,scoped));
        assertStatus(409,()->service.approve(jwt(user),"reset",approval("proof",registration)));
        login(user);assertFalse(access.blocked(installation));
    }
    @Test void recommendationRequiresTrustedRegistrationVerificationAndCurrentUserAndWithdrawalIsImmediate() {
        assertStatus(409,()->service.setRecommendation(user,vehicle,new Recommendation(true,true)));
        db.update("INSERT INTO recommendation_registrations (installation_id,registration_version,registered,verified,verified_at,verification_source) VALUES (?,1,TRUE,TRUE,CURRENT_TIMESTAMP,'verified feature provisioning')",installation.toString());
        assertStatus(403,()->service.setRecommendation(other,vehicle,new Recommendation(true,true)));
        assertTrue(service.setRecommendation(user,vehicle,new Recommendation(true,true)).enabled());assertTrue(settings().recommendationEnabled());
        service.setRecommendation(user,vehicle,new Recommendation(false,false));
        assertFalse(settings().recommendationConsented());assertFalse(settings().recommendationEnabled());
        service.setRecommendation(user,vehicle,new Recommendation(true,true));
        service.disconnect(bearer,new Disconnect(Kind.USER,"logout"));
        assertFalse(settings().recommendationEnabled());assertFalse(settings().recommendationConsented());
        rePair(other);assertFalse(settings().recommendationConsented());
    }
    @Test void syncNeverInfersLocalCompletionAndDoesNotLeakOtherOwners() {
        assertEquals("UNKNOWN",service.syncStatus(bearer).status());
        long now=System.currentTimeMillis();
        db.update("INSERT INTO installation_sync_checkpoints (installation_id,registration_version,user_id,pending_count,last_acknowledged_at,verified_at) VALUES (?,1,?,0,?,?)",installation.toString(),user.toString(),now-1000,now);
        db.update("INSERT INTO installation_sync_checkpoints (installation_id,registration_version,user_id,pending_count,last_acknowledged_at,verified_at) VALUES (?,1,?,12,?,?)",installation.toString(),other.toString(),now-1000,now);
        assertEquals("SYNCED",settings().syncStatus());assertEquals(1,service.syncStatus(bearer).owners().size());
        db.update("UPDATE installation_sync_checkpoints SET verified_at=? WHERE user_id=?",now-61_000,user.toString());
        assertEquals("UNKNOWN",settings().syncStatus());
        login(other);assertEquals("PENDING",service.syncStatus(bearer).status());assertEquals(other,service.syncStatus(bearer).owners().getFirst().owner());
        service.disconnect(bearer,new Disconnect(Kind.USER,"logout"));assertTrue(service.syncStatus(bearer).owners().isEmpty());
    }
    @Test void concurrentDisconnectRetriesCreateOnlyOneOperation() throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->service.disconnect(bearer,new Disconnect(Kind.USER,"concurrent")));
            var b=executor.submit(()->service.disconnect(bearer,new Disconnect(Kind.USER,"concurrent")));
            assertEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
        }
        assertEquals(1,count("connection_operations"));
        assertEquals(3,db.queryForObject("SELECT session_version FROM vehicle_installations",Long.class));
    }
    @Test void httpRequiresInstallationAndMobileAuthenticationAndValidPolicy() throws Exception {
        mvc.perform(post("/connection/settings").contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"\",\"operationId\":\"read\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/connection/settings").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"\",\"operationId\":\"read\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.features.fuel").value("UNKNOWN"));
        mvc.perform(get("/installation/sync-status").header("Authorization",bearer)).andExpect(status().isOk());
        mvc.perform(get("/mobile/connection-operations")).andExpect(status().isUnauthorized());
        service.disconnect(bearer,new Disconnect(Kind.VEHICLE,"reset"));
        mvc.perform(get("/mobile/connection-operations").header("Authorization","Bearer member")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(post("/mobile/connection-operations/reset/approve").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new Approve(installation,registration,"DELETE","proof")))).andExpect(status().isBadRequest());
        mvc.perform(post("/mobile/connection-operations/reset/approve").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(approval("proof",registration)))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
    }
    private void rePair(UUID owner) {
        db.update("UPDATE group_members SET role='MANAGER' WHERE vehicle_id=? AND user_id=?",vehicle.toString(),owner.toString());
        var request=pairing.issue(installation,"re-pair-"+UUID.randomUUID());
        var intent=pairing.intent(owner,request.id(),"intent-"+UUID.randomUUID(),new app.fuelog.dto.PairingDtos.IntentRequest(request.qr().proof(),vehicle,null));
        vehicle=pairing.confirm(installation,request.id(),intent.confirmation_code()).vehicle_id();
    }
    private String login(UUID owner) {
        var challenge=home.challenge(bearer,new VehicleHomeDtos.ChallengeRequest(installation,"login-"+UUID.randomUUID(),null));
        home.approve(jwt(owner),new VehicleHomeDtos.MobileApprovalRequest(challenge.challengeId(),challenge.proof(),vehicle));
        return "Bearer "+home.session(bearer,new VehicleHomeDtos.SessionRequest(challenge.challengeId())).scopedToken();
    }
    private Settings settings() { return service.settings(bearer,new Envelope("","read")); }
    private Approve approval(String proof,long version) { return new Approve(installation,version,"RETAIN_ORIGINAL_OWNER",proof); }
    private int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    private static Jwt jwt(UUID user) { return Jwt.withTokenValue("test").header("alg","ES256").subject(user.toString()).claim("role","authenticated").claim("is_anonymous",false).claim("user_metadata",Map.of("display_name","Driver")).issuedAt(Instant.now().minusSeconds(10)).expiresAt(Instant.now().plusSeconds(300)).build(); }
    private static Jwt reauth(UUID user,long timestamp) { return Jwt.withTokenValue("reauth").header("alg","ES256").subject(user.toString()).claim("role","authenticated").claim("is_anonymous",false).claim("amr",List.of(Map.of("method","otp","timestamp",timestamp))).expiresAt(Instant.now().plusSeconds(300)).build(); }
    private static void assertStatus(int code,Runnable action) { assertEquals(code,assertThrows(ResponseStatusException.class,action::run).getStatusCode().value()); }
}
