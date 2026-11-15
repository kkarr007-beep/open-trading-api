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

    public List<Map<String, Object>> list(String text, String sanction, Integer year) {
        StringBuilder sql = new StringBuilder(SELECT + " WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (SANCTIONS.contains(sanction)) { sql.append(" AND d.SanctionType=?"); args.add(sanction); }
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
