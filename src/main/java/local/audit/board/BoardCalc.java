package local.audit.board;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import local.audit.board.BoardData.Issue;
import local.audit.board.BoardData.Kpi;
import local.audit.board.BoardData.Org;

/**
 * 현황판 계산. 예전 현황판의 계산식을 그대로 옮겼다.
 * 기간(당일·주·월·분기·반기 누적), 영업일 진행률, 지표별 달성률, 직전 기간 대비, 기말 전망, 상태(이탈·주의·정상).
 */
public final class BoardCalc {
    public static final Map<String, String> PERIODS = new LinkedHashMap<>();
    static {
        PERIODS.put("d", "당일"); PERIODS.put("w", "주 누적"); PERIODS.put("m", "월 누적"); PERIODS.put("q", "분기 누적"); PERIODS.put("h", "반기 누적");
    }

    public record Range(int y, int sm, int months100, String start, String end, String label) {
        public double months() { return months100 / 100.0; }
    }
    public record Pace(double pct, int done, int total, String label) { }
    public record Landing(double est, double tgt, double pct, boolean avg) { }
    /** 지표 하나의 결과. none이면 입력 없음. state: miss·warn·ok. */
    public record Result(Kpi k, boolean none, double a, String show, String sub, double stretch, String state, Double prev, Landing landing) {
        public String pct() { return none ? "" : String.format("%.1f%%", a); }
        public double bar() { return Math.max(2, Math.min(100, a / 120 * 100)); }
        public double stretchMark() { return stretch > 0 ? Math.min(100, stretch / 120 * 100) : 0; }
        public Double delta() { return none || prev == null ? null : a - prev; }
    }
    public record Attn(int pri, double sort, String kind, String label, String name, String org, String val, String link) { }

    final BoardData d;
    public final String period;
    public final LocalDate sel;
    public final String selDate;
    private final Map<String, Result> cache = new LinkedHashMap<>();

    public BoardCalc(BoardData d, String period, LocalDate sel) {
        this.d = d;
        this.period = PERIODS.containsKey(period) ? period : "m";
        this.sel = sel;
        this.selDate = sel.toString();
    }

