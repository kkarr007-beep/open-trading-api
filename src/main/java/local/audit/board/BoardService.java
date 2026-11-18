package local.audit.board;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import local.audit.Db;
import local.audit.Kst;
import local.audit.Op;
import local.audit.Settings;
import local.audit.Txt;
import local.audit.board.BoardData.Card;
import local.audit.board.BoardData.Focus;
import local.audit.board.BoardData.Issue;
import local.audit.board.BoardData.Kpi;
import local.audit.board.BoardData.Org;

/** 경영 현황판 저장·작업. 자료는 Meta 'board.store'의 'exec-board-v3'(예전 현황판과 같은 형식)에 둔다. */
@Service
public class BoardService {
    static final String STORE = "board.store", MAIN = "exec-board-v3";
    static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    public static final List<String> CATS = List.of("추진과제", "민원", "인력", "시스템", "노사", "감사", "기타");
    public static final List<String> LEVELS = List.of("상", "중", "하");
    public static final List<String> STATES = List.of("진행", "보류", "해결");

    private final Db db;

    public BoardService(Db db) { this.db = db; }

    // ── 읽기·쓰기 ──
    Map<String, Object> store() {
        Map<String, Object> row = db.row("SELECT Value FROM Meta WHERE Key=?", STORE);
        if (row == null) return new LinkedHashMap<>();
        try { return JSON.readValue(Txt.str(row.get("Value")), new TypeReference<LinkedHashMap<String, Object>>() { }); }
        catch (Exception e) { return new LinkedHashMap<>(); }
    }

    public synchronized boolean hasData() { return store().get(MAIN) instanceof String; }

    public synchronized BoardData load() {
        Object raw = store().get(MAIN);
        if (!(raw instanceof String s) || s.isBlank()) return BoardData.seed();
        try { return migrate(JSON.readValue(s, BoardData.class)); }
        catch (Exception e) { return BoardData.seed(); }
    }

    synchronized void save(BoardData d) {
        d.v = 7;
        d.saved = java.time.Instant.now().toString();
        try {
            Map<String, Object> st = store();
            st.put(MAIN, JSON.writeValueAsString(d));
            String body = JSON.writeValueAsString(st);
            db.tx(conn -> { Settings.put(conn, STORE, body); return null; });
        } catch (Exception e) {
            throw new IllegalStateException("현황판 자료를 저장하지 못했습니다.", e);
        }
    }

    public BoardCalc calc(String period, LocalDate date) { return new BoardCalc(load(), period, date); }

