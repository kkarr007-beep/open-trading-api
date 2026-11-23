package local.audit;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import local.audit.extract.DocAnalyzer;

/** 징계기록 등재. */
@Service
public class DisciplineService {
    public static final List<String> SANCTIONS = DocAnalyzer.SANCTIONS;
    private static final String SELECT = """
        SELECT d.*, c.Title AS CaseTitle, c.DocumentNo AS CaseDocumentNo
        FROM Discipline d LEFT JOIN Cases c ON c.CaseId=d.CaseId""";

    private final Db db;
    private final Chain chain;
    private final Settings settings;

    public DisciplineService(Db db, Chain chain, Settings settings) {
        this.db = db;
        this.chain = chain;
        this.settings = settings;
    }

    private static Map<String, Object> decorate(Map<String, Object> d) {
        if (d == null) return null;
        d.put("PersonMasked", Txt.mask((String) d.get("PersonName")));
        String created = Kst.display((String) d.get("CreatedAt"));
        d.put("CreatedDate", created.length() >= 10 ? created.substring(0, 10) : created);
        return d;
    }

    public List<Map<String, Object>> list(String text, String sanction, Integer year) { return list(text, sanction, year, null); }

    public List<Map<String, Object>> list(String text, String sanction, Integer year, String dept) {
        StringBuilder sql = new StringBuilder(SELECT + " WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (!Txt.blank(sanction)) {
            if ("감급".equals(sanction) || "감봉".equals(sanction)) sql.append(" AND (d.SanctionType LIKE '%감급%' OR d.SanctionType LIKE '%감봉%')");
            else { sql.append(" AND d.SanctionType=?"); args.add(sanction); }
        }
        if (!Txt.blank(dept)) {
            if ("(부서 없음)".equals(dept)) sql.append(" AND TRIM(d.Department)=''");
            else {
                String e = Txt.like(dept.strip());
                e = e.substring(1, e.length() - 1);   // 앞뒤 % 뺀, 특수문자를 막은 글
                sql.append(" AND (TRIM(d.Department)=? OR d.Department LIKE ? ESCAPE '\\' OR d.Department LIKE ? ESCAPE '\\' OR d.Department LIKE ? ESCAPE '\\')");
                args.add(dept.strip()); args.add(e + " %"); args.add(e + "/%"); args.add(e + ">%");
            }
        }
        if (year != null) { sql.append(" AND d.Year=?"); args.add(year); }
        if (!Txt.blank(text)) {
            String[] cols = { "d.EmployeeNo", "d.PersonName", "d.Department", "d.Position", "d.ViolationText", "d.RuleText",
                "d.DecisionBody", "d.DocNo", "d.Remark", "c.Title", "c.DocumentNo" };
            sql.append(" AND (");
            for (int i = 0; i < cols.length; i++) {
                sql.append(i == 0 ? "" : " OR ").append(cols[i]).append(" LIKE ? ESCAPE '\\'");
                args.add(Txt.like(text.strip()));
            }
            sql.append(")");
        }
        sql.append(" ORDER BY d.Seq DESC");
        List<Map<String, Object>> rows = db.rows(sql.toString(), args.toArray());
        rows.forEach(DisciplineService::decorate);
        return rows;
    }

    public List<Map<String, Object>> forCase(String caseId) {
        List<Map<String, Object>> rows = db.rows(SELECT + " WHERE d.CaseId=? ORDER BY d.Seq", caseId);
        rows.forEach(DisciplineService::decorate);
        return rows;
    }

    // ───── 징계 현황판: 연도·부서·팀별 건수, 누적 징계 검토 ─────

    /** 같은 사람이 기간 안에 같은 징계를 정한 횟수 이상 받으면 상위 징계를 검토한다. */
    public record Rule(String from, int years, int times, String to) {
        public String text() { return (years == 1 ? "1년" : years + "년") + " 안에 " + from + " " + times + "회 → " + to + " 검토"; }
    }

