package app.fuelog;

import app.fuelog.dto.MaintenanceDtos.*;
import app.fuelog.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
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
class MaintenanceTests {
    @Autowired JdbcTemplate db;
    @Autowired PairingService pairing;
    @Autowired MaintenanceService service;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    UUID owner, member, vehicle, installation, item;
    String bearer;
    @BeforeEach
    void setup() {
        TestSchema.create(db);
        owner=UUID.randomUUID(); member=UUID.randomUUID(); vehicle=UUID.randomUUID(); item=UUID.randomUUID();
        var issued=pairing.createInstallation(); installation=issued.installation_id(); bearer="Bearer "+issued.installation_secret();
        db.update("INSERT INTO vehicles (id,name,created_by,created_at,updated_at) VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",vehicle.toString(),"C06 car",owner.toString());
        db.update("UPDATE vehicle_installations SET vehicle_id=? WHERE id=?",vehicle.toString(),installation.toString());
        addMember(owner,"OWNER"); addMember(member,"MEMBER");
        when(decoder.decode("owner")).thenReturn(jwt(owner)); when(decoder.decode("member")).thenReturn(jwt(member));
    }

    @Test
    void emptySummaryAndDisconnectedInstallations() throws Exception {
        var empty=service.summary(bearer);
        assertEquals(0,empty.revision()); assertTrue(empty.items().isEmpty()); assertNull(empty.odometer().km());
        mvc.perform(get("/maintenance/summary").header("Authorization",bearer).param("vehicleId",UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray()).andExpect(header().string("Cache-Control","no-store"));
        db.update("UPDATE vehicle_installations SET vehicle_id=NULL WHERE id=?",installation.toString());
        denied(403,()->service.summary(bearer));
        mvc.perform(get("/maintenance/summary")).andExpect(status().isUnauthorized());
    }

    @Test
    void recalculatesBothAxesAndKeepsPrivateFieldsOutOfVehicleResponse() throws Exception {
        plan(item,"엔진오일",new BigDecimal("10000"),12);
        service.create(owner,vehicle,"oil",event(item,"2026-01-10","42000",true,Status.CONFIRMED));
        odo("48200.5",Instant.now().minusSeconds(5));
        var row=service.summary(bearer).items().getFirst();
        assertEquals(LocalDate.parse("2027-01-10"),row.nextDueDate());
        equal("52000",row.nextDueKm()); equal("3799.5",row.remainingKm());
        assertEquals(false,row.distanceDue());
        equal("10000",row.intervalKm()); assertEquals(12,row.intervalMonths());
        String response=mvc.perform(get("/maintenance/summary").header("Authorization",bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (String secret:List.of("cost","memo","shop","receiptUrl","private-memo","85000")) assertFalse(response.contains(secret),response);
        assertNull(service.events(member,vehicle,null).items().getFirst().details());
        assertEquals("private-memo",service.events(owner,vehicle,null).items().getFirst().details().memo());
    }

    @Test
    void vehicleCycleSurvivesUserDisconnectAndIsIsolatedFromOtherVehicles() throws Exception {
        plan(item,"타이어 점검",new BigDecimal("10000"),12);
        UUID personal=UUID.randomUUID();
        service.putPlan(member,vehicle,personal,null,new PlanInput("개인 알림",false,Basis.USER,null,new BigDecimal("1000"),null));
        db.update("UPDATE vehicle_installations SET personal_access_blocked=TRUE WHERE id=?",installation.toString());
        var common=service.summary(bearer).items();
        assertEquals(1,common.size());
        equal("10000",common.getFirst().intervalKm()); assertEquals(12,common.getFirst().intervalMonths());
        assertNull(common.getFirst().nextDueDate()); assertNull(common.getFirst().nextDueKm());
        mvc.perform(get("/maintenance/summary").header("Authorization",bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].intervalKm").value(10000))
                .andExpect(jsonPath("$.items[0].intervalMonths").value(12));
        var another=pairing.createInstallation(); UUID otherVehicle=UUID.randomUUID();
        db.update("INSERT INTO vehicles (id,name,created_by,created_at,updated_at) VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",otherVehicle.toString(),"다른 차량",owner.toString());
        db.update("INSERT INTO group_members (vehicle_id,user_id,role,created_at) VALUES (?,?,?,CURRENT_TIMESTAMP)",otherVehicle.toString(),owner.toString(),"OWNER");
        db.update("UPDATE vehicle_installations SET vehicle_id=? WHERE id=?",otherVehicle.toString(),another.installation_id().toString());
        service.putPlan(owner,otherVehicle,UUID.randomUUID(),null,new PlanInput("다른 차량 주기",true,Basis.USER,null,new BigDecimal("5000"),6));
        var other=service.summary("Bearer "+another.installation_secret()).items().getFirst();
        equal("5000",other.intervalKm()); assertEquals(6,other.intervalMonths());
        equal("10000",service.summary(bearer).items().getFirst().intervalKm());
    }

    @Test
    void duplicatePostIsStableEvenAfterEditAndDeletion() {
        plan(item,"oil",new BigDecimal("10000"),12);
        EventInput body=event(item,"2026-01-10","42000",true,Status.CONFIRMED);
        Event first=service.create(owner,vehicle,"same",body);
        long revision=service.summary(bearer).revision();
        for (int i=0;i<10;i++) assertEquals(first,service.create(owner,vehicle,"same",body));
        assertEquals(revision,service.summary(bearer).revision());
        assertEquals(1,count("maintenance_events")); assertEquals(1,count("maintenance_event_audit"));
        denied(409,()->service.create(owner,vehicle,"same",event(item,"2026-01-11","42000",true,Status.CONFIRMED)));
        var deleted=service.deleteEvent(owner,vehicle,first.id(),"1");
        assertEquals(deleted,service.deleteEvent(owner,vehicle,first.id(),"1"));
        assertEquals(first,service.create(owner,vehicle,"same",body));
        assertTrue(service.events(owner,vehicle,null).items().isEmpty());
        assertNull(service.summary(bearer).items().getFirst().nextDueDate());
        assertEquals(2,count("maintenance_event_audit"));
    }

    @Test
    void changingPastDatesItemsAndDeletingRecomputesFromConfirmedRecord() {
        plan(item,"oil",new BigDecimal("10000"),12);
        UUID second=UUID.randomUUID(); plan(second,"tires",new BigDecimal("20000"),24);
        Event older=service.create(owner,vehicle,"older",event(item,"2025-01-10","30000",true,Status.CONFIRMED));
        Event newer=service.create(owner,vehicle,"newer",event(item,"2026-01-10","42000",true,Status.CONFIRMED));
        service.create(owner,vehicle,"visit",event(item,"2026-11-01","80000",true,Status.VISIT_ESTIMATE));
        equal("52000",find(item).nextDueKm());
        service.patch(owner,vehicle,newer.id(),"1",json.readTree("{\"serviceDate\":\"2024-01-10\",\"odometerKm\":20000}"));
        equal("40000",find(item).nextDueKm());
        service.patch(owner,vehicle,older.id(),"1",json.readTree("{\"itemId\":\""+second+"\"}"));
        equal("30000",find(item).nextDueKm()); equal("50000",find(second).nextDueKm());
        service.deleteEvent(owner,vehicle,newer.id(),"2");
        assertNull(find(item).nextDueDate()); assertNull(find(item).nextDueKm());
        assertEquals(6,count("maintenance_event_audit"));
    }

    @Test
    void missingAxisIsNotFilledFromAnOlderRecordAndPlanDisablePreservesHistory() {
        plan(item,"oil",new BigDecimal("10000"),12);
        service.create(owner,vehicle,"old",event(item,"2025-01-10","42000",true,Status.CONFIRMED));
        Event latest=service.create(owner,vehicle,"km-only",event(item,null,"45000",true,Status.CONFIRMED));
        assertNull(find(item).nextDueDate()); equal("55000",find(item).nextDueKm());
        service.patch(owner,vehicle,latest.id(),"1",json.readTree("{\"serviceDate\":\"2026-11-01\",\"odometerKm\":null}"));
        assertNull(find(item).nextDueKm()); assertEquals(LocalDate.parse("2027-11-01"),find(item).nextDueDate());
        service.deletePlan(owner,vehicle,item,"1");
        assertTrue(service.summary(bearer).items().isEmpty()); assertEquals(2,count("maintenance_events"));
        service.putPlan(owner,vehicle,item,"2",new PlanInput("oil",true,Basis.USER,null,new BigDecimal("5000"),6));
        assertEquals(LocalDate.parse("2027-05-01"),find(item).nextDueDate());
    }

    @Test
    void privateRecordsAndDetailsUseSeparateAccessChecks() {
        plan(item,"shared",new BigDecimal("10000"),12);
        service.create(owner,vehicle,"shared",event(item,"2025-01-10","30000",true,Status.CONFIRMED));
        Event privateEvent=service.create(member,vehicle,"private",event(item,"2026-11-01","90000",false,Status.CONFIRMED));
        equal("40000",find(item).nextDueKm());
        assertEquals(1,service.events(owner,vehicle,null).items().size());
        assertEquals(2,service.events(member,vehicle,null).items().size());
        denied(403,()->service.patch(owner,vehicle,privateEvent.id(),"1",json.readTree("{\"memo\":\"not yours\"}")));
        UUID privatePlan=UUID.randomUUID();
        service.putPlan(member,vehicle,privatePlan,null,new PlanInput("personal",false,Basis.USER,null,new BigDecimal("1000"),null));
        assertEquals(1,service.plans(owner,vehicle).items().size());
        assertEquals(1,service.summary(bearer).items().size());
        denied(403,()->service.create(member,vehicle,"shared-denied",event(item,"2026-01-10","42000",true,Status.CONFIRMED)));
        UUID sharedId=service.events(owner,vehicle,null).items().getFirst().id();
        service.patch(owner,vehicle,sharedId,"1",json.readTree("{\"detailsShared\":true}"));
        var shared=service.events(member,vehicle,null).items().stream().filter(e->e.id().equals(sharedId)).findFirst().orElseThrow();
        assertNotNull(shared.details());
        db.update("DELETE FROM group_members WHERE vehicle_id=? AND user_id=?",vehicle.toString(),member.toString());
        denied(403,()->service.events(member,vehicle,null));
    }

    @Test
    void staleOdometerIsDisplayedButNotUsedForRemainingDistance() {
        plan(item,"oil",new BigDecimal("10000"),null);
        service.create(owner,vehicle,"base",event(item,"2026-01-10","42000",true,Status.CONFIRMED));
        Instant at=Instant.now().minus(Duration.ofDays(8)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        UUID sample=UUID.randomUUID();
        var input=sample("48200",at);
        var first=service.odometer(bearer,sample,input);
        assertEquals(first,service.odometer(bearer,sample,input));
        var stale=service.summary(bearer);
        assertEquals(at.toEpochMilli(),stale.odometer().observedAt()); assertEquals("stale",stale.odometer().status());
        equal("48200",stale.odometer().km()); assertNull(stale.items().getFirst().remainingKm()); assertNull(stale.items().getFirst().distanceDue());
        service.odometer(bearer,UUID.randomUUID(),new OdometerInput(null,Instant.now().toEpochMilli(),"km","PLEOS_VEHICLE_API",Quality.ERROR));
        equal("48200",service.summary(bearer).odometer().km());
        denied(409,()->service.odometer(bearer,sample,sample("48201",at)));
        denied(422,()->odo("47000",Instant.now()));
        odo("46000",at.minus(Duration.ofDays(1)));
        odo("53000",Instant.now());
        assertEquals(true,service.summary(bearer).items().getFirst().distanceDue());
        denied(400,()->odo("54000",Instant.now().plusSeconds(120)));
    }

    @Test
    void reachedConditionsRankBeforeFutureItemsWithoutCrossUnitPrediction() {
        UUID future=UUID.randomUUID(),distance=UUID.randomUUID();
        plan(item,"date",null,1); plan(distance,"distance",new BigDecimal("1000"),null); plan(future,"future",null,120);
        service.create(owner,vehicle,"date",event(item,LocalDate.now(ZoneOffset.UTC).minusMonths(2).toString(),null,true,Status.CONFIRMED));
        service.create(owner,vehicle,"dist",event(distance,"2026-01-01","10000",true,Status.CONFIRMED));
        service.create(owner,vehicle,"future",event(future,"2026-01-01",null,true,Status.CONFIRMED));
        odo("12000",Instant.now());
        var summary=service.summary(bearer);
        assertEquals(2,summary.items().size());
        assertTrue(summary.items().stream().anyMatch(e->e.id().equals(item) && Boolean.TRUE.equals(e.dateDue())));
        assertTrue(summary.items().stream().anyMatch(e->e.id().equals(distance) && Boolean.TRUE.equals(e.distanceDue())));
        assertFalse(summary.items().stream().anyMatch(e->e.id().equals(future)));
    }

    @Test
    void manufacturerBasisRequiresVerifiedApplicableDocumentAndExactCycle() {
        UUID evidence=UUID.randomUUID();
        PlanInput b=new PlanInput("oil",true,Basis.MANUFACTURER,evidence,new BigDecimal("10000"),12);
        denied(422,()->service.putPlan(owner,vehicle,item,null,b));
        db.update("INSERT INTO vehicle_maintenance_profiles (vehicle_id,manufacturer,model,model_year,verified_at,verified_by) VALUES (?,'maker','model',2026,CURRENT_TIMESTAMP,'trusted-test')",vehicle.toString());
        db.update("INSERT INTO maintenance_manufacturer_evidence (id,document_url,document_title,manufacturer,model,model_year_from,model_year_to,service_name,scope_text,interval_km,interval_months,verified_at,verified_by) VALUES (?,'https://example.test/manual','manual','maker','model',2025,2027,'oil','verified normal service',10000,12,CURRENT_TIMESTAMP,'trusted-test')",evidence.toString());
        service.putPlan(owner,vehicle,item,null,b);
        assertEquals(Basis.MANUFACTURER,service.summary(bearer).items().getFirst().basis());
        denied(422,()->service.putPlan(owner,vehicle,item,"1",new PlanInput("oil",true,Basis.MANUFACTURER,evidence,new BigDecimal("5000"),12)));
        db.update("UPDATE maintenance_manufacturer_evidence SET revoked_at=CURRENT_TIMESTAMP WHERE id=?",evidence.toString());
        assertTrue(service.summary(bearer).items().isEmpty()); assertFalse(find(item).evidence().verified());
    }

    @Test
    void concurrentCreationAndStaleWritesAreSerialized() throws Exception {
        plan(item,"oil",new BigDecimal("10000"),12);
        var b=event(item,"2026-01-10","42000",true,Status.CONFIRMED);
        Event created;
        try (var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->service.create(owner,vehicle,"concurrent",b));
            var c=executor.submit(()->service.create(owner,vehicle,"concurrent",b));
            created=a.get(5,TimeUnit.SECONDS); assertEquals(created,c.get(5,TimeUnit.SECONDS));
        }
        assertEquals(1,count("maintenance_events"));
        denied(428,()->service.patch(owner,vehicle,created.id(),null,json.readTree("{\"memo\":\"x\"}")));
        service.patch(owner,vehicle,created.id(),"\"1\"",json.readTree("{\"memo\":\"changed\"}"));
        denied(412,()->service.patch(owner,vehicle,created.id(),"1",json.readTree("{\"memo\":\"stale\"}")));
        denied(428,()->service.putPlan(owner,vehicle,item,null,new PlanInput("oil",true,Basis.USER,null,new BigDecimal("5000"),12)));
        assertEquals(2,count("maintenance_event_audit"));
    }

    @Test
    void httpSupportsEtagAndRejectsInvalidPatchUnitsAndOtherUsers() throws Exception {
        String root="/mobile/vehicles/"+vehicle;
        mvc.perform(put(root+"/maintenance-plans/"+item).header("Authorization","Bearer owner")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new PlanInput("oil",true,Basis.USER,null,new BigDecimal("10000"),12))))
                .andExpect(status().isOk()).andExpect(header().string("ETag","\"1\""));
        Event created=service.create(owner,vehicle,"http",event(item,"2026-01-10","42000",true,Status.CONFIRMED));
        mvc.perform(patch(root+"/maintenance-events/"+created.id()).header("Authorization","Bearer owner").header("If-Match","1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"BAD\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/vehicle-odometer/samples/"+UUID.randomUUID()).header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"km\":1000,\"observedAt\":1,\"unit\":\"mile\",\"source\":\"PLEOS_VEHICLE_API\",\"quality\":\"VALID\"}"))
                .andExpect(status().isBadRequest());
        denied(403,()->service.plans(UUID.randomUUID(),vehicle));
        mvc.perform(get(root+"/maintenance-events")).andExpect(status().isUnauthorized());
        assertEquals(1,count("maintenance_event_audit"));
    }

    @Test
    void concurrentEditsWithSameRevisionHaveOnlyOneWinner() throws Exception {
        plan(item,"oil",new BigDecimal("10000"),12);
        Event created=service.create(owner,vehicle,"edit",event(item,"2026-01-10","42000",true,Status.CONFIRMED));
        try (var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->tryEdit(created.id(),"a"));
            var b=executor.submit(()->tryEdit(created.id(),"b"));
            var codes=new ArrayList<>(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS)));
            Collections.sort(codes); assertEquals(List.of(200,412),codes);
        }
        assertEquals(2,count("maintenance_event_audit"));
    }

    @Test
    void swaggerHasDescriptionsAndExamplesForAllMaintenanceOperations() throws Exception {
        var doc=com.jayway.jsonpath.JsonPath.parse(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String base="/mobile/vehicles/{vehicleId}";
        String[][] paths={{"/maintenance/summary","get"},{"/vehicle-odometer/samples/{sampleId}","put"},
                {base+"/maintenance-events","get"},{base+"/maintenance-events","post"},
                {base+"/maintenance-events/{eventId}","patch"},{base+"/maintenance-events/{eventId}","delete"},
                {base+"/maintenance-plans","get"},{base+"/maintenance-plans/{itemId}","put"},{base+"/maintenance-plans/{itemId}","delete"}};
        for (var operation:paths) {
            String p="$['paths']['"+operation[0]+"']['"+operation[1]+"']";
            assertFalse(((String)doc.read(p+"['summary']")).isBlank());
            assertFalse(((String)doc.read(p+"['description']")).isBlank());
            Map<String,Object> content=doc.read(p+"['responses']['200']['content']['application/json']");
            assertTrue(content.containsKey("example") || content.containsKey("examples"));
        }
    }

    private int tryEdit(UUID event,String memo) {
        try { service.patch(owner,vehicle,event,"1",json.readTree("{\"memo\":\""+memo+"\"}")); return 200; }
        catch (ResponseStatusException ex) { return ex.getStatusCode().value(); }
    }

    @Test
    void overduePlanWaitsForExplicitConfirmationThenCreatesExactlyOneSuccessor() {
        plan(item,"oil",new BigDecimal("1000"),1);
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        service.create(owner,vehicle,"baseline",event(item,today.minusMonths(3).toString(),"10000",true,Status.CONFIRMED));
        Plan before=find(item);
        service.create(owner,vehicle,"visit",event(item,today.toString(),"12000",true,Status.VISIT_ESTIMATE));
        assertEquals(before.nextDueDate(),find(item).nextDueDate());
        assertEquals(1,count("maintenance_plans"));
        CompletionInput input=new CompletionInput(true,today,new BigDecimal("12000"));
        Completion done=service.complete(owner,vehicle,item,"1","complete",input);
        assertFalse(done.completedPlan().active());
        assertEquals(PlanStatus.COMPLETE,done.completedPlan().status());
        assertNotNull(done.completedPlan().completedAt()); assertNotNull(done.completedPlan().deletedAt());
        assertEquals(before.nextDueDate(),done.completedPlan().nextDueDate());
        Plan next=done.nextPlan();
        assertNotEquals(item,next.id()); assertEquals(next.id(),done.completedPlan().nextPlanId());
        assertTrue(next.active()); assertEquals("oil",next.name());
        equal("1000",next.intervalKm()); assertEquals(1,next.intervalMonths());
        assertEquals(today.plusMonths(1),next.nextDueDate()); equal("13000",next.nextDueKm());
        assertEquals(done,service.complete(owner,vehicle,item,"1","complete",input));
        assertEquals(2,count("maintenance_plans")); assertEquals(3,count("maintenance_events"));
        assertEquals(next.id(),service.summary(bearer).items().getFirst().id());
        denied(409,()->service.putPlan(owner,vehicle,item,"2",new PlanInput("oil",true,Basis.USER,null,new BigDecimal("1000"),1)));
        denied(409,()->service.complete(owner,vehicle,item,"2","another",input));
        denied(409,()->service.complete(owner,vehicle,item,"1","complete",new CompletionInput(true,today,new BigDecimal("12001"))));
        denied(409,()->service.deleteEvent(owner,vehicle,done.event().id(),"1"));
        denied(409,()->service.patch(owner,vehicle,done.event().id(),"1",json.readTree("{\"status\":\"VISIT_ESTIMATE\"}")));
        service.putPlan(owner,vehicle,next.id(),"1",new PlanInput("oil",true,Basis.USER,null,new BigDecimal("2000"),2));
        assertEquals(today.plusMonths(2),find(next.id()).nextDueDate()); equal("14000",find(next.id()).nextDueKm());
        Completion again=service.complete(owner,vehicle,next.id(),"2","second-cycle",
                new CompletionInput(true,today,new BigDecimal("13000")));
        equal("15000",again.nextPlan().nextDueKm()); assertEquals(3,count("maintenance_plans"));
    }

    @Test
    void completionRequiresConfirmationPermissionRevisionAndBothConfiguredAxes() throws Exception {
        plan(item,"oil",new BigDecimal("1000"),1);
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        CompletionInput input=new CompletionInput(true,today,new BigDecimal("10000"));
        denied(403,()->service.complete(member,vehicle,item,"1","no-role",input));
        denied(428,()->service.complete(owner,vehicle,item,null,"no-version",input));
        denied(412,()->service.complete(owner,vehicle,item,"2","stale",input));
        denied(400,()->service.complete(owner,vehicle,item,"1","false",new CompletionInput(false,today,new BigDecimal("10000"))));
        denied(400,()->service.complete(owner,vehicle,item,"1","missing-km",new CompletionInput(true,today,null)));
        denied(400,()->service.complete(owner,vehicle,item,"1","future",new CompletionInput(true,today.plusDays(1),new BigDecimal("10000"))));
        assertEquals(0,count("maintenance_events")); assertEquals(1,count("maintenance_plans"));
        String path="/maintenance/plans/"+item+"/complete";
        mvc.perform(post(path).header("Authorization",bearer).header("If-Match","1").header("Idempotency-Key","http")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input))).andExpect(status().isForbidden());
        authenticateDevice(owner);
        mvc.perform(post(path).header("Authorization",bearer).header("If-Match","1").header("Idempotency-Key","http")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.completedPlan.active").value(false))
                .andExpect(jsonPath("$.completedPlan.deletedAt").isNotEmpty()).andExpect(jsonPath("$.nextPlan.active").value(true));
        UUID personal=UUID.randomUUID();
        service.putPlan(member,vehicle,personal,null,new PlanInput("personal",false,Basis.USER,null,null,1));
        mvc.perform(post("/mobile/vehicles/"+vehicle+"/maintenance-plans/"+personal+"/complete")
                .header("Authorization","Bearer member").header("If-Match","1").header("Idempotency-Key","personal")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new CompletionInput(true,today,null))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextPlan.shared").value(false));
    }

    @Test
    void simultaneousCompletionRetriesCreateOneNextPlan() throws Exception {
        plan(item,"oil",null,1);
        var input=new CompletionInput(true,LocalDate.now(ZoneId.of("Asia/Seoul")),null);
        try (var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->service.complete(owner,vehicle,item,"1","same-complete",input));
            var b=executor.submit(()->service.complete(owner,vehicle,item,"1","same-complete",input));
            assertEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
        }
        assertEquals(2,count("maintenance_plans")); assertEquals(1,count("maintenance_events"));
    }

    @Test
    void distanceDangerStartsAtExactlyOneAndHalfCyclesAndIgnoresStaleSamples() {
        plan(item,"oil",new BigDecimal("1000"),null);
        assertEquals(RiskLevel.UNKNOWN,find(item).riskLevel());
        service.create(owner,vehicle,"base",event(item,null,"10000",true,Status.CONFIRMED));
        odo("10999",Instant.now().minusSeconds(3));
        assertEquals(RiskLevel.NORMAL,find(item).riskLevel());
        odo("11499.999",Instant.now().minusSeconds(2));
        assertEquals(RiskLevel.OVERDUE,find(item).riskLevel());
        odo("11500",Instant.now().minusSeconds(1));
        assertEquals(RiskLevel.DANGER,find(item).riskLevel());
        assertEquals(RiskLevel.DANGER,service.summary(bearer).items().getFirst().riskLevel());
        db.update("UPDATE vehicle_odometer_samples SET observed_at=DATEADD('DAY',-8,CURRENT_TIMESTAMP)");
        assertEquals(RiskLevel.UNKNOWN,find(item).riskLevel());
        assertEquals(RiskLevel.UNKNOWN,service.summary(bearer).items().getFirst().riskLevel());
    }

    @Test
    void dateDangerDoesNotCreateAnotherPlan() {
        plan(item,"oil",null,1);
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate last=today.minusMonths(3);
        service.create(owner,vehicle,"base",event(item,last.toString(),null,true,Status.CONFIRMED));
        assertEquals(RiskLevel.DANGER,find(item).riskLevel());
        service.patch(owner,vehicle,service.events(owner,vehicle,null).items().getFirst().id(),"1",
                json.readTree("{\"serviceDate\":\""+today+"\"}"));
        assertEquals(RiskLevel.NORMAL,find(item).riskLevel());
        assertEquals(today.plusMonths(1),find(item).nextDueDate()); assertEquals(1,count("maintenance_plans"));
    }

    private void plan(UUID id,String name,BigDecimal km,Integer months) { service.putPlan(owner,vehicle,id,null,new PlanInput(name,true,Basis.USER,null,km,months)); }
    @Test
    void vehicleOwnerCanEditCyclesWhileAnonymousMembersAndManagersOnlyRead() throws Exception {
        plan(item,"타이어",new BigDecimal("10000"),12);
        assertFalse(service.summary(bearer).canEdit());
        denied(403,()->service.vehiclePlans(bearer));
        authenticateDevice(owner);
        assertTrue(service.summary(bearer).canEdit());
        assertEquals(owner,service.summary(bearer).editorSubjectId());
        VehiclePlanInput edit=new VehiclePlanInput(new PlanInput("타이어",true,Basis.USER,null,new BigDecimal("8000"),6),
                LocalDate.now(ZoneId.of("Asia/Seoul")).minusMonths(1),new BigDecimal("45000"),"vehicle-edit");
        Plan updated=service.putVehiclePlan(bearer,item,"1",edit);
        equal("8000",updated.intervalKm()); assertEquals(6,updated.intervalMonths());
        assertEquals(edit.lastServiceDate().plusMonths(6),updated.nextDueDate());
        mvc.perform(get("/maintenance/plans").header("Authorization",bearer)).andExpect(status().isOk());
        denied(412,()->service.putVehiclePlan(bearer,item,"1",edit));
        assertEquals(1,count("maintenance_events"));
        UUID newItem=UUID.randomUUID();
        VehiclePlanInput future=new VehiclePlanInput(new PlanInput("잘못된 기록",true,Basis.USER,null,null,12),
                LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1),null,"future-edit");
        denied(400,()->service.putVehiclePlan(bearer,newItem,"0",future));
        assertTrue(service.vehiclePlans(bearer).items().stream().noneMatch(p->p.id().equals(newItem)));
        authenticateDevice(member);
        assertFalse(service.summary(bearer).canEdit());
        assertEquals(1,service.summary(bearer).items().size());
        denied(403,()->service.putVehiclePlan(bearer,item,"2",edit));
        db.update("UPDATE group_members SET role='MANAGER' WHERE vehicle_id=? AND user_id=?",vehicle.toString(),member.toString());
        assertFalse(service.summary(bearer).canEdit());
        denied(403,()->service.deleteVehiclePlan(bearer,item,"2"));
        denied(403,()->service.putPlan(member,vehicle,item,"2",edit.plan()));
        authenticateDevice(owner);
        service.deleteVehiclePlan(bearer,item,"2");
        assertTrue(service.summary(bearer).items().isEmpty());
        assertEquals(1,count("maintenance_events"));
    }

    private void authenticateDevice(UUID user) {
        db.update("DELETE FROM pairing_requests WHERE installation_id=?",installation.toString());
        db.update("INSERT INTO pairing_requests(id,installation_id,status,selected_vehicle_id,approved_user_id,updated_at) VALUES(?,?,'completed',?,?,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(),installation.toString(),vehicle.toString(),user.toString());
    }

    private Plan find(UUID id) { return service.plans(owner,vehicle).items().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow(); }
    private EventInput event(UUID id,String date,String km,boolean shared,Status status) {
        return new EventInput(id,date==null ? null : LocalDate.parse(date),km==null ? null : new BigDecimal(km),status,shared,false,new BigDecimal("85000"),"KRW","private-memo","private-shop","https://example.test/private-receipt");
    }
    private static OdometerInput sample(String km,Instant at) { return new OdometerInput(new BigDecimal(km),at.toEpochMilli(),"km","PLEOS_VEHICLE_API",Quality.VALID); }
    private void odo(String km,Instant at) { service.odometer(bearer,UUID.randomUUID(),sample(km,at)); }
    private void addMember(UUID user,String role) { db.update("INSERT INTO group_members (vehicle_id,user_id,role,created_at) VALUES (?,?,?,CURRENT_TIMESTAMP)",vehicle.toString(),user.toString(),role); }
    private int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    private static void equal(String expected,BigDecimal actual) { assertNotNull(actual); assertEquals(0,new BigDecimal(expected).compareTo(actual)); }
    private static void denied(int status,Runnable action) { assertEquals(status,assertThrows(ResponseStatusException.class,action::run).getStatusCode().value()); }
    private static Jwt jwt(UUID user) { return Jwt.withTokenValue("test").header("alg","ES256").subject(user.toString()).claim("role","authenticated").claim("is_anonymous",false).build(); }
}
