package local.audit.tools;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import local.audit.Db;
import local.audit.Kst;
import local.audit.Op;
import local.audit.Settings;
import local.audit.Txt;
import local.audit.work.Work;

/**
 * 법인카드 점검. 카드 사용내역 표를 읽어 열을 연결하고, 규칙별로 점수를 매긴다.
 * 소명·정상 처리는 카드번호+승인번호로 기억해 다음 회차에도 이어진다. 집주소는 시·구·동 이름만 남기고 원문은 저장하지 않는다.
 */
@Service
public class CardCheck {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String SETTINGS = "cardcheck.settings";
    static final String LAST_HEADERS = "cardcheck.headers";

    public record Field(String key, String label, boolean required, String... aliases) { }

    public static final List<Field> FIELDS = List.of(
        new Field("card", "카드번호", false, "카드번호", "카드"),
        new Field("approval", "승인번호", false, "승인번호"),
        new Field("dept", "부서", false, "부서", "부서명", "소속", "팀"),
        new Field("name", "이름", true, "이름", "성명", "사용자", "사용자명", "사원명", "카드사용자"),
        new Field("date", "승인일자", true, "승인일자", "결제일자", "사용일자", "거래일자", "승인일", "이용일자"),
        new Field("time", "승인시간", false, "승인시간", "결제시간", "사용시간", "승인시각", "이용시간"),
        new Field("amount", "승인금액", true, "승인금액", "결제금액", "사용금액", "이용금액", "금액"),
        new Field("cancelAmt", "취소금액", false, "취소금액"),
        new Field("cancelYn", "취소여부", false, "취소여부", "취소구분"),
        new Field("costType", "비용구분", false, "비용구분", "비용항목", "계정과목"),
        new Field("bizNo", "가맹점 사업자번호", false, "가맹점사업자번호", "사업자번호", "가맹점사업자등록번호", "사업자등록번호"),
        new Field("merchant", "가맹점명", true, "가맹점명", "가맹점", "사용처", "업장", "가맹점명(업장)"),
        new Field("industry", "업종", false, "업종", "업종명"),
        new Field("bizType", "업태", false, "업태"),
        new Field("merchantAddr", "가맹점 주소", false, "가맹점주소", "가맹점소재지", "가맹점위치"),
        new Field("homeAddr", "집주소", false, "집주소", "자택주소", "주소(자택)", "자택"),
        new Field("purpose", "사용내역", false, "사용내역", "사용목적", "적요", "사용용도", "사용사유"),
        new Field("regDate", "결재내역 등록일", false, "결재내역등록일", "결재등록일", "등록일"));

    /** 규칙 이름. 화면·보고서 표시용. */
    public static final Map<String, String> RULES = new LinkedHashMap<>();
    static {
        RULES.put("restricted", "제한업종");
        RULES.put("bar", "주점");
        RULES.put("night", "심야");
        RULES.put("holiday", "주말·공휴일");
        RULES.put("split", "쪼개기 결제");
        RULES.put("multicard", "여러 카드 나눠 결제");
        RULES.put("near", "한도 바로 아래");
        RULES.put("round", "딱 떨어지는 고액");
        RULES.put("repeat", "같은 금액 반복");
        RULES.put("home", "자택 인근");
        RULES.put("combo", "자택 인근 + 휴일·심야");
        RULES.put("second", "식사 후 주점");
        RULES.put("purpose", "사용 사유 부실");
        RULES.put("late", "늦은 등록");
    }

    /** 규칙마다 무엇을 보는지(처음 점검하는 사람용 설명). */
    public static final Map<String, String> HELP = new LinkedHashMap<>();
    static {
        HELP.put("restricted", "업종·업태·가맹점명에 제한업종 낱말(유흥주점·노래방·골프·안마 등)이 있는 건. 업종코드를 '일반음식점'으로 등록한 유흥업소도 가맹점명으로 잡습니다.");
        HELP.put("bar", "주점·호프·포차 등. 그 자체로 위반은 아니지만 심야·휴일·쪼개기와 겹치면 위험이 커집니다.");
        HELP.put("night", "심야 시간대(기본 23시~06시) 사용. 야근 식대가 많으면 시작 시각을 늦추세요.");
        HELP.put("holiday", "토·일요일과 공휴일 사용. 휴일근무·출장은 '예외'로 등록하면 다음부터 빠집니다.");
        HELP.put("split", "같은 사람(카드)·같은 날·같은 가맹점에서 가까운 시각에 여러 번 결제했는데, 한 건씩은 한도 아래이고 합하면 한도를 넘는 건(전결·일상감사 회피 의심).");
        HELP.put("multicard", "같은 부서가 같은 가맹점에서 같은 시간대에 카드 여러 장으로 나눠 결제했고 합계가 한도를 넘는 건(실제 적발 사례: 97만 원을 49만·48만 원으로 나눔).");
        HELP.put("near", "한도의 90% 이상~한도 미만 금액. 1인당 기준·전결 한도에 맞춘 금액 조정을 의심합니다.");
        HELP.put("round", "10만 원 이상이면서 만 원 단위로 딱 떨어지는 금액. 실제 음식값은 끝자리가 남는 경우가 많아 현금화(카드깡)를 의심합니다.");
        HELP.put("repeat", "같은 사람이 같은 가맹점에서 같은 금액을 여러 번(기본 3회 이상) 결제한 건. 정기 결제인지, 허위 결제인지 확인합니다.");
        HELP.put("home", "가맹점 주소가 집주소와 같은 동(또는 같은 도로)이면 점수 전부, 같은 구이면 4분의 1(회사와 집이 같은 구인 사람이 많아서). 집주소 원문은 저장하지 않습니다.");
        HELP.put("combo", "자택 인근 사용이 휴일·심야와 겹친 건. 개인 사용 가능성이 가장 높은 조합입니다.");
        HELP.put("second", "식당 결제 뒤 몇 시간 안에(기본 4시간) 다른 곳 주점·제한업종 결제(2차).");
        HELP.put("purpose", "사용내역(사용 사유)이 비었거나 너무 짧거나 '기타·업무·식대'처럼 모호한 건.");
        HELP.put("late", "사용일보다 한참 뒤(기본 30일 초과) 결재내역을 등록한 건.");
    }

