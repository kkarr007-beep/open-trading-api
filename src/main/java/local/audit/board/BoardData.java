package local.audit.board;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 경영 현황판 자료. 예전 현황판(HTML)의 저장 형식(exec-board-v3, 판 7)과 같은 모양이라
 * 그동안 입력한 값과 예전 백업 파일을 그대로 읽고 쓴다. 모르는 항목(화면 배치 등)은 그대로 보존한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class BoardData {
    public int v = 7;
    public String saved;
    public List<Org> orgs = new ArrayList<>();
    public List<Kpi> kpis = new ArrayList<>();
    /** 날짜(yyyy-MM-dd) → 지표 ID → 값. */
    public Map<String, Map<String, Object>> entries = new TreeMap<>();
    /** 주(월요일 날짜) → 지표 ID → 값. */
    public Map<String, Map<String, Object>> weeks = new TreeMap<>();
    public Map<String, Issue> issues = new LinkedHashMap<>();
    public List<Focus> focus = new ArrayList<>();
    public List<Card> cards = new ArrayList<>();
    private final Map<String, Object> other = new LinkedHashMap<>();

    @JsonAnySetter public void put(String k, Object val) { other.put(k, val); }
    @JsonAnyGetter public Map<String, Object> other() { return other; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Org {
        public String id = "", name = "", kind = "사업부";
        public List<String> units = new ArrayList<>();
        public Org() { }
        public Org(String id, String name, String kind, List<String> units) { this.id = id; this.name = name; this.kind = kind; this.units = new ArrayList<>(units); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Kpi {
        public String id = "", org = "", unit = "", grp = "base", name = "", calc = "avg", cycle = "d", uom = "";
        public double target, stretch;
        public Kpi() { }
        public Kpi(String id, String org, String grp, String name, String calc, String cycle, double target, double stretch, String uom) {
            this.id = id; this.org = org; this.grp = grp; this.name = name; this.calc = calc; this.cycle = cycle; this.target = target; this.stretch = stretch; this.uom = uom;
        }
        public boolean weekly() { return "w".equals(cycle); }
        public String calcName() { return "sum".equals(calc) ? "누적형" : "lower".equals(calc) ? "역방향" : "평균형"; }
        public String grpName() { return "vu".equals(grp) ? "Value-up" : "기본"; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Issue {
        public String org = "", unit = "", cat = "기타", lv = "중", st = "진행", title = "", note = "", at;
        public String level() { return "상".equals(lv) || "하".equals(lv) ? lv : "중"; }
        public boolean open() { return !"해결".equals(st); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Focus {
        public String t = "", w = "";
        public Focus() { }
        public Focus(String t, String w) { this.t = t; this.w = w; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Card {
        public String lab = "", val = "", uom = "", sub = "", st = "";
    }

    public static BoardData seed() {
        BoardData d = new BoardData();
        d.orgs.add(new Org("c1", "고객1 사업부", "사업부", List.of("상품콜 1파트", "상품콜 2파트", "지원파트")));
        d.orgs.add(new Org("c2", "고객2 사업부", "사업부", List.of("다이렉트콜 1파트", "다이렉트콜 2파트")));
        d.orgs.add(new Org("cp", "장기일반보상사업부", "사업부", List.of("장기 지급심사파트", "일반 지급심사파트", "인 지급심사파트", "물 지급심사파트", "손해조사팀")));
        d.orgs.add(new Org("st", "스탭", "스탭", List.of("경영지원파트", "인사파트", "경영진단파트")));
        Object[][] k = {
            { "k1", "c1", "base", "생산성", "avg", "d", 95, 110, "콜" }, { "k2", "c1", "base", "S/L 지표", "avg", "d", 85, 90, "%" },
            { "k3", "c1", "base", "고객만족", "avg", "d", 95, 96, "점" }, { "ivr_lt", "c1", "base", "IVR 수신율 (장기물)", "avg", "w", 92, 95, "%" },
            { "ivr_lf", "c1", "base", "IVR 수신율 (인보험)", "avg", "w", 92, 95, "%" }, { "k4", "c1", "vu", "계약유지", "sum", "d", 1.6, 0, "억원" },
            { "k5", "c1", "vu", "실효 최고", "sum", "d", 7.3, 0, "억원" }, { "k6", "c1", "vu", "신용대출", "sum", "d", 1900, 0, "건" },
            { "c2k1", "c2", "base", "생산성", "avg", "d", 95, 110, "콜" }, { "c2k2", "c2", "base", "S/L 지표", "avg", "d", 85, 90, "%" },
            { "c2k3", "c2", "base", "고객만족", "avg", "d", 95, 96, "점" }, { "c2vu1", "c2", "vu", "다이렉트 인수", "sum", "d", 2.1, 0, "억원" },
            { "c2vu2", "c2", "vu", "신용대출", "sum", "d", 1600, 0, "건" }, { "cpk1", "cp", "base", "지급 처리일수", "lower", "d", 3.5, 2.8, "일" },
            { "cpk2", "cp", "base", "지급 정확도", "avg", "d", 99.5, 99.8, "%" }, { "cpk3", "cp", "base", "고객만족", "avg", "d", 95, 96, "점" },
            { "cpvu1", "cp", "vu", "누수 환수", "sum", "d", 1.2, 0, "억원" }, { "stk1", "st", "base", "인력 가동률", "avg", "d", 92, 95, "%" },
            { "stk2", "st", "base", "교육 이수율", "avg", "d", 100, 100, "%" }, { "stvu1", "st", "vu", "비용 효율", "lower", "d", 100, 95, "지수" } };
        for (Object[] r : k) d.kpis.add(new Kpi((String) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4], (String) r[5],
            ((Number) r[6]).doubleValue(), ((Number) r[7]).doubleValue(), (String) r[8]));
        return d;
    }

    /** 값이 숫자일 때만. */
    public static Double num(Object o) {
        if (o instanceof Number n) { double x = n.doubleValue(); return Double.isNaN(x) ? null : x; }
        return null;
    }

    public Double day(String date, String kid) { Map<String, Object> m = entries.get(date); return m == null ? null : num(m.get(kid)); }
    public Double week(String wk, String kid) { Map<String, Object> m = weeks.get(wk); return m == null ? null : num(m.get(kid)); }

    public Org org(String id) { for (Org o : orgs) if (o.id.equals(id)) return o; return null; }
    public String orgName(String id) { Org o = org(id); return o == null ? "(삭제된 조직)" : o.name; }
    public Kpi kpi(String id) { for (Kpi k : kpis) if (k.id.equals(id)) return k; return null; }
}