    static final String DEFAULT_RULES = "주의,1,2,경고;경고,2,2,감급;견책,2,2,정직;감급,2,2,면직";

    public List<Rule> rules() {
        List<Rule> out = new ArrayList<>();
        for (String part : settings.get("disciplineRules", DEFAULT_RULES).split(";")) {
            String[] f = part.split(",");
            if (f.length != 4) continue;
            Integer y = Txt.intOrNull(f[1].strip()), n = Txt.intOrNull(f[2].strip());
            if (y == null || n == null || y < 1 || n < 2 || f[0].isBlank() || f[3].isBlank()) continue;
            out.add(new Rule(f[0].strip(), y, n, f[3].strip()));
        }
        return out;
    }

    public Op saveRules(Map<String, String> p) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            String from = Txt.str(p.get("from" + i)).strip(), to = Txt.str(p.get("to" + i)).strip();
            Integer y = Txt.intOrNull(p.get("years" + i)), n = Txt.intOrNull(p.get("times" + i));
            if (from.isEmpty() && to.isEmpty()) continue;
            if (from.isEmpty() || to.isEmpty() || y == null || n == null || y < 1 || y > 10 || n < 2 || n > 9 || from.contains(",") || from.contains(";") || to.contains(",") || to.contains(";"))
                return Op.bad((i + 1) + "번째 줄을 확인하세요: 징계·기간(1~10년)·횟수(2~9회)·검토할 징계를 모두 적어야 합니다.");
            if (sb.length() > 0) sb.append(';');
            sb.append(from).append(',').append(y).append(',').append(n).append(',').append(to);
        }
        settings.set("disciplineRules", sb.toString());
        return Op.ok("누적 징계 검토 기준을 저장했습니다.");
    }

    /** 감급과 감봉은 같은 징계로 본다. */
    static boolean same(String sanction, String name) {
        String a = Txt.str(sanction).replace("감봉", "감급"), b = Txt.str(name).replace("감봉", "감급");
        return a.contains(b);
    }

    /** 부서 칸의 첫 말을 부서(본부·실), 나머지 전체를 팀으로 본다. 한 말뿐이면 둘 다 같다. */
    static String[] deptTeam(String department) {
        String d = Txt.str(department).strip();
        if (d.isEmpty()) return new String[] { "(부서 없음)", "(부서 없음)" };
        String[] w = d.split("\\s*[/>·]\\s*|\\s+");
        return new String[] { w[0], d };
    }

    static String when(Map<String, Object> d) {
        String s = Txt.str(d.get("SanctionDate"));
        return s.length() >= 10 ? s.substring(0, 10) : Txt.str(d.get("ViolationDate"));
    }

    static String personKey(Map<String, Object> d) {
        String no = Txt.str(d.get("EmployeeNo")).strip();
        return no.isEmpty() ? "n:" + Txt.str(d.get("PersonName")).strip() + "|" + Txt.str(d.get("Department")).strip() : "e:" + no;
    }

    /** 누적 징계 검토 대상: 사람마다, 기준마다, 기간 안에 정한 횟수 이상 받은 경우. */
    public List<Map<String, Object>> escalations() {
        List<Map<String, Object>> all = db.rows(SELECT + " WHERE d.Status<>'삭제됨' ORDER BY d.Seq");
        all.forEach(DisciplineService::decorate);
        Map<String, List<Map<String, Object>>> byPerson = new java.util.LinkedHashMap<>();
        for (Map<String, Object> d : all) byPerson.computeIfAbsent(personKey(d), k -> new ArrayList<>()).add(d);
        java.util.Set<String> reviewed = new java.util.HashSet<>(java.util.Arrays.asList(settings.get("disciplineReviewed", "").split(";")));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Rule r : rules()) {
            for (List<Map<String, Object>> recs : byPerson.values()) {
                List<Map<String, Object>> hits = new ArrayList<>(recs.stream().filter(d -> same(Txt.str(d.get("SanctionType")), r.from()) && when(d).length() >= 10).toList());
                if (hits.size() < r.times()) continue;
                hits.sort(java.util.Comparator.comparing(DisciplineService::when));
                // 기간 안에 든 가장 최근 묶음을 찾는다
                for (int end = hits.size() - 1; end >= r.times() - 1; end--) {
                    java.time.LocalDate last = java.time.LocalDate.parse(when(hits.get(end)));
                    java.time.LocalDate first = java.time.LocalDate.parse(when(hits.get(end - r.times() + 1)));
                    if (first.isBefore(last.minusYears(r.years()))) continue;
                    List<Map<String, Object>> group = new ArrayList<>(hits.subList(end - r.times() + 1, end + 1));
                    for (int k = end - r.times(); k >= 0 && !java.time.LocalDate.parse(when(hits.get(k))).isBefore(last.minusYears(r.years())); k--) group.add(0, hits.get(k));
                    Map<String, Object> last0 = group.get(group.size() - 1);
                    String key = personKey(last0) + "|" + r.from() + "|" + when(last0);
                    // 마지막 건 뒤에 이미 상위 징계를 받았으면 처리된 것으로 본다
                    boolean done = recs.stream().anyMatch(d -> same(Txt.str(d.get("SanctionType")), r.to()) && when(d).compareTo(when(last0)) >= 0);
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("key", key);
                    m.put("rule", r.text());
                    m.put("to", r.to());
                    m.put("from", r.from());
                    m.put("count", group.size());
                    m.put("person", last0.get("PersonMasked"));
                    m.put("employeeNo", last0.get("EmployeeNo"));
                    m.put("department", last0.get("Department"));
                    m.put("records", group);
                    m.put("lastDate", when(last0));
                    m.put("done", done);
                    m.put("reviewed", reviewed.contains(key));
                    out.add(m);
                    break;
                }
            }
        }
        out.sort(java.util.Comparator.comparing((Map<String, Object> m) -> (Boolean) m.get("done") || (Boolean) m.get("reviewed"))
            .thenComparing(m -> (String) m.get("lastDate"), java.util.Comparator.reverseOrder()));
        return out;
    }

    public Op markReviewed(String key, boolean on) {
        if (Txt.blank(key) || key.length() > 300 || key.contains(";")) return Op.bad("검토 표시를 찾지 못했습니다.");
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>(java.util.Arrays.asList(settings.get("disciplineReviewed", "").split(";")));
        set.remove("");
        if (on) set.add(key); else set.remove(key);
        settings.set("disciplineReviewed", String.join(";", set));
        return Op.ok(on ? "검토한 것으로 표시했습니다." : "검토 표시를 풀었습니다.");
    }

    /** 현황판 숫자: 연도×징계종류, 부서·팀×연도. */
    public Map<String, Object> board() {
        List<Map<String, Object>> all = db.rows("SELECT Year, Department, SanctionType FROM Discipline WHERE Status<>'삭제됨'");
        java.util.TreeSet<Integer> years = new java.util.TreeSet<>(java.util.Comparator.reverseOrder());
        java.util.LinkedHashSet<String> kinds = new java.util.LinkedHashSet<>();
        for (String k : SANCTIONS) kinds.add(k.replace("감봉", "감급"));
        Map<String, Integer> byYK = new java.util.HashMap<>(), byYear = new java.util.HashMap<>(), byKind = new java.util.HashMap<>();
        Map<String, Map<Integer, Integer>> byDept = new java.util.TreeMap<>(), byTeam = new java.util.TreeMap<>();
        for (Map<String, Object> d : all) {
            int y = ((Number) d.get("Year")).intValue();
            String k = Txt.str(d.get("SanctionType")).replace("감봉", "감급");
            years.add(y);
            if (!k.isEmpty()) kinds.add(k);
            byYK.merge(y + "|" + k, 1, Integer::sum);
            byYear.merge(String.valueOf(y), 1, Integer::sum);
            byKind.merge(k, 1, Integer::sum);
            String[] dt = deptTeam(Txt.str(d.get("Department")));
            byDept.computeIfAbsent(dt[0], x -> new java.util.HashMap<>()).merge(y, 1, Integer::sum);
            byTeam.computeIfAbsent(dt[1], x -> new java.util.HashMap<>()).merge(y, 1, Integer::sum);
        }
        kinds.removeIf(k -> !byKind.containsKey(k));
        List<Integer> recent = years.stream().limit(5).toList();
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("total", all.size());
        m.put("years", new ArrayList<>(years));
        m.put("recentYears", recent);
        m.put("kinds", new ArrayList<>(kinds));
        m.put("byYK", byYK);
        m.put("byYear", byYear);
        m.put("byKind", byKind);
        m.put("depts", rowsOf(byDept, recent));
        m.put("teams", rowsOf(byTeam, recent));
        m.put("thisYear", byYear.getOrDefault(String.valueOf(Kst.today().getYear()), 0));
        m.put("year", Kst.today().getYear());
        return m;
    }

    static List<Map<String, Object>> rowsOf(Map<String, Map<Integer, Integer>> src, List<Integer> recent) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : src.entrySet()) {
            Map<String, Object> r = new java.util.LinkedHashMap<>();
            r.put("name", e.getKey());
            List<Integer> cells = new ArrayList<>();
            for (Integer y : recent) cells.add(e.getValue().getOrDefault(y, 0));
            r.put("cells", cells);
            r.put("total", e.getValue().values().stream().mapToInt(Integer::intValue).sum());
            out.add(r);
        }
        out.sort(java.util.Comparator.comparing((Map<String, Object> r) -> (Integer) r.get("total")).reversed());
        return out;
    }

    public List<Map<String, Object>> years() { return db.rows("SELECT DISTINCT Year FROM Discipline ORDER BY Year DESC"); }

    public Map<String, Object> open(String id) {
        if (!Txt.isId(id)) return null;
        Map<String, Object> d = decorate(db.row(SELECT + " WHERE d.DisciplineId=?", id));
        if (d == null) return null;
        List<Map<String, Object>> history = db.rows("SELECT * FROM History WHERE TargetKind='징계' AND TargetId=? ORDER BY Seq", id);
        for (Map<String, Object> h : history) h.put("AtDisplay", Kst.display((String) h.get("At")));
        d.put("History", history);
        return d;
    }

    /** 감사번호나 건 제목으로 건을 찾는다. 비어 있으면 연결하지 않는다. */
    private static String resolveCase(Connection conn, String ref) throws SQLException {
        if (Txt.blank(ref)) return null;
        String r = ref.strip();
        List<Map<String, Object>> hit = Db.rows(conn, "SELECT CaseId FROM Cases WHERE DocumentNo=?", r);
        if (hit.isEmpty()) hit = Db.rows(conn, "SELECT CaseId FROM Cases WHERE Title=?", r);
        if (hit.isEmpty() && Txt.isId(r)) hit = Db.rows(conn, "SELECT CaseId FROM Cases WHERE CaseId=?", r);
        if (hit.size() != 1) throw Op.abort(hit.isEmpty() ? "연결할 감사 건을 찾지 못했습니다. 감사번호를 확인하세요." : "같은 제목의 건이 여럿입니다. 감사번호로 적으세요.");
        return (String) hit.get(0).get("CaseId");
    }

    /** in의 열쇠: id, version, caseRef, year, employeeNo, personName, department, position, violationDate, violationText, ruleText, sanctionType, sanctionDate, sanctionTerm, decisionBody, docNo, status, remark */
    public Op save(Map<String, String> in) {
        if (!chain.intact()) return Op.fail(503, CaseService.FROZEN);
        String id = in.get("id");
        Integer year = Txt.intOrNull(in.get("year"));
        String empNo = Txt.clean(in.get("employeeNo"), 40, false), name = Txt.clean(in.get("personName"), 40, false);
        String dept = Txt.clean(in.get("department"), 80, false), pos = Txt.clean(in.get("position"), 40, false);
        String vText = Txt.clean(in.get("violationText"), 500, true), rule = Txt.clean(in.get("ruleText"), 240, true);
        String term = Txt.clean(in.get("sanctionTerm"), 80, false), body = Txt.clean(in.get("decisionBody"), 80, false);
        String docNo = Txt.clean(in.get("docNo"), 80, false), remark = Txt.clean(in.get("remark"), 500, true);
        String type = in.get("sanctionType"), status = Txt.blank(in.get("status")) ? "초안" : in.get("status");
        LocalDate vDate = Kst.date(in.get("violationDate"));
        String sDateRaw = Txt.str(in.get("sanctionDate")).strip();
        LocalDate sDate = Kst.date(sDateRaw);
        if (year == null || year < 1990 || year > 2100 || Txt.blank(name) || vDate == null || Txt.blank(vText) || !SANCTIONS.contains(type)
            || empNo == null || dept == null || pos == null || rule == null || term == null || body == null || docNo == null || remark == null
            || !(status.equals("초안") || status.equals("확정")) || (!sDateRaw.isEmpty() && sDate == null))
            return Op.bad("성명, 위반일자, 위반내용, 징계종류를 확인하세요.");
        if (status.equals("확정") && sDate == null) return Op.bad("확정하려면 징계일자를 입력하세요. 징계일자가 보존 기산일입니다.");
        String me = settings.owner();
        return db.run(conn -> {
            String caseId = resolveCase(conn, in.get("caseRef"));
            String confirmedOn = status.equals("확정") ? sDate.toString() : null;
            if (Txt.blank(id) || "new".equals(id)) {
                String newId = Txt.newId();
                int seq = Db.intOf(conn, "SELECT IFNULL(MAX(Seq),0) FROM Discipline") + 1;
                Db.update(conn, """
                    INSERT INTO Discipline(DisciplineId, Seq, CaseId, Year, EmployeeNo, PersonName, Department, Position, ViolationDate,
                      ViolationText, RuleText, SanctionType, SanctionDate, SanctionTerm, DecisionBody, DocNo, Status, Remark,
                      ConfirmedOn, Version, CreatedAt, CreatedBy)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,?)""",
                    newId, seq, caseId, year, empNo, name, dept, pos, vDate.toString(), vText, rule, type,
                    sDate == null ? "" : sDate.toString(), term, body, docNo, status, remark, confirmedOn, Kst.stamp(), me);
                Chain.append(conn, caseId, me, "등록", "징계", newId, "징계기록 순번 " + seq + " 등재");
                return Op.ok("등재했습니다.", newId);
            }
            Map<String, Object> row = Db.row(conn, "SELECT * FROM Discipline WHERE DisciplineId=?", id);
            if (row == null) throw Op.abort(404, "징계기록을 찾을 수 없음");
            if ("삭제됨".equals(row.get("Status"))) throw Op.abort(409, CaseService.LOCKED);
            Integer version = Txt.intOrNull(in.get("version"));
            if (version != null && !version.equals(row.get("Version"))) throw Op.abort(409, CaseService.STALE);
            Db.update(conn, """
                UPDATE Discipline SET CaseId=?, Year=?, EmployeeNo=?, PersonName=?, Department=?, Position=?, ViolationDate=?,
                  ViolationText=?, RuleText=?, SanctionType=?, SanctionDate=?, SanctionTerm=?, DecisionBody=?, DocNo=?, Status=?,
                  Remark=?, ConfirmedOn=?, Version=Version+1 WHERE DisciplineId=?""",
                caseId, year, empNo, name, dept, pos, vDate.toString(), vText, rule, type, sDate == null ? "" : sDate.toString(),
                term, body, docNo, status, remark, confirmedOn, id);
            String summary = "징계기록 수정" + (status.equals(row.get("Status")) ? "" : ". 상태 " + row.get("Status") + " → " + status);
            Chain.append(conn, caseId, me, "수정", "징계", id, summary);
            return Op.ok("저장했습니다.", id);
        });
    }

    public Op setHold(String id, boolean hold, String reason) {
        if (!chain.intact()) return Op.fail(503, CaseService.FROZEN);
        String why = Txt.clean(reason, 240, false);
        if (Txt.blank(why)) return Op.bad(CaseService.REASON);
        return db.run(conn -> {
            Map<String, Object> row = Db.row(conn, "SELECT * FROM Discipline WHERE DisciplineId=?", id);
            if (row == null) throw Op.abort(404, "징계기록을 찾을 수 없음");
            Db.update(conn, "UPDATE Discipline SET Hold=?, Version=Version+1 WHERE DisciplineId=?", hold, id);
            Chain.append(conn, (String) row.get("CaseId"), settings.owner(), hold ? "보류" : "보류해제", "징계", id,
                (hold ? "삭제 보류 설정" : "삭제 보류 해제") + ". 사유: " + why);
            return Op.ok(hold ? "삭제 보류를 설정했습니다." : "삭제 보류를 해제했습니다.", id);
        });
    }

    public Op setRetention(String id, Integer years) {
        if (years == null || years < 1 || years > 100) return Op.bad("보존연한은 1년에서 100년 사이입니다.");
        return db.run(conn -> {
            Map<String, Object> row = Db.row(conn, "SELECT * FROM Discipline WHERE DisciplineId=?", id);
            if (row == null || "삭제됨".equals(row.get("Status"))) throw Op.abort(404, "징계기록을 찾을 수 없음");
            if (years.equals(row.get("RetentionYears"))) return Op.ok("저장했습니다.", id);
            Db.update(conn, "UPDATE Discipline SET RetentionYears=?, Version=Version+1 WHERE DisciplineId=?", years, id);
            Chain.append(conn, (String) row.get("CaseId"), settings.owner(), "연한변경", "징계", id, row.get("RetentionYears") + "년 → " + years + "년");
            return Op.ok("저장했습니다.", id);
        });
    }

    /** CSV 한 줄을 칸으로 나눈다. 따옴표 안의 쉼표와 줄바꿈을 지킨다. */
    public static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        if (t.startsWith("\uFEFF")) t = t.substring(1);
        char sep = t.indexOf('\t') >= 0 && t.indexOf(',') < 0 ? '\t' : t.split("\n", 2)[0].chars().filter(c -> c == ';').count() > t.split("\n", 2)[0].chars().filter(c -> c == ',').count() ? ';' : ',';
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < t.length() && t.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else if (c == '"') quoted = false;
                else cell.append(c);
            } else if (c == '"' && cell.length() == 0) quoted = true;
            else if (c == sep) { row.add(cell.toString()); cell.setLength(0); }
            else if (c == '\n') { row.add(cell.toString()); cell.setLength(0); rows.add(row); row = new ArrayList<>(); }
            else cell.append(c);
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
        return rows;
    }

    /**
     * 표(줄마다 칸의 목록)에서 징계기록을 한꺼번에 등재한다. 첫 머리글 줄에서 열을 찾는다.
     * 같은 사번·성명·위반일자·징계종류가 이미 있으면 건너뛴다. 모두 초안으로 넣고, 상태 칸이 '확정'이고 징계일자가 있으면 확정으로 넣는다.
     */
    public Op importRows(List<List<String>> rows) {
        if (!chain.intact()) return Op.fail(503, CaseService.FROZEN);
        Map<String, Integer> cols = null;
        int statusCol = -1, caseCol = -1, yearCol = -1, added = 0, same = 0, line = 0;
        List<String> problems = new ArrayList<>();
        for (List<String> cells : rows) {
            line++;
            if (cells.stream().allMatch(String::isBlank)) continue;
            if (cols == null) {
                List<String> flat = new ArrayList<>();
                for (String c : cells) flat.add(c.replaceAll("[\\t\\n]", " "));
                cols = DocAnalyzer.disciplineHeader(String.join("\t", flat));
                if (cols != null) {
                    for (int i = 0; i < cells.size(); i++) {
                        String h = cells.get(i).replaceAll("\\s", "");
                        if (h.equals("상태")) statusCol = i;
                        else if (h.matches("감사번호|감사건|감사건연결|문서번호\\(감사\\)")) caseCol = i;
                        else if (h.equals("연도")) yearCol = i;
                    }
                }
                continue;
            }
            Map<String, String> in = new java.util.HashMap<>();
            for (Map.Entry<String, Integer> e : cols.entrySet())
                in.put(e.getKey(), e.getValue() < cells.size() ? cells.get(e.getValue()).strip() : "");
            String name = in.get("personName"), type = DocAnalyzer.sanction(in.get("sanctionType"));
            if (Txt.blank(name) && Txt.blank(in.get("sanctionType"))) continue;
            String status = statusCol >= 0 && statusCol < cells.size() ? cells.get(statusCol).strip() : "";
            if (status.equals("삭제됨")) continue;
            LocalDate vDate = Kst.date(in.get("violationDate")), sDate = Kst.date(in.get("sanctionDate"));
            if (vDate == null) vDate = DocAnalyzer.firstDate(in.get("violationDate"));
            if (sDate == null) sDate = DocAnalyzer.firstDate(in.get("sanctionDate"));
            String why = Txt.blank(name) ? "성명 없음" : type.isEmpty() ? "징계종류를 알 수 없음(" + in.get("sanctionType") + ")"
                : vDate == null ? "위반일자를 알 수 없음(" + in.get("violationDate") + ")" : Txt.blank(in.get("violationText")) ? "위반내용 없음" : null;
            if (why != null) { if (problems.size() < 8) problems.add(line + "번째 줄: " + why); else if (problems.size() == 8) problems.add("…"); continue; }
            String empNo = Txt.str(in.get("employeeNo"));
            if (db.row("SELECT 1 FROM Discipline WHERE EmployeeNo=? AND PersonName=? AND ViolationDate=? AND SanctionType=?", Txt.fit(empNo, 40, false), Txt.fit(name, 40, false), vDate.toString(), type) != null) { same++; continue; }
            // 징계종류 칸에 '감봉 1개월'처럼 기간이 함께 적힌 경우 기간으로 옮긴다.
            String rest = in.get("sanctionType").replace(type, "").strip();
            if (Txt.blank(in.get("sanctionTerm")) && !rest.isEmpty()) in.put("sanctionTerm", rest);
            in.put("sanctionType", type);
            in.put("violationDate", vDate.toString());
            in.put("sanctionDate", sDate == null ? "" : sDate.toString());
            Integer year = yearCol >= 0 && yearCol < cells.size() ? Txt.intOrNull(cells.get(yearCol)) : null;
            in.put("year", Integer.toString(year != null && year >= 1990 && year <= 2100 ? year : vDate.getYear()));
            in.put("status", status.equals("확정") && sDate != null ? "확정" : "초안");
            in.put("caseRef", caseCol >= 0 && caseCol < cells.size() ? cells.get(caseCol).strip() : "");
            in.put("personName", Txt.fit(name, 40, false));
            in.put("employeeNo", Txt.fit(empNo, 40, false));
            in.put("position", Txt.fit(in.get("position"), 40, false));
            for (String k : List.of("department", "sanctionTerm", "decisionBody", "docNo")) in.put(k, Txt.fit(in.get(k), 80, false));
            in.put("violationText", Txt.fit(in.get("violationText"), 500, true));
            in.put("ruleText", Txt.fit(in.get("ruleText"), 240, true));
            in.put("remark", Txt.fit(in.get("remark"), 500, true));
            Op op = save(in);
            if (!op.ok && !Txt.blank(in.get("caseRef"))) { in.put("caseRef", ""); in.put("remark", Txt.fit((in.get("remark") + " 감사 건 연결 안 됨").strip(), 500, true)); op = save(in); }
            if (op.ok) added++;
            else if (problems.size() < 8) problems.add(line + "번째 줄: " + op.message);
        }
        if (cols == null) return Op.bad("머리글 줄을 찾지 못했습니다. 첫 줄에 성명, 징계종류, 위반일자 같은 항목 이름이 있어야 합니다. '양식 받기'의 파일을 참고하세요.");
        String message = added + "건 등재" + (same > 0 ? ", 이미 있는 " + same + "건은 건너뜀" : "") + (problems.isEmpty() ? "" : ". 넣지 못한 줄 — " + String.join(" / ", problems));
        return added > 0 || problems.isEmpty() ? Op.ok(message) : Op.bad(message);
    }

    public static String templateCsv() {
        return "\uFEFF사번,성명,부서,직위,위반일자,위반내용,관련규정,징계종류,징계일자,징계기간,결정기관,근거문서번호,상태,비고,감사번호\r\n"
            + "A-1001,홍길동,예시부서,대리,2026-01-05,근태 기록 허위 입력(예시),취업규칙 제0조,견책,2026-02-01,,인사위원회,인사-2026-001,초안,,\r\n";
    }

    public String exportCsv() {
        List<Map<String, Object>> rows = list(null, null, null);
        StringBuilder sb = new StringBuilder("﻿순번,사번,성명,부서,직위,위반일자,위반내용,관련규정,징계종류,징계일자,징계기간,결정기관,근거문서번호,상태,비고,등록일자,담당자\r\n");
        for (int i = rows.size() - 1; i >= 0; i--) {
            Map<String, Object> d = rows.get(i);
            Object[] cols = { d.get("Seq"), d.get("EmployeeNo"), d.get("PersonName"), d.get("Department"), d.get("Position"),
                d.get("ViolationDate"), d.get("ViolationText"), d.get("RuleText"), d.get("SanctionType"), d.get("SanctionDate"),
                d.get("SanctionTerm"), d.get("DecisionBody"), d.get("DocNo"), d.get("Status"), d.get("Remark"), d.get("CreatedDate"), d.get("CreatedBy") };
            for (int c = 0; c < cols.length; c++) sb.append(c == 0 ? "" : ",").append(Txt.csv(cols[c]));
            sb.append("\r\n");
        }
        db.tx(conn -> Chain.append(conn, null, settings.owner(), "내보내기", "징계", null, "징계기록 CSV " + rows.size() + "건"));
        return sb.toString();
    }

    /** 보존 만료: 성명과 위반내용은 지우고 순번·날짜·이력은 남긴다. 보류 중이면 건너뛴다. */
    public int purgeDue() {
        return db.tx(conn -> {
            int n = 0;
            for (Map<String, Object> d : Db.rows(conn, "SELECT * FROM Discipline WHERE Status='확정' AND ConfirmedOn IS NOT NULL AND Hold=0")) {
                LocalDate anchor = Kst.date(d.get("ConfirmedOn"));
                if (anchor == null || !Kst.isDue(anchor, (Integer) d.get("RetentionYears"))) continue;
                Db.update(conn, """
                    UPDATE Discipline SET EmployeeNo='', PersonName='삭제됨', Department='', Position='', ViolationText='', RuleText='',
                      SanctionTerm='', DecisionBody='', DocNo='', Remark='', Status='삭제됨', Version=Version+1 WHERE DisciplineId=?""", d.get("DisciplineId"));
                Chain.append(conn, (String) d.get("CaseId"), null, "자료삭제", "징계", (String) d.get("DisciplineId"), "징계기록 보존 만료");
                n++;
            }
            return n;
        });
    }
}
