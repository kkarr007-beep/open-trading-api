package local.audit.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.springframework.stereotype.Service;

import local.audit.Db;
import local.audit.Kst;
import local.audit.Op;
import local.audit.Txt;

/**
 * 법인카드 점검의 '고른 건 상세 분석'. 점검 목록에서 고른 건만 따로 묶어,
 * 같은 회차 전체 거래와 지난 회차를 잇는 연결고리(같은 날·같은 가맹점·동석·같은 금액·지난 이력)로 깊게 본다.
 * 건마다 판정(문제·소명 필요·정상)과 문제 요지·확인 질문·요청 자료를 적어 조사 준비 자료로 정리한다.
 */
@Service
public class CardAnalysis {
    public static final List<String> VERDICTS = List.of("검토 중", "문제", "소명 필요", "정상");

    /** 규칙마다 기본 확인 질문과 요청 자료. 빈 칸이면 이것을 쓴다. */
    static final Map<String, String[]> ASK = new LinkedHashMap<>();
    static {
        ASK.put("restricted", new String[] { "제한업종 가맹점을 쓴 업무상 이유와 참석자는 누구입니까?", "영수증(품목 내역), 참석자 명단, 품의서" });
        ASK.put("bar", new String[] { "주점을 쓴 목적과 참석자, 사전 승인을 받았는지 알려 주십시오.", "참석자 명단, 사전 품의서" });
        ASK.put("night", new String[] { "심야에 쓴 사유(야근·행사 등)를 알려 주십시오.", "야근 기록, 출입 기록" });
        ASK.put("holiday", new String[] { "휴일에 쓴 사유를 알려 주십시오.", "휴일근무명령서 또는 출장명령서" });
        ASK.put("split", new String[] { "같은 가맹점에서 잇달아 나눠 결제한 사유는 무엇입니까?", "영수증 원본, 품목 내역" });
        ASK.put("multicard", new String[] { "여러 카드로 나눠 결제한 사유는 무엇입니까?", "참석자 명단, 영수증 원본" });
        ASK.put("near", new String[] { "한도 바로 아래 금액이 나온 근거(인원·품목)를 알려 주십시오.", "영수증(품목 내역), 참석 인원" });
        ASK.put("round", new String[] { "만 원 단위로 딱 떨어지는 금액의 구매 내역은 무엇입니까?", "영수증(품목 내역), 거래명세서" });
        ASK.put("repeat", new String[] { "같은 금액을 되풀이해 결제한 내용(정기 결제 여부)을 알려 주십시오.", "계약서 또는 청구서" });
        ASK.put("home", new String[] { "자택 근처에서 쓴 업무상 이유는 무엇입니까?", "업무 일정, 참석자 명단" });
        ASK.put("combo", new String[] { "휴일·심야에 자택 근처에서 쓴 업무상 이유는 무엇입니까?", "근무 기록, 참석자 명단" });
        ASK.put("second", new String[] { "식사 뒤 2차로 쓴 목적과 참석자는 누구입니까?", "참석자 명단, 품의서" });
        ASK.put("purpose", new String[] { "구체적인 사용 목적을 알려 주십시오.", "품의서, 참석자 명단" });
        ASK.put("late", new String[] { "결재 등록이 늦어진 사유는 무엇입니까?", "" });
    }

    private static final String DOW = "월화수목금토일";

    private final Db db;
    private final CardCheck card;

    public CardAnalysis(Db db, CardCheck card) { this.db = db; this.card = card; }

    // ------------------------------------------------------------------ 만들기·고치기

    public Op create(String runId, List<Long> ids, String title) {
        Map<String, Object> run = card.run(runId);
        if (run == null) return Op.fail(404, "점검 회차를 찾지 못했습니다.");
        if (ids == null || ids.isEmpty()) return Op.bad("분석할 건을 고르세요.");
        if (ids.size() > 500) return Op.bad("한 번에 500건까지 분석할 수 있습니다.");
        String id = Txt.newId(), now = Kst.stamp();
        String t = Txt.blank(title) ? Txt.str(run.get("Title")) + " 상세 분석 (" + ids.size() + "건)" : Txt.fit(title, 120, false);
        return db.run(conn -> {
            Db.update(conn, "INSERT INTO CardSets(Id, RunId, Title, CreatedAt, UpdatedAt) VALUES(?,?,?,?,?)", id, runId, t, now, now);
            int n = 0;
            for (Long tx : ids) {
                if (Db.intOf(conn, "SELECT COUNT(*) FROM CardTx WHERE Id=? AND RunId=?", tx, runId) == 0) continue;
                n += Db.update(conn, "INSERT OR IGNORE INTO CardSetItems(SetId, TxId) VALUES(?,?)", id, tx);
            }
            if (n == 0) throw Op.abort("고른 건을 찾지 못했습니다.");
            return Op.ok(n + "건으로 상세 분석을 만들었습니다.", id);
        });
    }

