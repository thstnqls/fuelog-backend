package app.fuelog.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Public charger inventory only. Never logs a URL containing the upstream key. */
@Service
@org.springframework.context.annotation.Profile("!test")
public class ChargerCatalogImporter {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final String endpoint,key;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    public ChargerCatalogImporter(JdbcTemplate db,ObjectMapper json,TransactionTemplate transaction,
            @Value("${fuelog.charging.data-endpoint:}") String endpoint,@Value("${fuelog.charging.data-key:}") String key) {
        this.db=db;this.json=json;this.transaction=transaction;this.endpoint=unquote(endpoint);this.key=unquote(key);
    }
    @Scheduled(initialDelay=5000,fixedDelay=43200000)
    public void refresh() {
        if(endpoint.isBlank() || key.isBlank()) return;
        try {
            URI base=URI.create(endpoint);
            if(!"apis.data.go.kr".equals(base.getHost()) || !base.getPath().startsWith("/B552584/EvCharger")) return;
            String path=base.getPath().replaceAll("/$","");
            if(!path.endsWith("/getChargerInfo")) path+="/getChargerInfo";
            List<Object[]> rows=new ArrayList<>();
            boolean complete=false;
            Instant started=Instant.now();
            for(int page=1;page<=100;page++) {
                String query="serviceKey="+URLEncoder.encode(decodedKey(key),StandardCharsets.UTF_8)
                        +"&dataType=JSON&numOfRows=9999&pageNo="+page;
                URI uri=URI.create("https://apis.data.go.kr"+path+"?"+query);
                var response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(),HttpResponse.BodyHandlers.ofString());
                if(response.statusCode()!=200) return;
                JsonNode root=json.readTree(response.body());
                if(!"00".equals(root.path("resultCode").asText())) return;
                int total=root.path("totalCount").asInt(-1);
                if(total<0 || total>999900) return;
                JsonNode items=root.path("items").path("item");
                if(!items.isArray()) return;
                for(JsonNode item:items) {
                    Object[] row=parse(item,started);
                    if(row!=null) rows.add(row);
                }
                if(page*9999>=total) { complete=true;break; }
                if(items.isEmpty() || Duration.between(started,Instant.now()).toMinutes()>=5) return;
            }
            if(!complete || rows.isEmpty()) return;
            transaction.executeWithoutResult(status->{
                db.update("DELETE FROM charger_catalog");
                db.batchUpdate("INSERT INTO charger_catalog VALUES(?,?,?,?,?,?,?,?)",rows);
            });
        } catch(Exception failure) {
            // Keep the last complete snapshot. Exception messages can contain the service key.
            if(failure instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }
    static Object[] parse(JsonNode item,Instant at) {
        try {
            String op=item.path("busiId").asText(), id=item.path("statId").asText(),charger=item.path("chgerId").asText();
            if(!Set.of("PI","CV","ME","KP").contains(op) || id.isBlank() || id.length()>30 || charger.isBlank() || charger.length()>20
                    || "Y".equals(item.path("delYn").asText()) || "Y".equals(item.path("limitYn").asText())
                    || !"단독".equals(item.path("method").asText())) return null;
            double lat=Double.parseDouble(item.path("lat").asText()),lon=Double.parseDouble(item.path("lng").asText());
            BigDecimal kw=new BigDecimal(item.path("output").asText());
            if(!Double.isFinite(lat)||!Double.isFinite(lon)||lat<33||lat>39||lon<124||lon>132||kw.signum()<=0||kw.compareTo(BigDecimal.valueOf(2000))>0) return null;
            // GS's metered 3kW tariff and highway-specific CHAEVI prices need separate verified tariffs.
            if(op.equals("PI") && kw.compareTo(BigDecimal.valueOf(3))<=0 || op.equals("CV") && (item.path("addr").asText()+item.path("statNm").asText()).contains("휴게소")) return null;
            String name=item.path("statNm").asText();if(name.isBlank()||name.length()>200) return null;
            return new Object[]{id,charger,name,op,lat,lon,kw,LocalDateTime.ofInstant(at,ZoneOffset.UTC)};
        } catch(RuntimeException invalid) { return null; }
    }
    static String decodedKey(String key) { return URLDecoder.decode(key.replace("+","%2B"),StandardCharsets.UTF_8); }
    private static String unquote(String text) {
        String s=text.trim();
        return s.length()>1 && (s.startsWith("\"")&&s.endsWith("\"") || s.startsWith("'")&&s.endsWith("'"))?s.substring(1,s.length()-1):s;
    }
}