    /** 예전 판(4·5 미만) 자료를 지금 구조로: 장기·일반 보상 조직 합치기, IVR 지표 둘로 나누기. 값·이슈는 그대로. */
    static BoardData migrate(BoardData s) {
        if (s.orgs.isEmpty() || s.kpis.isEmpty()) {
            BoardData seed = BoardData.seed();
            if (s.orgs.isEmpty()) s.orgs = seed.orgs;
            if (s.kpis.isEmpty()) s.kpis = seed.kpis;
        }
        if (s.v < 4) {
            Map<String, String> merge = Map.of("lt", "cp", "gn", "cp");
            List<Org> keep = new ArrayList<>();
            Org merged = null;
            for (Org o : s.orgs) {
                if (o.id.equals("lt") || o.id.equals("gn") || o.id.equals("cp")) {
                    if (merged == null) { merged = new Org("cp", "장기일반보상사업부", "사업부", List.of()); keep.add(merged); }
                    for (String u : o.units) if (!merged.units.contains(u)) merged.units.add(u);
                } else keep.add(o);
            }
            if (merged != null && !merged.units.contains("손해조사팀")) merged.units.add("손해조사팀");
            if (!keep.isEmpty()) s.orgs = keep;
            for (Kpi k : s.kpis) if (merge.containsKey(k.org)) k.org = merge.get(k.org);
            for (Issue x : s.issues.values()) if (x != null && merge.containsKey(x.org)) x.org = merge.get(x.org);
        }
        if (s.v < 5) {
            for (Kpi k : s.kpis) {
                if (k.id.equals("ivr") || k.name.equals("IVR 수신율")) {
                    String old = k.id;
                    if (!k.name.contains("장기물") && !k.name.contains("인보험")) k.name = "IVR 수신율 (장기물)";
                    if (k.id.equals("ivr")) k.id = "ivr_lt";
                    if (!old.equals(k.id)) { remap(s.weeks, old, k.id); remap(s.entries, old, k.id); }
                }
            }
            boolean lt = s.kpis.stream().anyMatch(k -> k.name.toUpperCase().contains("IVR") && k.name.contains("장기물"));
            boolean lf = s.kpis.stream().anyMatch(k -> k.name.toUpperCase().contains("IVR") && k.name.contains("인보험"));
            String org = s.kpis.stream().filter(k -> k.name.contains("장기물")).map(k -> k.org).findFirst().orElse("c1");
            if (!lt) s.kpis.add(new Kpi("ivr_lt", org, "base", "IVR 수신율 (장기물)", "avg", "w", 92, 95, "%"));
            if (!lf) s.kpis.add(new Kpi("ivr_lf", org, "base", "IVR 수신율 (인보험)", "avg", "w", 92, 95, "%"));
        }
        for (Kpi k : s.kpis) {
            if (k.uom == null) k.uom = "";
            if (k.unit == null) k.unit = "";
            if (k.cycle == null || k.cycle.isBlank()) k.cycle = "d";
        }
        s.v = 7;
        return s;
    }

    static void remap(Map<String, Map<String, Object>> store, String from, String to) {
        for (Map<String, Object> row : store.values()) {
            if (row != null && row.get(from) instanceof Number) { row.putIfAbsent(to, row.get(from)); row.remove(from); }
        }
    }

    // ── 실적 ──
    /** 입력 화면 값 저장. 비운 칸은 그 값만 지우고, 화면에 없는 지표의 값은 둔다. */
    public synchronized Op saveEntries(LocalDate date, Map<String, String> p) {
        BoardData d = load();
        String day = date.toString(), wk = BoardCalc.weekKey(date);
        Map<String, Object> dm = new LinkedHashMap<>(d.entries.getOrDefault(day, Map.of()));
        Map<String, Object> wm = new LinkedHashMap<>(d.weeks.getOrDefault(wk, Map.of()));
        int nD = 0, nW = 0, del = 0, bad = 0;
        for (Kpi k : d.kpis) {
            String key = "v_" + k.id;
            if (!p.containsKey(key)) continue;
            String t = Txt.str(p.get(key)).replace(",", "").trim();
            Map<String, Object> m = k.weekly() ? wm : dm;
            if (t.isEmpty()) { if (m.remove(k.id) != null) del++; continue; }
            try {
                double v = Double.parseDouble(t);
                if (Double.isNaN(v) || Double.isInfinite(v)) { bad++; continue; }
                m.put(k.id, v);
                if (k.weekly()) nW++; else nD++;
            } catch (NumberFormatException e) { bad++; }
        }
        if (dm.isEmpty()) d.entries.remove(day); else d.entries.put(day, dm);
        if (wm.isEmpty()) d.weeks.remove(wk); else d.weeks.put(wk, wm);
        save(d);
        return Op.ok("실적을 저장했습니다 — 일별 " + nD + "건" + (nW > 0 ? ", 주간 " + nW + "건" : "") + (del > 0 ? ", 지움 " + del + "건" : "")
            + (bad > 0 ? " (숫자가 아닌 " + bad + "칸은 건너뜀)" : ""));
    }

    public synchronized Op clearDay(LocalDate date) {
        BoardData d = load();
        if (d.entries.remove(date.toString()) == null) return Op.bad("지울 일별 실적이 없습니다.");
        save(d);
        return Op.ok(date + "의 일별 실적을 지웠습니다. 주간 지표는 남겨 두었습니다.");
    }