    /** 이미 만든 분석에 건을 더한다. */
    public Op addItems(String setId, List<Long> ids) {
        Map<String, Object> s = set(setId);
        if (s == null) return Op.fail(404, "분석을 찾지 못했습니다.");
        if (ids == null || ids.isEmpty()) return Op.bad("더할 건을 고르세요.");
        String runId = Txt.str(s.get("RunId"));
        return db.run(conn -> {
            int n = 0;
            for (Long tx : ids)
                if (Db.intOf(conn, "SELECT COUNT(*) FROM CardTx WHERE Id=? AND RunId=?", tx, runId) > 0)
                    n += Db.update(conn, "INSERT OR IGNORE INTO CardSetItems(SetId, TxId) VALUES(?,?)", setId, tx);
            Db.update(conn, "UPDATE CardSets SET UpdatedAt=? WHERE Id=?", Kst.stamp(), setId);
            return Op.ok(n == 0 ? "더할 새 건이 없습니다(이미 들어 있음)." : n + "건을 분석에 더했습니다.", setId);
        });
    }

    public Op saveItem(String setId, long txId, Map<String, String> p) {
        String v = Txt.str(p.get("verdict"));
        if (!v.isEmpty() && !VERDICTS.contains(v)) return Op.bad("없는 판정입니다.");
        return db.run(conn -> {
            Map<String, Object> it = Db.row(conn, "SELECT * FROM CardSetItems WHERE SetId=? AND TxId=?", setId, txId);
            if (it == null) throw Op.abort(404, "분석 대상 건을 찾지 못했습니다.");
            Db.update(conn, "UPDATE CardSetItems SET Verdict=?, Finding=?, Question=?, Docs=? WHERE SetId=? AND TxId=?",
                v.isEmpty() ? it.get("Verdict") : v,
                p.containsKey("finding") ? Txt.fit(p.get("finding"), 1000, true) : it.get("Finding"),
                p.containsKey("question") ? Txt.fit(p.get("question"), 1000, true) : it.get("Question"),
                p.containsKey("docs") ? Txt.fit(p.get("docs"), 500, false) : it.get("Docs"), setId, txId);
            Db.update(conn, "UPDATE CardSets SET UpdatedAt=? WHERE Id=?", Kst.stamp(), setId);
            return Op.ok("저장했습니다.", setId);
        });
    }

    public Op removeItem(String setId, long txId) {
        return db.run(conn -> {
            if (Db.intOf(conn, "SELECT COUNT(*) FROM CardSetItems WHERE SetId=?", setId) <= 1) throw Op.abort("마지막 건은 뺄 수 없습니다. 분석을 지우세요.");
            Db.update(conn, "DELETE FROM CardSetItems WHERE SetId=? AND TxId=?", setId, txId);
            return Op.ok("분석에서 뺐습니다.", setId);
        });
    }

    public Op meta(String setId, String title, String note) {
        String t = Txt.fit(title, 120, false);
        if (Txt.blank(t)) return Op.bad("분석 이름을 적으세요.");
        return db.run(conn -> {
            if (Db.update(conn, "UPDATE CardSets SET Title=?, Note=?, UpdatedAt=? WHERE Id=?", t, Txt.fit(note, 4000, true), Kst.stamp(), setId) == 0)
                throw Op.abort(404, "분석을 찾지 못했습니다.");
            return Op.ok("저장했습니다.", setId);
        });
    }

    public Op delete(String setId) {
        Map<String, Object> s = set(setId);
        if (s == null) return Op.fail(404, "분석을 찾지 못했습니다.");
        return db.run(conn -> {
            Db.update(conn, "DELETE FROM CardSetItems WHERE SetId=?", setId);
            Db.update(conn, "DELETE FROM CardSets WHERE Id=?", setId);
            return Op.ok("분석을 지웠습니다. 점검 결과는 그대로입니다.", Txt.str(s.get("RunId")));
        });
    }

    /** 회차를 지울 때 그 회차의 분석도 지운다. */
    public void deleteForRun(String runId) {
        db.run(conn -> {
            Db.update(conn, "DELETE FROM CardSetItems WHERE SetId IN (SELECT Id FROM CardSets WHERE RunId=?)", runId);
            Db.update(conn, "DELETE FROM CardSets WHERE RunId=?", runId);
            return Op.ok("");
        });
    }

    // ------------------------------------------------------------------ 읽기

    public Map<String, Object> set(String setId) {
        return Txt.isId(setId) ? db.row("SELECT * FROM CardSets WHERE Id=?", setId) : null;
    }

