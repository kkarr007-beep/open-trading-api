package local.audit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import local.audit.extract.Rich;
import local.audit.extract.TextReader;

/** 감사 건: 건, 결과, 조치요구, 이행, 자료, 이력. */
@Service
public class CaseService {
    public static final String STALE = "다른 창에서 먼저 변경했습니다. 최신 내용을 확인한 뒤 다시 저장하세요.";
    public static final String LOCKED = "만료되었거나 이미 삭제된 내용은 다시 넣을 수 없습니다. 삭제된 자료는 복구되지 않습니다.";
    public static final String REASON = "사유를 입력하세요. 사유는 이력에 남습니다.";
    public static final String FROZEN = "이력 훼손 — 기록의 신뢰성을 확인할 수 없어 변경을 멈췄습니다. 관리 화면에서 조사 후 해제하세요.";
    public static final String BAD = "입력값을 확인하세요.";
    /** 기본 구분. 관리 화면에서 더 넣을 수 있다. */
    public static final List<String> CATEGORIES = java.util.Arrays.asList("업무진단", "준법감사");

    /** 쓰는 구분 전체(구분코드 순). */
    public List<String> categories() {
        List<String> out = new java.util.ArrayList<>();
        for (Map<String, Object> r : db.rows("SELECT Category FROM CategoryCodes ORDER BY Code")) out.add((String) r.get("Category"));
        return out;
    }

    boolean isCategory(String c) {
        return c != null && db.row("SELECT 1 AS X FROM CategoryCodes WHERE Category=?", c) != null;
    }

    static boolean isCategory(Connection conn, String c) throws SQLException {
        return c != null && Db.row(conn, "SELECT 1 AS X FROM CategoryCodes WHERE Category=?", c) != null;
    }

    /** 구분을 새로 넣는다. 코드는 숫자 두 자리, 이름과 코드 모두 겹치면 안 된다. */
    public Op addCategory(String name, String code) {
        String n = Txt.clean(name, 20, false), c = code == null ? "" : code.strip();
        if (Txt.blank(n)) return Op.bad("업무 구분 이름을 적으세요.");
        if (!c.matches("\\d{2}")) return Op.bad("구분코드는 숫자 두 자리입니다.");
        return db.run(conn -> {
            if (Db.intOf(conn, "SELECT COUNT(*) FROM CategoryCodes WHERE Category=?", n) > 0) throw Op.abort("이미 있는 업무 구분입니다.");
            if (Db.intOf(conn, "SELECT COUNT(*) FROM CategoryCodes WHERE Code=?", c) > 0) throw Op.abort("이미 쓰는 구분코드입니다.");
            Db.update(conn, "INSERT INTO CategoryCodes(Category, Code, Used) VALUES (?,?,0)", n, c);
            log(conn, null, "추가", "구분코드", n, n + " " + c + " 추가");
            return Op.ok("업무 구분 '" + n + "'(" + c + ")을 넣었습니다.");
        });
    }

    /** 아직 번호를 발급하지 않고 건도 없는 구분만 뺀다. */
    public Op removeCategory(String name) {
        return db.run(conn -> {
            Map<String, Object> row = Db.row(conn, "SELECT Code, Used FROM CategoryCodes WHERE Category=?", name);
            if (row == null) throw Op.abort(404, "업무 구분을 찾을 수 없습니다.");
            if (flag(row, "Used") || Db.intOf(conn, "SELECT COUNT(*) FROM Cases WHERE Category=?", name) > 0)
                throw Op.abort("이미 번호를 발급했거나 건이 있는 구분은 뺄 수 없습니다.");
            if (Db.intOf(conn, "SELECT COUNT(*) FROM CategoryCodes") <= 1) throw Op.abort("구분이 하나는 있어야 합니다.");
            Db.update(conn, "DELETE FROM CategoryCodes WHERE Category=?", name);
            log(conn, null, "삭제", "구분코드", name, name + " " + row.get("Code") + " 뺌");
            return Op.ok("업무 구분 '" + name + "'을 뺐습니다.");
        });
    }
    public static final List<String> KINDS = java.util.Arrays.asList("결과원문", "증빙", "회신첨부");
    public static final List<String> ACTION_TYPES = java.util.Arrays.asList("지적", "개선요구", "권고");

    interface CaseWork { Op run(Connection conn, Map<String, Object> row) throws SQLException; }

