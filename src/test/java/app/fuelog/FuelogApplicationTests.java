package app.fuelog;

import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FuelogApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointChecksDatabaseWithoutExposingDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void managementEnvironmentIsNotExposed() throws Exception {
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isNotFound());
    }

    @Test
    void openApiDocumentContainsApiMetadata() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Fuelog API"))
                .andExpect(jsonPath("$.info.version").value("v1"));
    }

    @Test
    void openApiSeparatesDeviceAuthenticationAndModelsAndDeprecatesReset() throws Exception {
        var doc = JsonPath.parse(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        // 같은 이름의 Java record가 서로 다른 응답 구조를 덮어쓰지 않아야 한다.
        Map<String, Object> trip = doc.read("$.components.schemas.TripSummarySummary.properties");
        Map<String, Object> maintenance = doc.read("$.components.schemas.MaintenanceSummary.properties");
        assertTrue(trip.containsKey("fuelEfficiencyKmPerLiter"));
        assertFalse(trip.containsKey("odometer"));
        assertTrue(maintenance.containsKey("odometer"));
        assertFalse(maintenance.containsKey("fuelEfficiencyKmPerLiter"));

        for (var path : List.of("/connection/requests", "/vehicle/home",
                "/trip-user/candidates", "/recording-intervals/{tripId}/summary")) {
            String method = path.endsWith("requests") || path.endsWith("candidates") ? "post" : "get";
            List<Map<String, Object>> security = doc.read("$['paths']['" + path + "']['" + method + "']['security']");
            assertTrue(security.stream().anyMatch(s -> s.containsKey("installationBearer")), path);
        }
        Map<String, Object> reset = doc.read("$['paths']['/connection/reset']['post']");
        assertTrue(Boolean.TRUE.equals(reset.get("deprecated")));
        Map<String, Object> responses = doc.read("$['paths']['/connection/reset']['post']['responses']");
        assertFalse(responses.containsKey("200"));
        assertTrue(responses.containsKey("409"));
    }

    @Test
    void pairingOpenApiExplainsEveryOperationAndShowsInputsAndOutputs() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var doc = JsonPath.parse(json);
        String[][] operations = {
                {"/auth/me", "get", "200"},
                {"/installations", "post", "201"},
                {"/installations/me", "get", "200"},
                {"/pairing-requests", "post", "201"},
                {"/pairing-requests/current", "get", "200"},
                {"/pairing-requests/preview", "post", "200"},
                {"/pairing-requests/{id}/intent", "post", "200"},
                {"/pairing-requests/{id}/confirm", "post", "200"},
                {"/pairing-requests/{id}", "get", "200"},
                {"/pairing-requests/{id}/cancel", "post", "200"}
        };
        for (String[] operation : operations) {
            String root = "$['paths']['" + operation[0] + "']['" + operation[1] + "']";
            assertFalse(((String) doc.read(root + "['summary']")).isBlank(), operation[0]);
            assertFalse(((String) doc.read(root + "['description']")).isBlank(), operation[0]);
            Map<String, Object> content = doc.read(root + "['responses']['" + operation[2]
                    + "']['content']['application/json']");
            assertTrue(content.containsKey("examples") || content.containsKey("example"),
                    operation[0] + " response example: " + content);
        }
        Map<String, Object> previewBody = doc.read("$['paths']['/pairing-requests/preview']['post']['requestBody']['content']['application/json']");
        Map<String, Object> intentBody = doc.read("$['paths']['/pairing-requests/{id}/intent']['post']['requestBody']['content']['application/json']");
        Map<String, Object> confirmBody = doc.read("$['paths']['/pairing-requests/{id}/confirm']['post']['requestBody']['content']['application/json']");
        assertTrue(previewBody.containsKey("examples") || previewBody.containsKey("example"));
        assertTrue(intentBody.containsKey("examples") || intentBody.containsKey("example"));
        assertTrue(confirmBody.containsKey("examples") || confirmBody.containsKey("example"));
        List<Map<String, Object>> issueParameters = doc.read("$['paths']['/pairing-requests']['post']['parameters']");
        assertTrue(issueParameters.stream().anyMatch(p -> "X-Installation-Secret".equals(p.get("name"))
                && Boolean.TRUE.equals(p.get("required"))));
        assertTrue(issueParameters.stream().anyMatch(p -> "Idempotency-Key".equals(p.get("name"))));
        Map<String, Object> proof = doc.read("$['components']['schemas']['PairingQrPayload']['properties']['proof']");
        assertFalse(((String) proof.get("description")).isBlank());
        assertTrue(proof.containsKey("example"));
        List<Map<String, Object>> meSecurity = doc.read("$['paths']['/auth/me']['get']['security']");
        assertTrue(meSecurity.stream().anyMatch(s -> s.containsKey("supabaseBearer")));
    }

    @Test
    void vehicleHomeOpenApiDescribesInputsAndOutputs() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var doc = JsonPath.parse(json);
        String[][] operations = {
                {"/vehicle/home", "get"},
                {"/trip-user/challenges", "post"},
                {"/trip-user/candidates", "post"},
                {"/trip-user/verify", "post"},
                {"/trip-user/revoke", "post"},
                {"/trip-user/events", "get"},
                {"/mobile/trip-user/consent", "post"},
                {"/mobile/trip-user/approve", "post"},
                {"/trip-user/session", "post"},
                {"/trip-user/session", "delete"},
                {"/recording-intervals", "post"},
                {"/recording-intervals/{id}/samples", "post"},
                {"/recording-intervals/{id}/finish", "post"},
                {"/sync/status", "get"}
        };
        for (String[] operation : operations) {
            String root = "$['paths']['" + operation[0] + "']['" + operation[1] + "']";
            assertFalse(((String) doc.read(root + "['summary']")).isBlank(), operation[0]);
            assertFalse(((String) doc.read(root + "['description']")).isBlank(), operation[0]);
            Map<String, Object> response = doc.read(root + "['responses']['200']['content']['application/json']");
            assertTrue(response.containsKey("examples") || response.containsKey("example"), operation[0]);
        }
        for (String path : List.of("/trip-user/challenges", "/trip-user/candidates", "/trip-user/verify",
                "/trip-user/revoke", "/mobile/trip-user/consent", "/mobile/trip-user/approve",
                "/trip-user/session", "/recording-intervals",
                "/recording-intervals/{id}/samples", "/recording-intervals/{id}/finish")) {
            Map<String, Object> body = doc.read("$['paths']['" + path + "']['post']['requestBody']['content']['application/json']");
            assertTrue(body.containsKey("examples") || body.containsKey("example"), path);
        }
    }

    @Test
    void swaggerUiIsServed() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));

        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"));
    }

    @Test
    void tripSummaryOpenApiDescribesAllOperations() throws Exception {
        var doc = JsonPath.parse(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String[][] operations = {{"/recording-intervals/{tripId}", "put"},
                {"/recording-intervals/{tripId}/summary", "get"},
                {"/mobile-inbox/trips/{tripId}", "put"}, {"/mobile/inbox", "get"},
                {"/mobile/inbox/{itemId}", "patch"}};
        for (var operation : operations) {
            String path = "$['paths']['" + operation[0] + "']['" + operation[1] + "']";
            assertFalse(((String) doc.read(path + "['summary']")).isBlank());
            assertFalse(((String) doc.read(path + "['description']")).isBlank());
            Map<String, Object> response = doc.read(path + "['responses']['200']['content']['application/json']");
            assertTrue(response.containsKey("examples") || response.containsKey("example"));
        }
    }
}