    public List<Map<String, Object>> sets(String runId) {
        return db.rows("""
            SELECT s.Id, s.Title, s.CreatedAt, s.UpdatedAt, COUNT(i.TxId) AS N,
              SUM(CASE WHEN i.Verdict='문제' THEN 1 ELSE 0 END) AS Bad, SUM(CASE WHEN i.Verdict='소명 필요' THEN 1 ELSE 0 END) AS Ask,
              SUM(CASE WHEN i.Verdict='검토 중' THEN 1 ELSE 0 END) AS Open
            FROM CardSets s LEFT JOIN CardSetItems i ON i.SetId=s.Id WHERE s.RunId=? GROUP BY s.Id ORDER BY s.CreatedAt DESC""", runId);
    }

    /** 분석 대상 건(점수·사유·처리·판정 포함). */
    public List<Map<String, Object>> items(String setId) {
        List<Map<String, Object>> rows = db.rows("""
            SELECT t.*, i.Verdict, i.Finding, i.Question, i.Docs,
              COALESCE((SELECT SUM(Score) FROM CardHits h WHERE h.TxId=t.Id AND h.ExceptionId IS NULL),0) AS Score,
              COALESCE((SELECT GROUP_CONCAT(Rule, ',') FROM CardHits h WHERE h.TxId=t.Id AND h.ExceptionId IS NULL),'') AS Rules,
              COALESCE((SELECT GROUP_CONCAT(Detail, ' · ') FROM CardHits h WHERE h.TxId=t.Id AND h.ExceptionId IS NULL),'') AS Reasons,
              COALESCE(r.Status,'') AS Status, COALESCE(r.Note,'') AS ReviewNote
            FROM CardSetItems i JOIN CardTx t ON t.Id=i.TxId LEFT JOIN CardReviews r ON r.TxKey=t.TxKey
            WHERE i.SetId=? ORDER BY t.Name, t.TxDate, t.TxTime""", setId);
        for (Map<String, Object> r : rows) decorate(r);
        return rows;
    }

    private static void decorate(Map<String, Object> r) {
        LocalDate d = CardCheck.date(Txt.str(r.get("TxDate")));
        r.put("Dow", d == null ? "" : String.valueOf(DOW.charAt(d.getDayOfWeek().getValue() - 1)));
        r.put("Weekend", d != null && d.getDayOfWeek().getValue() >= 6);
        String tm = Txt.str(r.get("TxTime"));
        r.put("Hm", tm.length() >= 5 ? tm.substring(0, 5) : tm);
        if (r.containsKey("Score")) {
            int s = ((Number) r.get("Score")).intValue();
            r.put("Level", s > 0 ? CardCheck.level(s) : "");
            List<String> rules = new ArrayList<>();
            for (String k : Txt.str(r.get("Rules")).split(",")) if (!k.isBlank() && !rules.contains(k)) rules.add(k);
            r.put("RuleList", rules);
            List<String> labels = new ArrayList<>();
            for (String k : rules) labels.add(CardCheck.RULES.getOrDefault(k, k));
            r.put("RuleLabels", String.join(", ", labels));
            Set<String> q = new LinkedHashSet<>(), docs = new LinkedHashSet<>();
            for (String k : rules) {
                String[] a = ASK.get(k);
                if (a == null) continue;
                q.add(a[0]);
                for (String x : a[1].split(",\\s*")) if (!x.isBlank()) docs.add(x.strip());
            }
            r.put("AutoQuestion", String.join(" ", q));
            r.put("AutoDocs", String.join(", ", docs));
        }
        r.remove("HomeArea");
    }