    private final Db db;
    private final Chain chain;
    private final Settings settings;
    private final FileStore files;
    private final TextReader reader;
    private final ExecutorService readQueue = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "read-queue");
        t.setDaemon(true);
        return t;
    });
    private volatile Future<?> lastRead;

    public CaseService(Db db, Chain chain, Settings settings, FileStore files, TextReader reader) {
        this.db = db;
        this.chain = chain;
        this.settings = settings;
        this.files = files;
        this.reader = reader;
    }

    // ───────────── 공통 ─────────────

    private String me() { return settings.owner(); }

    private Op withCase(String caseId, Integer version, CaseWork work) {
        if (!chain.intact()) return Op.fail(503, FROZEN);
        if (!Txt.isId(caseId)) return Op.fail(404, "건을 찾을 수 없음");
        return db.run(conn -> {
            Map<String, Object> row = Db.row(conn, "SELECT * FROM Cases WHERE CaseId=?", caseId);
            if (row == null) throw Op.abort(404, "건을 찾을 수 없음");
            if (version != null && !version.equals(row.get("Version"))) throw Op.abort(409, STALE);
            Op op = work.run(conn, row);
            op.id = caseId;
            return op;
        });
    }

    private static void bump(Connection conn, String caseId, String set, Object... args) throws SQLException {
        Object[] all = new Object[args.length + 1];
        System.arraycopy(args, 0, all, 0, args.length);
        all[args.length] = caseId;
        Db.update(conn, "UPDATE Cases SET " + (set.isEmpty() ? "" : set + ", ") + "Version=Version+1 WHERE CaseId=?", all);
    }

    private static boolean flag(Map<String, Object> row, String key) { return Integer.valueOf(1).equals(row.get(key)); }

    static boolean due(Map<String, Object> row) {
        LocalDate anchor = Kst.date(row.get("FirstConfirmedOn"));
        Integer years = (Integer) row.get("RetentionYears");
        return anchor != null && years != null && Kst.isDue(anchor, years);
    }

    /** 만료되었거나 이미 비워진 건에는 본문과 파일을 다시 넣지 않는다. */
    static boolean contentLocked(Map<String, Object> row) {
        return row.get("PurgedAt") != null || row.get("BodiesClearedAt") != null || (due(row) && !flag(row, "Hold"));
    }

    private long log(Connection conn, String caseId, String action, String kind, String targetId, String summary) throws SQLException {
        return Chain.append(conn, caseId, me(), action, kind, targetId, summary);
    }

    // ───────────── 문서번호 ─────────────

    public static String formatDocumentNo(int year, String code, int seq) {
        return year + "-" + code + "-" + (seq > 99 ? Integer.toString(seq) : String.format("%02d", seq));
    }

    public String peekDocumentNo(int year, String category) {
        if (year < 1990 || year > 2100 || !isCategory(category)) return "";
        return db.read(conn -> {
            Map<String, Object> code = Db.row(conn, "SELECT Code FROM CategoryCodes WHERE Category=?", category);
            if (code == null) return "";
            int last = Db.intOf(conn, "SELECT IFNULL(MAX(LastSeq),0) FROM DocCounters WHERE Year=? AND Code=?", year, code.get("Code"));
            return formatDocumentNo(year, (String) code.get("Code"), last + 1);
        });
    }

    private static String allocateDocumentNo(Connection conn, int year, String category) throws SQLException {
        String code = (String) Db.row(conn, "SELECT Code FROM CategoryCodes WHERE Category=?", category).get("Code");
        Db.update(conn, "UPDATE CategoryCodes SET Used=1 WHERE Category=?", category);
        if (Db.update(conn, "UPDATE DocCounters SET LastSeq=LastSeq+1 WHERE Year=? AND Code=?", year, code) == 0)
            Db.update(conn, "INSERT INTO DocCounters(Year, Code, LastSeq) VALUES (?,?,1)", year, code);
        return formatDocumentNo(year, code, Db.intOf(conn, "SELECT LastSeq FROM DocCounters WHERE Year=? AND Code=?", year, code));
    }

    public List<Map<String, Object>> categoryCodes() { return db.rows("SELECT Category, Code, Used FROM CategoryCodes ORDER BY Code"); }

    public Op setCategoryCode(String category, String code) {
        if (!isCategory(category)) return Op.bad("없는 업무 구분입니다.");
        String next = code == null ? "" : code.strip();
        if (!next.matches("\\d{2}")) return Op.bad("구분코드는 숫자 두 자리입니다.");
        return db.run(conn -> {
            Map<String, Object> row = Db.row(conn, "SELECT Code, Used FROM CategoryCodes WHERE Category=?", category);
            if (next.equals(row.get("Code"))) return Op.ok("같은 코드입니다.");
            if (flag(row, "Used")) throw Op.abort("이미 번호를 발급한 구분코드는 바꿀 수 없습니다.");
            if (Db.intOf(conn, "SELECT COUNT(*) FROM CategoryCodes WHERE Code=?", next) > 0) throw Op.abort("이미 쓰는 구분코드입니다.");
            Db.update(conn, "UPDATE CategoryCodes SET Code=? WHERE Category=?", next, category);
            log(conn, null, "수정", "구분코드", category, category + " " + row.get("Code") + " → " + next);
            return Op.ok("구분코드를 저장했습니다.");
        });
    }

    // ───────────── 건 ─────────────

    public Op register(int year, String category, String title, String target, String shelf, String binder,
                       String summary, String body, Integer retention) {
        if (!chain.intact()) return Op.fail(503, FROZEN);
        String t = Txt.clean(title, 120, false), tg = Txt.clean(target, 120, false);
        String sh = Txt.clean(shelf, 80, false), bi = Txt.clean(binder, 80, false);
        String su = Txt.clean(summary, 160, false), bo = Txt.clean(body, 20000, true);
        String why = why(year, category, t, tg, null, sh, bi);
        if (why == null && su == null) why = "결과 한 줄은 160자까지 적을 수 있습니다.";
        if (why == null && bo == null) why = "결과 본문은 2만 자까지 적을 수 있습니다. 나머지는 첨부 파일로 붙이세요.";
        if (why == null && retention != null && (retention < 1 || retention > 100)) why = "보존연한은 1~100년으로 적으세요.";
        if (why != null) return Op.bad(why);
        return db.run(conn -> {
            String id = Txt.newId();
            String no = allocateDocumentNo(conn, year, category);
            Db.update(conn, """
                INSERT INTO Cases(CaseId, Year, Category, Title, Target, Owner, Shelf, BinderNo, DocumentNo, SummaryLine, Body,
                                  RetentionYears, Phase, Version, CreatedAt)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'초안',1,?)""",
                id, year, category, t, tg, me(), sh, bi, no, su, bo.isEmpty() ? null : bo, retention, Kst.stamp());
            log(conn, id, "등록", "건", id, "건 등록");
            return Op.ok("저장했습니다.", id).extra(no);
        });
    }

    /** 건 정보 입력이 왜 안 되는지 칸 이름으로 알려 준다. 괜찮으면 null. */
    private String why(int year, String category, String t, String tg, String ow, String sh, String bi) {
        if (year < 1990 || year > 2100) return "연도를 확인하세요(1990~2100).";
        if (!isCategory(category)) return "구분을 고르세요.";
        if (t == null) return "제목은 120자까지 적을 수 있습니다.";
        if (Txt.blank(t)) return "제목을 적으세요.";
        if (tg == null) return "대상은 120자까지 적을 수 있습니다.";
        if (Txt.blank(tg)) return "대상을 적으세요.";
        if (sh == null) return "서가는 80자까지 적을 수 있습니다.";
        if (bi == null) return "철번호는 80자까지 적을 수 있습니다.";
        return null;
    }

    public Op saveCase(String caseId, Integer version, int year, String category, String title, String target,
                       String owner, String shelf, String binder) {
        String t = Txt.clean(title, 120, false), tg = Txt.clean(target, 120, false), ow = Txt.clean(owner, 40, false);
        String sh = Txt.clean(shelf, 80, false), bi = Txt.clean(binder, 80, false);
        String why = why(year, category, t, tg, ow, sh, bi);
        if (why == null && ow == null) why = "담당은 40자까지 적을 수 있습니다.";
        if (why != null) return Op.bad(why);
        return withCase(caseId, version, (conn, row) -> {
            if (row.get("PurgedAt") != null) throw Op.abort(409, LOCKED);
            bump(conn, caseId, "Year=?, Category=?, Title=?, Target=?, Owner=?, Shelf=?, BinderNo=?",
                year, category, t, tg, ow.isEmpty() ? row.get("Owner") : ow, sh, bi);
            log(conn, caseId, "수정", "건", caseId, "건 목록 정보 수정");
            return Op.ok("저장했습니다.");
        });
    }

    public Op saveResult(String caseId, Integer version, String summary, String body) {
        String line = Txt.clean(summary, 160, false), text = Txt.clean(body, 20000, true);
        if (line == null) return Op.bad("결과 한 줄은 160자까지 적을 수 있습니다.");
        if (text == null) return Op.bad("결과 본문은 2만 자까지 적을 수 있습니다. 나머지는 첨부 파일로 붙이세요.");
        return withCase(caseId, version, (conn, row) -> {
            if (!"초안".equals(row.get("Phase"))) throw Op.abort("확정된 결과 본문은 확정 취소 뒤에만 고칠 수 있습니다.");
            if (contentLocked(row)) throw Op.abort(409, LOCKED);
            // 본문 글자를 고쳤으면 가져온 원문 모양은 더 맞지 않으므로 치운다.
            if (!text.equals(Txt.str(row.get("Body")))) Db.update(conn, "DELETE FROM CaseRich WHERE CaseId=?", caseId);
            bump(conn, caseId, "SummaryLine=?, Body=?", line, text.isEmpty() ? null : text);
            log(conn, caseId, "수정", "결과", caseId, "결과 저장");
            return Op.ok("저장했습니다.");
        });
    }

    public Op changeRetention(String caseId, Integer version, Integer years, String reason) {
        if (years == null || years < 1 || years > 100) return Op.bad("보존연한은 1년에서 100년 사이입니다. 법정 연한을 대신 정하지 않습니다.");
        String why = Txt.clean(reason, 240, false);
        return withCase(caseId, version, (conn, row) -> {
            if (row.get("PurgedAt") != null || row.get("BodiesClearedAt") != null)
                throw Op.abort(409, "이미 삭제된 자료는 연한을 바꿔도 복구되지 않습니다.");
            if (flag(row, "EverConfirmed") && Txt.blank(why)) throw Op.abort(REASON);
            if (years.equals(row.get("RetentionYears"))) return Op.ok("저장했습니다.");
            bump(conn, caseId, "RetentionYears=?", years);
            String summary = (row.get("RetentionYears") == null ? "없음" : row.get("RetentionYears") + "년") + " → " + years + "년";
            if (!Txt.blank(why)) summary += ". 사유: " + why;
            log(conn, caseId, "연한변경", "건", caseId, summary);
            return Op.ok("저장했습니다.");
        });
    }

    public Op confirm(String caseId, Integer version) {
        return withCase(caseId, version, (conn, row) -> {
            if (!"초안".equals(row.get("Phase"))) throw Op.abort("초안만 확정할 수 있습니다.");
            if (row.get("RetentionYears") == null) throw Op.abort("보존연한을 입력해야 확정할 수 있습니다.");
            if (Txt.blank(row.get("SummaryLine"))) throw Op.abort("결과 한 줄을 입력해야 확정할 수 있습니다.");
            if (contentLocked(row)) throw Op.abort(409, LOCKED);
            String today = Kst.today().toString();
            String first = row.get("FirstConfirmedOn") == null ? today : (String) row.get("FirstConfirmedOn");
            int actions = Db.intOf(conn, "SELECT COUNT(*) FROM Actions WHERE CaseId=?", caseId);
            bump(conn, caseId, "FirstConfirmedOn=?, ConfirmedOn=?, EverConfirmed=1, Phase=?, Confirmer=?",
                first, today, actions > 0 ? "조치중" : "확정", me());
            log(conn, caseId, "결과확정", "결과", caseId, flag(row, "EverConfirmed") ? "재확정. 기산일은 최초 확정일" : "결과 확정");
            return Op.ok("결과를 확정했습니다. 확정은 징계 확정이나 전자결재가 아닙니다.");
        });
    }

    public Op cancelConfirm(String caseId, Integer version, String reason) {
        String why = Txt.clean(reason, 240, false);
        if (Txt.blank(why)) return Op.bad(REASON);
        return withCase(caseId, version, (conn, row) -> {
            if ("초안".equals(row.get("Phase"))) throw Op.abort("확정된 건만 취소할 수 있습니다.");
            int deleted = Db.intOf(conn, "SELECT COUNT(*) FROM Attachments WHERE CaseId=? AND StorageState='Deleted'", caseId);
            boolean cleared = row.get("BodiesClearedAt") != null || deleted > 0;
            bump(conn, caseId, "ConfirmedOn=NULL, Phase='초안'");
            log(conn, caseId, "확정취소", "결과", caseId, (cleared ? "확정 취소. 이미 삭제된 자료는 복구되지 않음" : "확정 취소") + ". 사유: " + why);
            return Op.ok("확정을 취소했습니다. 기존 조치 기록은 유지되며 이미 지운 파일과 본문은 돌아오지 않습니다.");
        });
    }

    public Op close(String caseId, Integer version, boolean noActionConfirmed) {
        return withCase(caseId, version, (conn, row) -> {
            String phase = (String) row.get("Phase");
            if (!(phase.equals("확정") || phase.equals("조치중")) || row.get("PurgedAt") != null)
                throw Op.abort("확정 또는 조치중 건만 종결할 수 있습니다.");
            if (Db.intOf(conn, "SELECT COUNT(*) FROM Actions WHERE CaseId=? AND ActionState<>'이행완료'", caseId) > 0)
                throw Op.abort("이행 완료되지 않은 조치요구가 있어 종결할 수 없습니다.");
            if (Db.intOf(conn, "SELECT COUNT(*) FROM Actions WHERE CaseId=?", caseId) == 0 && !noActionConfirmed)
                throw Op.abort("조치요구가 없습니다. 종결하려면 조치요구 없음을 확인하세요.");
            bump(conn, caseId, "Phase='종결'");
            log(conn, caseId, "종결", "건", caseId, "종결. 자료는 보존연한까지 남음");
            return Op.ok("종결했습니다. 종결은 자료 삭제가 아닙니다.");
        });
    }

    // ───────────── 조치요구·이행 ─────────────

    public Op addAction(String caseId, Integer version, String title, String type, String dept,
                        String requestedOn, String dueOn, String content) {
        String t = Txt.clean(title, 120, false), d = Txt.clean(dept, 120, false), c = Txt.clean(content, 20000, true);
        if (Txt.blank(t) || Txt.blank(d) || !ACTION_TYPES.contains(type) || c == null) return Op.bad(BAD);
        if (Kst.date(requestedOn) == null) return Op.bad("요구일을 확인하세요.");
        if (Kst.date(dueOn) == null) return Op.bad("회신기한을 확인하세요.");
        return withCase(caseId, version, (conn, row) -> {
            if ("종결".equals(row.get("Phase")) || contentLocked(row)) throw Op.abort(409, LOCKED);
            int seq = Db.intOf(conn, "SELECT COALESCE(MAX(Sequence),0) FROM Actions WHERE CaseId=?", caseId) + 1;
            String actionId = Txt.newId();
            Db.update(conn, """
                INSERT INTO Actions(ActionId, CaseId, Sequence, Title, Type, TargetDept, RequestedOn, ReplyDueOn, Content, ActionState)
                VALUES (?,?,?,?,?,?,?,?,?,'요구중')""", actionId, caseId, seq, t, type, d, Kst.date(requestedOn).toString(), Kst.date(dueOn).toString(), c.isEmpty() ? null : c);
            bump(conn, caseId, "확정".equals(row.get("Phase")) ? "Phase='조치중'" : "");
            log(conn, caseId, "조치요구", "조치", actionId, "조치요구 " + seq + " 추가");
            return Op.ok("저장했습니다.").extra(actionId);
        });
    }

    public Op recordReply(String caseId, Integer version, String actionId, String reply) {
        return fulfil(caseId, version, actionId, reply, "회신", "회신수신", "회신 내용을 입력하세요.",
            "이행 완료된 조치에는 회신을 추가하지 않습니다.", "회신 기록. 이행 완료 아님", "회신을 기록했습니다. 회신 수신은 이행 완료가 아닙니다.");
    }

    public Op verifyAction(String caseId, Integer version, String actionId, String check) {
        return fulfil(caseId, version, actionId, check, "이행확인", "이행완료", "이행 확인 내용을 입력하세요.",
            "이미 이행 완료된 조치입니다.", "이행 확인", "이행을 확인했습니다. 종결과 자료 삭제는 별도입니다.");
    }

    private Op fulfil(String caseId, Integer version, String actionId, String body, String kind, String toState,
                      String emptyMsg, String doneMsg, String summary, String okMsg) {
        String text = Txt.clean(body, 20000, true);
        if (text == null) return Op.bad(BAD);
        if (text.isEmpty()) return Op.bad(emptyMsg);
        return withCase(caseId, version, (conn, row) -> {
            if (contentLocked(row) || "종결".equals(row.get("Phase"))) throw Op.abort(409, LOCKED);
            Map<String, Object> action = Db.row(conn, "SELECT * FROM Actions WHERE CaseId=? AND ActionId=?", caseId, actionId);
            if (action == null) throw Op.abort(404, "조치요구를 찾을 수 없음");
            if ("이행완료".equals(action.get("ActionState"))) throw Op.abort(doneMsg);
            Db.update(conn, "UPDATE Actions SET ActionState=? WHERE ActionId=?", toState, actionId);
            bump(conn, caseId, "");
            long hist = log(conn, caseId, kind, "조치", actionId, summary);
            boolean reply = kind.equals("회신");
            Db.update(conn, """
                INSERT INTO Fulfillments(FulfillmentId, ActionId, At, Actor, FromState, ToState, Kind, ReplyBody, CheckBody, HistorySeq)
                VALUES (?,?,?,?,?,?,?,?,?,?)""", Txt.newId(), actionId, Kst.stamp(), me(), action.get("ActionState"), toState, kind,
                reply ? text : null, reply ? null : text, hist);
            return Op.ok(okMsg);
        });
    }

    // ───────────── 자료 ─────────────

    /**
     * 파일을 건에 붙인다. 원문은 받은 그대로 files 폴더에 둔다.
     * pre가 있으면 이미 읽어 둔 내용을 쓰고, 없으면 뒤에서 읽는다.
     */
    public Op addFile(String caseId, Integer version, String kind, String displayName, String originalName,
                      InputStream content, TextReader.Result pre) {
        String name = Txt.clean(FileStore.nameWithExt(displayName, originalName), 120, false);
        if (Txt.blank(name) || !KINDS.contains(kind)) return Op.bad(BAD);
        if (!chain.intact()) return Op.fail(503, FROZEN);
        Map<String, Object> probe = Txt.isId(caseId) ? db.row("SELECT * FROM Cases WHERE CaseId=?", caseId) : null;
        if (probe == null) return Op.fail(404, "건을 찾을 수 없음");
        if (version != null && !version.equals(probe.get("Version"))) return Op.fail(409, STALE);
        if ("종결".equals(probe.get("Phase")) || contentLocked(probe)) return Op.fail(409, LOCKED);
        String attachmentId = Txt.newId();
        FileStore.Stored stored;
        try {
            stored = files.save(caseId, attachmentId, Txt.ext(name), content);
        } catch (IOException e) {
            return Op.fail(503, "자료를 저장하지 못했습니다.");
        }
        Op op = withCase(caseId, version, (conn, row) -> {
            if ("종결".equals(row.get("Phase")) || contentLocked(row)) throw Op.abort(409, LOCKED);
            Db.update(conn, """
                INSERT INTO Attachments(AttachmentId, CaseId, Kind, DisplayName, StoragePath, SizeBytes, Sha256, RegisteredAt, RegisteredBy, StorageState, ReadState)
                VALUES (?,?,?,?,?,?,?,?,?,'Present',?)""", attachmentId, caseId, kind, name, stored.key(), stored.size(), stored.sha256(),
                Kst.stamp(), me(), pre == null ? "읽는 중" : "안읽음");
            if (pre != null) storeRead(conn, attachmentId, pre);
            bump(conn, caseId, "");
            log(conn, caseId, "수정", "자료", attachmentId, "자료 추가");
            return Op.ok("저장했습니다.").extra(attachmentId);
        });
        if (!op.ok) files.delete(stored.key());
        else if (pre == null) queueRead(attachmentId);
        return op;
    }

    private void storeRead(Connection conn, String attachmentId, TextReader.Result r) throws SQLException {
        Db.update(conn, "UPDATE Attachments SET Encrypted=?, ReadState=?, ReadMethod=?, ReadNote=? WHERE AttachmentId=?",
            r.encrypted(), r.state(), r.method(), r.note(), attachmentId);
        Db.update(conn, "DELETE FROM AttachmentText WHERE AttachmentId=?", attachmentId);
        if (r.ok() && !r.text().isBlank() && settings.keepText())
            Db.update(conn, "INSERT INTO AttachmentText(AttachmentId, Body, Html) VALUES (?,?,?)", attachmentId, r.text(), r.html());
    }

    private void queueRead(String attachmentId) {
        lastRead = readQueue.submit(() -> {
            try {
                readNow(attachmentId);
            } catch (RuntimeException e) {
                db.tx(conn -> Db.update(conn, "UPDATE Attachments SET ReadState='실패', ReadNote=? WHERE AttachmentId=?", "읽는 중 오류가 났습니다.", attachmentId));
            }
        });
    }

    private void readNow(String attachmentId) {
        Map<String, Object> att = db.row("SELECT * FROM Attachments WHERE AttachmentId=? AND StorageState='Present'", attachmentId);
        if (att == null) return;
        Path path = files.resolve((String) att.get("StoragePath"));
        TextReader.Result r = reader.read(path, (String) att.get("DisplayName"));
        db.tx(conn -> {
            // 읽는 사이에 지워졌으면 내용을 남기지 않는다.
            if (Db.intOf(conn, "SELECT COUNT(*) FROM Attachments WHERE AttachmentId=? AND StorageState='Present'", attachmentId) == 1)
                storeRead(conn, attachmentId, r);
            return null;
        });
    }

    /** 시험과 수집 처리에서 읽기가 끝나기를 기다린다. */
    public void awaitReads() {
        Future<?> f = lastRead;
        if (f == null) return;
        try { f.get(180, TimeUnit.SECONDS); } catch (Exception ignored) { }
    }

    public Op reread(String caseId, String attachmentId) {
        Map<String, Object> att = db.row("SELECT * FROM Attachments WHERE CaseId=? AND AttachmentId=? AND StorageState='Present'", caseId, attachmentId);
        if (att == null) return Op.fail(404, "자료를 찾을 수 없음");
        db.tx(conn -> Db.update(conn, "UPDATE Attachments SET ReadState='읽는 중', ReadNote='' WHERE AttachmentId=?", attachmentId));
        queueRead(attachmentId);
        return Op.ok("내용을 다시 읽습니다. 암호화된 문서는 사무용 프로그램이 여는 동안 잠시 걸립니다.");
    }

    public String fileText(String caseId, String attachmentId) {
        Map<String, Object> row = db.row("""
            SELECT t.Body FROM AttachmentText t JOIN Attachments a ON a.AttachmentId=t.AttachmentId
            WHERE a.CaseId=? AND a.AttachmentId=?""", caseId, attachmentId);
        return row == null ? null : (String) row.get("Body");
    }

    /** 화면에 보여 줄 모양. 표·그림을 살린 것이 있으면 그것을, 없으면 글자에서 만든다. */
    public String fileHtml(String caseId, String attachmentId) {
        Map<String, Object> row = db.row("""
            SELECT t.Body, t.Html FROM AttachmentText t JOIN Attachments a ON a.AttachmentId=t.AttachmentId
            WHERE a.CaseId=? AND a.AttachmentId=?""", caseId, attachmentId);
        if (row == null) return null;
        return row.get("Html") != null ? (String) row.get("Html") : Rich.fromText((String) row.get("Body"));
    }

    /** 방금 넣은 조치요구·이행 기록의 ID. 편집기로 꾸민 모양을 붙일 때 쓴다. */
    public String lastActionId(String caseId) {
        Map<String, Object> r = db.row("SELECT ActionId FROM Actions WHERE CaseId=? ORDER BY Sequence DESC LIMIT 1", caseId);
        return r == null ? null : (String) r.get("ActionId");
    }

    public String lastEntryId(String actionId) {
        Map<String, Object> r = db.row("SELECT FulfillmentId FROM Fulfillments WHERE ActionId=? ORDER BY HistorySeq DESC, At DESC LIMIT 1", actionId);
        return r == null ? null : (String) r.get("FulfillmentId");
    }

    /** 가져온 원문 모양을 건의 결과 본문 옆에 둔다. */
    public void setBodyHtml(String caseId, String html) {
        db.tx(conn -> {
            Db.update(conn, "DELETE FROM CaseRich WHERE CaseId=?", caseId);
            if (!Txt.blank(html)) Db.update(conn, "INSERT INTO CaseRich(CaseId, Html) VALUES (?,?)", caseId, html);
            return null;
        });
    }

    public Map<String, Object> attachment(String caseId, String attachmentId) {
        return db.row("SELECT * FROM Attachments WHERE CaseId=? AND AttachmentId=?", caseId, attachmentId);
    }

    /** 읽은 내용을 초안의 결과 본문에 옮긴다. */
    public Op fillBodyFromFile(String caseId, Integer version, String attachmentId) {
        String text = fileText(caseId, attachmentId);
        if (Txt.blank(text)) return Op.bad("읽은 내용이 없습니다.");
        return withCase(caseId, version, (conn, row) -> {
            if (!"초안".equals(row.get("Phase"))) throw Op.abort("확정된 결과 본문은 확정 취소 뒤에만 고칠 수 있습니다.");
            if (contentLocked(row)) throw Op.abort(409, LOCKED);
            bump(conn, caseId, "Body=?", Txt.fit(text, 20000, true));
            Db.update(conn, "DELETE FROM CaseRich WHERE CaseId=?", caseId);
            Map<String, Object> rich = Db.row(conn, "SELECT Html FROM AttachmentText WHERE AttachmentId=?", attachmentId);
            if (rich != null && rich.get("Html") != null) Db.update(conn, "INSERT INTO CaseRich(CaseId, Html) VALUES (?,?)", caseId, rich.get("Html"));
            log(conn, caseId, "수정", "결과", caseId, "자료에서 읽은 내용으로 결과 본문 채움");
            return Op.ok("읽은 내용을 결과 본문에 넣었습니다. 내용을 확인하세요.");
        });
    }

    public Op verifyFileHash(String caseId, String attachmentId) {
        Map<String, Object> att = attachment(caseId, attachmentId);
        if (att == null || !"Present".equals(att.get("StorageState"))) return Op.fail(404, "자료를 찾을 수 없음");
        try {
            String now = FileStore.sha256(files.resolve((String) att.get("StoragePath")));
            return now.equals(att.get("Sha256")) ? Op.ok("등록할 때와 같은 파일입니다.") : Op.fail(409, "등록할 때와 내용이 다릅니다. 파일이 바뀌었습니다.");
        } catch (IOException e) {
            return Op.fail(409, "파일을 읽지 못했습니다. 위치를 확인하세요.");
        }
    }

    public Op removeDraftFile(String caseId, Integer version, String attachmentId) {
        return deleteFile(caseId, version, attachmentId, null);
    }

    public Op earlyDeleteFile(String caseId, Integer version, String attachmentId, String reason) {
        String why = Txt.clean(reason, 240, false);
        if (Txt.blank(why)) return Op.bad(REASON);
        return deleteFile(caseId, version, attachmentId, why);
    }

    /**
     * 자료 삭제 단추 하나로 지운다. 확정한 적 없는 초안 건이면 바로 빼고, 한 번이라도 확정된 건이면 사유를 받아 지운다.
     * 삭제 보류 중이거나 보존 만료로 정리된 건은 지우지 않는다. 지운 사실과 사유는 이력에 남는다.
     */
    public Op deleteFileAny(String caseId, Integer version, String attachmentId, String reason) {
        Map<String, Object> row = Txt.isId(caseId) ? db.row("SELECT EverConfirmed, Phase FROM Cases WHERE CaseId=?", caseId) : null;
        if (row == null) return Op.fail(404, "건을 찾을 수 없음");
        boolean draftOnly = !flag(row, "EverConfirmed") && "초안".equals(row.get("Phase"));
        if (draftOnly) {
            String why = Txt.clean(reason, 240, false);
            Op op = deleteFile(caseId, version, attachmentId, null);
            if (op.ok && !Txt.blank(why)) db.tx(conn -> { log(conn, caseId, "자료삭제", "자료", attachmentId, "삭제 사유: " + why); return null; });
            return op;
        }
        return earlyDeleteFile(caseId, version, attachmentId, reason);
    }

    private Op deleteFile(String caseId, Integer version, String attachmentId, String why) {
        String[] key = new String[1];
        Op op = withCase(caseId, version, (conn, row) -> {
            boolean ever = flag(row, "EverConfirmed");
            if (why == null && (ever || !"초안".equals(row.get("Phase"))))
                throw Op.abort(403, "한 번이라도 확정된 건의 파일은 사유를 적고 '파일 삭제'로 지웁니다.");
            if (why != null) {
                if (!ever) throw Op.abort("확정 전 파일은 첨부 빼기를 사용합니다.");
                if (flag(row, "Hold")) throw Op.abort(409, "삭제 보류 중이라 삭제하지 않습니다.");
                if (row.get("PurgedAt") != null || row.get("BodiesClearedAt") != null) throw Op.abort(409, LOCKED);
            }
            Map<String, Object> att = Db.row(conn, "SELECT * FROM Attachments WHERE CaseId=? AND AttachmentId=? AND StorageState='Present'", caseId, attachmentId);
            if (att == null) throw Op.abort(404, "자료를 찾을 수 없음");
            key[0] = (String) att.get("StoragePath");
            Db.update(conn, "DELETE FROM AttachmentText WHERE AttachmentId=?", attachmentId);
            Db.update(conn, "UPDATE Attachments SET StorageState='Deleted', DeletedAt=?, StoragePath=NULL, ReadState='삭제됨', ReadNote='' WHERE AttachmentId=?", Kst.stamp(), attachmentId);
            bump(conn, caseId, "");
            log(conn, caseId, "자료삭제", "자료", attachmentId, why == null ? "확정 전 첨부 빼기" : "연한 전 파일 삭제. 사유: " + why);
            return Op.ok("파일을 지웠습니다. 표시 이름과 삭제일은 남습니다.");
        });
        if (op.ok && key[0] != null && !files.delete(key[0]))
            return Op.ok("기록은 지웠지만 파일이 열려 있어 지우지 못했습니다. 프로그램을 닫은 뒤 files 폴더에서 지우세요.");
        return op;
    }

    /**
     * 고른 건을 통째로 지운다: 건, 조치요구·이행 기록, 자료 파일과 읽은 내용, 편집기 내용.
     * 지운 사실·제목·문서번호·사유는 이력에 남기고(이력 지문은 그대로 이어짐), 징계기록·수집함에서는 연결만 푼다.
     * 삭제 보류 중인 건은 지우지 않는다.
     */
    public Op deleteCases(List<String> ids, String reason) {
        if (!chain.intact()) return Op.fail(503, FROZEN);
        String why = Txt.clean(reason, 240, false);
        if (Txt.blank(why)) return Op.bad(REASON);
        if (ids == null || ids.isEmpty()) return Op.bad("지울 건을 고르세요.");
        List<String> paths = new java.util.ArrayList<>();
        int[] done = { 0 };
        List<String> held = new java.util.ArrayList<>();
        Op op = db.run(conn -> {
            for (String id : ids) {
                if (!Txt.isId(id)) continue;
                Map<String, Object> c = Db.row(conn, "SELECT Title, DocumentNo, Hold FROM Cases WHERE CaseId=?", id);
                if (c == null) continue;
                if (flag(c, "Hold")) { held.add(Txt.str(c.get("Title"))); continue; }
                for (Map<String, Object> a : Db.rows(conn, "SELECT StoragePath FROM Attachments WHERE CaseId=? AND StoragePath IS NOT NULL", id))
                    paths.add((String) a.get("StoragePath"));
                Db.update(conn, "DELETE FROM RichText WHERE Owner IN (SELECT 'entry:' || f.FulfillmentId FROM Fulfillments f JOIN Actions a ON a.ActionId=f.ActionId WHERE a.CaseId=?)", id);
                Db.update(conn, "DELETE FROM RichText WHERE Owner IN (SELECT 'action:' || ActionId FROM Actions WHERE CaseId=?)", id);
                Db.update(conn, "DELETE FROM Fulfillments WHERE ActionId IN (SELECT ActionId FROM Actions WHERE CaseId=?)", id);
                Db.update(conn, "DELETE FROM Actions WHERE CaseId=?", id);
                Db.update(conn, "DELETE FROM AttachmentText WHERE AttachmentId IN (SELECT AttachmentId FROM Attachments WHERE CaseId=?)", id);
                Db.update(conn, "DELETE FROM Attachments WHERE CaseId=?", id);
                Db.update(conn, "DELETE FROM CaseRich WHERE CaseId=?", id);
                Db.update(conn, "UPDATE Inbox SET AppliedCaseId=NULL WHERE AppliedCaseId=?", id);
                Db.update(conn, "UPDATE Discipline SET CaseId=NULL WHERE CaseId=?", id);
                log(conn, id, "건삭제", "건", id, "건 삭제: " + c.get("DocumentNo") + " " + c.get("Title") + ". 사유: " + why);
                Db.update(conn, "DELETE FROM Cases WHERE CaseId=?", id);
                done[0]++;
            }
            if (done[0] == 0 && held.isEmpty()) throw Op.abort(404, "지울 건을 찾지 못했습니다.");
            return Op.ok("");
        });
        if (!op.ok) return op;
        int failed = 0;
        for (String p : paths) if (!files.delete(p)) failed++;
        String msg = "건 " + done[0] + "개를 지웠습니다. 지운 사실과 사유는 이력에 남았습니다.";
        if (!held.isEmpty()) msg += " 삭제 보류 중이라 지우지 않은 건: " + String.join(", ", held) + ".";
        if (failed > 0) msg += " 열려 있어 지우지 못한 파일 " + failed + "개는 프로그램을 닫은 뒤 files 폴더에서 지우세요.";
        return done[0] == 0 ? Op.bad(msg) : Op.ok(msg);
    }

    public Op setHold(String caseId, Integer version, String reason) {
        String why = Txt.clean(reason, 240, false);
        if (Txt.blank(why)) return Op.bad(REASON);
        return withCase(caseId, version, (conn, row) -> {
            if (flag(row, "Hold")) return Op.ok("이미 삭제 보류 중입니다.");
            bump(conn, caseId, "Hold=1, HoldReason=?, HoldSetAt=?", why, Kst.stamp());
            log(conn, caseId, "보류", "건", caseId, "삭제 보류 설정. 사유: " + why);
            return Op.ok("삭제 보류를 설정했습니다. 이미 삭제된 자료는 복구되지 않습니다.");
        });
    }

    public Op releaseHold(String caseId, Integer version, String reason) {
        String why = Txt.clean(reason, 240, false);
        if (Txt.blank(why)) return Op.bad(REASON);
        return withCase(caseId, version, (conn, row) -> {
            if (!flag(row, "Hold")) throw Op.abort("보류 중인 건이 아닙니다.");
            bump(conn, caseId, "Hold=0, HoldReleasedAt=?", Kst.stamp());
            log(conn, caseId, "보류해제", "건", caseId, "삭제 보류 해제. 사유: " + why);
            return Op.ok("삭제 보류를 해제했습니다. 유예기간은 없으며, 이미 만료된 건은 다음 보존 삭제 대상입니다.");
        });
    }

    // ───────────── 읽기 ─────────────

    private void decorate(Map<String, Object> c) {
        boolean purged = c.get("PurgedAt") != null;
        c.put("DisplayState", purged ? "자료삭제됨" : c.get("Phase"));
        LocalDate anchor = Kst.date(c.get("FirstConfirmedOn"));
        Integer years = (Integer) c.get("RetentionYears");
        boolean isDue = false, soon = false;
        c.put("ExpiryDate", null);
        c.put("DaysLeft", null);
        if (anchor != null && years != null) {
            LocalDate exp = Kst.expiry(anchor, years);
            long left = Kst.daysUntil(anchor, years);
            isDue = Kst.isDue(anchor, years);
            soon = !isDue && !purged && left <= 30;
            c.put("ExpiryDate", exp.toString());
            c.put("DaysLeft", left);
        }
        c.put("Expired", isDue);
        c.put("DeletionDelayed", isDue && !purged && !flag(c, "Hold"));
        c.put("DueSoon", soon);
    }

    public Map<String, Object> get(String caseId) {
        if (!Txt.isId(caseId)) return null;
        return db.read(conn -> {
            Map<String, Object> c = Db.row(conn, "SELECT * FROM Cases WHERE CaseId=?", caseId);
            if (c == null) return null;
            decorate(c);
            LocalDate today = Kst.today();
            List<Map<String, Object>> actions = Db.rows(conn, "SELECT * FROM Actions WHERE CaseId=? ORDER BY Sequence", caseId);
            Map<String, Integer> seqOf = new LinkedHashMap<>();
            int done = 0;
            for (Map<String, Object> a : actions) {
                seqOf.put((String) a.get("ActionId"), (Integer) a.get("Sequence"));
                boolean finished = "이행완료".equals(a.get("ActionState"));
                if (finished) done++;
                LocalDate dueOn = Kst.date(a.get("ReplyDueOn"));
                a.put("Overdue", !finished && dueOn != null && dueOn.isBefore(today));
                List<Map<String, Object>> entries = Db.rows(conn, "SELECT * FROM Fulfillments WHERE ActionId=? ORDER BY At", a.get("ActionId"));
                for (Map<String, Object> e : entries) {
                    e.put("AtDisplay", Kst.display((String) e.get("At")));
                    Map<String, Object> er = Db.row(conn, "SELECT Html FROM RichText WHERE Owner=?", "entry:" + e.get("FulfillmentId"));
                    String eh = er == null ? null : (String) er.get("Html");
                    e.put("ReplyView", eh != null && e.get("ReplyBody") != null ? eh : Rich.fromText((String) e.get("ReplyBody")));
                    e.put("CheckView", eh != null && e.get("CheckBody") != null ? eh : Rich.fromText((String) e.get("CheckBody")));
                }
                a.put("Entries", entries);
                Map<String, Object> ar = Db.row(conn, "SELECT Html FROM RichText WHERE Owner=?", "action:" + a.get("ActionId"));
                a.put("ContentView", ar != null && a.get("Content") != null ? ar.get("Html") : Rich.fromText((String) a.get("Content")));
            }
            Map<String, Object> rich = Db.row(conn, "SELECT Html FROM CaseRich WHERE CaseId=?", caseId);
            c.put("HasRichBody", rich != null);
            c.put("BodyView", rich != null ? rich.get("Html") : Rich.fromText((String) c.get("Body")));
            c.put("Actions", actions);
            c.put("ActionCount", actions.size());
            c.put("DoneActions", done);
            List<Map<String, Object>> fileRows = Db.rows(conn, """
                SELECT a.*, (SELECT LENGTH(t.Body) FROM AttachmentText t WHERE t.AttachmentId=a.AttachmentId) AS TextLength
                FROM Attachments a WHERE a.CaseId=? ORDER BY a.RegisteredAt""", caseId);
            int deleted = 0;
            for (Map<String, Object> f : fileRows) {
                decorateFile(f);
                if ("Deleted".equals(f.get("StorageState"))) deleted++;
            }
            c.put("Files", fileRows);
            List<Map<String, Object>> history = Db.rows(conn, "SELECT Seq, At, Actor, Action, TargetKind, TargetId, Summary FROM History WHERE CaseId=? ORDER BY Seq", caseId);
            for (Map<String, Object> h : history) {
                h.put("AtDisplay", Kst.display((String) h.get("At")));
                h.put("ActionSequence", "조치".equals(h.get("TargetKind")) ? seqOf.get(h.get("TargetId")) : null);
            }
            c.put("History", history);
            c.put("PurgeNote", c.get("PurgedAt") != null ? "보존 만료로 삭제 " + Kst.display((String) c.get("PurgedAt"))
                : c.get("BodiesClearedAt") != null ? "본문 삭제, 파일 삭제 미완료" : deleted > 0 ? "파일 " + deleted + "개 삭제" : "");

            boolean purged = c.get("PurgedAt") != null, locked = contentLocked(c), ever = flag(c, "EverConfirmed");
            String phase = (String) c.get("Phase");
            Map<String, Object> caps = new LinkedHashMap<>();
            caps.put("EditList", !purged);
            caps.put("EditResult", phase.equals("초안") && !locked);
            caps.put("SetRetention", !purged && c.get("BodiesClearedAt") == null);
            caps.put("RetentionNeedsReason", ever);
            caps.put("Confirm", phase.equals("초안") && !locked);
            caps.put("Cancel", !phase.equals("초안") && !purged);
            caps.put("Close", (phase.equals("확정") || phase.equals("조치중")) && !purged);
            boolean open = !phase.equals("종결") && !locked;
            caps.put("AddAction", open);
            caps.put("Reply", open);
            caps.put("AddFile", open);
            caps.put("RemoveDraftFile", !ever && phase.equals("초안"));
            caps.put("EarlyDelete", ever && !flag(c, "Hold") && !purged && c.get("BodiesClearedAt") == null);
            caps.put("DeleteFile", (!ever && phase.equals("초안")) || (ever && !flag(c, "Hold") && !purged && c.get("BodiesClearedAt") == null));
            caps.put("DeleteNeedsReason", ever);
            caps.put("DeleteBlocked", flag(c, "Hold") ? "삭제 보류 중인 건이라 지울 수 없습니다. 보류를 먼저 해제하세요."
                : purged || c.get("BodiesClearedAt") != null ? "보존 만료로 정리된 건입니다." : "");
            c.put("Caps", caps);
            return c;
        });
    }

    private void decorateFile(Map<String, Object> f) {
        boolean present = "Present".equals(f.get("StorageState"));
        String name = (String) f.get("DisplayName");
        String open = !present ? "none" : flag(f, "Encrypted") ? "download" : FileStore.openKind(name);
        f.put("OpenKind", open);
        String ext = Txt.ext(name);
        String tile = ext.equals(".pdf") ? "pdf" : open.equals("image") ? "img" : open.equals("text") ? "txt"
            : ext.startsWith(".doc") || ext.startsWith(".hw") ? "doc" : ext.startsWith(".xls") ? "xls" : "file";
        f.put("Tile", tile);
        f.put("TileLabel", ext.startsWith(".hw") ? "HWP" : tile.equals("img") ? "IMG" : tile.equals("file") ? "FILE" : tile.toUpperCase());
        long size = ((Number) f.get("SizeBytes")).longValue();
        f.put("SizeText", size < 1024 ? size + " B" : size < 1024 * 1024 ? (size / 1024) + " KB" : String.format("%.1f MB", size / 1048576.0));
        f.put("RegisteredAtDisplay", Kst.display((String) f.get("RegisteredAt")));
        f.put("DeletedAtDisplay", Kst.display((String) f.get("DeletedAt")));
        Path path = present ? files.resolve((String) f.get("StoragePath")) : null;
        f.put("Location", path == null ? null : path.toString());
        if (present && path != null && !Files.isRegularFile(path)) f.put("LocationNote", "파일이 제자리에 없습니다");
        f.put("HasText", f.get("TextLength") != null);
    }

    public Map<String, Object> list(Integer year, String category, String status, boolean overdueOnly, boolean soonOnly,
                                    String text, String sort, String dir, int page, int pageSize) {
        StringBuilder sql = new StringBuilder("""
            SELECT c.*,
              (SELECT MIN(a.ReplyDueOn) FROM Actions a WHERE a.CaseId=c.CaseId AND a.ActionState<>'이행완료') AS NextDue,
              (SELECT COUNT(*) FROM Actions a WHERE a.CaseId=c.CaseId AND a.ActionState<>'이행완료') AS OpenActions
            FROM Cases c WHERE 1=1""");
        List<Object> args = new ArrayList<>();
        if (year != null) { sql.append(" AND c.Year=?"); args.add(year); }
        if (!Txt.blank(category) && isCategory(category)) { sql.append(" AND c.Category=?"); args.add(category); }
        if ("자료삭제됨".equals(status)) sql.append(" AND c.PurgedAt IS NOT NULL");
        else if (java.util.Arrays.asList("초안", "확정", "조치중", "종결").contains(status)) { sql.append(" AND c.PurgedAt IS NULL AND c.Phase=?"); args.add(status); }
        if (!Txt.blank(text)) {
            sql.append("""
                \s AND (c.Title LIKE ? ESCAPE '\\' OR c.Target LIKE ? ESCAPE '\\' OR c.DocumentNo LIKE ? ESCAPE '\\'
                   OR c.BinderNo LIKE ? ESCAPE '\\' OR c.SummaryLine LIKE ? ESCAPE '\\' OR c.Body LIKE ? ESCAPE '\\'
                   OR EXISTS (SELECT 1 FROM Attachments f LEFT JOIN AttachmentText t ON t.AttachmentId=f.AttachmentId
                              WHERE f.CaseId=c.CaseId AND (f.DisplayName LIKE ? ESCAPE '\\' OR t.Body LIKE ? ESCAPE '\\')))""");
            String q = Txt.like(text.strip());
            for (int i = 0; i < 8; i++) args.add(q);
        }
        List<Map<String, Object>> items = db.rows(sql.toString(), args.toArray());
        LocalDate today = Kst.today();
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> c : items) {
            decorate(c);
            LocalDate next = Kst.date(c.get("NextDue"));
            boolean overdue = next != null && next.isBefore(today);
            c.put("Overdue", overdue);
            c.remove("Body");
            if (overdueOnly && !overdue) continue;
            if (soonOnly && !(Boolean.TRUE.equals(c.get("DueSoon")) || Boolean.TRUE.equals(c.get("DeletionDelayed")))) continue;
            kept.add(c);
        }
        Comparator<Map<String, Object>> order = switch (sort == null ? "" : sort) {
            case "title" -> Comparator.comparing(m -> Txt.str(m.get("Title")));
            case "due" -> Comparator.comparing(m -> m.get("NextDue") == null ? "9999" : Txt.str(m.get("NextDue")));
            case "expiry" -> Comparator.comparing(m -> m.get("ExpiryDate") == null ? "9999" : Txt.str(m.get("ExpiryDate")));
            default -> Comparator.<Map<String, Object>, String>comparing(m -> m.get("Year") + "|" + Txt.str(m.get("DocumentNo")));
        };
        boolean desc = dir == null ? !java.util.Arrays.asList("title", "due", "expiry").contains(sort) : "desc".equals(dir);
        kept.sort(desc ? order.reversed() : order);
        int size = pageSize == 25 || pageSize == 100 ? pageSize : 50;
        int pages = Math.max(1, (kept.size() + size - 1) / size);
        int p = Math.min(Math.max(1, page), pages);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("All", kept);
        out.put("Items", kept.subList((p - 1) * size, Math.min(kept.size(), p * size)));
        out.put("Total", kept.size());
        out.put("Page", p);
        out.put("Pages", pages);
        out.put("PageSize", size);
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> all() {
        return (List<Map<String, Object>>) list(null, null, null, false, false, null, "year", "desc", 1, 100).get("All");
    }

    public Map<String, Object> todos() {
        LocalDate today = Kst.today();
        List<Map<String, Object>> overdue = db.rows("""
            SELECT c.CaseId, c.Title AS CaseTitle, c.Target, a.ActionId, a.Sequence, a.Title, a.ReplyDueOn, a.ActionState
            FROM Actions a JOIN Cases c ON c.CaseId=a.CaseId
            WHERE a.ActionState<>'이행완료' AND c.PurgedAt IS NULL ORDER BY a.ReplyDueOn, c.CaseId, a.Sequence""");
        overdue.removeIf(r -> {
            LocalDate d = Kst.date(r.get("ReplyDueOn"));
            if (d == null || !d.isBefore(today)) return true;
            r.put("DaysOver", java.time.temporal.ChronoUnit.DAYS.between(d, today));
            return false;
        });
        List<Map<String, Object>> upcoming = db.rows("""
            SELECT c.CaseId, c.Title AS CaseTitle, c.Target, a.ActionId, a.Sequence, a.Title, a.ReplyDueOn, a.ActionState
            FROM Actions a JOIN Cases c ON c.CaseId=a.CaseId
            WHERE a.ActionState<>'이행완료' AND c.PurgedAt IS NULL AND a.ReplyDueOn>=? AND a.ReplyDueOn<=?
            ORDER BY a.ReplyDueOn, c.CaseId, a.Sequence""", today.toString(), today.plusDays(7).toString());
        for (Map<String, Object> r : upcoming)
            r.put("DaysLeft", java.time.temporal.ChronoUnit.DAYS.between(today, Kst.date(r.get("ReplyDueOn"))));
        List<Map<String, Object>> drafts = new ArrayList<>(), expiring = new ArrayList<>();
        for (Map<String, Object> c : all()) {
            if ("초안".equals(c.get("DisplayState"))) {
                c.put("Ready", c.get("RetentionYears") != null && !Txt.blank(c.get("SummaryLine")));
                drafts.add(c);
            }
            if (Boolean.TRUE.equals(c.get("DueSoon")) || Boolean.TRUE.equals(c.get("DeletionDelayed"))
                || (Boolean.TRUE.equals(c.get("Expired")) && c.get("PurgedAt") == null)) expiring.add(c);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("Overdue", overdue);
        out.put("Upcoming", upcoming);
        out.put("Drafts", drafts);
        out.put("Expiring", expiring);
        return out;
    }

    public List<Map<String, Object>> years() { return db.rows("SELECT DISTINCT Year FROM Cases ORDER BY Year DESC"); }

    public List<Map<String, Object>> caseRefs() {
        return db.rows("SELECT CaseId, DocumentNo, Title, Year, Category, Phase, PurgedAt FROM Cases ORDER BY Year DESC, DocumentNo DESC");
    }

    public List<Map<String, Object>> searchFiles(String name, String kind, String caseTitle, String body) {
        StringBuilder sql = new StringBuilder("""
            SELECT a.*, c.Title AS CaseTitle, c.DocumentNo, LENGTH(t.Body) AS TextLength, t.Body AS FullText,
              c.EverConfirmed AS CaseEver, c.Phase AS CasePhase, c.Hold AS CaseHold,
              CASE WHEN c.PurgedAt IS NOT NULL OR c.BodiesClearedAt IS NOT NULL THEN 1 ELSE 0 END AS CaseCleared
            FROM Attachments a JOIN Cases c ON c.CaseId=a.CaseId
            LEFT JOIN AttachmentText t ON t.AttachmentId=a.AttachmentId
            WHERE a.StorageState='Present'""");
        List<Object> args = new ArrayList<>();
        if (!Txt.blank(name)) { sql.append(" AND a.DisplayName LIKE ? ESCAPE '\\'"); args.add(Txt.like(name.strip())); }
        if (KINDS.contains(kind)) { sql.append(" AND a.Kind=?"); args.add(kind); }
        if (!Txt.blank(caseTitle)) { sql.append(" AND (c.Title LIKE ? ESCAPE '\\' OR c.DocumentNo LIKE ? ESCAPE '\\')"); args.add(Txt.like(caseTitle.strip())); args.add(Txt.like(caseTitle.strip())); }
        if (!Txt.blank(body)) { sql.append(" AND t.Body LIKE ? ESCAPE '\\'"); args.add(Txt.like(body.strip())); }
        sql.append(" ORDER BY a.RegisteredAt DESC LIMIT 300");
        List<Map<String, Object>> rows = db.rows(sql.toString(), args.toArray());
        for (Map<String, Object> f : rows) {
            decorateFile(f);
            String full = (String) f.remove("FullText");
            f.put("Snippet", null);
            if (!Txt.blank(body) && full != null) {
                int at = full.toLowerCase().indexOf(body.strip().toLowerCase());
                if (at >= 0) {
                    int from = Math.max(0, at - 40), to = Math.min(full.length(), at + body.strip().length() + 80);
                    f.put("Snippet", (from > 0 ? "…" : "") + full.substring(from, to).replaceAll("\\s+", " ") + (to < full.length() ? "…" : ""));
                }
            }
        }
        return rows;
    }

    public String exportCsv(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder("﻿연도,구분,문서번호,제목,대상,담당,상태,다음 회신기한,보존연한,만료 예정,보류,서가,철번호,결과 한 줄\r\n");
        for (Map<String, Object> c : rows) {
            Object[] cols = { c.get("Year"), c.get("Category"), c.get("DocumentNo"), c.get("Title"), c.get("Target"), c.get("Owner"),
                c.get("DisplayState"), c.get("NextDue"), c.get("RetentionYears"), c.get("ExpiryDate"), flag(c, "Hold") ? "삭제 보류" : "",
                c.get("Shelf"), c.get("BinderNo"), c.get("SummaryLine") };
            for (int i = 0; i < cols.length; i++) sb.append(i == 0 ? "" : ",").append(Txt.csv(cols[i]));
            sb.append("\r\n");
        }
        db.tx(conn -> log(conn, null, "내보내기", "건", null, "건 목록 CSV " + rows.size() + "건"));
        return sb.toString();
    }
}
