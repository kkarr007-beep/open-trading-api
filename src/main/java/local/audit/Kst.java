package local.audit;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** 한국 시각 기준의 날짜 계산. */
public final class Kst {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    /** 시험에서만 바꾼다. */
    public static volatile Clock clock = Clock.systemUTC();
    private static final DateTimeFormatter CANON = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSxxx");
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private Kst() { }

    public static ZonedDateTime now() { return ZonedDateTime.now(clock).withZoneSameInstant(ZONE); }
    public static LocalDate today() { return now().toLocalDate(); }
    public static String canon(ZonedDateTime at) { return at.withZoneSameInstant(ZONE).format(CANON); }
    public static String stamp() { return canon(now()); }

    public static String display(String canon) {
        if (canon == null || canon.isEmpty()) return "";
        try {
            return OffsetDateTime.parse(canon).atZoneSameInstant(ZONE).format(DISPLAY);
        } catch (RuntimeException e) {
            return canon;
        }
    }

    /** 2026-12-31, 2026.12.31, 2026/12/31, 20261231, 2026년 12월 31일을 모두 받는다. */
    public static LocalDate date(Object text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("^(\\d{4})\\s*[-./년]?\\s*(\\d{1,2})\\s*[-./월]?\\s*(\\d{1,2})\\s*일?\\.?$").matcher(text.toString().strip());
        if (!m.matches()) return null;
        try {
            return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 만료일. 기산일에 달력 연수를 더한다. 2월 29일은 그달 말로 맞춘다. */
    public static LocalDate expiry(LocalDate anchor, int years) { return anchor.plusYears(years); }

    /** 만료 시각은 만료일 00:00 KST. */
    public static boolean isDue(LocalDate anchor, int years) { return !today().isBefore(expiry(anchor, years)); }

    public static long daysUntil(LocalDate anchor, int years) { return ChronoUnit.DAYS.between(today(), expiry(anchor, years)); }
}