    /** 점검 기준. 화면에서 고친다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Config {
        public long limit = 300_000;
        public int splitMinutes = 60;
        public int nightFrom = 23;
        public int nightTo = 6;
        public long roundUnit = 10_000;
        public long roundMin = 100_000;
        public int nearPct = 90;
        public int repeatMin = 3;
        public long repeatAmountMin = 10_000;
        public int secondHours = 4;
        public int lateDays = 30;
        public int purposeMinLength = 4;
        public String restrictedWords = "유흥주점, 단란주점, 룸살롱, 룸싸롱, 나이트, 가라오케, 노래방, 노래연습장, 카지노, 복권, 경마, 경륜, 경정, 골프, 사우나, 찜질, 안마, 마사지, 스파, 피부관리, 미용실, 네일, PC방, 피씨방, 오락실, 게임장, 당구장, 스크린";
        public String barWords = "주점, 호프, 포차, 이자카야, 와인바, 칵테일, 맥주, 펍, 술집";
        public String mealWords = "음식, 식당, 한식, 중식, 일식, 양식, 고기, 갈비, 횟집, 분식, 뷔페";
        public String vagueWords = "기타, 업무, 식대, 식사, 경비, 비용, 회의, 업무추진";
        public Map<String, Boolean> on = new LinkedHashMap<>();
        public Map<String, Integer> score = new LinkedHashMap<>();

        public Config() {
            int[] s = { 40, 10, 15, 15, 30, 25, 10, 10, 10, 20, 15, 10, 5, 5 };
            int i = 0;
            for (String k : RULES.keySet()) { on.put(k, true); score.put(k, s[i++]); }
        }

        public boolean on(String rule) { return on.getOrDefault(rule, true); }
        public int score(String rule) { return Math.max(0, Math.min(100, score.getOrDefault(rule, 10))); }
    }

    private final Db db;
    private final Settings settings;

    public CardCheck(Db db, Settings settings) {
        this.db = db;
        this.settings = settings;
    }

    // ------------------------------------------------------------------ 기준

    public Config config() {
        try {
            Config c = JSON.readValue(settings.get(SETTINGS, "{}"), Config.class);
            Config d = new Config();
            for (String k : RULES.keySet()) { c.on.putIfAbsent(k, d.on.get(k)); c.score.putIfAbsent(k, d.score.get(k)); }
            return c;
        } catch (Exception e) {
            return new Config();
        }
    }

    public Op saveConfig(Map<String, String> p) {
        Config c = config();
        c.limit = num(p.get("limit"), c.limit, 1, 100_000_000);
        c.splitMinutes = (int) num(p.get("splitMinutes"), c.splitMinutes, 1, 1440);
        c.nightFrom = (int) num(p.get("nightFrom"), c.nightFrom, 0, 23);
        c.nightTo = (int) num(p.get("nightTo"), c.nightTo, 0, 23);
        c.roundUnit = num(p.get("roundUnit"), c.roundUnit, 1000, 10_000_000);
        c.roundMin = num(p.get("roundMin"), c.roundMin, 0, 100_000_000);
        c.nearPct = (int) num(p.get("nearPct"), c.nearPct, 50, 99);
        c.repeatMin = (int) num(p.get("repeatMin"), c.repeatMin, 2, 100);
        c.repeatAmountMin = num(p.get("repeatAmountMin"), c.repeatAmountMin, 0, 100_000_000);
        c.secondHours = (int) num(p.get("secondHours"), c.secondHours, 1, 12);
        c.lateDays = (int) num(p.get("lateDays"), c.lateDays, 1, 365);
        c.purposeMinLength = (int) num(p.get("purposeMinLength"), c.purposeMinLength, 0, 50);
        c.restrictedWords = Txt.fit(p.getOrDefault("restrictedWords", c.restrictedWords), 4000, false);
        c.barWords = Txt.fit(p.getOrDefault("barWords", c.barWords), 2000, false);
        c.mealWords = Txt.fit(p.getOrDefault("mealWords", c.mealWords), 2000, false);
        c.vagueWords = Txt.fit(p.getOrDefault("vagueWords", c.vagueWords), 2000, false);
        for (String k : RULES.keySet()) {
            c.on.put(k, "1".equals(p.get("on_" + k)));
            c.score.put(k, (int) num(p.get("score_" + k), c.score(k), 0, 100));
        }
        try {
            settings.set(SETTINGS, JSON.writeValueAsString(c));
        } catch (Exception e) {
            return Op.fail(500, "기준을 저장하지 못했습니다.");
        }
        int n = 0;
        for (Map<String, Object> r : db.rows("SELECT Id FROM CardRuns WHERE State='점검 완료'")) { recheck((String) r.get("Id")); n++; }
        return Op.ok("점검 기준을 저장했습니다." + (n > 0 ? " 회차 " + n + "개에 다시 적용했습니다." : ""));
    }

    public Op resetConfig() {
        settings.set(SETTINGS, "{}");
        for (Map<String, Object> r : db.rows("SELECT Id FROM CardRuns WHERE State='점검 완료'")) recheck((String) r.get("Id"));
        return Op.ok("점검 기준을 처음 값으로 되돌렸습니다.");
    }

    static long num(String s, long fallback, long min, long max) {
        if (s == null || s.isBlank()) return fallback;
        try {
            long v = Long.parseLong(s.replaceAll("[,\\s원]", ""));
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ 불러오기

    static String norm(String h) { return Txt.str(h).replaceAll("[\\s\\u00a0_·.]", "").toLowerCase(Locale.ROOT); }

    /** 표에서 머리글 줄을 찾는다: 아는 열 이름이 가장 많이 맞는 줄. */
    static int[] headerOf(List<List<String>> rows) {
        int best = -1, bestScore = 0;
        for (int r = 0; r < Math.min(rows.size(), 25); r++) {
            int score = 0;
            for (String cell : rows.get(r)) {
                String n = norm(cell);
                if (n.isEmpty()) continue;
                for (Field f : FIELDS) for (String a : f.aliases()) if (norm(a).equals(n)) { score++; break; }
            }
            if (score > bestScore) { bestScore = score; best = r; }
        }
        return new int[] { best, bestScore };
    }

    /** 열 자동 연결. 지난번 연결한 머리글 → 같은 이름 → 이름을 포함하는 순서. */
    Map<String, Integer> autoMap(List<String> headers) {
        Map<String, Integer> map = new LinkedHashMap<>();
        Set<Integer> used = new HashSet<>();
        Map<String, String> last = new HashMap<>();
        try { last = JSON.readValue(settings.get(LAST_HEADERS, "{}"), new TypeReference<Map<String, String>>() { }); } catch (Exception ignored) { }
        for (Field f : FIELDS) {
            String want = last.get(f.key());
            if (want == null) continue;
            for (int i = 0; i < headers.size(); i++) if (!used.contains(i) && norm(headers.get(i)).equals(norm(want))) { map.put(f.key(), i); used.add(i); break; }
        }
        for (Field f : FIELDS) {
            if (map.containsKey(f.key())) continue;
            outer:
            for (String a : f.aliases()) for (int i = 0; i < headers.size(); i++)
                if (!used.contains(i) && norm(headers.get(i)).equals(norm(a))) { map.put(f.key(), i); used.add(i); break outer; }
        }
        for (Field f : FIELDS) {
            if (map.containsKey(f.key())) continue;
            outer:
            for (String a : f.aliases()) {
                if (norm(a).length() < 3) continue;
                for (int i = 0; i < headers.size(); i++)
                    if (!used.contains(i) && norm(headers.get(i)).contains(norm(a))) { map.put(f.key(), i); used.add(i); break outer; }
            }
        }
        return map;
    }

    /** 표 파일이나 붙여 넣은 글을 새 회차로 올린다. 열 연결은 다음 화면에서 확인한다. */
    public Op upload(String title, String fileName, List<Sheet.Table> tables) {
        Sheet.Table pick = null;
        int head = -1, best = -1;
        for (Sheet.Table t : tables) {
            int[] h = headerOf(t.rows());
            if (h[1] > best) { best = h[1]; head = h[0]; pick = t; }
        }
        if (pick == null || head < 0 || best < 2) return Op.bad("표에서 머리글(이름·승인일자·승인금액·가맹점명 같은 열 이름)을 찾지 못했습니다. 첫 줄에 열 이름이 있는지 보세요.");
        List<String> headers = new ArrayList<>(pick.rows().get(head));
        List<List<String>> body = new ArrayList<>();
        for (int r = head + 1; r < pick.rows().size(); r++) {
            List<String> row = pick.rows().get(r);
            if (row.stream().allMatch(c -> c == null || c.isBlank())) continue;
            body.add(row);
        }
        if (body.isEmpty()) return Op.bad("머리글 아래에 사용내역이 없습니다.");
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("sheet", pick.name());
        pending.put("headers", headers);
        pending.put("rows", body);
        String id = Txt.newId();
        String t = Txt.blank(title) ? Kst.today().toString().substring(0, 7) + " 법인카드 점검" : Txt.fit(title, 120, false);
        try {
            String pj = JSON.writeValueAsString(pending), mj = JSON.writeValueAsString(autoMap(headers));
            db.tx(conn -> Db.update(conn, "INSERT INTO CardRuns(Id, Title, FileName, CreatedAt, State, Pending, Mapping, RowCount) VALUES (?,?,?,?, '연결 필요', ?,?,?)",
                id, t, Txt.fit(fileName, 200, false), Kst.stamp(), pj, mj, body.size()));
        } catch (Exception e) {
            return Op.fail(500, "표를 저장하지 못했습니다.");
        }
        return Op.ok("표를 읽었습니다(" + body.size() + "줄). 열 연결을 확인하세요.", id);
    }

