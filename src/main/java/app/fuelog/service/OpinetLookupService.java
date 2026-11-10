package app.fuelog.service;

import jakarta.validation.constraints.*;
import org.locationtech.proj4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Opinet names and addresses; raw query coordinates and API keys are never logged or persisted. */
@Service
public class OpinetLookupService {
    public record Request(@NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @NotNull Instant measuredAt, @NotNull Instant receivedAt,
            @NotBlank @Pattern(regexp="PLEOS_CURRENT_UPDATE") String source,
            @NotBlank @Pattern(regexp="B034|B027|D047|C004|K015") String fuelType) {}
    public record Station(String stationId,String name,String address,double distanceMeters,String fuelType,
            BigDecimal postedPriceWonPerLiter,Instant pricePostedAt,Instant priceFetchedAt,String priceStatus) {}
    public record Result(String searchStatus,Instant fetchedAt,List<Station> stations) {}
    private final ObjectMapper json;
    private final String key,coordinates,projection;
    private final HttpClient http;
    private final String base;
    @org.springframework.beans.factory.annotation.Autowired
    public OpinetLookupService(ObjectMapper json,@Value("${OPINET_KEY:}") String key,
            @Value("${fuelog.charging.pleos-coordinate-system:}") String coordinates,
            @Value("${OPINET_KATEC_PROJ4:}") String projection) {
        this(json,key,coordinates,projection,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),"https://www.opinet.co.kr/api/");
    }
    OpinetLookupService(ObjectMapper json,String key,String coordinates,String projection,HttpClient http,String base) {
        this.json=json;this.key=unquote(key);this.coordinates=unquote(coordinates);this.projection=unquote(projection);this.http=http;this.base=base;
    }
    public Result lookup(Request b) {
        Instant now=Instant.now();
        if(!Double.isFinite(b.latitude()) || !Double.isFinite(b.longitude()) || Math.abs(b.latitude())>90 || Math.abs(b.longitude())>180
                || b.measuredAt().isBefore(now.minusSeconds(30)) || b.measuredAt().isAfter(b.receivedAt())
                || b.receivedAt().isAfter(now.plusSeconds(5)) || b.receivedAt().isAfter(b.measuredAt().plusSeconds(10)))
            return new Result("INVALID_LOCATION_EVIDENCE",now,List.of());
        if(key.isBlank()) return new Result("OPINET_NOT_CONFIGURED",now,List.of());
        if(!"WGS84".equals(coordinates) || !projection.contains("+proj=tmerc") || !projection.contains("+units=m")) return new Result("COORDINATE_SYSTEM_UNVERIFIED",now,List.of());
        try {
            ProjCoordinate xy=convert(b.longitude(),b.latitude(),projection);
            JsonNode nearby=rows(get("aroundAll.do","x="+xy.x+"&y="+xy.y+"&radius=1000&sort=2&prodcd="+b.fuelType()));
            if(nearby.isEmpty()) nearby=rows(get("aroundAll.do","x="+xy.x+"&y="+xy.y+"&radius=3000&sort=2&prodcd="+b.fuelType()));
            List<JsonNode> candidates=new ArrayList<>();nearby.forEach(candidates::add);
            candidates.removeIf(n->n.path("UNI_ID").asText().isBlank() || !Double.isFinite(n.path("DISTANCE").asDouble(Double.NaN))
                    || n.path("DISTANCE").asDouble(-1)<0 || n.path("DISTANCE").asDouble()>3000);
            candidates.sort(Comparator.comparingDouble(n->n.path("DISTANCE").asDouble()));
            List<Station> result=new ArrayList<>();Set<String> seen=new HashSet<>();
            int detailRequests=0;
            for(JsonNode n:candidates) {
                String id=n.path("UNI_ID").asText(); if(!seen.add(id)) continue;
                if(++detailRequests>3) break;
                JsonNode details=rows(get("detailById.do","id="+encode(id)));
                JsonNode detail=null;for(JsonNode row:details) if(id.equals(row.path("UNI_ID").asText())) {detail=row;break;}
                if(detail==null) continue;
                result.add(station(detail,n.path("DISTANCE").asDouble(),b.fuelType(),now));
                if(result.size()==3) break;
                // Bound external calls even if upstream returns mismatched detail records.
                if(seen.size()>=3) break;
            }
            return new Result(result.isEmpty()?"NO_STATIONS":"COMPLETED",now,result);
        } catch(Exception error) {
            if(error instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Result("OPINET_UNAVAILABLE",now,List.of());
        }
    }
    static ProjCoordinate convert(double lon,double lat,String definition) {
        CRSFactory factory=new CRSFactory();
        var source=factory.createFromParameters("WGS84","+proj=longlat +datum=WGS84 +no_defs");
        var target=factory.createFromParameters("verified-katec",definition);
        ProjCoordinate result=new ProjCoordinate();new CoordinateTransformFactory().createTransform(source,target).transform(new ProjCoordinate(lon,lat),result);
        if(!Double.isFinite(result.x) || !Double.isFinite(result.y)) throw new IllegalArgumentException("Invalid projection");
        return result;
    }
    private JsonNode get(String endpoint,String query) throws Exception {
        URI uri=URI.create(base+endpoint+"?out=json&code="+encode(key)+"&"+query);
        var response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(7)).GET().build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200) throw new IllegalStateException("Opinet unavailable");
        JsonNode root=json.readTree(response.body());
        if(!root.path("RESULT").has("OIL")) throw new IllegalArgumentException("Invalid Opinet response");
        return root;
    }
    private JsonNode rows(JsonNode root) {
        JsonNode node=root.path("RESULT").path("OIL");
        if(node.isArray()) return node;
        var array=json.createArrayNode();if(node.isObject()) array.add(node);return array;
    }
    static Station station(JsonNode detail,double meters,String type,Instant now) {
        String address=detail.path("NEW_ADR").asText("").trim();if(address.isEmpty()) address=detail.path("VAN_ADR").asText("").trim();
        BigDecimal price=null;Instant posted=null;
        for(JsonNode p:detail.path("OIL_PRICE")) if(type.equals(p.path("PRODCD").asText())) {
            try {
                BigDecimal candidate=new BigDecimal(p.path("PRICE").asText());
                Instant time=LocalDateTime.parse(p.path("TRADE_DT").asText()+p.path("TRADE_TM").asText(),DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(java.time.format.ResolverStyle.STRICT)).atZone(ZoneId.of("Asia/Seoul")).toInstant();
                if(candidate.signum()>0) {price=candidate;posted=time;}
            } catch(RuntimeException invalid) { }
            break;
        }
        String status=price==null || posted==null?"UNAVAILABLE":posted.isAfter(now)||posted.isBefore(now.minusSeconds(86400))?"STALE":"VALID";
        return new Station(detail.path("UNI_ID").asText(),detail.path("OS_NM").asText(),address,meters,type,
                "VALID".equals(status)?price:null,posted,now,status);
    }
    private static String unquote(String value) {
        String text=value.trim();
        return text.length()>1 && (text.startsWith("\"")&&text.endsWith("\"") || text.startsWith("'")&&text.endsWith("'")) ? text.substring(1,text.length()-1) : text;
    }
    private static String encode(String value) {return URLEncoder.encode(value,StandardCharsets.UTF_8);}
}
