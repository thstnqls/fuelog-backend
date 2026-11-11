package app.fuelog;

import app.fuelog.common.TimeUtils;
import app.fuelog.dto.TripSummaryDtos.*;
import app.fuelog.dto.TripUserCandidateDtos;
import app.fuelog.dto.VehicleHomeDtos;
import app.fuelog.service.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

@SpringBootTest(properties="fuelog.charging.pleos-coordinate-system=WGS84")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TripSummaryTests {
    @Autowired JdbcTemplate db;
    @Autowired PairingService pairing;
    @Autowired VehicleHomeService home;
    @Autowired TripUserCandidateService candidates;
    @Autowired TripSummaryService service;
    @Autowired TripEvCostService costs;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean JwtDecoder decoder;
    UUID installation, vehicle, user, trip, interval, consent;
    String bearer;
    Instant start, end;

    @BeforeEach
    void setup() {
        TestSchema.create(db);
        var issued = pairing.createInstallation();
        installation = issued.installation_id();
        bearer = "Bearer " + issued.installation_secret();
        vehicle = UUID.randomUUID(); user = UUID.randomUUID(); trip = UUID.randomUUID();
        db.update("INSERT INTO vehicles (id,name,created_by,created_at,updated_at) VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                vehicle.toString(), "C04 car", user.toString());
        db.update("INSERT INTO group_members (vehicle_id,user_id,role,created_at) VALUES (?,?,?,CURRENT_TIMESTAMP)",
                vehicle.toString(), user.toString(), "OWNER");
        db.update("UPDATE vehicle_installations SET vehicle_id=? WHERE id=?", vehicle.toString(), installation.toString());
        start = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
        end = start.plusSeconds(50);
        interval = home.start(bearer, null, new VehicleHomeDtos.IntervalStartRequest(trip.toString(), start, "manual_app")).id();
        var challenge = home.challenge(bearer, new VehicleHomeDtos.ChallengeRequest(null, "c04-challenge", interval));
        consent = candidates.consent(jwt(user), new TripUserCandidateDtos.MobileConsentRequest(vehicle, installation,
                interval, challenge.challengeId(), challenge.proof(), "c04-consent")).candidateId();
        candidates.verify(bearer, new TripUserCandidateDtos.VerifyRequest(interval, consent, "c04-verify"));
        // Fixture represents a grant issued before recording began, with the preparation interval already allocated.
        db.update("UPDATE trip_user_candidates SET updated_at=? WHERE id=?",
                TimeUtils.toLocalDateTime(start.minusSeconds(1)), consent.toString());
        when(decoder.decode("owner")).thenReturn(jwt(user));
        when(decoder.decode("other")).thenReturn(jwt(UUID.randomUUID()));
    }

    @Test
    void retriesCreateOneTripAndOneInboxAndRejectOtherUsers() throws Exception {
        Upload body = body(1, consent);
        Synced first = service.upload(bearer, trip, body);
        for (int i = 0; i < 10; i++) assertEquals(first, service.upload(bearer, trip, body));
        assertEquals(1, count("trip_summaries"));
        Summary summary = service.summary(bearer, trip, consent);
        assertEquals(0, summary.fuelEfficiencyKmPerLiter().value().compareTo(new BigDecimal("15")));
        assertEquals(0, summary.estimatedCost().value().compareTo(new BigDecimal("6800")));
        assertEquals("finished", db.queryForObject("SELECT status FROM recording_intervals WHERE id=?", String.class, interval.toString()));
        assertStatus(403, () -> service.summary(bearer, trip, null));
        var delivered = service.deliver(bearer, trip, consent, new Deliver("mobile-inbox:" + trip, 1L));
        for (int i = 0; i < 10; i++) assertEquals(delivered,
                service.deliver(bearer, trip, consent, new Deliver("mobile-inbox:" + trip, 1L)));
        assertEquals(1, count("mobile_trip_inbox"));
        mvc.perform(get("/mobile/inbox").header("Authorization", "Bearer owner"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].tripId").value(trip.toString()));
        mvc.perform(get("/mobile/inbox").header("Authorization", "Bearer other"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(patch("/mobile/inbox/" + delivered.inboxItemId()).header("Authorization", "Bearer other")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Read(Instant.now()))))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/mobile/inbox/" + delivered.inboxItemId()).header("Authorization", "Bearer owner")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Read(Instant.now()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readAt").isNotEmpty());
    }

    @Test
    void revisionsAreConditionalAndInboxKeepsDeliveredSnapshot() {
        service.upload(bearer, trip, body(1, consent));
        var delivery = new Deliver("mobile-inbox:" + trip, 1L);
        service.deliver(bearer, trip, consent, delivery);
        Upload changed = change(body(1, consent), "\"distanceKm\":60", "\"distanceKm\":61");
        assertStatus(409, () -> service.upload(bearer, trip, changed));
        service.upload(bearer, trip, body(2, consent));
        assertEquals(1, service.inbox(user, 0).items().getFirst().tripRevision());
        assertStatus(409, () -> service.deliver(bearer, trip, consent, delivery));
        service.deliver(bearer, trip, consent, new Deliver("mobile-inbox:" + trip, 2L));
        assertEquals(2, service.inbox(user, 0).items().getFirst().tripRevision());
        assertStatus(409, () -> service.upload(bearer, trip, body(1, consent)));
        assertEquals(1, count("trip_summaries"));
        assertEquals(1, count("mobile_trip_inbox"));
    }

    @Test
    void anonymousAndMixedIntervalsCannotBeAssignedLater() {
        service.upload(bearer, trip, body(1, null));
        assertStatus(409, () -> service.deliver(bearer, trip, consent, new Deliver("mobile-inbox:" + trip, 1L)));
        assertStatus(409, () -> service.upload(bearer, trip, body(2, consent)));
        assertNull(db.queryForObject("SELECT user_id FROM trip_summaries", String.class));
    }

    @Test
    void removedMembershipRevokedConsentAndMixedSamplesAreRejected() {
        db.update("INSERT INTO recording_samples (interval_id,sequence,batch_id,observed_at,distance_km,fuel_status,received_at) VALUES (?,1,'anonymous',?,1,'unsupported',CURRENT_TIMESTAMP)",
                interval.toString(), TimeUtils.toLocalDateTime(start.plusSeconds(1)));
        assertStatus(403, () -> service.upload(bearer, trip, body(1, consent)));
        db.update("DELETE FROM recording_samples WHERE interval_id=?", interval.toString());
        service.upload(bearer, trip, body(1, consent));
        service.deliver(bearer, trip, consent, new Deliver("mobile-inbox:" + trip, 1L));
        db.update("DELETE FROM group_members WHERE vehicle_id=?", vehicle.toString());
        assertStatus(403, () -> service.summary(bearer, trip, consent));
        assertTrue(service.inbox(user, 0).items().isEmpty());
        db.update("INSERT INTO group_members (vehicle_id,user_id,role,created_at) VALUES (?,?,?,CURRENT_TIMESTAMP)", vehicle.toString(), user.toString(), "MEMBER");
        candidates.revokeByInstallation(bearer, consent);
        assertStatus(403, () -> service.upload(bearer, trip, body(2, consent)));
    }

    @Test
    void concurrentRetriesHaveOneResult() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> service.upload(bearer, trip, body(1, consent)));
            var b = executor.submit(() -> service.upload(bearer, trip, body(1, consent)));
            assertEquals(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, count("trip_summaries"));
    }

    @Test
    void historicalGrantWorksOnlyForItsOriginalIntervalAndBoundaries() {
        // Offline upload after grant expiry is still scoped to the server-verified original interval.
        db.update("UPDATE trip_user_candidates SET verification_expires_at=?,expires_at=? WHERE id=?",
                TimeUtils.toLocalDateTime(end),
                TimeUtils.toLocalDateTime(end), consent.toString());
        service.upload(bearer, trip, body(1, consent));
        Upload changed = change(body(2, consent), start.toString(), start.minusSeconds(1).toString());
        assertStatus(409, () -> service.upload(bearer, trip, changed));
        UUID unrelated = UUID.randomUUID();
        Upload otherTrip = change(body(1, consent), trip.toString(), unrelated.toString());
        assertStatus(403, () -> service.upload(bearer, unrelated, otherTrip));
    }

    @Test
    void firstOdometerBaselineIsNotAMissingRecording() {
        db.update("INSERT INTO recording_samples (interval_id,sequence,batch_id,observed_at,missing_reason,fuel_status,received_at) VALUES (?,1,'baseline',?,'baseline_required','unsupported',CURRENT_TIMESTAMP)",
                interval.toString(), TimeUtils.toLocalDateTime(start.plusSeconds(1)));
        service.upload(bearer, trip, body(1, null));
        assertFalse(service.summary(bearer, trip, null).quality().missing());
        db.update("UPDATE recording_samples SET sequence=2 WHERE interval_id=?", interval.toString());
        assertStatus(409, () -> service.upload(bearer, trip, body(2, null)));
    }

    @Test
    void missingSamplesCannotBeReportedAsComplete() {
        db.update("INSERT INTO recording_samples (interval_id,sequence,batch_id,observed_at,missing_reason,fuel_status,received_at) VALUES (?,1,'missing',?,'odometer_reset','unsupported',CURRENT_TIMESTAMP)",
                interval.toString(), TimeUtils.toLocalDateTime(start.plusSeconds(1)));
        assertStatus(409, () -> service.upload(bearer, trip, body(1, null)));
        var partial = change(body(1, null), "\"missing\":false", "\"missing\":true");
        service.upload(bearer, trip, partial);
        Summary result = service.summary(bearer, trip, null);
        assertEquals("partial_recording", result.distanceKm().reason());
        assertNull(result.estimatedCost().value());
    }

    @Test
    void verificationAfterRecordingStartCannotClaimPastTrip() {
        db.update("UPDATE trip_user_candidates SET updated_at=? WHERE id=?",
                TimeUtils.toLocalDateTime(start.plusSeconds(1)), consent.toString());
        assertStatus(403, () -> service.upload(bearer, trip, body(1, consent)));
        assertEquals(0, count("trip_summaries"));
    }

    @Test
    void calculatorKeepsDistanceWhenFuelOrPriceIsUnavailable() {
        for (String flag : new String[] {"missing", "refueled", "ownerChanged", "endEstimated"}) {
            Summary result = TripSummaryService.calculate(change(body(1, null), "\"" + flag + "\":false", "\"" + flag + "\":true"), Instant.now());
            assertEquals("available", result.distanceKm().status());
            assertNull(result.fuelEfficiencyKmPerLiter().value());
            assertNull(result.estimatedCost().value());
        }
        Summary zero = TripSummaryService.calculate(change(body(1, null), "\"endFuelLiters\":36", "\"endFuelLiters\":40"), Instant.now());
        assertEquals("non_positive_consumption", zero.estimatedCost().reason());
        Summary noPrice = TripSummaryService.calculate(change(body(1, null), "\"priceValid\":true", "\"priceValid\":false"), Instant.now());
        assertEquals("available", noPrice.fuelConsumedLiters().status());
        assertNull(noPrice.estimatedCost().value());
    }

    @Test
    void electricCostUsesEnergyAndPreservesMissingPriceAndChargingGuards() {
        Upload base = new Upload(trip, 1L, "manual_app", null, start, end, false,
                new BigDecimal("60"), true, null, null, false, false, false, false,
                new BigDecimal("350"), "KRW", "confirmed-charge:reference", start.minusSeconds(60), true,
                new Ev(true, true, false, false, new BigDecimal("4")));
        Summary result = TripSummaryService.calculate(base, Instant.now());
        assertEquals(0, new BigDecimal("4").compareTo(result.energyConsumedKwh().value()));
        assertEquals(0, new BigDecimal("15").compareTo(result.energyEfficiencyKmPerKwh().value()));
        assertEquals(0, new BigDecimal("1400").compareTo(result.estimatedCost().value()));
        assertNull(result.fuelConsumedLiters().value());
        assertNull(TripSummaryService.calculate(change(base, "\"priceValid\":true", "\"priceValid\":false"), Instant.now()).estimatedCost().value());
        assertNull(TripSummaryService.calculate(change(base, "\"chargedDuringInterval\":false", "\"chargedDuringInterval\":true"), Instant.now()).estimatedCost().value());
    }

    @Test
    void httpValidatesInputAndRequiresAuthentication() throws Exception {
        mvc.perform(put("/recording-intervals/" + trip).header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body(1, consent))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        mvc.perform(get("/recording-intervals/" + trip + "/summary").header("Authorization", bearer)
                        .header("X-Trip-User-Candidate", consent))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estimatedCost.value").value(6800));
        mvc.perform(put("/recording-intervals/" + trip).header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/mobile/inbox")).andExpect(status().isUnauthorized());
        var other = pairing.createInstallation();
        assertStatus(404, () -> service.summary("Bearer " + other.installation_secret(), trip, consent));
    }

    @Test
    void slowTripCostUsesHistoricalRatesAndCanOnlyBeSavedOnce() {
        prepareCostTrip();
        slowStationsAndRates(3);
        assertEquals("LOCATION_REQUIRED",costs.get(bearer,trip).status());
        assertEquals(0,count("trip_ev_costs"));
        Instant before=Instant.now();
        EvCostResult saved=costs.calculate(bearer,trip,costLocation());
        assertEquals("SAVED",saved.status());
        assertEquals(0,new BigDecimal("1320").compareTo(saved.cost().amount()));
        assertEquals(0,new BigDecimal("330").compareTo(saved.cost().averageWonPerKwh()));
        assertEquals(3,saved.cost().sourceCount());
        assertEquals(end.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate(),saved.cost().tariffDate());
        assertTrue(saved.cost().calculatedAt().isAfter(before.minusSeconds(1)));
        assertNotEquals(saved.cost().tariffDate(),saved.cost().calculatedAt().atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate());
        db.update("UPDATE charging_tariffs SET won_per_kwh=999");
        assertEquals(saved,costs.calculate(bearer,trip,new EvCostLocation(38.0,128.0,Instant.now(),Instant.now(),"PLEOS_CURRENT_UPDATE","CONFIRMATION")));
        assertEquals(saved,costs.get(bearer,trip));
        assertEquals(1,count("trip_ev_costs"));
        Summary summary=service.summary(bearer,trip,null);
        assertEquals(saved.cost(),summary.savedEvCost());
        assertEquals(0,new BigDecimal("1320").compareTo(summary.estimatedCost().value()));
        assertStatus(409,()->service.upload(bearer,trip,evBody(2,new BigDecimal("5"),false)));
        String evidence=db.queryForObject("SELECT price_evidence FROM trip_ev_costs",String.class);
        assertFalse(evidence.contains("latitude"));assertFalse(evidence.contains("longitude"));
        assertFalse(json.writeValueAsString(saved).contains("stations"));
    }

    @Test
    void legacyMobileSnapshotWithoutEvMetricsStillLoads() {
        service.upload(bearer,trip,body(1,consent));
        service.deliver(bearer,trip,consent,new Deliver("mobile-inbox:"+trip,1L));
        @SuppressWarnings("unchecked")
        Map<String,Object> old=json.readValue(db.queryForObject("SELECT snapshot FROM mobile_trip_inbox",String.class),Map.class);
        old.remove("energyConsumedKwh");old.remove("energyEfficiencyKmPerKwh");old.remove("savedEvCost");
        db.update("UPDATE mobile_trip_inbox SET snapshot=?",json.writeValueAsString(old));
        assertEquals(0,new BigDecimal("6800").compareTo(service.inbox(user,0).items().getFirst().summary().estimatedCost().value()));
    }

    @Test
    void tripEndLocationSavesImmediatelyThroughHttpAndGetNeedsNoLocation() throws Exception {
        prepareCostTrip(51);slowStationsAndRates(3);
        var location=new EvCostLocation(37.0,127.0,Instant.now(),Instant.now(),"PLEOS_CURRENT_UPDATE","TRIP_END");
        mvc.perform(post("/recording-intervals/"+trip+"/ev-cost").header("Authorization",bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(location)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SAVED"))
                .andExpect(jsonPath("$.cost.amount").value(1320)).andExpect(jsonPath("$.cost.locationBasis").value("TRIP_END"))
                .andExpect(jsonPath("$.stations").doesNotExist());
        mvc.perform(get("/recording-intervals/"+trip+"/ev-cost").header("Authorization",bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cost.amount").value(1320));
        mvc.perform(get("/recording-intervals/"+trip+"/ev-cost")).andExpect(status().isUnauthorized());
        mvc.perform(post("/recording-intervals/"+trip+"/ev-cost").header("Authorization",bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"latitude\":999}"))
                .andExpect(status().isBadRequest());
        assertEquals(1,count("trip_ev_costs"));
    }

    @Test
    void historicalPriceSelectionIgnoresLaterVersionsAndRequiresThreeSlowOperators() {
        prepareCostTrip();slowStationsAndRates(2);
        assertEquals("INSUFFICIENT_HISTORICAL_SLOW_RATES",costs.calculate(bearer,trip,costLocation()).status());
        assertEquals(0,count("trip_ev_costs"));
        slowStationsAndRates(3);
        db.update("UPDATE charging_tariffs SET verified_at=?",TimeUtils.toLocalDateTime(end.plusSeconds(1)));
        assertEquals("INSUFFICIENT_HISTORICAL_SLOW_RATES",costs.calculate(bearer,trip,costLocation()).status());
        slowStationsAndRates(3);
        db.update("INSERT INTO charging_tariffs(operator_id,power_band,tariff_version,won_per_kwh,source_url,verified_at,valid_until) VALUES('PI','SLOW','later',999,'https://example.test',?,?)",
                TimeUtils.toLocalDateTime(end.plusSeconds(1)),TimeUtils.toLocalDateTime(Instant.now().plusSeconds(86400)));
        assertEquals(0,new BigDecimal("1320").compareTo(costs.calculate(bearer,trip,costLocation()).cost().amount()));
    }

    @Test
    void costIsOwnerBoundAndFirstSuccessfulConcurrentRequestWins() throws Exception {
        prepareCostTrip();slowStationsAndRates(3);
        var other=pairing.createInstallation();
        assertStatus(404,()->costs.calculate("Bearer "+other.installation_secret(),trip,costLocation()));
        db.update("UPDATE pairing_requests SET approved_user_id=? WHERE installation_id=?",UUID.randomUUID().toString(),installation.toString());
        assertStatus(403,()->costs.calculate(bearer,trip,costLocation()));
        db.update("UPDATE pairing_requests SET approved_user_id=? WHERE installation_id=?",user.toString(),installation.toString());
        assertStatus(400,()->costs.calculate(bearer,trip,new EvCostLocation(37.0,127.0,Instant.now(),Instant.now(),"PLEOS_CURRENT_UPDATE","TRIP_END")));
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try {
            var tasks=java.util.stream.IntStream.range(0,4).mapToObj(i->pool.submit(()->costs.calculate(bearer,trip,costLocation()))).toList();
            var first=tasks.getFirst().get(10,TimeUnit.SECONDS);
            for(var task:tasks) assertEquals(first,task.get(10,TimeUnit.SECONDS));
            assertEquals(1,count("trip_ev_costs"));
        } finally {pool.shutdownNow();}
    }

    @Test
    void existingLegacyAmountIsPreservedButNewClientPricesCannotBypassMean() {
        prepareCostTrip();
        service.upload(bearer,trip,evBody(2,new BigDecimal("4"),true));
        assertEquals("LOCATION_REQUIRED",costs.get(bearer,trip).status());
        assertNull(service.summary(bearer,trip,null).estimatedCost().value());
        db.update("UPDATE trip_summaries SET legacy_ev_cost_eligible=TRUE");
        var saved=costs.get(bearer,trip);
        assertEquals("LEGACY_SAVED_ESTIMATE",saved.cost().basis());
        assertEquals(0,new BigDecimal("1400").compareTo(saved.cost().amount()));
        slowStationsAndRates(3);
        assertEquals(saved,costs.calculate(bearer,trip,costLocation()));
    }

    @Test
    void mobileInboxSeesCostAddedLaterWithoutCreatingAnotherTrip() {
        prepareCostTrip();slowStationsAndRates(3);
        service.deliver(bearer,trip,null,new Deliver("mobile-inbox:"+trip,1L));
        assertNull(service.inbox(user,0).items().getFirst().summary().savedEvCost());
        var saved=costs.calculate(bearer,trip,costLocation());
        var item=service.inbox(user,0).items().getFirst();
        assertEquals(saved.cost(),item.summary().savedEvCost());
        assertEquals(1,count("mobile_trip_inbox"));assertEquals(1,count("trip_summaries"));
    }

    private void prepareCostTrip() {prepareCostTrip(2*86400+60);}
    private void prepareCostTrip(long agoSeconds) {
        start=Instant.now().minusSeconds(agoSeconds).truncatedTo(ChronoUnit.SECONDS);end=start.plusSeconds(50);
        db.update("UPDATE recording_intervals SET started_at=?,user_id=? WHERE id=?",TimeUtils.toLocalDateTime(start),user.toString(),interval.toString());
        db.update("INSERT INTO pairing_requests(id,installation_id,status,selected_vehicle_id,approved_user_id,updated_at) VALUES(?,?,'completed',?,?,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(),installation.toString(),vehicle.toString(),user.toString());
        service.upload(bearer,trip,evBody(1,new BigDecimal("4"),false));
    }
    private Upload evBody(long revision,BigDecimal energy,boolean clientPrice) {
        return new Upload(trip,revision,"manual_app",null,start,end,false,new BigDecimal("60"),true,null,null,false,false,false,false,
                clientPrice?new BigDecimal("350"):null,clientPrice?"KRW":null,clientPrice?"old":null,clientPrice?start:null,clientPrice,
                new Ev(true,true,false,false,energy));
    }
    private EvCostLocation costLocation() {return new EvCostLocation(37.0,127.0,Instant.now(),Instant.now(),"PLEOS_CURRENT_UPDATE","CONFIRMATION");}
    private void slowStationsAndRates(int operators) {
        db.update("DELETE FROM charger_catalog");
        db.update("UPDATE charging_tariffs SET verified_at=?,valid_until=?",TimeUtils.toLocalDateTime(start.minusSeconds(86400)),TimeUtils.toLocalDateTime(Instant.now().plusSeconds(86400)));
        String[] ids={"PI","ME","KP"};
        for(int i=0;i<3;i++) {
            db.update("UPDATE charging_tariffs SET won_per_kwh=? WHERE operator_id=? AND power_band='SLOW'",300+i*30,ids[i]);
            if(i<operators) db.update("INSERT INTO charger_catalog(station_id,charger_id,station_name,operator_id,latitude,longitude,connector_kw,observed_at) VALUES(?,?,?,?,?,?,?,?)",
                    "slow-"+i,"01","slow-"+i,ids[i],37.0+i*.001,127.0,7,TimeUtils.toLocalDateTime(Instant.now()));
        }
        db.update("INSERT INTO charger_catalog(station_id,charger_id,station_name,operator_id,latitude,longitude,connector_kw,observed_at) VALUES('fast','01','fast','CV',37,127,200,?)",TimeUtils.toLocalDateTime(Instant.now()));
    }

    private Upload body(long revision, UUID candidate) {
        return new Upload(trip, revision, "manual_app", candidate, start, end, false, new BigDecimal("60"), true,
                new BigDecimal("40"), new BigDecimal("36"), true, false, false, false,
                new BigDecimal("1700"), "KRW", "사용자 확인 단가", start.minusSeconds(60), true);
    }
    private Upload change(Upload body, String from, String to) {
        String source = json.writeValueAsString(body);
        assertTrue(source.contains(from), source);
        return json.readValue(source.replace(from, to), Upload.class);
    }
    private int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private static Jwt jwt(UUID user) {
        return Jwt.withTokenValue("test").header("alg", "ES256").subject(user.toString())
                .claim("role", "authenticated").claim("is_anonymous", false)
                .claim("user_metadata", Map.of("display_name", "Test driver")).build();
    }
    private static void assertStatus(int code, Runnable action) {
        assertEquals(code, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value());
    }
}