    /** 전 영업일 값으로 빈칸 채우기(이미 있는 값은 둔다). */
    public synchronized Op fillPrev(LocalDate date) {
        BoardData d = load();
        LocalDate prev = BoardCalc.prevBiz(date);
        String day = date.toString(), wk = BoardCalc.weekKey(date), pwk = BoardCalc.weekKey(prev);
        int n = 0, skip = 0;
        for (Kpi k : d.kpis) {
            if (k.weekly()) {
                if (d.week(wk, k.id) != null) { skip++; continue; }
                Double pv = d.week(pwk, k.id);
                if (pv != null && !wk.equals(pwk)) { d.weeks.computeIfAbsent(wk, x -> new LinkedHashMap<>()).put(k.id, pv); n++; }
            } else {
                if (d.day(day, k.id) != null) { skip++; continue; }
                Double pv = d.day(prev.toString(), k.id);
                if (pv != null) { d.entries.computeIfAbsent(day, x -> new LinkedHashMap<>()).put(k.id, pv); n++; }
            }
        }
        if (n == 0) return Op.bad("가져올 전 영업일(" + prev + ") 값이 없습니다.");
        save(d);
        return Op.ok("전 영업일 " + prev + "에서 " + n + "건을 넣었습니다." + (skip > 0 ? " 이미 있던 " + skip + "건은 그대로 두었습니다." : ""));
    }

    static String normName(String t) { return Txt.str(t).replaceAll("[\\s/·.()%]", "").toLowerCase(Locale.ROOT); }

    static Kpi findKpi(BoardData d, String nm) {
        String key = normName(nm);
        if (key.isEmpty()) return null;
        for (Kpi k : d.kpis) if (k.name.equals(nm)) return k;
        for (Kpi k : d.kpis) if (normName(k.name).equals(key)) return k;
        if (nm.contains("장기물")) for (Kpi k : d.kpis) if (k.name.toUpperCase().contains("IVR") && k.name.contains("장기물")) return k;
        if (nm.contains("인보험")) for (Kpi k : d.kpis) if (k.name.toUpperCase().contains("IVR") && k.name.contains("인보험")) return k;
        List<Kpi> hits = new ArrayList<>();
        for (Kpi k : d.kpis) { String n = normName(k.name); if (!n.isEmpty() && (n.startsWith(key) || key.startsWith(n))) hits.add(k); }
        if (hits.size() == 1) return hits.get(0);
        if (key.contains("ivr") && key.contains("수신율"))
            for (Kpi k : d.kpis) if (k.name.toUpperCase().contains("IVR") && k.name.contains("장기물")) return k;
        return hits.isEmpty() ? null : hits.get(0);
    }

    /** 엑셀에서 복사한 '지표 이름 · 값' 줄. 조직 이름이 앞에 붙어도 되고, 같은 이름이 여러 조직에 있으면 조직 이름으로 고른다. */
    public record PasteRow(String src, boolean ok, String why, String kid, Double value) { }