    /** 열 연결 화면에 쓸 머리글·자동 연결·앞 몇 줄. */
    public Map<String, Object> pending(String runId) {
        Map<String, Object> run = run(runId);
        if (run == null || run.get("Pending") == null) return null;
        try {
            Map<String, Object> p = JSON.readValue((String) run.get("Pending"), new TypeReference<Map<String, Object>>() { });
            Map<String, Integer> map = JSON.readValue(Txt.str(run.get("Mapping")), new TypeReference<Map<String, Integer>>() { });
            @SuppressWarnings("unchecked") List<List<String>> rows = (List<List<String>>) p.get("rows");
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("run", run);
            v.put("headers", p.get("headers"));
            v.put("map", map);
            v.put("sample", rows.subList(0, Math.min(5, rows.size())));
            v.put("homeCol", map.getOrDefault("homeAddr", -1));
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    /** 열 연결을 확정하고 사용내역을 정리해 넣은 뒤 점검한다. */
    public Op confirm(String runId, Map<String, String> p) {
        Map<String, Object> run = run(runId);
        if (run == null) return Op.fail(404, "점검 회차를 찾을 수 없습니다.");
        if (run.get("Pending") == null) return Op.bad("이미 점검한 회차입니다.");
        Map<String, Object> pend;
        try { pend = JSON.readValue((String) run.get("Pending"), new TypeReference<Map<String, Object>>() { }); } catch (Exception e) { return Op.fail(500, "올린 표를 읽지 못했습니다."); }
        @SuppressWarnings("unchecked") List<String> headers = (List<String>) pend.get("headers");
        @SuppressWarnings("unchecked") List<List<String>> rows = (List<List<String>>) pend.get("rows");
        Map<String, Integer> map = new LinkedHashMap<>();
        for (Field f : FIELDS) {
            Integer c = Txt.intOrNull(p.get("col_" + f.key()));
            if (c != null && c >= 0 && c < headers.size()) map.put(f.key(), c);
        }
        for (Field f : FIELDS) if (f.required() && !map.containsKey(f.key())) return Op.bad("'" + f.label() + "' 열을 골라 주세요.");
        Set<Integer> used = new HashSet<>(map.values());
        int home = map.getOrDefault("homeAddr", -1);
        List<Object[]> out = new ArrayList<>();
        int skipped = 0, rowNo = 0;
        for (List<String> row : rows) {
            rowNo++;
            String name = cell(row, map.get("name")), merchant = cell(row, map.get("merchant"));
            LocalDate date = date(cell(row, map.get("date")));
            Long amount = amount(cell(row, map.get("amount")));
            if (Txt.blank(name) || date == null || amount == null || merchant.startsWith("예시")) { skipped++; continue; }
            String time = time(cell(row, map.get("time")));
            if (time.isEmpty()) time = time(Txt.str(cell(row, map.get("date"))).replaceFirst("^\\S+\\s*", ""));
            Long cancelAmt = amount(cell(row, map.get("cancelAmt")));
            String yn = cell(row, map.get("cancelYn")).trim().toUpperCase(Locale.ROOT);
            boolean cancelled = amount < 0 || (cancelAmt != null && cancelAmt != 0 && Math.abs(cancelAmt) >= Math.abs(amount))
                || yn.equals("Y") || yn.equals("예") || yn.equals("취소") || yn.equals("TRUE") || yn.equals("1") || yn.startsWith("취소");
            String card = cell(row, map.get("card")), appr = cell(row, map.get("approval"));
            String key = !card.isBlank() && !appr.isBlank() ? card.trim() + "|" + appr.trim()
                : Txt.hex(Txt.sha256((name + "|" + date + "|" + time + "|" + amount + "|" + merchant).getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 32);
            LocalDate reg = date(cell(row, map.get("regDate")));
            Map<String, String> extra = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                if (used.contains(i) || i == home) continue;
                String v = cell(row, i);
                if (!v.isBlank()) extra.put(Txt.fit(headers.get(i), 60, false), Txt.fit(v, 500, false));
            }
            String extraJson;
            try { extraJson = JSON.writeValueAsString(extra); } catch (Exception e) { extraJson = "{}"; }
            out.add(new Object[] { runId, rowNo, key, Txt.fit(card, 40, false), Txt.fit(appr, 40, false), Txt.fit(cell(row, map.get("dept")), 80, false),
                Txt.fit(name, 60, false), date.toString(), time, amount, cancelled ? 1 : 0, Txt.fit(cell(row, map.get("costType")), 60, false),
                Txt.fit(cell(row, map.get("bizNo")).replaceAll("[^0-9]", ""), 20, false), Txt.fit(merchant, 120, false), Txt.fit(cell(row, map.get("industry")), 80, false),
                Txt.fit(cell(row, map.get("bizType")), 80, false), Txt.fit(cell(row, map.get("merchantAddr")), 200, false),
                home >= 0 ? String.join("|", area(cell(row, home))) : "", Txt.fit(cell(row, map.get("purpose")), 500, false),
                reg == null ? "" : reg.toString(), extraJson });
        }
        if (out.isEmpty()) return Op.bad("정리할 수 있는 사용내역이 없습니다. 이름·승인일자·승인금액 열을 다시 확인하세요.");
        Map<String, String> remember = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : map.entrySet()) remember.put(e.getKey(), headers.get(e.getValue()));
        String mj, hj;
        try { mj = JSON.writeValueAsString(map); hj = JSON.writeValueAsString(remember); } catch (Exception e) { return Op.fail(500, "열 연결을 저장하지 못했습니다."); }
        int skip = skipped;
        db.tx(conn -> {
            Db.update(conn, "DELETE FROM CardTx WHERE RunId=?", runId);
            for (Object[] a : out) Db.update(conn, """
                INSERT INTO CardTx(RunId, RowNo, TxKey, CardNo, ApprovalNo, Dept, Name, TxDate, TxTime, Amount, Cancelled, CostType, BizNo,
                  Merchant, Industry, BizType, MerchantAddr, HomeArea, Purpose, RegDate, Extra) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", a);
            // 올린 원문(집주소 포함)은 지운다.
            Db.update(conn, "UPDATE CardRuns SET Pending=NULL, Mapping=?, RowCount=?, State='점검 완료', Note=? WHERE Id=?", mj, out.size(),
                skip > 0 ? "읽지 못해 뺀 줄 " + skip + "개" : "", runId);
            Local.put(conn, LAST_HEADERS, hj);
            return null;
        });
        recheck(runId);
        return Op.ok("사용내역 " + out.size() + "건을 점검했습니다." + (skip > 0 ? " (읽지 못한 줄 " + skip + "개는 뺐습니다)" : ""), runId);
    }

    /** Settings.put을 거래 안에서 쓰기 위한 다리. */
    static final class Local {
        static void put(java.sql.Connection conn, String key, String value) throws java.sql.SQLException { Settings.put(conn, key, value); }
    }

    static String cell(List<String> row, Integer col) {
        if (col == null || col < 0 || col >= row.size()) return "";
        String v = row.get(col);
        return v == null ? "" : v.replace(' ', ' ').trim();
    }

    static final Pattern YMD = Pattern.compile("(\\d{4})\\s*[-./년]\\s*(\\d{1,2})\\s*[-./월]\\s*(\\d{1,2})");

    public static LocalDate date(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim();
        Matcher m = YMD.matcher(v);
        try {
            if (m.find()) return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            String d = v.replaceAll("\\D", "");
            if (d.length() >= 8 && v.matches("\\d{8}.*")) return LocalDate.of(Integer.parseInt(d.substring(0, 4)), Integer.parseInt(d.substring(4, 6)), Integer.parseInt(d.substring(6, 8)));
            if (v.matches("\\d{5}(\\.\\d+)?")) { // 엑셀 날짜 일련번호
                double serial = Double.parseDouble(v);
                if (serial > 20000 && serial < 80000) return LocalDate.of(1899, 12, 30).plusDays((long) Math.floor(serial));
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    public static String time(String s) {
        if (s == null || s.isBlank()) return "";
        String v = s.trim().toUpperCase(Locale.ROOT);
        try {
            if (v.matches("0?\\.\\d+")) { // 엑셀 시간(하루의 몇 분의 몇)
                long sec = Math.round(Double.parseDouble(v) * 86400);
                return String.format("%02d:%02d:%02d", sec / 3600 % 24, sec / 60 % 60, sec % 60);
            }
            boolean pm = v.contains("오후") || v.contains("PM"), am = v.contains("오전") || v.contains("AM");
            int h, mi, se = 0;
            Matcher m = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?").matcher(v);
            if (m.find()) {
                h = Integer.parseInt(m.group(1)); mi = Integer.parseInt(m.group(2));
                if (m.group(3) != null) se = Integer.parseInt(m.group(3));
            } else {
                String d = v.replaceAll("\\D", "");
                if (d.length() == 6) { h = Integer.parseInt(d.substring(0, 2)); mi = Integer.parseInt(d.substring(2, 4)); se = Integer.parseInt(d.substring(4, 6)); }
                else if (d.length() == 5) { h = Integer.parseInt(d.substring(0, 1)); mi = Integer.parseInt(d.substring(1, 3)); se = Integer.parseInt(d.substring(3, 5)); }
                else if (d.length() == 4) { h = Integer.parseInt(d.substring(0, 2)); mi = Integer.parseInt(d.substring(2, 4)); }
                else if (d.length() == 3) { h = Integer.parseInt(d.substring(0, 1)); mi = Integer.parseInt(d.substring(1, 3)); }
                else return "";
            }
            if (pm && h < 12) h += 12;
            if (am && h == 12) h = 0;
            if (h > 23 || mi > 59 || se > 59) return "";
            return String.format("%02d:%02d:%02d", h, mi, se);
        } catch (RuntimeException e) {
            return "";
        }
    }

    public static Long amount(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.replaceAll("[,\\s원₩]", "");
        boolean neg = v.startsWith("(") && v.endsWith(")");
        v = v.replace("(", "").replace(")", "");
        try {
            long n = Math.round(Double.parseDouble(v));
            return neg ? -n : n;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 주소

    static final String[][] SIDO = {
        { "서울", "서울" }, { "부산", "부산" }, { "대구", "대구" }, { "인천", "인천" }, { "광주", "광주" }, { "대전", "대전" }, { "울산", "울산" },
        { "세종", "세종" }, { "경기", "경기" }, { "강원", "강원" }, { "충북", "충북" }, { "충청북", "충북" }, { "충남", "충남" }, { "충청남", "충남" },
        { "전북", "전북" }, { "전라북", "전북" }, { "전남", "전남" }, { "전라남", "전남" }, { "경북", "경북" }, { "경상북", "경북" },
        { "경남", "경남" }, { "경상남", "경남" }, { "제주", "제주" } };

    /** 주소 → [시도, 시군구, 동·읍·면, 도로명]. 비교용 이름만 남긴다. */
    public static String[] area(String addr) {
        String sido = "", sgg = "", dong = "", road = "";
        if (addr == null || addr.isBlank()) return new String[] { sido, sgg, dong, road };
        String a = addr.replace(',', ' ');
        Matcher paren = Pattern.compile("\\(([^)]*)\\)").matcher(a);
        while (paren.find()) {
            for (String t : paren.group(1).split("[\\s,]+")) if (dong.isEmpty() && t.matches(".+[동읍면가]") && !t.matches("\\d+.*")) dong = t;
        }
        a = a.replaceAll("\\([^)]*\\)", " ");
        String[] tok = a.trim().split("\\s+");
        int i = 0;
        if (tok.length > 0) {
            for (String[] s : SIDO) if (tok[0].startsWith(s[0])) { sido = s[1]; i = 1; break; }
        }
        List<String> sg = new ArrayList<>();
        for (; i < tok.length && sg.size() < 2; i++) {
            if (tok[i].matches(".+[시군구]") && !tok[i].matches(".*\\d.*")) sg.add(tok[i]);
            else if (!sg.isEmpty()) break;
        }
        sgg = String.join(" ", sg);
        for (; i < tok.length; i++) {
            String t = tok[i];
            if (dong.isEmpty() && t.matches("[가-힣0-9]+[동읍면가]") && !t.matches("\\d+.*") && !t.matches(".*\\d+(번)?가$|.*\\d동$") ) dong = t;
            else if (dong.isEmpty() && t.matches("[가-힣]+\\d*동")) dong = t;
            if (road.isEmpty() && t.matches("[가-힣0-9.]+(로|길)(\\d+[가-힣]*)?")) road = t.replaceAll("\\d+(번)?길$", "").replaceAll("(로|길)\\d+.*$", "$1");
        }
        dong = dong.replaceAll("\\d+동$", "동");
        return new String[] { sido, sgg, dong, road };
    }

    /** 0 = 다름, 1 = 같은 구, 2 = 같은 동(또는 같은 도로). */
    public static int near(String[] home, String[] shop) {
        if (home[1].isEmpty() || shop[1].isEmpty()) return 0;
        if (!home[0].isEmpty() && !shop[0].isEmpty() && !home[0].equals(shop[0])) return 0;
        if (!home[1].equals(shop[1]) && !home[1].endsWith(shop[1]) && !shop[1].endsWith(home[1])) return 0;
        if (home[0].isEmpty() || shop[0].isEmpty()) {
            // 시도가 없으면 '중구'처럼 흔한 구 이름만으로는 판단하지 않는다.
            if (!home[1].contains(" ") && !shop[1].contains(" ") && home[1].endsWith("구")) return 0;
        }
        if (!home[2].isEmpty() && home[2].equals(shop[2])) return 2;
        if (!home[3].isEmpty() && home[3].equals(shop[3])) return 2;
        return 1;
    }

    // ------------------------------------------------------------------ 점검

    static final class Tx {
        long id;
        String key, card, name, dept, date, time, merchant, bizNo, industry, bizType, addr, home, purpose, reg;
        long amount;
        int minute = -1;
        LocalDate day;

        String shop() { return bizNo.isEmpty() ? merchant.replaceAll("\\s+", "") : bizNo; }
        String kind() { return (industry + " " + bizType + " " + merchant).toLowerCase(Locale.ROOT); }
    }

    record Hit(long txId, String rule, int score, String detail) { }

    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        for (String w : Txt.str(s).split("[,\\n]")) if (!w.isBlank()) out.add(w.trim().toLowerCase(Locale.ROOT));
        return out;
    }

    static String hit(String text, List<String> words) {
        for (String w : words) if (text.contains(w)) return w;
        return null;
    }

    static String won(long v) { return String.format("%,d원", v); }

    /** 회차의 사용내역에 규칙을 다시 적용한다. */
    public void recheck(String runId) {
        Config c = config();
        List<Tx> txs = new ArrayList<>();
        for (Map<String, Object> r : db.rows("SELECT * FROM CardTx WHERE RunId=? AND Cancelled=0 ORDER BY TxDate, TxTime, Id", runId)) {
            Tx t = new Tx();
            t.id = ((Number) r.get("Id")).longValue();
            t.key = Txt.str(r.get("TxKey")); t.card = Txt.str(r.get("CardNo")); t.name = Txt.str(r.get("Name")); t.dept = Txt.str(r.get("Dept"));
            t.date = Txt.str(r.get("TxDate")); t.time = Txt.str(r.get("TxTime")); t.merchant = Txt.str(r.get("Merchant")); t.bizNo = Txt.str(r.get("BizNo"));
            t.industry = Txt.str(r.get("Industry")); t.bizType = Txt.str(r.get("BizType")); t.addr = Txt.str(r.get("MerchantAddr"));
            t.home = Txt.str(r.get("HomeArea")); t.purpose = Txt.str(r.get("Purpose")); t.reg = Txt.str(r.get("RegDate"));
            t.amount = ((Number) r.get("Amount")).longValue();
            t.day = LocalDate.parse(t.date);
            if (t.time.length() >= 5) t.minute = Integer.parseInt(t.time.substring(0, 2)) * 60 + Integer.parseInt(t.time.substring(3, 5));
            txs.add(t);
        }
        List<Hit> hits = rules(txs, c);
        List<Map<String, Object>> ex = db.rows("SELECT * FROM CardExceptions");
        Map<Long, Tx> byId = new HashMap<>();
        for (Tx t : txs) byId.put(t.id, t);
        db.tx(conn -> {
            Db.update(conn, "DELETE FROM CardHits WHERE RunId=?", runId);
            for (Hit h : hits) {
                Tx t = byId.get(h.txId());
                String exId = null;
                for (Map<String, Object> e : ex) {
                    String rule = Txt.str(e.get("Rule")), name = Txt.str(e.get("Name")), shop = Txt.str(e.get("Merchant")), day = Txt.str(e.get("TxDate"));
                    if (!rule.isEmpty() && !rule.equals(h.rule())) continue;
                    if (!name.isEmpty() && !name.equals(t.name)) continue;
                    if (!shop.isEmpty() && !shop.equals(t.shop()) && !shop.equals(t.merchant)) continue;
                    if (!day.isEmpty() && !day.equals(t.date)) continue;
                    if (rule.isEmpty() && name.isEmpty() && shop.isEmpty() && day.isEmpty()) continue;
                    exId = (String) e.get("Id");
                    break;
                }
                Db.update(conn, "INSERT INTO CardHits(RunId, TxId, Rule, Score, Detail, ExceptionId) VALUES (?,?,?,?,?,?)",
                    runId, h.txId(), h.rule(), h.score(), Txt.fit(h.detail(), 300, false), exId);
            }
            Db.update(conn, "UPDATE CardRuns SET CheckedAt=? WHERE Id=?", Kst.stamp(), runId);
            return null;
        });
    }

    static List<Hit> rules(List<Tx> txs, Config c) {
        List<Hit> out = new ArrayList<>();
        List<String> restricted = words(c.restrictedWords), bars = words(c.barWords), meals = words(c.mealWords), vague = words(c.vagueWords);
        Set<Long> offOrNight = new HashSet<>();
        for (Tx t : txs) {
            String kind = t.kind();
            String w = hit(kind, restricted);
            if (c.on("restricted") && w != null) out.add(new Hit(t.id, "restricted", c.score("restricted"), "제한업종 '" + w + "'"));
            else if (c.on("bar") && hit(kind, bars) != null) out.add(new Hit(t.id, "bar", c.score("bar"), "주점 '" + hit(kind, bars) + "'"));
            if (t.minute >= 0) {
                int h = t.minute / 60;
                boolean night = c.nightFrom > c.nightTo ? (h >= c.nightFrom || h < c.nightTo) : (h >= c.nightFrom && h < c.nightTo);
                if (night) {
                    offOrNight.add(t.id);
                    if (c.on("night")) out.add(new Hit(t.id, "night", c.score("night"), "심야 " + t.time.substring(0, 5)));
                }
            }
            if (Work.isOff(t.day)) {
                offOrNight.add(t.id);
                String hol = Work.holiday(t.date);
                String label = hol != null ? hol : t.day.getDayOfWeek() == java.time.DayOfWeek.SATURDAY ? "토요일" : "일요일";
                if (c.on("holiday")) out.add(new Hit(t.id, "holiday", c.score("holiday"), label + " 사용"));
            }
            if (c.on("near") && t.amount < c.limit && t.amount >= c.limit * c.nearPct / 100)
                out.add(new Hit(t.id, "near", c.score("near"), "한도(" + won(c.limit) + ") 바로 아래 " + won(t.amount)));
            if (c.on("round") && t.amount >= c.roundMin && c.roundUnit > 0 && t.amount % c.roundUnit == 0)
                out.add(new Hit(t.id, "round", c.score("round"), "딱 떨어지는 금액 " + won(t.amount)));
            if (c.on("purpose")) {
                String p = t.purpose.replaceAll("\\s+", "");
                if (p.isEmpty()) out.add(new Hit(t.id, "purpose", c.score("purpose"), "사용 사유 없음"));
                else if (p.length() < c.purposeMinLength || vague.contains(p.toLowerCase(Locale.ROOT))) out.add(new Hit(t.id, "purpose", c.score("purpose"), "사용 사유 모호 '" + t.purpose + "'"));
            }
            if (c.on("late") && !t.reg.isEmpty()) {
                long d = ChronoUnit.DAYS.between(t.day, LocalDate.parse(t.reg));
                if (d > c.lateDays) out.add(new Hit(t.id, "late", c.score("late"), "사용 " + d + "일 뒤 등록"));
            }
            if (!t.home.isEmpty() && !t.addr.isEmpty()) {
                int lv = near(t.home.split("\\|", -1), area(t.addr));
                if (lv > 0 && c.on("home")) out.add(new Hit(t.id, "home", lv == 2 ? c.score("home") : Math.max(1, c.score("home") / 4),
                    lv == 2 ? "자택과 같은 동·도로" : "자택과 같은 구"));
                if (lv == 2 && offOrNight.contains(t.id) && c.on("combo")) out.add(new Hit(t.id, "combo", c.score("combo"), "자택 인근에서 휴일·심야 사용"));
            }
        }
        // 쪼개기: 같은 사람(또는 카드)·같은 날·같은 가맹점, 가까운 시각, 한 건씩은 한도 아래인데 합하면 한도 넘음
        if (c.on("split")) {
            Map<String, List<Tx>> g = new LinkedHashMap<>();
            for (Tx t : txs) g.computeIfAbsent((t.card.isEmpty() ? t.name : t.card) + "|" + t.date + "|" + t.shop(), k -> new ArrayList<>()).add(t);
            for (List<Tx> list : g.values()) for (List<Tx> cl : clusters(list, c.splitMinutes)) {
                long sum = cl.stream().mapToLong(x -> x.amount).sum();
                if (cl.size() >= 2 && sum > c.limit && cl.stream().allMatch(x -> x.amount <= c.limit))
                    for (Tx t : cl) out.add(new Hit(t.id, "split", c.score("split"), "쪼개기 의심 " + cl.size() + "건 합계 " + won(sum)));
            }
        }
        // 여러 카드로 같은 가맹점에서 같은 때 나눠 결제
        if (c.on("multicard")) {
            Map<String, List<Tx>> g = new LinkedHashMap<>();
            // 같은 부서(팀)가 같은 가맹점에서 같은 때 나눠 낸 것만. 다른 부서가 우연히 같은 식당에 간 것은 빼야 오탐이 줄어든다.
            for (Tx t : txs) g.computeIfAbsent(t.date + "|" + t.shop() + "|" + t.dept, k -> new ArrayList<>()).add(t);
            for (List<Tx> list : g.values()) for (List<Tx> cl : clusters(list, Math.min(c.splitMinutes, 30))) {
                Set<String> who = new HashSet<>();
                for (Tx t : cl) who.add(t.card.isEmpty() ? t.name : t.card);
                long sum = cl.stream().mapToLong(x -> x.amount).sum();
                if (who.size() >= 2 && sum > c.limit && cl.stream().allMatch(x -> x.amount <= c.limit))
                    for (Tx t : cl) out.add(new Hit(t.id, "multicard", c.score("multicard"), "카드 " + who.size() + "장으로 나눠 결제 합계 " + won(sum)));
            }
        }
        // 같은 사람·같은 가맹점·같은 금액 반복
        if (c.on("repeat")) {
            Map<String, List<Tx>> g = new LinkedHashMap<>();
            for (Tx t : txs) if (t.amount >= c.repeatAmountMin) g.computeIfAbsent(t.name + "|" + t.shop() + "|" + t.amount, k -> new ArrayList<>()).add(t);
            for (List<Tx> list : g.values()) if (list.size() >= c.repeatMin)
                for (Tx t : list) out.add(new Hit(t.id, "repeat", c.score("repeat"), "같은 곳 같은 금액 " + list.size() + "회"));
        }
        // 식사 뒤 몇 시간 안에 주점
        if (c.on("second")) {
            Map<String, List<Tx>> g = new LinkedHashMap<>();
            for (Tx t : txs) if (t.minute >= 0) g.computeIfAbsent(t.name + "|" + t.date, k -> new ArrayList<>()).add(t);
            for (List<Tx> list : g.values()) {
                list.sort(Comparator.comparingInt(x -> x.minute));
                for (int i = 0; i < list.size(); i++) {
                    Tx meal = list.get(i);
                    if (hit(meal.kind(), meals) == null || hit(meal.kind(), bars) != null) continue;
                    for (int j = i + 1; j < list.size(); j++) {
                        Tx b = list.get(j);
                        if (b.minute - meal.minute > c.secondHours * 60) break;
                        if (!b.shop().equals(meal.shop()) && (hit(b.kind(), bars) != null || hit(b.kind(), restricted) != null))
                            out.add(new Hit(b.id, "second", c.score("second"), "식사(" + meal.time.substring(0, 5) + ") 후 주점"));
                    }
                }
            }
        }
        // 한 건에 같은 규칙이 겹치면 하나만
        Map<String, Hit> uniq = new LinkedHashMap<>();
        for (Hit h : out) uniq.putIfAbsent(h.txId() + "|" + h.rule(), h);
        return new ArrayList<>(uniq.values());
    }

    /** 시각이 가까운 것끼리 묶는다(시각이 없으면 하루 전체를 한 묶음으로). */
    static List<List<Tx>> clusters(List<Tx> list, int minutes) {
        List<List<Tx>> out = new ArrayList<>();
        if (list.size() < 2) return out;
        if (list.stream().anyMatch(t -> t.minute < 0)) { out.add(list); return out; }
        List<Tx> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparingInt(t -> t.minute));
        List<Tx> cur = new ArrayList<>();
        for (Tx t : sorted) {
            if (!cur.isEmpty() && t.minute - cur.get(cur.size() - 1).minute > minutes) { out.add(cur); cur = new ArrayList<>(); }
            cur.add(t);
        }
        out.add(cur);
        return out;
    }

    // ------------------------------------------------------------------ 조회

    public List<Map<String, Object>> runs() {
        return db.rows("""
            SELECT r.Id, r.Title, r.FileName, r.CreatedAt, r.State, r.RowCount, r.CheckedAt, r.Note,
              (SELECT COUNT(*) FROM (SELECT h.TxId FROM CardHits h WHERE h.RunId=r.Id AND h.ExceptionId IS NULL GROUP BY h.TxId HAVING SUM(h.Score)>=20)) AS HitTx,
              (SELECT COUNT(*) FROM (SELECT h.TxId FROM CardHits h WHERE h.RunId=r.Id AND h.ExceptionId IS NULL GROUP BY h.TxId HAVING SUM(h.Score)>=40)) AS HighTx
            FROM CardRuns r ORDER BY r.CreatedAt DESC""");
    }

    public Map<String, Object> run(String id) { return Txt.isId(id) ? db.row("SELECT * FROM CardRuns WHERE Id=?", id) : null; }

    public Op deleteRun(String id) {
        Map<String, Object> r = run(id);
        if (r == null) return Op.fail(404, "점검 회차를 찾을 수 없습니다.");
        db.tx(conn -> {
            Db.update(conn, "DELETE FROM CardHits WHERE RunId=?", id);
            Db.update(conn, "DELETE FROM CardTx WHERE RunId=?", id);
            Db.update(conn, "DELETE FROM CardRuns WHERE Id=?", id);
            return null;
        });
        return Op.ok("'" + r.get("Title") + "' 회차를 지웠습니다. 소명·예외 기록은 남겨 둡니다.");
    }

    /** 이 점수 이상을 '확인 대상'으로 본다. 약한 신호 하나(주말만, 사유 짧음만)로는 넘지 않게 한다. */
    public static final int FOCUS = 20;

    public static String level(int score) { return score >= 40 ? "높음" : score >= FOCUS ? "중간" : "낮음"; }

    /** 회차 요약: 건수·금액·규칙별 건수·사람별 요약. */
    public Map<String, Object> summary(String runId) {
        Map<String, Object> v = new LinkedHashMap<>();
        Map<String, Object> tot = db.row("""
            SELECT COUNT(*) AS N, COALESCE(SUM(CASE WHEN Cancelled=0 THEN Amount END),0) AS Amt, SUM(Cancelled) AS Cancelled,
              COUNT(DISTINCT Name) AS People FROM CardTx WHERE RunId=?""", runId);
        Map<String, Object> hit = db.row("""
            SELECT SUM(CASE WHEN s.S>=20 THEN 1 ELSE 0 END) AS N, COALESCE(SUM(CASE WHEN s.S>=20 THEN t.Amount END),0) AS Amt,
              SUM(CASE WHEN s.S>=40 THEN 1 ELSE 0 END) AS High, SUM(CASE WHEN s.S<20 THEN 1 ELSE 0 END) AS Low,
              SUM(CASE WHEN s.S>=20 AND COALESCE(r.Status,'') IN ('','확인 중') THEN 1 ELSE 0 END) AS Open
            FROM (SELECT TxId, SUM(Score) AS S FROM CardHits WHERE RunId=? AND ExceptionId IS NULL GROUP BY TxId) s
            JOIN CardTx t ON t.Id=s.TxId LEFT JOIN CardReviews r ON r.TxKey=t.TxKey""", runId);
        v.put("total", tot);
        v.put("hit", hit);
        List<Map<String, Object>> byRule = new ArrayList<>();
        for (Map<String, Object> r : db.rows("""
            SELECT Rule, SUM(CASE WHEN ExceptionId IS NULL THEN 1 ELSE 0 END) AS N, SUM(CASE WHEN ExceptionId IS NOT NULL THEN 1 ELSE 0 END) AS Ex
            FROM CardHits WHERE RunId=? GROUP BY Rule""", runId)) {
            r.put("Label", RULES.getOrDefault(Txt.str(r.get("Rule")), Txt.str(r.get("Rule"))));
            byRule.add(r);
        }
        byRule.sort(Comparator.comparingInt((Map<String, Object> r) -> new ArrayList<>(RULES.keySet()).indexOf(Txt.str(r.get("Rule")))));
        v.put("byRule", byRule);
        // 사람별: 사용 금액, 점검에 걸린 건, 점수 합, 부서 평균 대비, 지난 회차 반복
        List<Map<String, Object>> people = db.rows("""
            SELECT t.Name, t.Dept, COUNT(*) AS N, SUM(t.Amount) AS Amt,
              SUM(CASE WHEN s.S>=20 THEN 1 ELSE 0 END) AS HitN, COALESCE(SUM(s.S),0) AS Score, SUM(CASE WHEN s.S>=40 THEN 1 ELSE 0 END) AS High
            FROM CardTx t LEFT JOIN (SELECT TxId, SUM(Score) AS S FROM CardHits WHERE RunId=? AND ExceptionId IS NULL GROUP BY TxId) s ON s.TxId=t.Id
            WHERE t.RunId=? AND t.Cancelled=0 GROUP BY t.Name, t.Dept""", runId, runId);
        Map<String, long[]> dept = new HashMap<>();
        for (Map<String, Object> p : people) {
            long[] d = dept.computeIfAbsent(Txt.str(p.get("Dept")), k -> new long[2]);
            d[0] += ((Number) p.get("Amt")).longValue();
            d[1]++;
        }
        Map<String, Integer> repeat = new HashMap<>();
        for (Map<String, Object> r : db.rows("""
            SELECT t.Name, COUNT(DISTINCT t.RunId) AS Runs FROM CardTx t
            JOIN (SELECT TxId, SUM(Score) AS S FROM CardHits WHERE ExceptionId IS NULL GROUP BY TxId) s ON s.TxId=t.Id
            WHERE s.S>=20 AND t.RunId<>? GROUP BY t.Name""", runId))
            repeat.put(Txt.str(r.get("Name")), ((Number) r.get("Runs")).intValue());
        for (Map<String, Object> p : people) {
            long[] d = dept.get(Txt.str(p.get("Dept")));
            double avg = d[1] == 0 ? 0 : (double) d[0] / d[1];
            double ratio = avg <= 0 ? 0 : ((Number) p.get("Amt")).longValue() / avg;
            p.put("Ratio", d[1] >= 3 ? String.format("%.1f배", ratio) : "");
            p.put("Over", d[1] >= 3 && ratio >= 2.0);
            p.put("Repeat", repeat.getOrDefault(Txt.str(p.get("Name")), 0));
        }
        people.sort(Comparator.comparingLong((Map<String, Object> p) -> ((Number) p.get("Score")).longValue()).reversed()
            .thenComparing(p -> -((Number) p.get("Amt")).longValue()));
        v.put("people", people);
        return v;
    }

    /** 점검에 걸린 건 목록. */
    public Map<String, Object> list(String runId, Map<String, String> q, int page, int size) {
        StringBuilder sql = new StringBuilder("""
            SELECT t.*, s.S AS Score, s.Rules, s.Reasons, COALESCE(r.Status,'') AS Status, COALESCE(r.Note,'') AS ReviewNote
            FROM CardTx t
            JOIN (SELECT TxId, SUM(Score) AS S, GROUP_CONCAT(Rule, ',') AS Rules, GROUP_CONCAT(Detail, ' · ') AS Reasons
                  FROM CardHits WHERE RunId=? AND ExceptionId IS NULL GROUP BY TxId) s ON s.TxId=t.Id
            LEFT JOIN CardReviews r ON r.TxKey=t.TxKey
            WHERE t.RunId=?""");
        List<Object> args = new ArrayList<>(List.of(runId, runId));
        String rule = Txt.str(q.get("rule")), name = Txt.str(q.get("name")), dept = Txt.str(q.get("dept")), status = Txt.str(q.get("status")), text = Txt.str(q.get("q"));
        if (!rule.isEmpty()) { sql.append(" AND (',' || s.Rules || ',') LIKE ?"); args.add("%," + rule + ",%"); }
        if (!name.isEmpty()) { sql.append(" AND t.Name=?"); args.add(name); }
        if (!dept.isEmpty()) { sql.append(" AND t.Dept=?"); args.add(dept); }
        if ("open".equals(status)) sql.append(" AND COALESCE(r.Status,'') IN ('','확인 중')");
        else if (!status.isEmpty()) { sql.append(" AND r.Status=?"); args.add(status); }
        if (!text.isEmpty()) {
            sql.append(" AND (t.Merchant LIKE ? ESCAPE '\\' OR t.Purpose LIKE ? ESCAPE '\\' OR t.Name LIKE ? ESCAPE '\\' OR s.Reasons LIKE ? ESCAPE '\\')");
            for (int i = 0; i < 4; i++) args.add(Txt.like(text));
        }
        Integer min = Txt.intOrNull(q.get("min"));
        if (min != null && min > 0) { sql.append(" AND s.S>=?"); args.add(min); }
        sql.append(" ORDER BY s.S DESC, t.TxDate, t.TxTime");
        List<Map<String, Object>> all = db.rows(sql.toString(), args.toArray());
        int pages = Math.max(1, (all.size() + size - 1) / size);
        int p = Math.max(1, Math.min(page, pages));
        List<Map<String, Object>> items = new ArrayList<>(all.subList(Math.min(all.size(), (p - 1) * size), Math.min(all.size(), p * size)));
        for (Map<String, Object> it : items) {
            int s = ((Number) it.get("Score")).intValue();
            it.put("Level", level(s));
            List<String> rs = new ArrayList<>();
            for (String k : Txt.str(it.get("Rules")).split(",")) if (!k.isBlank() && !rs.contains(k)) rs.add(k);
            it.put("RuleList", rs);
            it.remove("HomeArea");
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("items", items);
        v.put("total", all.size());
        v.put("page", p);
        v.put("pages", pages);
        v.put("amount", all.stream().mapToLong(m -> ((Number) m.get("Amount")).longValue()).sum());
        return v;
    }

    public List<String> names(String runId) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : db.rows("SELECT DISTINCT Name FROM CardTx WHERE RunId=? ORDER BY Name", runId)) out.add(Txt.str(r.get("Name")));
        return out;
    }

    public List<String> depts(String runId) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : db.rows("SELECT DISTINCT Dept FROM CardTx WHERE RunId=? AND Dept<>'' ORDER BY Dept", runId)) out.add(Txt.str(r.get("Dept")));
        return out;
    }

    // ------------------------------------------------------------------ 소명·예외

    public static final List<String> STATUSES = List.of("확인 중", "소명 받음", "정상", "위반");

    public Op review(String txKey, String status, String note) {
        if (Txt.blank(txKey)) return Op.bad("건을 찾을 수 없습니다.");
        String s = Txt.str(status);
        if (!s.isEmpty() && !STATUSES.contains(s)) return Op.bad("처리 상태를 고르세요.");
        db.tx(conn -> Db.update(conn, """
            INSERT INTO CardReviews(TxKey, Status, Note, UpdatedAt) VALUES (?,?,?,?)
            ON CONFLICT(TxKey) DO UPDATE SET Status=excluded.Status, Note=excluded.Note, UpdatedAt=excluded.UpdatedAt""",
            Txt.fit(txKey, 120, false), s, Txt.fit(note, 1000, true), Kst.stamp()));
        return Op.ok(s.isEmpty() ? "메모를 저장했습니다." : "'" + s + "'(으)로 기록했습니다.");
    }

    /** 예외 등록. scope: personDate(이 사람 이 날) · shop(이 가맹점) · person(이 사람). */
    public Op addException(long txId, String scope, String rule, String note) {
        Map<String, Object> t = db.row("SELECT * FROM CardTx WHERE Id=?", txId);
        if (t == null) return Op.fail(404, "건을 찾을 수 없습니다.");
        String r = RULES.containsKey(Txt.str(rule)) ? rule : "";
        String name = "", shop = "", day = "";
        switch (Txt.str(scope)) {
            case "personDate" -> { name = Txt.str(t.get("Name")); day = Txt.str(t.get("TxDate")); }
            case "shop" -> shop = Txt.str(t.get("BizNo")).isEmpty() ? Txt.str(t.get("Merchant")).replaceAll("\\s+", "") : Txt.str(t.get("BizNo"));
            case "person" -> name = Txt.str(t.get("Name"));
            default -> { return Op.bad("예외 범위를 고르세요."); }
        }
        if ("person".equals(scope) && r.isEmpty()) return Op.bad("사람 전체를 모든 규칙에서 빼는 예외는 둘 수 없습니다. 규칙을 고르세요.");
        String id = Txt.newId(), n = name, s = shop, d = day;
        String label = ("shop".equals(scope) ? Txt.str(t.get("Merchant")) : "") + (Txt.blank(note) ? "" : "");
        db.tx(conn -> Db.update(conn, "INSERT INTO CardExceptions(Id, Rule, Name, Merchant, TxDate, Note, CreatedAt) VALUES (?,?,?,?,?,?,?)",
            id, r, n, s, d, Txt.fit(Txt.blank(note) ? label : note, 300, false), Kst.stamp()));
        int runs = 0;
        for (Map<String, Object> run : db.rows("SELECT Id FROM CardRuns WHERE State='점검 완료'")) { recheck((String) run.get("Id")); runs++; }
        return Op.ok("예외를 등록했습니다. 회차 " + runs + "개에 다시 적용했습니다.");
    }

    public List<Map<String, Object>> exceptions() {
        List<Map<String, Object>> out = db.rows("SELECT e.*, (SELECT COUNT(*) FROM CardHits h WHERE h.ExceptionId=e.Id) AS Used FROM CardExceptions e ORDER BY e.CreatedAt DESC");
        for (Map<String, Object> e : out) e.put("RuleLabel", Txt.blank(e.get("Rule")) ? "모든 규칙" : RULES.getOrDefault(Txt.str(e.get("Rule")), Txt.str(e.get("Rule"))));
        return out;
    }

    public Op deleteException(String id) {
        if (db.tx(conn -> Db.update(conn, "DELETE FROM CardExceptions WHERE Id=?", id)) == 0) return Op.fail(404, "예외를 찾을 수 없습니다.");
        for (Map<String, Object> run : db.rows("SELECT Id FROM CardRuns WHERE State='점검 완료'")) recheck((String) run.get("Id"));
        return Op.ok("예외를 지웠습니다. 다시 점검했습니다.");
    }

    /** 고른 건을 감사기록 건 본문으로 쓸 글. */
    public Map<String, Object> caseDraft(String runId, List<Long> ids) {
        Map<String, Object> run = run(runId);
        if (run == null || ids == null || ids.isEmpty()) return null;
        StringBuilder body = new StringBuilder();
        body.append("법인카드 점검 '").append(run.get("Title")).append("'에서 확인이 필요한 건입니다.\n\n");
        Set<String> names = new java.util.TreeSet<>(), depts = new java.util.TreeSet<>();
        long sum = 0;
        int n = 0;
        for (Long id : ids) {
            Map<String, Object> t = db.row("""
                SELECT t.*, (SELECT GROUP_CONCAT(Detail, ' · ') FROM CardHits h WHERE h.TxId=t.Id AND h.ExceptionId IS NULL) AS Reasons,
                  (SELECT SUM(Score) FROM CardHits h WHERE h.TxId=t.Id AND h.ExceptionId IS NULL) AS Score,
                  r.Status, r.Note AS ReviewNote
                FROM CardTx t LEFT JOIN CardReviews r ON r.TxKey=t.TxKey WHERE t.Id=? AND t.RunId=?""", id, runId);
            if (t == null) continue;
            n++;
            sum += ((Number) t.get("Amount")).longValue();
            names.add(Txt.str(t.get("Name")));
            if (!Txt.blank(t.get("Dept"))) depts.add(Txt.str(t.get("Dept")));
            body.append("- ").append(t.get("TxDate")).append(' ').append(Txt.str(t.get("TxTime"))).append(" · ").append(t.get("Dept")).append(' ').append(t.get("Name"))
                .append(" · ").append(t.get("Merchant")).append(" · ").append(won(((Number) t.get("Amount")).longValue()))
                .append("\n  점검: ").append(Txt.str(t.get("Reasons"))).append(" (점수 ").append(Txt.str(t.get("Score"))).append(")");
            if (!Txt.blank(t.get("Purpose"))) body.append("\n  사용내역: ").append(t.get("Purpose"));
            if (!Txt.blank(t.get("Status")) || !Txt.blank(t.get("ReviewNote"))) body.append("\n  처리: ").append(Txt.str(t.get("Status"))).append(' ').append(Txt.str(t.get("ReviewNote")));
            body.append('\n');
        }
        if (n == 0) return null;
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("title", "법인카드 사용 확인 — " + String.join(", ", names.stream().limit(3).toList()) + (names.size() > 3 ? " 외 " + (names.size() - 3) + "명" : ""));
        v.put("target", depts.isEmpty() ? String.join(", ", names) : String.join(", ", depts));
        v.put("summary", n + "건 " + won(sum) + " 확인 필요");
        v.put("body", body.toString());
        return v;
    }

    /** 내려받기(CSV). 집주소는 넣지 않는다. */
    public String csv(String runId, boolean all) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("점수,수준,점검 사유,처리,메모,부서,이름,카드번호,승인번호,승인일자,승인시간,가맹점명,업종,업태,승인금액,취소,비용구분,사용내역,가맹점 주소\n");
        String sql = """
            SELECT t.*, COALESCE(s.S,0) AS Score, COALESCE(s.Reasons,'') AS Reasons, COALESCE(r.Status,'') AS Status, COALESCE(r.Note,'') AS ReviewNote
            FROM CardTx t
            LEFT JOIN (SELECT TxId, SUM(Score) AS S, GROUP_CONCAT(Detail, ' · ') AS Reasons FROM CardHits WHERE RunId=? AND ExceptionId IS NULL GROUP BY TxId) s ON s.TxId=t.Id
            LEFT JOIN CardReviews r ON r.TxKey=t.TxKey
            WHERE t.RunId=?""" + (all ? "" : " AND s.S IS NOT NULL") + " ORDER BY COALESCE(s.S,0) DESC, t.TxDate, t.TxTime";
        for (Map<String, Object> t : db.rows(sql, runId, runId)) {
            int s = ((Number) t.get("Score")).intValue();
            Object[] cols = { s, s > 0 ? level(s) : "", t.get("Reasons"), t.get("Status"), t.get("ReviewNote"), t.get("Dept"), t.get("Name"), t.get("CardNo"),
                t.get("ApprovalNo"), t.get("TxDate"), t.get("TxTime"), t.get("Merchant"), t.get("Industry"), t.get("BizType"), t.get("Amount"),
                ((Number) t.get("Cancelled")).intValue() == 1 ? "취소" : "", t.get("CostType"), t.get("Purpose"), t.get("MerchantAddr") };
            for (int i = 0; i < cols.length; i++) { if (i > 0) sb.append(','); sb.append(Txt.csv(cols[i])); }
            sb.append('\n');
        }
        return sb.toString();
    }
}