    /** 화면 하나에 필요한 것 전부. */
    public Map<String, Object> view(String setId) {
        Map<String, Object> s = set(setId);
        if (s == null) return null;
        String runId = Txt.str(s.get("RunId"));
        List<Map<String, Object>> items = items(setId);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("set", s);
        v.put("run", card.run(runId));
        v.put("items", items);
        long sum = 0;
        Set<String> people = new LinkedHashSet<>();
        String from = null, to = null;
        Map<String, Integer> verdicts = new LinkedHashMap<>();
        for (String x : VERDICTS) verdicts.put(x, 0);
        for (Map<String, Object> it : items) {
            sum += ((Number) it.get("Amount")).longValue();
            people.add(Txt.str(it.get("Name")));
            String d = Txt.str(it.get("TxDate"));
            if (from == null || d.compareTo(from) < 0) from = d;
            if (to == null || d.compareTo(to) > 0) to = d;
            verdicts.merge(Txt.str(it.get("Verdict")), 1, Integer::sum);
        }
        v.put("sum", sum);
        v.put("peopleCount", people.size());
        v.put("period", from == null ? "" : from.equals(to) ? from : from + " ~ " + to);
        v.put("verdicts", verdicts);

        // 같은 회차 전체 거래(취소 제외)를 한 번 읽어 연결고리를 찾는다
        List<Map<String, Object>> all = db.rows("""
            SELECT t.Id, t.Name, t.Dept, t.CardNo, t.TxDate, t.TxTime, t.Merchant, t.BizNo, t.Industry, t.Amount, t.Purpose,
              COALESCE(s.S,0) AS Score FROM CardTx t
            LEFT JOIN (SELECT TxId, SUM(Score) AS S FROM CardHits WHERE RunId=? AND ExceptionId IS NULL GROUP BY TxId) s ON s.TxId=t.Id
            WHERE t.RunId=? AND t.Cancelled=0 ORDER BY t.TxDate, t.TxTime""", runId, runId);
        for (Map<String, Object> r : all) decorate(r);
        Set<Long> picked = new java.util.HashSet<>();
        for (Map<String, Object> it : items) picked.add(((Number) it.get("Id")).longValue());
        for (Map<String, Object> r : all) r.put("Picked", picked.contains(((Number) r.get("Id")).longValue()));

        // 지난 회차: 같은 사람이 확인 대상(20점 이상)에 든 횟수, 같은 가맹점이 걸린 횟수
        Map<String, Integer> pastPerson = new HashMap<>(), pastShop = new HashMap<>();
        for (Map<String, Object> r : db.rows("""
            SELECT t.Name, t.Merchant FROM CardTx t JOIN (SELECT TxId, SUM(Score) AS S FROM CardHits WHERE ExceptionId IS NULL GROUP BY TxId) s ON s.TxId=t.Id
            WHERE s.S>=20 AND t.RunId<>?""", runId)) {
            pastPerson.merge(Txt.str(r.get("Name")), 1, Integer::sum);
            pastShop.merge(Txt.str(r.get("Merchant")), 1, Integer::sum);
        }

        // 건마다 연결고리
        for (Map<String, Object> it : items) {
            long id = ((Number) it.get("Id")).longValue();
            String name = Txt.str(it.get("Name")), shop = Txt.str(it.get("Merchant")), day = Txt.str(it.get("TxDate"));
            long amt = ((Number) it.get("Amount")).longValue();
            LocalDate d0 = CardCheck.date(day);
            List<Map<String, Object>> sameDay = new ArrayList<>(), together = new ArrayList<>();
            int shopTimes = 0, sameAmt = 0;
            long shopSum = 0;
            for (Map<String, Object> r : all) {
                if (((Number) r.get("Id")).longValue() == id) continue;
                String rn = Txt.str(r.get("Name")), rs = Txt.str(r.get("Merchant"));
                long ra = ((Number) r.get("Amount")).longValue();
                if (rn.equals(name) && day.equals(Txt.str(r.get("TxDate")))) sameDay.add(r);
                if (!rn.equals(name) && !shop.isEmpty() && rs.equals(shop)) {
                    LocalDate d1 = CardCheck.date(Txt.str(r.get("TxDate")));
                    if (d0 != null && d1 != null && Math.abs(d0.toEpochDay() - d1.toEpochDay()) <= 1) together.add(r);
                }
                if (rn.equals(name) && rs.equals(shop)) { shopTimes++; shopSum += ra; }
                if (rn.equals(name) && ra == amt && amt >= 10_000) sameAmt++;
            }
            List<String> chips = new ArrayList<>();
            if (!sameDay.isEmpty()) chips.add("같은 날 다른 사용 " + sameDay.size() + "건");
            if (!together.isEmpty()) chips.add("같은 가맹점 다른 사람(전후 1일) " + together.size() + "건");
            if (shopTimes > 0) chips.add("이 가맹점 이번 회차 " + (shopTimes + 1) + "회 · " + CardCheck.won(shopSum + amt));
            if (sameAmt > 0) chips.add("같은 금액 " + (sameAmt + 1) + "회");
            if (pastPerson.getOrDefault(name, 0) > 0) chips.add("지난 회차 확인 대상 " + pastPerson.get(name) + "건");
            if (pastShop.getOrDefault(shop, 0) > 0) chips.add("이 가맹점 지난 회차 " + pastShop.get(shop) + "건");
            it.put("SameDay", sameDay);
            it.put("Together", together);
            it.put("Chips", chips);
        }

        // 사람별: 이번 회차 전체 사용 대비 분석 대상 비중
        Map<String, Map<String, Object>> byPerson = new LinkedHashMap<>();
        for (Map<String, Object> it : items) {
            Map<String, Object> p = byPerson.computeIfAbsent(Txt.str(it.get("Name")), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("Name", k); m.put("Dept", it.get("Dept")); m.put("N", 0); m.put("Amt", 0L); m.put("Bad", 0); m.put("Ask", 0);
                m.put("RunN", 0); m.put("RunAmt", 0L); m.put("Weekend", 0); m.put("Night", 0);
                return m;
            });
            p.put("N", (int) p.get("N") + 1);
            p.put("Amt", (long) p.get("Amt") + ((Number) it.get("Amount")).longValue());
            if ("문제".equals(it.get("Verdict"))) p.put("Bad", (int) p.get("Bad") + 1);
            if ("소명 필요".equals(it.get("Verdict"))) p.put("Ask", (int) p.get("Ask") + 1);
        }
        for (Map<String, Object> r : all) {
            Map<String, Object> p = byPerson.get(Txt.str(r.get("Name")));
            if (p == null) continue;
            p.put("RunN", (int) p.get("RunN") + 1);
            p.put("RunAmt", (long) p.get("RunAmt") + ((Number) r.get("Amount")).longValue());
            if (Boolean.TRUE.equals(r.get("Weekend"))) p.put("Weekend", (int) p.get("Weekend") + 1);
            String hm = Txt.str(r.get("Hm"));
            if (hm.compareTo("23:00") >= 0 || (!hm.isEmpty() && hm.compareTo("06:00") < 0)) p.put("Night", (int) p.get("Night") + 1);
        }
        for (Map<String, Object> p : byPerson.values()) {
            long ra = (long) p.get("RunAmt");
            p.put("Share", ra == 0 ? "" : Math.round((long) p.get("Amt") * 100.0 / ra) + "%");
            p.put("Past", pastPerson.getOrDefault(Txt.str(p.get("Name")), 0));
            List<Map<String, Object>> tl = new ArrayList<>();
            for (Map<String, Object> r : all) if (Txt.str(r.get("Name")).equals(p.get("Name"))) tl.add(r);
            p.put("Timeline", tl);
        }
        List<Map<String, Object>> persons = new ArrayList<>(byPerson.values());
        persons.sort(Comparator.comparingLong((Map<String, Object> p) -> (long) p.get("Amt")).reversed());
        v.put("people", persons);