    public List<PasteRow> paste(String text) {
        BoardData d = load();
        List<PasteRow> out = new ArrayList<>();
        for (String ln : Txt.str(text).split("\\r?\\n")) {
            String line = ln.trim();
            if (line.isEmpty()) continue;
            List<String> cells = new ArrayList<>();
            for (String c : line.split("\\t|,(?!\\d{3})|\\s{2,}")) if (!c.isBlank()) cells.add(c.trim());
            String name = null;
            Double v = null;
            for (int i = cells.size() - 1; i >= 1; i--) {
                String t = cells.get(i).replace(",", "").replaceAll("[%건원점콜명일억만]", "").trim();
                if (t.matches("-?\\d*\\.?\\d+")) { v = Double.parseDouble(t); name = String.join(" ", cells.subList(0, i)); break; }
            }
            if (v == null) { out.add(new PasteRow(line, false, "숫자를 찾지 못했습니다", null, null)); continue; }
            Kpi hit = null;
            for (Org o : d.orgs) {
                if (!name.contains(o.name)) continue;
                String rest = name.replace(o.name, "").trim();
                for (Kpi k : d.kpis) if (k.org.equals(o.id) && normName(k.name).equals(normName(rest))) { hit = k; break; }
                if (hit != null) break;
            }
            if (hit == null) hit = findKpi(d, name);
            if (hit == null) { out.add(new PasteRow(line, false, "'" + name + "'와 같은 이름의 지표가 없습니다", null, null)); continue; }
            out.add(new PasteRow(line, true, d.orgName(hit.org) + " · " + hit.name + " ← " + BoardCalc.fmt(v, hit.uom) + (hit.weekly() ? " (주간)" : ""), hit.id, v));
        }
        return out;
    }

    // ── 이슈 ──
    public synchronized Op saveIssue(String id, Map<String, String> p) {
        BoardData d = load();
        String title = Txt.fit(p.get("title"), 200, false).trim();
        if (title.isEmpty()) return Op.bad("이슈 제목을 적으세요.");
        if (d.org(Txt.str(p.get("org"))) == null) return Op.bad("조직을 고르세요.");
        boolean edit = !Txt.blank(id) && d.issues.containsKey(id);
        String key = edit ? id : "i" + System.currentTimeMillis();
        Issue x = edit ? d.issues.get(id) : new Issue();
        x.org = Txt.str(p.get("org"));
        x.unit = Txt.fit(p.get("unit"), 80, false);
        x.cat = CATS.contains(p.get("cat")) ? p.get("cat") : "기타";
        x.lv = LEVELS.contains(p.get("lv")) ? p.get("lv") : "중";
        x.st = STATES.contains(p.get("st")) ? p.get("st") : "진행";
        x.title = title;
        x.note = Txt.fit(p.get("note"), 4000, true).trim();
        if (x.at == null) x.at = java.time.Instant.now().toString();
        d.issues.put(key, x);
        save(d);
        return Op.ok(edit ? "이슈를 고쳤습니다." : "이슈를 등록했습니다.", key);
    }

    public synchronized Op deleteIssue(String id) {
        BoardData d = load();
        Issue x = d.issues.remove(id);
        if (x == null) return Op.fail(404, "이슈를 찾을 수 없습니다.");
        save(d);
        return Op.ok("'" + x.title + "' 이슈를 지웠습니다.");
    }

    /** 계획 이탈 지표로 이슈 초안 만들기(같은 조직에 그 지표 이름의 진행 중 이슈가 있으면 건너뜀). */
    public synchronized Op autoIssues(String period, LocalDate date) {
        BoardData d = load();
        BoardCalc c = new BoardCalc(d, period, date);
        int made = 0, skip = 0;
        long base = System.currentTimeMillis();
        for (BoardCalc.Result r : c.missing()) {
            Kpi k = r.k();
            boolean exists = d.issues.values().stream().anyMatch(x -> x != null && x.open() && k.org.equals(x.org) && x.title.contains(k.name));
            if (exists) { skip++; continue; }
            Issue x = new Issue();
            x.org = k.org; x.unit = k.unit; x.cat = "추진과제"; x.lv = "중"; x.st = "진행";
            x.title = k.name + " 계획 이탈";
            x.note = String.format("현황판에서 만든 초안입니다. 달성률 %.1f%% · 실적 %s · 기준 %s.", r.a(), r.show(), date);
            x.at = java.time.Instant.now().toString();
            d.issues.put("i" + (base + made), x);
            made++;
        }
        if (made == 0) return Op.bad("새로 만들 이탈 이슈가 없습니다." + (skip > 0 ? " (이미 있는 " + skip + "건)" : ""));
        save(d);
        return Op.ok("이슈 초안 " + made + "건을 등록했습니다." + (skip > 0 ? " 이미 있는 " + skip + "건은 건너뛰었습니다." : ""));
    }

