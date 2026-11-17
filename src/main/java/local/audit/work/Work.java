package local.audit.work;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 업무·일정의 이름표, 날짜 계산, 공휴일. */
public final class Work {
    private Work() { }

    /** 칸(필드)만 읽고 쓰는 JSON 변환기. 화면용 get… 이름은 저장하지 않는다. */
    public static final ObjectMapper JSON = new ObjectMapper()
        .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
        .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.PUBLIC_ONLY)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);

    public static final Map<String, String> STATUS = ordered(
        "todo", "대기", "doing", "진행", "ext", "타 부서 대기", "review", "보고대기", "done", "완료", "hold", "보류");
    public static final Map<String, String> PRI = ordered("high", "높음", "mid", "보통", "low", "낮음");
    public static final Map<String, String> CYCLE = ordered("none", "수시·기한", "months", "정기(월)", "always", "상시", "count", "횟수 목표");
    public static final Map<String, String> REPEAT = ordered("", "반복 안 함", "daily", "매일", "weekdays", "평일마다", "weekly", "매주", "monthly", "매월");
    public static final Map<String, String> EVENT = ordered("meet", "회의", "report", "보고", "field", "출장·현장", "edu", "교육", "off", "휴가·부재", "etc", "기타");
    public static final Map<String, String> NOTE = ordered("daily", "일일 노트", "meet", "회의", "todo", "할 일", "idea", "아이디어", "research", "조사", "doc", "문서");
    public static final Map<String, String> KIND = ordered("work", "업무", "dir", "지시", "event", "일정", "note", "노트", "plan", "계획 대기", "inbox", "생각 담기", "issue", "쟁점");
    public static final String[] DOW = { "월", "화", "수", "목", "금", "토", "일" };

    private static Map<String, String> ordered(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return java.util.Collections.unmodifiableMap(m);
    }

    public static String statusName(String k) { return STATUS.getOrDefault(k, "대기"); }
    public static String priName(String k) { return PRI.getOrDefault(k, "보통"); }
    public static String priIcon(String k) { return "high".equals(k) ? "▲" : "low".equals(k) ? "▼" : "■"; }
    public static String kindName(String k) { return KIND.getOrDefault(k, k); }
    public static String eventName(String k) { return EVENT.getOrDefault(k == null ? "etc" : k, "기타"); }

    // ───── 날짜 ─────
    public static LocalDate date(String s) {
        if (s == null || s.length() < 10) return null;
        try { return LocalDate.parse(s.substring(0, 10)); } catch (RuntimeException e) { return null; }
    }

    public static String ymd(LocalDate d) { return d == null ? "" : d.toString(); }

    public static long diff(String a, String b) {
        LocalDate x = date(a), y = date(b);
        return x == null || y == null ? 0 : ChronoUnit.DAYS.between(x, y);
    }

    public static LocalDate weekStart(LocalDate d) { return d.minusDays(d.getDayOfWeek().getValue() - 1L); }

    /** 11/20(목) 꼴. */
    public static String md(String s) {
        LocalDate d = date(s);
        if (d == null) return "";
        return d.getMonthValue() + "/" + d.getDayOfMonth() + "(" + DOW[d.getDayOfWeek().getValue() - 1] + ")";
    }

    // ───── 대한민국 공휴일 (참고용, 대체공휴일 포함) ─────
    private static final Map<String, String> HOL = new HashMap<>();
    static {
        Map<String, String> fixed = ordered("01-01", "신정", "03-01", "삼일절", "05-05", "어린이날", "06-06", "현충일",
            "08-15", "광복절", "10-03", "개천절", "10-09", "한글날", "12-25", "성탄절");
        for (int y = 2024; y <= 2030; y++) for (var e : fixed.entrySet()) HOL.put(y + "-" + e.getKey(), e.getValue());
        String[][] lunar = {
            { "2025", "01-28", "설날 연휴", "01-29", "설날", "01-30", "설날 연휴", "05-05", "부처님오신날", "05-06", "대체공휴일",
                "10-05", "추석 연휴", "10-06", "추석", "10-07", "추석 연휴", "10-08", "대체공휴일" },
            { "2026", "02-16", "설날 연휴", "02-17", "설날", "02-18", "설날 연휴", "03-02", "대체공휴일", "05-24", "부처님오신날",
                "05-25", "대체공휴일", "06-03", "지방선거", "08-17", "대체공휴일", "09-24", "추석 연휴", "09-25", "추석",
                "09-26", "추석 연휴", "10-05", "대체공휴일" },
            { "2027", "02-06", "설날 연휴", "02-07", "설날", "02-08", "설날 연휴", "02-09", "대체공휴일", "05-13", "부처님오신날",
                "08-16", "대체공휴일", "09-14", "추석 연휴", "09-15", "추석", "09-16", "추석 연휴", "10-04", "대체공휴일",
                "10-11", "대체공휴일", "12-27", "대체공휴일" },
            { "2028", "01-26", "설날 연휴", "01-27", "설날", "01-28", "설날 연휴", "05-02", "부처님오신날", "10-02", "추석 연휴",
                "10-03", "추석·개천절", "10-04", "추석 연휴", "10-05", "대체공휴일" } };
        for (String[] row : lunar) for (int i = 1; i + 1 < row.length; i += 2) HOL.put(row[0] + "-" + row[i], row[i + 1]);
    }

    public static String holiday(String ymd) { return HOL.get(ymd); }

    public static boolean isOff(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY || HOL.containsKey(d.toString());
    }

    /** 반복 주기 이름. */
    public static String cycleLabel(WorkItem x) {
        switch (x.cycle == null ? "none" : x.cycle) {
            case "always": return "상시";
            case "count": return "연 " + x.target + "회";
            case "months": {
                int n = x.months.size();
                return n == 12 ? "매월" : n == 4 ? "분기" : n == 2 ? "반기" : n == 1 ? "연 1회" : "연 " + n + "회";
            }
            default: return x.repeat == null || x.repeat.isEmpty() ? "단발" : REPEAT.getOrDefault(x.repeat, "단발");
        }
    }

    public static List<String> keys(Map<String, String> m) { return List.copyOf(m.keySet()); }
}
