package app.fuelog.service;

import app.fuelog.dto.ChargeCandidateDtos.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** Same power band, one nearest station per operator, at most three operators. */
@Service
public class ChargingEstimateService {
    private final JdbcTemplate db;
    public ChargingEstimateService(JdbcTemplate db) { this.db=db; }
    public static String powerBand(BigDecimal kw) {
        if(kw==null || kw.signum()<=0) return null;
        if(kw.compareTo(BigDecimal.valueOf(30))<0) return "SLOW";
        if(kw.compareTo(BigDecimal.valueOf(50))<0) return "MEDIUM";
        if(kw.compareTo(BigDecimal.valueOf(100))<0) return "FAST_50";
        if(kw.compareTo(BigDecimal.valueOf(200))<0) return "FAST_100";
        return "ULTRA";
    }
    public static double distance(double a,double b,double c,double d) {
        double x=Math.sin(Math.toRadians(c-a)/2),y=Math.sin(Math.toRadians(d-b)/2);
        double v=x*x+Math.cos(Math.toRadians(a))*Math.cos(Math.toRadians(c))*y*y;
        return 6371000*2*Math.asin(Math.sqrt(Math.min(1,Math.max(0,v))));
    }
    public List<Scenario> estimate(double lat,double lon,BigDecimal kwh,Instant now) {
        var tariffs=db.query("SELECT * FROM charging_tariffs WHERE verified_at<=? AND valid_until>? ORDER BY verified_at DESC",
            (r,n)->new Tariff(r.getString("operator_id"),r.getString("power_band"),r.getBigDecimal("won_per_kwh"),r.getString("tariff_version"),r.getString("source_url"),r.getString("source_updated_at"),r.getObject("verified_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)), time(now),time(now));
        Map<String,Tariff> byKey=new HashMap<>();
        for(var t:tariffs) byKey.putIfAbsent(t.operator+":"+t.band,t);
        var stations=db.query("SELECT * FROM charger_catalog WHERE observed_at>=? AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?",
            (r,n)->new Charger(r.getString("station_id"),r.getString("station_name"),r.getString("operator_id"),r.getDouble("latitude"),r.getDouble("longitude"),r.getBigDecimal("connector_kw")),
            time(now.minusSeconds(86400)),lat-.05,lat+.05,lon-.06,lon+.06);
        List<Scenario> result=new ArrayList<>();
        for(String band:List.of("SLOW","MEDIUM","FAST_50","FAST_100","ULTRA")) {
            List<ComparedStation> priced=new ArrayList<>();
            for(var s:stations) {
                var t=byKey.get(s.operator+":"+band);
                double meters=distance(lat,lon,s.lat,s.lon);
                if(t==null || !band.equals(powerBand(s.kw)) || meters>3000) continue;
                priced.add(new ComparedStation(s.id,s.name,s.operator,Math.round(meters*10)/10.0,s.kw,t.price,t.version,t.url,t.updated,t.verified));
            }
            priced.sort(Comparator.comparingDouble(ComparedStation::distanceMeters).thenComparing(ComparedStation::stationId));
            var local=select(priced,1000);
            if(local.size()<2) local=select(priced,3000);
            if(local.size()<2) continue;
            BigDecimal avg=local.stream().map(ComparedStation::wonPerKwh).reduce(BigDecimal.ZERO,BigDecimal::add)
                .divide(BigDecimal.valueOf(local.size()),6,RoundingMode.HALF_UP);
            result.add(new Scenario(band,"NEARBY_STANDARD_MEMBER_REFERENCE_MEAN",avg,
                kwh.multiply(avg).setScale(0,RoundingMode.HALF_UP),local));
        }
        return result;
    }
    private static List<ComparedStation> select(List<ComparedStation> all,double radius) {
        Map<String,ComparedStation> unique=new LinkedHashMap<>();
        for(var s:all) if(s.distanceMeters()<=radius) unique.putIfAbsent(s.operatorId(),s);
        return unique.values().stream().limit(3).toList();
    }
    public static List<Scenario> reprice(List<Scenario> snapshots,BigDecimal kwh) {
        return snapshots.stream().map(s->new Scenario(s.powerBand(),s.basis(),s.averageWonPerKwh(),
                kwh.multiply(s.averageWonPerKwh()).setScale(0,RoundingMode.HALF_UP),s.stations())).toList();
    }
    private record Tariff(String operator,String band,BigDecimal price,String version,String url,String updated,Instant verified) {}
    private record Charger(String id,String name,String operator,double lat,double lon,BigDecimal kw) {}
    private static LocalDateTime time(Instant t) { return LocalDateTime.ofInstant(t,ZoneOffset.UTC); }
}
