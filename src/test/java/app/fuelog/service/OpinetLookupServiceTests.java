package app.fuelog.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class OpinetLookupServiceTests {
    private final ObjectMapper json=new ObjectMapper();
    // Synthetic WGS84 projection fixture; it is not an asserted definition of Opinet KATEC.
    private final String projection="+proj=tmerc +lat_0=38 +lon_0=128 +k=1 +x_0=400000 +y_0=600000 +datum=WGS84 +units=m";
    private OpinetLookupService.Request request() {return new OpinetLookupService.Request(38.0,128.0,Instant.now(),Instant.now(),"PLEOS_CURRENT_UPDATE","B027");}
    @Test void verifiedProjectionIsAppliedAndDetailsProvideNamesAndAddresses() throws Exception {
        var calls=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            calls.incrementAndGet();
            assertTrue(exchange.getRequestURI().getRawQuery().contains("code=a%2Bb"));
            String body=exchange.getRequestURI().getPath().contains("aroundAll")
                    ? "{\"RESULT\":{\"OIL\":[{\"UNI_ID\":\"A1\",\"DISTANCE\":100}]}}"
                    : "{\"RESULT\":{\"OIL\":[{\"UNI_ID\":\"A1\",\"OS_NM\":\"테스트주유소\",\"NEW_ADR\":\"서울 테스트로 1\",\"OIL_PRICE\":[]}]}}";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var service=new OpinetLookupService(json,"a+b","WGS84",projection,HttpClient.newHttpClient(),"http://127.0.0.1:"+server.getAddress().getPort()+"/");
            var result=service.lookup(request());
            assertEquals("COMPLETED",result.searchStatus());
            assertEquals("테스트주유소",result.stations().getFirst().name());
            assertEquals("서울 테스트로 1",result.stations().getFirst().address());
            assertEquals("UNAVAILABLE",result.stations().getFirst().priceStatus());
            assertEquals(2,calls.get());
            var xy=OpinetLookupService.convert(128,38,projection);
            assertEquals(400000,xy.x,.01);assertEquals(600000,xy.y,.01);
        } finally {server.stop(0);}
    }
    @Test void missingSettingsAndInvalidLocationsNeverCallUpstream() {
        assertEquals("OPINET_NOT_CONFIGURED",new OpinetLookupService(json,"","WGS84",projection).lookup(request()).searchStatus());
        assertEquals("COORDINATE_SYSTEM_UNVERIFIED",new OpinetLookupService(json,"key","",projection).lookup(request()).searchStatus());
        assertEquals("COORDINATE_SYSTEM_UNVERIFIED",new OpinetLookupService(json,"key","WGS84","").lookup(request()).searchStatus());
        assertEquals("INVALID_LOCATION_EVIDENCE",new OpinetLookupService(json,"key","WGS84",projection).lookup(
                new OpinetLookupService.Request(Double.NaN,128.0,Instant.now(),Instant.now(),"PLEOS_CURRENT_UPDATE","B027")).searchStatus());
    }
    @Test void addressFallsBackAndStalePriceDoesNotHideStation() {
        var detail=json.readTree("{\"UNI_ID\":\"A1\",\"OS_NM\":\"테스트\",\"NEW_ADR\":\"\",\"VAN_ADR\":\"서울 테스트동 1\",\"OIL_PRICE\":[{\"PRODCD\":\"B027\",\"PRICE\":\"1700\",\"TRADE_DT\":\"20261101\",\"TRADE_TM\":\"120000\"}]}");
        var result=OpinetLookupService.station(detail,100,"B027",Instant.parse("2026-11-09T00:00:00Z"));
        assertEquals("서울 테스트동 1",result.address());assertNull(result.postedPriceWonPerLiter());assertEquals("STALE",result.priceStatus());
    }
}