    // ── 집중 사항·보고 항목 ──
    public synchronized Op saveFocus(String text) {
        BoardData d = load();
        List<Focus> out = new ArrayList<>();
        for (String l : Txt.str(text).split("\\r?\\n")) {
            String t = l.trim();
            if (t.isEmpty() || out.size() >= 30) continue;
            int i = t.indexOf('|');
            out.add(i < 0 ? new Focus(Txt.fit(t, 200, false), "") : new Focus(Txt.fit(t.substring(0, i).trim(), 200, false), Txt.fit(t.substring(i + 1).trim(), 200, false)));
        }
        d.focus = out;
        save(d);
        return Op.ok("이번 주 집중 사항 " + out.size() + "개를 저장했습니다.");
    }

    public synchronized Op saveCard(Integer index, Map<String, String> p) {
        BoardData d = load();
        Card c = new Card();
        c.lab = Txt.fit(p.get("lab"), 60, false).trim();
        c.val = Txt.fit(p.get("val"), 40, false).trim();
        c.uom = Txt.fit(p.get("uom"), 20, false).trim();
        c.sub = Txt.fit(p.get("sub"), 120, false).trim();
        c.st = List.of("", "g", "a", "r").contains(Txt.str(p.get("st"))) ? Txt.str(p.get("st")) : "";
        if (c.lab.isEmpty() || c.val.isEmpty()) return Op.bad("항목 이름과 값을 적으세요.");
        if (index != null && index >= 0 && index < d.cards.size()) d.cards.set(index, c); else d.cards.add(c);
        save(d);
        return Op.ok(index != null ? "보고 항목을 고쳤습니다." : "보고 항목을 추가했습니다.");
    }

    public synchronized Op deleteCard(int index) {
        BoardData d = load();
        if (index < 0 || index >= d.cards.size()) return Op.fail(404, "항목을 찾을 수 없습니다.");
        d.cards.remove(index);
        save(d);
        return Op.ok("보고 항목을 내렸습니다.");
    }

    // ── 조직·지표 ──
    public synchronized Op saveOrg(Map<String, String> p) {
        BoardData d = load();
        String id = Txt.str(p.get("id")), name = Txt.fit(p.get("name"), 60, false).trim();
        if (name.isEmpty()) return Op.bad("조직 이름을 적으세요.");
        List<String> units = new ArrayList<>();
        for (String u : Txt.str(p.get("units")).split("[,\\n]")) if (!u.isBlank() && !units.contains(u.trim())) units.add(Txt.fit(u.trim(), 60, false));
        String kind = "스탭".equals(p.get("kind")) ? "스탭" : "사업부";
        Org o = d.org(id);
        for (Org x : d.orgs) if (x != o && x.name.equals(name)) return Op.bad("같은 이름의 조직 '" + name + "'이 이미 있습니다.");
        if (o == null) {
            String nid = "o" + Long.toString(System.currentTimeMillis(), 36);
            d.orgs.add(new Org(nid, name, kind, units));
        } else { o.name = name; o.kind = kind; o.units = units; }
        save(d);
        return Op.ok(o == null ? "조직을 추가했습니다." : "조직을 고쳤습니다.");
    }

    public synchronized Op deleteOrg(String id) {
        BoardData d = load();
        Org o = d.org(id);
        if (o == null) return Op.fail(404, "조직을 찾을 수 없습니다.");
        long ks = d.kpis.stream().filter(k -> k.org.equals(id)).count();
        if (ks > 0) return Op.bad("이 조직의 지표 " + ks + "개를 먼저 지우거나 다른 조직으로 옮기세요.");
        d.orgs.remove(o);
        save(d);
        return Op.ok("'" + o.name + "' 조직을 지웠습니다.");
    }