        // 가맹점별: 분석 대상에 든 가맹점이 이번 회차에서 얼마나 쓰였나
        Map<String, Map<String, Object>> byShop = new LinkedHashMap<>();
        for (Map<String, Object> it : items) {
            String k = Txt.str(it.get("Merchant"));
            Map<String, Object> m = byShop.computeIfAbsent(k, z -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("Merchant", z); x.put("Industry", it.get("Industry")); x.put("N", 0); x.put("Amt", 0L);
                x.put("RunN", 0); x.put("RunAmt", 0L); x.put("People", new LinkedHashSet<String>());
                return x;
            });
            m.put("N", (int) m.get("N") + 1);
            m.put("Amt", (long) m.get("Amt") + ((Number) it.get("Amount")).longValue());
        }
        for (Map<String, Object> r : all) {
            Map<String, Object> m = byShop.get(Txt.str(r.get("Merchant")));
            if (m == null) continue;
            m.put("RunN", (int) m.get("RunN") + 1);
            m.put("RunAmt", (long) m.get("RunAmt") + ((Number) r.get("Amount")).longValue());
            @SuppressWarnings("unchecked") Set<String> ps = (Set<String>) m.get("People");
            ps.add(Txt.str(r.get("Name")));
        }
        for (Map<String, Object> m : byShop.values()) {
            @SuppressWarnings("unchecked") Set<String> ps = (Set<String>) m.get("People");
            m.put("PeopleN", ps.size());
            m.put("PeopleText", String.join(", ", ps.stream().limit(5).toList()) + (ps.size() > 5 ? " 외 " + (ps.size() - 5) + "명" : ""));
            m.put("Past", pastShop.getOrDefault(Txt.str(m.get("Merchant")), 0));
        }
        List<Map<String, Object>> shops = new ArrayList<>(byShop.values());
        shops.sort(Comparator.comparingInt((Map<String, Object> m) -> (int) m.get("RunN")).reversed());
        v.put("shops", shops);

        // 같은 자리 묶음: 분석 대상 중 같은 날·같은 가맹점에서 결제된 건(여러 사람·여러 번)을 합쳐 본다
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> r : all) {
            if (Txt.blank(r.get("Merchant"))) continue;
            groups.computeIfAbsent(r.get("TxDate") + "|" + r.get("Merchant"), k -> new ArrayList<>()).add(r);
        }
        List<Map<String, Object>> bundles = new ArrayList<>();
        long limit = card.config().limit;
        for (Map.Entry<String, List<Map<String, Object>>> e : groups.entrySet()) {
            List<Map<String, Object>> g = e.getValue();
            if (g.size() < 2 || g.stream().noneMatch(r -> Boolean.TRUE.equals(r.get("Picked")))) continue;
            long total = g.stream().mapToLong(r -> ((Number) r.get("Amount")).longValue()).sum();
            Map<String, Object> b = new LinkedHashMap<>();
            String[] k = e.getKey().split("\\|", 2);
            b.put("Date", k[0]); b.put("Merchant", k[1]); b.put("Rows", g); b.put("Total", total);
            b.put("People", g.stream().map(r -> Txt.str(r.get("Name"))).distinct().count());
            b.put("OverLimit", total >= limit);
            bundles.add(b);
        }
        bundles.sort(Comparator.comparingLong((Map<String, Object> b) -> (long) b.get("Total")).reversed());
        v.put("bundles", bundles);
        v.put("limit", limit);

        // 요일·시간대 분포(분석 대상)
        int[] dow = new int[7];
        int[] hours = new int[6]; // 0-6, 6-9, 9-12, 12-18, 18-23, 23-24
        for (Map<String, Object> it : items) {
            LocalDate d = CardCheck.date(Txt.str(it.get("TxDate")));
            if (d != null) dow[d.getDayOfWeek().getValue() - 1]++;
            String hm = Txt.str(it.get("Hm"));
            int h = hm.length() >= 2 ? Txt.intOrNull(hm.substring(0, 2)) == null ? -1 : Txt.intOrNull(hm.substring(0, 2)) : -1;
            if (h >= 0) hours[h < 6 ? 0 : h < 9 ? 1 : h < 12 ? 2 : h < 18 ? 3 : h < 23 ? 4 : 5]++;
        }
        List<Map<String, Object>> dowRows = new ArrayList<>();
        int max = 1;
        for (int x : dow) max = Math.max(max, x);
        for (int i = 0; i < 7; i++) dowRows.add(Map.of("Label", String.valueOf(DOW.charAt(i)), "N", dow[i], "Pct", dow[i] * 100 / max, "Weekend", i >= 5));
        v.put("dow", dowRows);
        String[] hl = { "0~6시", "6~9시", "9~12시", "12~18시", "18~23시", "23시~" };
        int hmax = 1;
        for (int x : hours) hmax = Math.max(hmax, x);
        List<Map<String, Object>> hourRows = new ArrayList<>();
        for (int i = 0; i < 6; i++) hourRows.add(Map.of("Label", hl[i], "N", hours[i], "Pct", hours[i] * 100 / hmax, "Night", i == 0 || i == 5));
        v.put("hours", hourRows);

        // 규칙별
        Map<String, Integer> rules = new LinkedHashMap<>();
        for (Map<String, Object> it : items) {
            @SuppressWarnings("unchecked") List<String> rs = (List<String>) it.get("RuleList");
            for (String r : rs) rules.merge(CardCheck.RULES.getOrDefault(r, r), 1, Integer::sum);
        }
        v.put("rules", rules);
        v.put("prep", prep(items));
        return v;
    }

    /** 조사 준비: '문제'·'소명 필요'로 판정한 건을 사람별로 묶는다. 질문·요청 자료가 비면 규칙에서 채운다. */
    public List<Map<String, Object>> prep(List<Map<String, Object>> items) {
        Map<String, Map<String, Object>> by = new LinkedHashMap<>();
        for (Map<String, Object> it : items) {
            String verdict = Txt.str(it.get("Verdict"));
            if (!"문제".equals(verdict) && !"소명 필요".equals(verdict)) continue;
            Map<String, Object> p = by.computeIfAbsent(Txt.str(it.get("Name")), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("Name", k); m.put("Dept", it.get("Dept")); m.put("Items", new ArrayList<Map<String, Object>>()); m.put("Amt", 0L);
                m.put("Docs", new LinkedHashSet<String>());
                return m;
            });
            Map<String, Object> row = new LinkedHashMap<>(it);
            row.put("Q", Txt.blank(it.get("Question")) ? it.get("AutoQuestion") : it.get("Question"));
            String docs = Txt.blank(it.get("Docs")) ? Txt.str(it.get("AutoDocs")) : Txt.str(it.get("Docs"));
            row.put("D", docs);
            @SuppressWarnings("unchecked") List<Map<String, Object>> list = (List<Map<String, Object>>) p.get("Items");
            list.add(row);
            p.put("Amt", (long) p.get("Amt") + ((Number) it.get("Amount")).longValue());
            @SuppressWarnings("unchecked") Set<String> ds = (Set<String>) p.get("Docs");
            for (String x : docs.split(",\\s*")) if (!x.isBlank()) ds.add(x.strip());
        }
        for (Map<String, Object> p : by.values()) {
            @SuppressWarnings("unchecked") Set<String> ds = (Set<String>) p.get("Docs");
            p.put("DocsText", String.join(", ", ds));
        }
        return new ArrayList<>(by.values());
    }

    /** 감사기록으로 넘길 건: 문제·소명 필요로 판정한 건. */
    public List<Long> flagged(String setId) {
        List<Long> out = new ArrayList<>();
        for (Map<String, Object> r : db.rows("SELECT TxId FROM CardSetItems WHERE SetId=? AND Verdict IN ('문제','소명 필요')", setId))
            out.add(((Number) r.get("TxId")).longValue());
        return out;
    }

    // ------------------------------------------------------------------ 엑셀 장표

    @SuppressWarnings("unchecked")
    public byte[] xls(String setId) throws IOException {
        Map<String, Object> v = view(setId);
        if (v == null) return null;
        try (HSSFWorkbook wb = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle wrap = wb.createCellStyle();
            wrap.setWrapText(true);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0"));

            Map<String, Object> s = (Map<String, Object>) v.get("set"), run = (Map<String, Object>) v.get("run");
            Sheet sum = wb.createSheet("요약");
            int r = 0;
            r = line(sum, r, head, "법인카드 상세 분석", Txt.str(s.get("Title")));
            r = line(sum, r, null, "점검 회차", Txt.str(run.get("Title")) + " (" + Txt.str(run.get("FileName")) + ")");
            r = line(sum, r, null, "대상", ((List<?>) v.get("items")).size() + "건 · " + CardCheck.won((long) v.get("sum")) + " · " + v.get("peopleCount") + "명");
            r = line(sum, r, null, "기간", Txt.str(v.get("period")));
            for (Map.Entry<String, Integer> e : ((Map<String, Integer>) v.get("verdicts")).entrySet()) r = line(sum, r, null, "판정 · " + e.getKey(), e.getValue() + "건");
            r++;
            r = line(sum, r, head, "규칙", "건수");
            for (Map.Entry<String, Integer> e : ((Map<String, Integer>) v.get("rules")).entrySet()) r = line(sum, r, null, e.getKey(), e.getValue() + "건");
            if (!Txt.blank(s.get("Note"))) { r++; line(sum, r, head, "분석 메모", Txt.str(s.get("Note"))); }
            sum.setColumnWidth(0, 22 * 256);
            sum.setColumnWidth(1, 70 * 256);

            String[] tcols = { "판정", "문제 요지", "점수", "점검 사유", "규칙", "부서", "이름", "카드번호", "승인번호", "승인일자", "요일", "시각", "가맹점", "업종", "금액", "사용내역", "가맹점 주소", "연결고리", "처리", "소명·메모" };
            Sheet tx = wb.createSheet("대상 거래");
            header(tx, head, tcols);
            int i = 1;
            for (Map<String, Object> it : (List<Map<String, Object>>) v.get("items")) {
                Object[] c = { it.get("Verdict"), it.get("Finding"), it.get("Score"), it.get("Reasons"), it.get("RuleLabels"), it.get("Dept"), it.get("Name"), it.get("CardNo"),
                    it.get("ApprovalNo"), it.get("TxDate"), it.get("Dow"), it.get("Hm"), it.get("Merchant"), it.get("Industry"), it.get("Amount"), it.get("Purpose"),
                    it.get("MerchantAddr"), String.join(" / ", (List<String>) it.get("Chips")), it.get("Status"), it.get("ReviewNote") };
                row(tx, i++, c, money, 14);
            }
            widths(tx, 10, 30, 6, 40, 20, 14, 10, 18, 12, 11, 5, 7, 24, 14, 12, 30, 30, 40, 10, 30);
            tx.createFreezePane(0, 1);
            tx.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(1, i - 1), 0, tcols.length - 1));

            Sheet ln = wb.createSheet("연결고리");
            header(ln, head, new String[] { "분석 대상", "연결", "이름", "승인일자", "시각", "가맹점", "금액", "점수", "분석 대상 여부" });
            i = 1;
            for (Map<String, Object> it : (List<Map<String, Object>>) v.get("items")) {
                String base = it.get("TxDate") + " " + it.get("Hm") + " " + it.get("Name") + " · " + it.get("Merchant") + " · " + CardCheck.won(((Number) it.get("Amount")).longValue());
                for (Map<String, Object> o : (List<Map<String, Object>>) it.get("SameDay"))
                    row(ln, i++, new Object[] { base, "같은 날 다른 사용", o.get("Name"), o.get("TxDate"), o.get("Hm"), o.get("Merchant"), o.get("Amount"), o.get("Score"), Boolean.TRUE.equals(o.get("Picked")) ? "예" : "" }, money, 6);
                for (Map<String, Object> o : (List<Map<String, Object>>) it.get("Together"))
                    row(ln, i++, new Object[] { base, "같은 가맹점 다른 사람", o.get("Name"), o.get("TxDate"), o.get("Hm"), o.get("Merchant"), o.get("Amount"), o.get("Score"), Boolean.TRUE.equals(o.get("Picked")) ? "예" : "" }, money, 6);
            }
            widths(ln, 50, 20, 10, 11, 7, 24, 12, 6, 10);
            ln.createFreezePane(0, 1);

            Sheet bd = wb.createSheet("같은 자리 묶음");
            header(bd, head, new String[] { "승인일자", "가맹점", "합계", "사람 수", "한도 이상", "이름", "시각", "금액", "분석 대상" });
            i = 1;
            for (Map<String, Object> b : (List<Map<String, Object>>) v.get("bundles"))
                for (Map<String, Object> o : (List<Map<String, Object>>) b.get("Rows"))
                    row(bd, i++, new Object[] { b.get("Date"), b.get("Merchant"), b.get("Total"), b.get("People"), Boolean.TRUE.equals(b.get("OverLimit")) ? "예" : "", o.get("Name"), o.get("Hm"), o.get("Amount"), Boolean.TRUE.equals(o.get("Picked")) ? "예" : "" }, money, 2, 7);
            widths(bd, 11, 24, 12, 8, 9, 10, 7, 12, 10);

            Sheet pp = wb.createSheet("사람별");
            header(pp, head, new String[] { "이름", "부서", "분석 건수", "분석 금액", "이번 회차 전체 건수", "이번 회차 전체 금액", "비중", "주말 사용", "심야 사용", "지난 회차 확인 대상", "문제", "소명 필요" });
            i = 1;
            for (Map<String, Object> p : (List<Map<String, Object>>) v.get("people"))
                row(pp, i++, new Object[] { p.get("Name"), p.get("Dept"), p.get("N"), p.get("Amt"), p.get("RunN"), p.get("RunAmt"), p.get("Share"), p.get("Weekend"), p.get("Night"), p.get("Past"), p.get("Bad"), p.get("Ask") }, money, 3, 5);
            widths(pp, 10, 14, 9, 12, 12, 14, 7, 9, 9, 12, 7, 9);

            Sheet tl = wb.createSheet("사람별 전체 거래");
            header(tl, head, new String[] { "이름", "승인일자", "요일", "시각", "가맹점", "업종", "금액", "점수", "분석 대상", "사용내역" });
            i = 1;
            for (Map<String, Object> p : (List<Map<String, Object>>) v.get("people"))
                for (Map<String, Object> o : (List<Map<String, Object>>) p.get("Timeline"))
                    row(tl, i++, new Object[] { o.get("Name"), o.get("TxDate"), o.get("Dow"), o.get("Hm"), o.get("Merchant"), o.get("Industry"), o.get("Amount"), o.get("Score"), Boolean.TRUE.equals(o.get("Picked")) ? "예" : "", o.get("Purpose") }, money, 6);
            widths(tl, 10, 11, 5, 7, 24, 14, 12, 6, 9, 30);
            tl.createFreezePane(0, 1);

            Sheet pr = wb.createSheet("조사 준비");
            header(pr, head, new String[] { "이름", "부서", "판정", "승인일자", "가맹점", "금액", "문제 요지", "확인 질문", "요청 자료" });
            i = 1;
            for (Map<String, Object> p : (List<Map<String, Object>>) v.get("prep"))
                for (Map<String, Object> o : (List<Map<String, Object>>) p.get("Items"))
                    row(pr, i++, new Object[] { p.get("Name"), p.get("Dept"), o.get("Verdict"), o.get("TxDate"), o.get("Merchant"), o.get("Amount"), o.get("Finding"), o.get("Q"), o.get("D") }, money, 5);
            widths(pr, 10, 14, 10, 11, 24, 12, 40, 50, 30);
            for (int k = 1; k < i; k++) for (int c : new int[] { 6, 7, 8 }) if (pr.getRow(k).getCell(c) != null) pr.getRow(k).getCell(c).setCellStyle(wrap);

            wb.write(out);
            return out.toByteArray();
        }
    }

    private static int line(Sheet sh, int r, CellStyle st, String a, String b) {
        Row row = sh.createRow(r);
        row.createCell(0).setCellValue(a);
        row.createCell(1).setCellValue(b);
        if (st != null) { row.getCell(0).setCellStyle(st); row.getCell(1).setCellStyle(st); }
        return r + 1;
    }

    private static void header(Sheet sh, CellStyle st, String[] cols) {
        Row row = sh.createRow(0);
        for (int i = 0; i < cols.length; i++) { row.createCell(i).setCellValue(cols[i]); row.getCell(i).setCellStyle(st); }
    }

    private static void row(Sheet sh, int r, Object[] c, CellStyle money, int... moneyCols) {
        Row row = sh.createRow(r);
        Set<Integer> mc = new java.util.HashSet<>();
        for (int m : moneyCols) mc.add(m);
        for (int i = 0; i < c.length; i++) {
            Object o = c[i];
            if (o instanceof Number n) {
                row.createCell(i).setCellValue(n.doubleValue());
                if (mc.contains(i)) row.getCell(i).setCellStyle(money);
            } else row.createCell(i).setCellValue(Txt.str(o).replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " "));
        }
    }

    private static void widths(Sheet sh, int... w) {
        for (int i = 0; i < w.length; i++) sh.setColumnWidth(i, w[i] * 256);
    }
}
