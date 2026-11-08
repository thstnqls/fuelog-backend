package app.fuelog.common;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

public final class TimeUtils {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private TimeUtils() {}

    public static LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZONE);
    }

    public static Instant toInstant(LocalDateTime localDateTime) {
        return localDateTime == null ? null : localDateTime.atZone(ZONE).toInstant();
    }
}