    public synchronized Op saveKpi(Map<String, String> p) {
        BoardData d = load();
        String name = Txt.fit(p.get("name"), 80, false).trim();
        if (name.isEmpty()) return Op.bad("지표 이름을 적으세요.");
        if (d.org(Txt.str(p.get("org"))) == null) return Op.bad("조직을 고르세요.");
        double target, stretch;
        try {
            target = Double.parseDouble(Txt.str(p.get("target")).replace(",", "").trim());
            stretch = Txt.blank(p.get("stretch")) ? 0 : Double.parseDouble(Txt.str(p.get("stretch")).replace(",", "").trim());
        } catch (NumberFormatException e) { return Op.bad("목표는 숫자로 적으세요."); }
        if (target <= 0) return Op.bad("목표는 0보다 커야 합니다.");
        Kpi k = d.kpi(Txt.str(p.get("id")));
        for (Kpi x : d.kpis) if (x != k && x.name.equals(name) && x.org.equals(p.get("org"))) return Op.bad("이 조직에 같은 이름의 지표 '" + name + "'이 이미 있습니다.");
        boolean fresh = k == null;
        if (fresh) { k = new Kpi(); k.id = "k" + Long.toString(System.currentTimeMillis(), 36); d.kpis.add(k); }
        k.name = name;
        k.org = p.get("org");
        k.unit = Txt.fit(p.get("unit"), 60, false);
        k.grp = "vu".equals(p.get("grp")) ? "vu" : "base";
        k.calc = List.of("sum", "avg", "lower").contains(p.get("calc")) ? p.get("calc") : "avg";
        k.cycle = "w".equals(p.get("cycle")) ? "w" : "d";
        k.target = target;
        k.stretch = stretch;
        k.uom = Txt.fit(p.get("uom"), 12, false).trim();
        save(d);
        return Op.ok(fresh ? "'" + name + "' 지표를 추가했습니다." : "'" + name + "' 지표를 고쳤습니다.");
    }

    public synchronized Op deleteKpi(String id) {
        BoardData d = load();
        Kpi k = d.kpi(id);
        if (k == null) return Op.fail(404, "지표를 찾을 수 없습니다.");
        d.kpis.remove(k);
        save(d);
        return Op.ok("'" + k.name + "' 지표를 지웠습니다. 입력했던 값은 백업에 남아 있습니다.");
    }

    // ── 백업 ──
    public synchronized String exportJson() {
        try { return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(load()); }
        catch (Exception e) { return "{}"; }
    }

    /** 예전 현황판의 '백업·복원' 파일이나 이 화면에서 내려받은 파일을 넣는다(전부 바꾸기). */
    public synchronized Op importJson(String text) {
        BoardData in;
        try {
            Object raw = JSON.readValue(text, Object.class);
            if (raw instanceof Map<?, ?> m && m.get(MAIN) instanceof String s) text = s; // Portal 1.7~1.9 내보내기(키 묶음)
            in = JSON.readValue(text, BoardData.class);
        } catch (Exception e) {
            return Op.bad("현황판 백업 파일(JSON)이 아닙니다.");
        }
        if (in.orgs.isEmpty() && in.kpis.isEmpty() && in.entries.isEmpty() && in.issues.isEmpty())
            return Op.bad("파일에 현황판 자료(조직·지표·실적·이슈)가 없습니다.");
        BoardData m = migrate(in);
        save(m);
        Set<String> days = new HashSet<>(m.entries.keySet());
        return Op.ok("불러왔습니다 — 조직 " + m.orgs.size() + " · 지표 " + m.kpis.size() + " · 실적 " + days.size() + "일 · 이슈 " + m.issues.size() + "건.");
    }

    public static LocalDate date(String s) {
        LocalDate d = Kst.date(s);
        return d == null ? Kst.today() : d;
    }
}
