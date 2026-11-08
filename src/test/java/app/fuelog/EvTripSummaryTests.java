package app.fuelog;
import app.fuelog.dto.TripSummaryDtos.*;
import app.fuelog.service.TripSummaryService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
class EvTripSummaryTests {
    @Test void energySummaryAcceptsVehicleContractAndRejectsMixedOrZeroConsumption() {
        var json=new ObjectMapper();
        String body="{\"tripId\":\"11111111-1111-4111-8111-111111111111\",\"revision\":1,\"source\":\"manual_app\",\"startedAt\":\"2026-11-08T00:00:00Z\",\"endedAt\":\"2026-11-08T01:00:00Z\",\"endEstimated\":false,\"distanceKm\":60,\"distanceValid\":true,\"fuelEndpointsValid\":false,\"missing\":false,\"refueled\":false,\"ownerChanged\":false,\"priceValid\":false,\"ev\":{\"detected\":true,\"endpointsValid\":true,\"missing\":false,\"chargedDuringInterval\":false,\"consumedKwh\":4}}";
        Summary result=TripSummaryService.calculate(json.readValue(body,Upload.class),Instant.now());
        assertEquals(0,new BigDecimal("4").compareTo(result.energyConsumedKwh().value()));
        assertEquals(0,new BigDecimal("15").compareTo(result.energyEfficiencyKmPerKwh().value()));
        assertNull(result.fuelConsumedLiters().value());
        assertNull(TripSummaryService.calculate(json.readValue(body.replace("\"chargedDuringInterval\":false","\"chargedDuringInterval\":true"),Upload.class),Instant.now()).energyConsumedKwh().value());
        assertNull(TripSummaryService.calculate(json.readValue(body.replace("\"consumedKwh\":4","\"consumedKwh\":0"),Upload.class),Instant.now()).energyEfficiencyKmPerKwh().value());
    }
}
