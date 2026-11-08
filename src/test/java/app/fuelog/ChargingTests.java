package app.fuelog;

import app.fuelog.dto.ChargeCandidateDtos.*;
import app.fuelog.dto.VehicleHomeDtos;
import app.fuelog.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@SpringBootTest(properties="fuelog.charging.pleos-coordinate-system=WGS84")
@ActiveProfiles("test")
class ChargingTests {
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ChargingEstimateService estimates;
    @Autowired ChargeCandidateService charges;
    @Autowired InstallationAuth auth;
    @Autowired InstallationAccess access;
    @Autowired PairingService pairing;
    @Autowired app.fuelog.repository.PairingRepository pairingRepo;
    @Autowired app.fuelog.repository.VehicleHomeRepository homeRepo;
    @Autowired app.fuelog.repository.TripUserCandidateRepository members;
    @Autowired VehicleHomeService home;
    @Autowired ObjectMapper json;
    @MockitoBean JwtDecoder decoder;
    Instant now;
    @BeforeEach void setup() {
        TestSchema.create(db); now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        db.update("UPDATE charging_tariffs SET verified_at=?,valid_until=?",time(now.minusSeconds(3600)),time(now.plusSeconds(86400)));
    }
    @Test void powerBandsRespectBoundaries() {
        for(int i=0;i<5;i++) assertEquals(List.of("SLOW","MEDIUM","FAST_50","FAST_100","ULTRA").get(i),
                ChargingEstimateService.powerBand(BigDecimal.valueOf(new int[]{7,30,50,100,200}[i])));
        assertNull(ChargingEstimateService.powerBand(BigDecimal.ZERO));
        assertEquals(0,ChargingEstimateService.distance(37,127,37,127));
    }
    @Test void nearbyDistinctOperatorsAreAveragedOnceAndPowerBandsStaySeparate() {
        station("a","PI",37,127,50); station("a2","PI",37.001,127,50);
        station("b","ME",37.002,127,50); station("c","KP",37.003,127,50);
        station("far","CV",38,127,50); station("slow","CV",37,127,7);
        var result=estimates.estimate(37,127,new BigDecimal("12"),now);
        assertEquals(1,result.size());
        assertEquals("FAST_50",result.getFirst().powerBand());
        assertEquals(3,result.getFirst().stations().size());
        assertEquals(0,new BigDecimal("325.400000").compareTo(result.getFirst().averageWonPerKwh()));
        assertEquals(0,new BigDecimal("3905").compareTo(result.getFirst().estimatedAmount()));
        db.update("UPDATE charging_tariffs SET won_per_kwh=900");
        assertEquals(new BigDecimal("6508"),ChargingEstimateService.reprice(result,new BigDecimal("20")).getFirst().estimatedAmount());
    }
    @Test void insufficientStaleAndOutOfRangeEvidenceNeverCreatesAnAmount() {
        station("a","PI",37,127,50);
        assertTrue(estimates.estimate(37,127,BigDecimal.TEN,now).isEmpty());
        station("b","ME",37.02,127,50);
        assertEquals(2,estimates.estimate(37,127,BigDecimal.TEN,now).getFirst().stations().size());
        assertTrue(estimates.estimate(38,127,BigDecimal.TEN,now).isEmpty());
        db.update("UPDATE charger_catalog SET observed_at=?",time(now.minusSeconds(86401)));
        assertTrue(estimates.estimate(37,127,BigDecimal.TEN,now).isEmpty());
        db.update("UPDATE charger_catalog SET observed_at=?",time(now));
        db.update("UPDATE charging_tariffs SET valid_until=?",time(now.minusSeconds(1)));
        assertTrue(estimates.estimate(37,127,BigDecimal.TEN,now).isEmpty());
    }
    @Test void candidatesAreIdempotentOwnerBoundAndConfirmationDoesNotRegress() {
        Fixture f=fixture();
        Sync b=new Sync(UUID.randomUUID(),f.interval,f.user,now.minusSeconds(40).toEpochMilli(),now.minusSeconds(5).toEpochMilli(),BigDecimal.TEN,"AAOS_WH");
        View first=charges.sync(f.bearer,b);
        assertEquals(first,charges.sync(f.bearer,b));
        assertEquals(1,charges.list(f.user).size());
        assertThrows(ResponseStatusException.class,()->charges.detail(UUID.randomUUID(),b.id()));
        assertThrows(ResponseStatusException.class,()->charges.sync(f.bearer,new Sync(b.id(),b.intervalId(),UUID.randomUUID(),b.startAt(),b.endAt(),BigDecimal.TEN,b.basis())));
        Patch patch=new Patch("confirm-1",1,"CONFIRM",null,new BigDecimal("3300"),"확인한 충전소");
        View confirmed=charges.patch(f.user,b.id(),patch);
        assertEquals(confirmed,charges.patch(f.user,b.id(),patch));
        assertEquals("CONFIRMED",charges.sync(f.bearer,new Sync(b.id(),b.intervalId(),b.owner(),b.startAt(),b.endAt()+1000,new BigDecimal("12"),b.basis())).status());
        assertEquals(new BigDecimal("3300"),confirmed.correctedAmount());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM charge_candidates",Integer.class));
    }
    @Test void locationOperationIsOneShotAndRepricingUsesTheFrozenMean() {
        Fixture f=fixture(); station("a","PI",37,127,50);station("b","ME",37.001,127,50);
        Sync b=new Sync(UUID.randomUUID(),f.interval,f.user,now.minusSeconds(40).toEpochMilli(),now.minusSeconds(5).toEpochMilli(),BigDecimal.TEN,"AAOS_WH");
        charges.sync(f.bearer,b);
        Location location=new Location(b.id(),"charge-estimate:"+b.id(),37.0,127.0,now.minusSeconds(4),now.minusSeconds(3),"PLEOS_CURRENT_UPDATE");
        View priced=charges.estimate(f.bearer,b.id(),location);
        assertEquals("REFERENCE_ESTIMATE",priced.estimateStatus());
        assertEquals(priced,charges.estimate(f.bearer,b.id(),location));
        db.update("UPDATE charging_tariffs SET won_per_kwh=900");
        View increased=charges.sync(f.bearer,new Sync(b.id(),b.intervalId(),b.owner(),b.startAt(),b.endAt()+1000,new BigDecimal("12"),b.basis()));
        assertEquals(0,priced.scenarios().getFirst().averageWonPerKwh().compareTo(increased.scenarios().getFirst().averageWonPerKwh()));
        assertEquals(new BigDecimal("3904"),increased.scenarios().getFirst().estimatedAmount());
        String stored=db.queryForObject("SELECT payload FROM charge_candidates WHERE id=?",String.class,b.id().toString());
        assertFalse(stored.contains("latitude"));assertFalse(stored.contains("longitude"));
    }
    @Test void unknownCoordinateSystemAndLateLocationsKeepTheCandidateWithoutPrice() {
        Fixture f=fixture();
        Sync b=new Sync(UUID.randomUUID(),f.interval,f.user,now.minusSeconds(40).toEpochMilli(),now.minusSeconds(5).toEpochMilli(),BigDecimal.TEN,"AAOS_WH");
        charges.sync(f.bearer,b);
        assertThrows(ResponseStatusException.class,()->charges.estimate(f.bearer,b.id(),new Location(b.id(),"charge-estimate:"+b.id(),37.0,127.0,now.minusSeconds(10),now.minusSeconds(3),"PLEOS_CURRENT_UPDATE")));
        var unverified=new ChargeCandidateService(db,json,auth,access,pairingRepo,homeRepo,members,estimates,"");
        assertEquals("COORDINATE_SYSTEM_UNVERIFIED",unverified.estimate(f.bearer,b.id(),new Location(b.id(),"charge-estimate:"+b.id(),37.0,127.0,now.minusSeconds(4),now.minusSeconds(3),"PLEOS_CURRENT_UPDATE")).estimateStatus());
        assertEquals(BigDecimal.TEN,charges.detail(f.user,b.id()).estimatedKwh());
    }
    @Test void httpRoutesRequireCorrectInstallationAndMobileAuthentication() throws Exception {
        Fixture f=fixture();
        Sync b=new Sync(UUID.randomUUID(),f.interval,f.user,now.minusSeconds(40).toEpochMilli(),now.minusSeconds(5).toEpochMilli(),BigDecimal.TEN,"AAOS_WH");
        var post=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/charge-candidates/sync");
        mvc.perform(post.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/charge-candidates/sync")
                .header("Authorization",f.bearer).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/mobile/charge-candidates"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        var jwt=org.springframework.security.oauth2.jwt.Jwt.withTokenValue("owner").header("alg","RS256")
                .subject(f.user.toString()).claim("role","authenticated").claim("is_anonymous",false).issuedAt(Instant.now().minusSeconds(30)).expiresAt(Instant.now().plusSeconds(300)).build();
        org.mockito.Mockito.when(decoder.decode("owner")).thenReturn(jwt);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/mobile/charge-candidates").header("Authorization","Bearer owner"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].id").value(b.id().toString()));
    }
    private Fixture fixture() {
        var issued=pairing.createInstallation(); UUID user=UUID.randomUUID(),vehicle=UUID.randomUUID();
        String bearer="Bearer "+issued.installation_secret();
        db.update("INSERT INTO vehicles(id,name,created_by,created_at,updated_at) VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",vehicle.toString(),"EV",user.toString());
        db.update("INSERT INTO group_members VALUES(?,?,?,CURRENT_TIMESTAMP)",vehicle.toString(),user.toString(),"OWNER");
        db.update("UPDATE vehicle_installations SET vehicle_id=? WHERE id=?",vehicle.toString(),issued.installation_id().toString());
        db.update("INSERT INTO pairing_requests(id,installation_id,status,selected_vehicle_id,approved_user_id,updated_at) VALUES(?,?,'completed',?,?,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),issued.installation_id().toString(),vehicle.toString(),user.toString());
        var interval=home.start(bearer,null,new VehicleHomeDtos.IntervalStartRequest(UUID.randomUUID().toString(),now.minusSeconds(50),"manual_app",user));
        return new Fixture(bearer,user,interval.id());
    }
    private record Fixture(String bearer,UUID user,UUID interval) {}
    private void station(String id,String op,double lat,double lon,int kw) {
        db.update("INSERT INTO charger_catalog VALUES(?,?,?,?,?,?,?,?)",id,"01",id,op,lat,lon,kw,time(now));
    }
    private LocalDateTime time(Instant t) { return LocalDateTime.ofInstant(t,ZoneOffset.UTC); }
}
