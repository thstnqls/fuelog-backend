package app.fuelog;

import app.fuelog.dto.FuelCandidateDtos.*;
import app.fuelog.dto.TripUserCandidateDtos;
import app.fuelog.dto.VehicleHomeDtos;
import app.fuelog.service.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
class FuelCandidateTests {
    @Autowired JdbcTemplate db;
    @Autowired PairingService pairing;
    @Autowired VehicleHomeService home;
    @Autowired TripUserCandidateService consents;
    @Autowired FuelCandidateService service;
    @Autowired TripSummaryService summaries;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean JwtDecoder decoder;
    UUID installation, vehicle, user, trip, interval, consent, candidate;
    String bearer;
    long start, end;

    @BeforeEach
    void setup() {
        TestSchema.create(db);
        var issued=pairing.createInstallation();
        installation=issued.installation_id(); bearer="Bearer "+issued.installation_secret();
        vehicle=UUID.randomUUID(); user=UUID.randomUUID(); trip=UUID.randomUUID(); candidate=UUID.randomUUID();
        db.update("INSERT INTO vehicles (id,name,created_by,created_at,updated_at) VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",vehicle.toString(),"C05 car",user.toString());
        db.update("INSERT INTO group_members (vehicle_id,user_id,role,created_at) VALUES (?,?,?,CURRENT_TIMESTAMP)",vehicle.toString(),user.toString(),"OWNER");
        db.update("UPDATE vehicle_installations SET vehicle_id=? WHERE id=?",vehicle.toString(),installation.toString());
        start=Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS).toEpochMilli(); end=start+30_000;
        interval=home.start(bearer,null,new VehicleHomeDtos.IntervalStartRequest(trip.toString(),Instant.ofEpochMilli(start),"manual_app")).id();
        var challenge=home.challenge(bearer,new VehicleHomeDtos.ChallengeRequest(null,"challenge",interval));
        consent=consents.consent(jwt(user),new TripUserCandidateDtos.MobileConsentRequest(vehicle,installation,interval,challenge.challengeId(),challenge.proof(),"consent")).candidateId();
        consents.verify(bearer,new TripUserCandidateDtos.VerifyRequest(interval,consent,"verify"));
        db.update("UPDATE trip_user_candidates SET updated_at=? WHERE id=?",LocalDateTime.ofInstant(Instant.ofEpochMilli(start-1000),ZoneOffset.UTC),consent.toString());
        when(decoder.decode("owner")).thenReturn(jwt(user));
        when(decoder.decode("other")).thenReturn(jwt(UUID.randomUUID()));
    }

    @Test
    void duplicateSyncAndDeliveryAreIdempotentAndIgnoreClientCache() {
        View first=service.sync(bearer,body(end,"10",true));
        for(int i=0;i<5;i++) assertEquals(first,service.sync(bearer,body(end,"10",true)));
        assertEquals(1,count("fuel_candidates")); assertEquals(1,count("mobile_trip_inbox"));
        assertEquals(Status.CANDIDATE,first.status()); assertEquals(1,first.revision());
        assertFalse(first.capacityVerified()); assertNull(first.estimatedLiters()); assertNull(first.estimatedAmount());
        assertTrue(first.stationCandidates().isEmpty());
        var item=summaries.inbox(user,0).items().getFirst();
        assertEquals("FUEL_CANDIDATE",item.kind()); assertEquals(candidate,item.candidateId());
        assertEquals(1,item.candidateRevision()); assertNull(item.summary());
        assertTrue(summaries.inbox(UUID.randomUUID(),0).items().isEmpty());
    }

    @Test
    void evidenceAndConfirmationRemainIndependentWithStaleRetries() {
        var first=service.sync(bearer,body(end,"10",false));
        var confirmed=service.patch(user,candidate,new Patch("confirm",first.revision(),Action.CONFIRM,"My station",new BigDecimal("4.5"),new BigDecimal("8000")));
        var newer=service.sync(bearer,body(end+1000,"12",true));
        assertEquals(Status.CONFIRMED,newer.status()); assertTrue(newer.revision()>confirmed.revision());
        assertEquals(0,newer.increasePercent().compareTo(new BigDecimal("12")));
        assertEquals("My station",newer.correctedPlace()); assertNull(newer.estimatedLiters());
        assertEquals(newer,service.sync(bearer,body(end,"10",true)));
        assertEquals(newer,service.sync(bearer,body(end+1000,"99",true)));
        var rejected=service.patch(user,candidate,new Patch("reject",newer.revision(),Action.REJECT,null,null,null));
        assertEquals(Status.REJECTED,service.sync(bearer,body(end+2000,"15",true)).status());
        assertEquals(2,count("fuel_candidate_operations"));
        assertTrue(rejected.revision()>newer.revision());
    }

    @Test
    void laterEvidenceCanAdjustStartAndStaleRetriesCannotRestorePriorWindow() {
        service.sync(bearer,body(end,"10",false));
        Sync changed = new Sync(candidate,user,trip,start+1000,end+2000,new BigDecimal("11"),null,null,false,null,null,null,null,null);
        var updated=service.sync(bearer,changed);
        assertEquals(start+1000,updated.startAt());
        assertEquals(updated,service.sync(bearer,body(end,"10",false)));
    }

    @Test
    void mobileOperationsAreConditionalAuditedAndRetriableAfterNewerChanges() {
        var first=service.sync(bearer,body(end,"10",true));
        Patch patch=new Patch("op",first.revision(),Action.CONFIRM,null,null,null);
        var confirmed=service.patch(user,candidate,patch);
        assertEquals(confirmed,service.patch(user,candidate,patch));
        service.patch(user,candidate,new Patch("next",confirmed.revision(),Action.REJECT,null,null,null));
        assertEquals(confirmed,service.patch(user,candidate,patch));
        assertStatus(409,()->service.patch(user,candidate,new Patch("op",first.revision(),Action.REJECT,null,null,null)));
        assertStatus(409,()->service.patch(user,candidate,new Patch("stale",first.revision(),Action.CONFIRM,null,null,null)));
        assertEquals(2,count("fuel_candidate_operations"));
    }

    @Test
    void verifiedCapacityAndPriceAreRequiredForEstimates() {
        service.sync(bearer,body(end,"10",false));
        db.update("INSERT INTO vehicle_fuel_metadata (vehicle_id,capacity_liters,unit_price,price_at,price_valid_until,price_source) VALUES (?,50,1700,?,?,?)",vehicle.toString(),start,end+60_000,"trusted");
        assertFalse(service.sync(bearer,body(end,"10",false)).capacityVerified());
        db.update("UPDATE vehicle_fuel_metadata SET capacity_verified_at=CURRENT_TIMESTAMP,capacity_source='manufacturer'");
        View verified=service.sync(bearer,body(end,"10",false));
        assertTrue(verified.capacityVerified()); assertEquals(new BigDecimal("5.000"),verified.estimatedLiters());
        assertEquals(new BigDecimal("8500.00"),verified.estimatedAmount()); assertEquals(start,verified.priceAt());
        db.update("UPDATE vehicle_fuel_metadata SET price_valid_until=?",end-1);
        var expired=service.sync(bearer,body(end,"10",false));
        assertTrue(expired.revision()>verified.revision()); assertNull(expired.estimatedAmount()); assertNull(expired.priceAt());
        assertEquals(verified.estimatedLiters(),expired.estimatedLiters());
    }

    @Test
    void locationAndProviderFailurePreserveEvidenceAndLiters() {
        var first=service.sync(bearer,body(end,"10",false));
        assertEquals(first,service.stationSearch(bearer,candidate,new StationSearch(false,null,null,null)));
        assertEquals(first,service.stationSearch(bearer,candidate,new StationSearch(true,new BigDecimal("37"),new BigDecimal("127"),start-600_000)));
        assertEquals(first,service.stationSearch(bearer,candidate,new StationSearch(true,new BigDecimal("37"),new BigDecimal("127"),System.currentTimeMillis())));
    }

    @Test
    void ownershipMembershipAndConsentCannotBeBypassed() {
        Sync b=body(end,"10",false);
        Sync forged=new Sync(candidate,UUID.randomUUID(),trip,start,end,new BigDecimal("10"),null,null,false,null,null,null,null,null);
        assertStatus(403,()->service.sync(bearer,forged));
        assertEquals(0,count("fuel_candidates"));
        service.sync(bearer,b);
        assertStatus(403,()->service.detail(UUID.randomUUID(),candidate));
        var other=pairing.createInstallation();
        assertStatus(403,()->service.sync("Bearer "+other.installation_secret(),b));
        db.update("DELETE FROM group_members WHERE vehicle_id=?",vehicle.toString());
        assertStatus(403,()->service.sync(bearer,b)); assertStatus(403,()->service.detail(user,candidate));
        assertTrue(summaries.inbox(user,0).items().isEmpty());
        db.update("INSERT INTO group_members (vehicle_id,user_id,role) VALUES (?,?,'OWNER')",vehicle.toString(),user.toString());
        db.update("UPDATE trip_user_candidates SET verification_expires_at=?",LocalDateTime.ofInstant(Instant.now().minusSeconds(1),ZoneOffset.UTC));
        assertStatus(401,()->service.sync(bearer,b));
        db.update("UPDATE trip_user_candidates SET status='revoked'");
        assertStatus(403,()->service.sync(bearer,b));
    }

    @Test
    void unverifiedPastEvidenceAndNewIntervalsAreNotReassigned() {
        db.update("UPDATE trip_user_candidates SET updated_at=CURRENT_TIMESTAMP WHERE id=?",consent.toString());
        assertStatus(403,()->service.sync(bearer,body(end,"10",false)));
        db.update("UPDATE trip_user_candidates SET updated_at=? WHERE id=?",LocalDateTime.ofInstant(Instant.ofEpochMilli(start-1000),ZoneOffset.UTC),consent.toString());
        service.sync(bearer,body(end,"10",false));
        db.update("UPDATE recording_intervals SET status='finished',ended_at=CURRENT_TIMESTAMP WHERE id=?",interval.toString());
        home.start(bearer,null,new VehicleHomeDtos.IntervalStartRequest(UUID.randomUUID().toString(),Instant.now(),"manual_app"));
        assertStatus(403,()->service.sync(bearer,body(end,"10",false)));
        assertEquals(user,service.detail(user,candidate).owner());
    }

    @Test
    void concurrentUploadsAndMobileRevisionRaceHaveOneWinner() throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->service.sync(bearer,body(end,"10",true)));
            var b=executor.submit(()->service.sync(bearer,body(end,"10",true)));
            assertEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
            Callable<Integer> confirm=()-> { try { service.patch(user,candidate,new Patch(UUID.randomUUID().toString(),1L,Action.CONFIRM,null,null,null)); return 200; }
                catch(ResponseStatusException ex) { return ex.getStatusCode().value(); } };
            var c=executor.submit(confirm); var d=executor.submit(confirm);
            assertEquals(List.of(200,409),java.util.stream.Stream.of(c.get(5,TimeUnit.SECONDS),d.get(5,TimeUnit.SECONDS)).sorted().toList());
        }
        assertEquals(1,count("fuel_candidates")); assertEquals(1,count("mobile_trip_inbox")); assertEquals(1,count("fuel_candidate_operations"));
    }

    @Test
    void httpAuthValidationInboxAndMobileRoundTrip() throws Exception {
        mvc.perform(post("/fuel-candidates/sync").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body(end,"10",true)))).andExpect(status().isUnauthorized());
        mvc.perform(post("/fuel-candidates/sync").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content("{}")) .andExpect(status().isBadRequest());
        mvc.perform(post("/fuel-candidates/sync").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body(end,"10",true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANDIDATE")).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/mobile/fuel-candidates/"+candidate)).andExpect(status().isUnauthorized());
        mvc.perform(get("/mobile/fuel-candidates/"+candidate).header("Authorization","Bearer other")).andExpect(status().isForbidden());
        mvc.perform(get("/mobile/inbox").header("Authorization","Bearer owner")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].kind").value("FUEL_CANDIDATE")).andExpect(jsonPath("$.items[0].candidateId").value(candidate.toString()));
        mvc.perform(patch("/mobile/fuel-candidates/"+candidate).header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new Patch("mobile",1L,Action.CONFIRM,"station",new BigDecimal("5"),new BigDecimal("8500")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
        mvc.perform(get("/mobile/fuel-candidates/"+candidate).header("Authorization","Bearer owner")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertEquals(Status.CONFIRMED,service.sync(bearer,body(end,"10",true)).status());
    }
    private Sync body(long sampleEnd,String percent,boolean queued) {
        return new Sync(candidate,user,trip,start,sampleEnd,new BigDecimal(percent),Status.REJECTED,999L,queued,true,new BigDecimal("999"),new BigDecimal("999999"),1L,List.of("Untrusted station"));
    }
    private int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    private static Jwt jwt(UUID user) { return Jwt.withTokenValue("test").header("alg","ES256").subject(user.toString()).claim("role","authenticated").claim("is_anonymous",false).claim("user_metadata",Map.of("display_name","Driver")).build(); }
    private static void assertStatus(int code,Runnable action) { assertEquals(code,assertThrows(ResponseStatusException.class,action::run).getStatusCode().value()); }
}