    // ── 날짜 ──
    public static String weekKey(LocalDate d) { return d.minusDays(d.getDayOfWeek().getValue() - 1).toString(); }
    public static String weekKey(String ymd) { return weekKey(LocalDate.parse(ymd)); }
    public static String weekLabel(String wk) {
        LocalDate a = LocalDate.parse(wk), b = a.plusDays(6);
        return a.getMonthValue() + "/" + a.getDayOfMonth() + "~" + b.getMonthValue() + "/" + b.getDayOfMonth();
    }
    static List<String> weeksIn(String from, String to) {
        List<String> out = new ArrayList<>();
        LocalDate cur = LocalDate.parse(weekKey(from)), end = LocalDate.parse(weekKey(to));
        for (int g = 0; !cur.isAfter(end) && g < 400; g++) { out.add(cur.toString()); cur = cur.plusDays(7); }
        return out;
    }
    public static int bizCount(String from, String to) {
        if (to.compareTo(from) < 0) return 0;
        LocalDate a = LocalDate.parse(from), b = LocalDate.parse(to);
        int n = 0;
        for (int g = 0; !a.isAfter(b) && g < 1000; g++) {
            if (a.getDayOfWeek() != DayOfWeek.SATURDAY && a.getDayOfWeek() != DayOfWeek.SUNDAY) n++;
            a = a.plusDays(1);
        }
        return n;
    }
    public static LocalDate prevBiz(LocalDate d) {
        for (int i = 0; i < 14; i++) {
            d = d.minusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) return d;
        }
        return d;
    }

    public Range range() {
        int y = sel.getYear(), m = sel.getMonthValue();
        if ("w".equals(period)) {
            String wk = weekKey(sel);
            return new Range(y, m, 25, wk, LocalDate.parse(wk).plusDays(6).toString(), weekLabel(wk) + " 주");
        }
        int sm, months;
        if ("d".equals(period) || "m".equals(period)) { sm = m; months = 1; }
        else if ("q".equals(period)) { sm = (m - 1) / 3 * 3 + 1; months = 3; }
        else { sm = m <= 6 ? 1 : 7; months = 6; }
        int em = sm + months - 1;
        String label = "q".equals(period) ? "Q" + ((sm - 1) / 3 + 1) : "h".equals(period) ? (sm == 1 ? "상반기" : "하반기") : m + "월";
        return new Range(y, sm, months * 100, LocalDate.of(y, sm, 1).toString(), LocalDate.of(y, em, 1).withDayOfMonth(LocalDate.of(y, em, 1).lengthOfMonth()).toString(), label);
    }

    public Pace pace() {
        Range r = range();
        int total = bizCount(r.start(), r.end()), done = bizCount(r.start(), selDate.compareTo(r.end()) < 0 ? selDate : r.end());
        return new Pace(total == 0 ? 0 : (double) done / total, done, total, r.label());
    }

    String[] prevRange() {
        Range r = range();
        if ("w".equals(period)) {
            LocalDate s0 = LocalDate.parse(r.start()).minusDays(7);
            return new String[] { s0.toString(), s0.plusDays(6).toString() };
        }
        int y = r.y(), sm = r.sm(), mo = (int) r.months();
        sm -= mo;
        while (sm < 1) { sm += 12; y -= 1; }
        int em = sm + mo - 1;
        LocalDate e = LocalDate.of(y, em, 1);
        return new String[] { LocalDate.of(y, sm, 1).toString(), e.withDayOfMonth(e.lengthOfMonth()).toString() };
    }

    List<Double> vals(String kid, String from, String to) {
        List<Double> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : d.entries.entrySet()) {
            String day = e.getKey();
            if (day.compareTo(from) < 0 || day.compareTo(to) > 0) continue;
            Double v = BoardData.num(e.getValue().get(kid));
            if (v != null) out.add(v);
        }
        return out;
    }

    List<Double> weekVals(String kid, String from, String to) {
        List<Double> out = new ArrayList<>();
        for (String wk : weeksIn(from, to)) { Double v = d.week(wk, kid); if (v != null) out.add(v); }
        return out;
    }

    static double sum(List<Double> v) { double s = 0; for (double x : v) s += x; return s; }
    static double mean(List<Double> v) { return v.isEmpty() ? 0 : sum(v) / v.size(); }
    static double ratio(Kpi k, double m, double tgt) { return "lower".equals(k.calc) ? (m != 0 ? tgt / m * 100 : 0) : (tgt != 0 ? m / tgt * 100 : 0); }

    public static String fmt(Double n, String u) {
        if (n == null || n.isNaN()) return "—";
        String s = Math.abs(n) >= 1000 ? String.format("%,d", Math.round(n)) : strip(Math.round(n * 100) / 100.0);
        return s + (u == null ? "" : u);
    }
    /** 화면 표시용 숫자(단위 포함). */
    public String num(double v, String u) { return fmt(v, u); }

    /** 입력 칸에 넣을 숫자(쉼표 없이). */
    public static String plain(Double v) { return v == null ? "" : strip(v); }
    static String strip(double x) {
        if (x == Math.rint(x)) return Long.toString((long) x);
        String s = Double.toString(x);
        return s.contains("E") ? String.format("%.2f", x) : s;
    }

    public static String state(double a) { double g = a - 100; return g <= -3 ? "miss" : g <= -1 ? "warn" : "ok"; }

    double stretchPct(Kpi k) {
        if (k.stretch == 0 || k.target == 0) return 0;
        return "lower".equals(k.calc) ? k.target / k.stretch * 100 : k.stretch / k.target * 100;
    }

    Double prevScore(Kpi k) {
        String[] pr = prevRange();
        List<Double> v = k.weekly() ? weekVals(k.id, pr[0], pr[1]) : vals(k.id, pr[0], pr[1]);
        if (v.isEmpty()) return null;
        if ("sum".equals(k.calc)) {
            double pt = k.weekly() ? k.target * v.size() : k.target * (range().months() == 0 ? 1 : range().months());
            return pt != 0 ? sum(v) / pt * 100 : null;
        }
        return ratio(k, mean(v), k.target);
    }

    Landing landing(Kpi k) {
        if ("d".equals(period) || k.weekly()) return null;
        Pace pc = pace();
        if (pc.pct() == 0 || pc.pct() >= 0.999) return null;
        List<Double> v = vals(k.id, range().start(), selDate);
        if (v.isEmpty()) return null;
        double tgt = k.target * (range().months() == 0 ? 1 : range().months());
        if ("sum".equals(k.calc)) {
            double est = sum(v) / pc.pct();
            return new Landing(est, tgt, tgt != 0 ? est / tgt * 100 : 0, false);
        }
        double m = mean(v);
        return new Landing(m, k.target, ratio(k, m, k.target), true);
    }

    public Result calc(Kpi k) {
        return cache.computeIfAbsent(k.id, x -> compute(k));
    }

    Result compute(Kpi k) {
        Range r = range();
        if (k.weekly()) {
            List<Double> wv = weekVals(k.id, r.start(), selDate.compareTo(r.end()) < 0 ? selDate : r.end());
            if (wv.isEmpty()) return none(k);
            if ("sum".equals(k.calc)) {
                double acc = sum(wv), pt = k.target * wv.size();
                return done(k, pt != 0 ? acc / pt * 100 : 0, fmt(acc, k.uom), wv.size() + "주 목표 " + fmt(pt, k.uom));
            }
            double mw = mean(wv);
            return done(k, ratio(k, mw, k.target), fmt(mw, k.uom), (wv.size() > 1 ? wv.size() + "주 평균 · " : "") + "목표 " + fmt(k.target, k.uom));
        }
        Pace pc = pace();
        if ("d".equals(period)) {
            Double v = d.day(selDate, k.id);
            if (v == null) return none(k);
            LocalDate first = sel.withDayOfMonth(1);
            int bdM = bizCount(first.toString(), first.withDayOfMonth(first.lengthOfMonth()).toString());
            double tgt = "sum".equals(k.calc) ? k.target / (bdM == 0 ? 1 : bdM) : k.target;
            return done(k, ratio(k, v, tgt), fmt(v, k.uom), "목표 " + fmt(tgt, k.uom));
        }
        List<Double> v = vals(k.id, r.start(), selDate);
        if (v.isEmpty()) return none(k);
        if ("sum".equals(k.calc)) {
            double pt = k.target * (r.months() == 0 ? 1 : r.months()), acc = sum(v);
            double a = pt != 0 && pc.pct() != 0 ? acc / (pt * pc.pct()) * 100 : 0;
            return done(k, a, fmt(acc, k.uom), pc.label() + " 목표 " + fmt(pt, k.uom));
        }
        double m = mean(v);
        return done(k, ratio(k, m, k.target), fmt(m, k.uom), "목표 " + fmt(k.target, k.uom));
    }

    Result none(Kpi k) { return new Result(k, true, 0, "—", "", 0, "none", null, null); }
    Result done(Kpi k, double a, String show, String sub) { return new Result(k, false, a, show, sub, stretchPct(k), state(a), prevScore(k), landing(k)); }

    // ── 묶음 ──
    public List<Result> results(String orgId, String grp) {
        List<Result> out = new ArrayList<>();
        for (Kpi k : d.kpis) if ((orgId == null || k.org.equals(orgId)) && (grp == null || k.grp.equals(grp))) out.add(calc(k));
        return out;
    }
    public static Double avg(List<Result> rs) {
        double s = 0; int n = 0;
        for (Result r : rs) if (!r.none()) { s += r.a(); n++; }
        return n == 0 ? null : s / n;
    }
    public static Double avgLanding(List<Result> rs) {
        double s = 0; int n = 0;
        for (Result r : rs) if (r.landing() != null) { s += r.landing().pct(); n++; }
        return n == 0 ? null : s / n;
    }
    public static Double avgPrev(List<Result> rs) {
        double s = 0; int n = 0;
        for (Result r : rs) if (r.prev() != null) { s += r.prev(); n++; }
        return n == 0 ? null : s / n;
    }
    public Double groupAvg(String grp) { return avg(results(null, grp)); }
    public Double groupLanding(String grp) { return avgLanding(results(null, grp)); }
    public Double groupPrev(String grp) { return avgPrev(results(null, grp)); }

    public List<Result> missing() {
        List<Result> out = new ArrayList<>();
        for (Kpi k : d.kpis) { Result r = calc(k); if (!r.none() && "miss".equals(r.state())) out.add(r); }
        out.sort(Comparator.comparingDouble(Result::a));
        return out;
    }
    public long warnCount() { return d.kpis.stream().map(this::calc).filter(r -> !r.none() && "warn".equals(r.state())).count(); }
    public long okCount() { return d.kpis.stream().map(this::calc).filter(r -> !r.none() && "ok".equals(r.state())).count(); }
    public long noneCount() { return d.kpis.stream().map(this::calc).filter(Result::none).count(); }

    public List<String> openIssueIds() {
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, Issue> e : d.issues.entrySet()) if (e.getValue() != null && e.getValue().open()) ids.add(e.getKey());
        ids.sort(Comparator.comparingInt((String id) -> "상".equals(d.issues.get(id).level()) ? 0 : "중".equals(d.issues.get(id).level()) ? 1 : 2)
            .thenComparing(id -> d.issues.get(id).at == null ? "" : d.issues.get(id).at, Comparator.reverseOrder()));
        return ids;
    }
    public long openIssues(String orgId) { return d.issues.values().stream().filter(x -> x != null && x.open() && (orgId == null || orgId.equals(x.org))).count(); }
    public long highIssues() { return d.issues.values().stream().filter(x -> x != null && x.open() && "상".equals(x.level())).count(); }

    /** 확인 필요: 계획 이탈, 직전 기간 대비 2%p 넘게 악화, 중대 이슈, IVR 장기물·인보험 격차 2%p 이상. */
    public List<Attn> attention() {
        List<Attn> rows = new ArrayList<>();
        for (Kpi k : d.kpis) {
            Result c = calc(k);
            if (c.none()) continue;
            if ("miss".equals(c.state())) rows.add(new Attn(0, c.a(), "bad", "계획 이탈", k.name, d.orgName(k.org), String.format("%.1f%%", c.a()), "/board/kpi/" + k.id));
            if (c.prev() != null && c.a() - c.prev() <= -2) rows.add(new Attn(1, c.a() - c.prev(), "warn", "전기 대비 악화", k.name, d.orgName(k.org), String.format("%.1f%%p", c.a() - c.prev()), "/board/kpi/" + k.id));
        }
        for (String id : openIssueIds()) {
            Issue x = d.issues.get(id);
            if ("상".equals(x.level())) rows.add(new Attn(0, -999, "bad", "중대 이슈", x.title.isBlank() ? "(제목 없음)" : x.title, d.orgName(x.org), "상", "/board/issues#i-" + id));
        }
        Kpi[] pair = ivrPair();
        if (pair[0] != null && pair[1] != null) {
            Double la = lastWeek(pair[0]), lb = lastWeek(pair[1]);
            if (la != null && lb != null && Math.abs(la - lb) >= 2) {
                double gd = la - lb;
                rows.add(new Attn(1, -Math.abs(gd), "warn", "IVR 격차", "장기물 " + fmt(la, "%") + " · 인보험 " + fmt(lb, "%"), "주간 수신율",
                    (gd > 0 ? "장기물 +" : "인보험 +") + String.format("%.1f%%p", Math.abs(gd)), "/board/kpi/" + (gd > 0 ? pair[0].id : pair[1].id)));
            }
        }
        rows.sort(Comparator.comparingInt(Attn::pri).thenComparingDouble(Attn::sort));
        return rows.size() > 8 ? rows.subList(0, 8) : rows;
    }

    public Kpi[] ivrPair() {
        Kpi lt = null, lf = null;
        for (Kpi k : d.kpis) {
            if (!k.name.toUpperCase().contains("IVR")) continue;
            if (lt == null && k.name.contains("장기물")) lt = k;
            if (lf == null && k.name.contains("인보험")) lf = k;
        }
        return new Kpi[] { lt, lf };
    }

    /** 주간 지표의 최근 몇 주. */
    public List<String[]> weekSeries(Kpi k, int n) {
        List<String[]> out = new ArrayList<>();
        if (k == null) return out;
        List<String> wks = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : d.weeks.entrySet()) if (BoardData.num(e.getValue().get(k.id)) != null) wks.add(e.getKey());
        for (String w : wks.subList(Math.max(0, wks.size() - n), wks.size())) out.add(new String[] { w, weekLabel(w), fmt(d.week(w, k.id), "") });
        return out;
    }
    /** 주간 막대: [주 표시, 값 글자, 막대 높이(px), 목표 미달]. 70%~100% 구간을 넓게 보이게 한다. */
    public List<Object[]> weekBars(Kpi k, int n) {
        List<Object[]> out = new ArrayList<>();
        for (String[] w : weekSeries(k, n)) {
            double v = d.week(w[0], k.id);
            int h = (int) Math.max(4, Math.min(90, (v - 70) * 3));
            out.add(new Object[] { w[1], w[2], h, "lower".equals(k.calc) ? v > k.target : v < k.target });
        }
        return out;
    }

    Double lastWeek(Kpi k) {
        List<String[]> s = weekSeries(k, 1);
        return s.isEmpty() ? null : d.week(s.get(0)[0], k.id);
    }

    /** 최근 흐름(일별 14개 · 주별 8개): [표시 이름, 값, 달성률]. */
    public List<Object[]> trend(Kpi k) {
        List<Object[]> out = new ArrayList<>();
        if (k.weekly()) {
            for (String[] w : weekSeries(k, 8)) { double v = d.week(w[0], k.id); out.add(new Object[] { w[1], v, ratio(k, v, k.target) }); }
            return out;
        }
        List<String> days = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : d.entries.entrySet()) if (BoardData.num(e.getValue().get(k.id)) != null) days.add(e.getKey());
        for (String day : days.subList(Math.max(0, days.size() - 14), days.size())) {
            double v = d.day(day, k.id);
            LocalDate first = LocalDate.parse(day).withDayOfMonth(1);
            int bdM = bizCount(first.toString(), first.withDayOfMonth(first.lengthOfMonth()).toString());
            double tgt = "sum".equals(k.calc) ? k.target / (bdM == 0 ? 1 : bdM) : k.target;
            out.add(new Object[] { day.substring(5), v, ratio(k, v, tgt) });
        }
        return out;
    }

    /** 오늘 아직 안 넣은 지표 수: [일별, 주간]. */
    public int[] pending() {
        String wk = weekKey(sel);
        int dd = 0, ww = 0;
        for (Kpi k : d.kpis) {
            if (k.weekly()) { if (d.week(wk, k.id) == null) ww++; }
            else if (d.day(selDate, k.id) == null) dd++;
        }
        return new int[] { dd, ww };
    }

    /** 이번 달 영업일별 입력 현황: [날짜, 일자, 입력 수, 전체 수, 주말 여부]. */
    public List<Object[]> monthDays() {
        List<Object[]> out = new ArrayList<>();
        long daily = d.kpis.stream().filter(k -> !k.weekly()).count();
        LocalDate a = sel.withDayOfMonth(1);
        for (int i = 0; i < a.lengthOfMonth(); i++) {
            LocalDate x = a.plusDays(i);
            boolean off = x.getDayOfWeek() == DayOfWeek.SATURDAY || x.getDayOfWeek() == DayOfWeek.SUNDAY;
            Map<String, Object> m = d.entries.get(x.toString());
            long n = 0;
            if (m != null) for (Kpi k : d.kpis) if (!k.weekly() && BoardData.num(m.get(k.id)) != null) n++;
            out.add(new Object[] { x.toString(), x.getDayOfMonth(), n, daily, off, x.isAfter(sel) });
        }
        return out;
    }

    public List<Org> orgs() { return d.orgs; }

    /** 보고 문구(예전 현황판의 '보고 문구'와 같은 짜임). */
    public String report() {
        Range r = range();
        Pace pc = pace();
        List<String> L = new ArrayList<>();
        String head = "d".equals(period) ? selDate + " 기준" : r.label() + " 기준 (" + r.start() + " ~ " + selDate + ")";
        L.add("□ 경영 현황 보고 — " + head);
        L.add("");
        Double gb = groupAvg("base"), gv = groupAvg("vu");
        L.add(" - 종합 달성률");
        L.add("  · 기본 KPI " + (gb == null ? "미입력" : String.format("%.1f%%", gb)) + " / Value-up " + (gv == null ? "미입력" : String.format("%.1f%%", gv))
            + ("d".equals(period) ? "" : " (기간 진행 " + Math.round(pc.pct() * 100) + "%, 영업일 " + pc.done() + "/" + pc.total() + "일)"));
        Double lb = groupLanding("base"), lv = groupLanding("vu");
        if (lb != null || lv != null)
            L.add("  · 현 추세 유지 시 기말 전망 — 기본 KPI " + (lb == null ? "—" : String.format("%.1f%%", lb)) + " / Value-up " + (lv == null ? "—" : String.format("%.1f%%", lv)));
        L.add("");
        L.add(" - 사업부별");
        for (Org o : d.orgs) {
            List<Result> rs = results(o.id, null);
            Double a = avg(rs), g2 = avgLanding(rs);
            long nIss = openIssues(o.id);
            L.add("  · " + o.name + " — 달성률 " + (a == null ? "미입력" : String.format("%.1f%%", a)) + (g2 != null ? String.format(" · 기말 전망 %.1f%%", g2) : "") + (nIss > 0 ? " · 이슈 " + nIss + "건" : ""));
        }
        L.add("");
        List<Result> bad = missing();
        long warn = warnCount();
        L.add(" - 계획 이탈 지표 " + bad.size() + "건" + (warn > 0 ? " (주의 " + warn + "건 별도)" : ""));
        if (bad.isEmpty()) L.add("  · 계획에 뒤처진 지표 없음");
        for (int i = 0; i < Math.min(5, bad.size()); i++) {
            Result c = bad.get(i);
            L.add("  · " + d.orgName(c.k().org) + " " + c.k().name + " — 실적 " + c.show() + String.format(", 달성률 %.1f%%", c.a())
                + (c.prev() != null ? String.format(" (직전 기간 대비 %s%.1f%%p)", c.a() - c.prev() >= 0 ? "+" : "", c.a() - c.prev()) : "")
                + (c.landing() != null ? String.format(", 기말 전망 %.0f%%", c.landing().pct()) : ""));
        }
        if (bad.size() > 5) L.add("  · 이하 " + (bad.size() - 5) + "건 생략");
        L.add("");
        List<String> op = openIssueIds();
        L.add(" - 진행 중 이슈 " + op.size() + "건 (중대 " + highIssues() + "건)");
        for (String id : op.subList(0, Math.min(5, op.size()))) {
            Issue x = d.issues.get(id);
            L.add("  · [" + x.level() + "] " + d.orgName(x.org) + (x.unit.isBlank() ? "" : " " + x.unit) + " — " + (x.title.isBlank() ? "(제목 없음)" : x.title) + (x.st.isBlank() ? "" : " (" + x.st + ")"));
            if (!x.note.isBlank()) L.add("     " + x.note.replaceAll("\\s+", " "));
        }
        if (op.isEmpty()) L.add("  · 진행 중인 이슈 없음");
        if (!d.focus.isEmpty()) {
            L.add("");
            L.add(" - 이번 주 집중 사항");
            for (BoardData.Focus f : d.focus.subList(0, Math.min(6, d.focus.size()))) {
                L.add("  · " + f.t);
                if (!f.w.isBlank()) L.add("     " + f.w);
            }
        }
        List<Kpi> wk = d.kpis.stream().filter(Kpi::weekly).toList();
        if (!wk.isEmpty()) {
            L.add("");
            L.add(" - 주간 지표");
            for (Kpi k : wk) {
                Result c = calc(k);
                L.add("  · " + k.name + " — " + (c.none() ? "미입력" : c.show() + String.format(" (달성률 %.1f%%, 목표 %s)", c.a(), fmt(k.target, k.uom))));
            }
            Kpi[] pair = ivrPair();
            if (pair[0] != null && pair[1] != null) {
                Double la = lastWeek(pair[0]), lb2 = lastWeek(pair[1]);
                if (la != null && lb2 != null) {
                    double gd = la - lb2;
                    L.add(String.format("  · 장기물 vs 인보험 격차 %s%.1f%%p", gd >= 0 ? "+" : "", gd) + (Math.abs(gd) < 0.3 ? " (거의 같음)" : gd > 0 ? " — 장기물이 앞섬" : " — 인보험이 앞섬"));
                }
            }
        }
        L.add("");
        L.add("※ 경영 현황판에서 " + local.audit.Kst.today() + " 자동 정리");
        return String.join("\n", L);
    }
}
