package local.audit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import local.audit.extract.DocAnalyzer;
import local.audit.extract.TextReader;

/**
 * 수집함. 정해 둔 폴더에 파일이 생기면 알아서 가져와 내용을 읽고, 읽은 내용에 맞춰 등록한다.
 * 자동으로 만드는 것은 모두 '초안'이다. 확정은 사람이 한다.
 */
@Service
public class InboxService {
    private static final Logger log = LoggerFactory.getLogger(InboxService.class);
    private static final Set<String> SKIP_EXT = Set.of(".tmp", ".crdownload", ".part", ".partial", ".download", ".lnk", ".ini", ".db", ".exe", ".dll", ".bat", ".cmd", ".ps1");
    private static final long MAX_BYTES = 200L * 1024 * 1024;
    public static final String DONE_DIR = "처리완료";

    private final Db db;
    private final Paths2 paths;
    private final Settings settings;
    private final TextReader reader;
    private final CaseService cases;
    private final DisciplineService discipline;
    private final ObjectMapper json = new ObjectMapper();
    private final ReentrantLock scanLock = new ReentrantLock();
    private final Map<String, String> seen = new ConcurrentHashMap<>();

    public InboxService(Db db, Paths2 paths, Settings settings, TextReader reader, CaseService cases, DisciplineService discipline) {
        this.db = db;
        this.paths = paths;
        this.settings = settings;
        this.reader = reader;
        this.cases = cases;
        this.discipline = discipline;
    }

    public Path ownInbox() { return paths.inbox; }

    public List<Path> folders() {
        List<Path> list = new ArrayList<>();
        list.add(paths.inbox);
        for (String f : settings.watchFolders()) {
            try {
                Path p = Path.of(f).toAbsolutePath().normalize();
                if (!list.contains(p)) list.add(p);
            } catch (RuntimeException ignored) { }
        }
        return list;
    }

    /** 설정에서 켠 경우에만 스스로 살펴본다. 기본은 '지금 수집'을 누를 때만 가져온다. */
    @Scheduled(initialDelay = 8_000, fixedDelay = 15_000)
    public void tick() {
        try {
            if (settings.autoScan()) scan(false);
        } catch (RuntimeException e) {
            log.warn("수집 실패: {}", e.toString());
        }
    }

    /** 폴더를 살펴 새 파일을 가져온다. force면 방금 생긴 파일도 기다리지 않고 가져온다. */
    public Op scan(boolean force) {
        if (!scanLock.tryLock()) return Op.ok("이미 수집하고 있습니다.");
        int found = 0, applied = 0;
        try {
            for (Path folder : folders()) {
                if (!Files.isDirectory(folder)) continue;
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
                    for (Path file : stream) {
                        try {
                            if (!candidate(file)) continue;
                            long size = Files.size(file), modified = Files.getLastModifiedTime(file).toMillis();
                            String mark = size + ":" + modified;
                            String key = file.toString();
                            boolean stable = force || mark.equals(seen.put(key, mark)) || System.currentTimeMillis() - modified > 20_000;
                            if (!stable) continue;
                            if (db.row("SELECT 1 FROM Inbox WHERE SourcePath=? AND SizeBytes=? AND ModifiedAt=?", key, size, modified) != null) continue;
                            String id = ingest(file, size, modified);
                            found++;
                            if (id != null && autoApply(id)) applied++;
                        } catch (IOException | RuntimeException e) {
                            log.info("수집 건너뜀 {}: {}", file.getFileName(), e.toString());
                        }
                    }
                } catch (IOException e) {
                    log.info("폴더를 읽지 못함 {}: {}", folder, e.toString());
                }
            }
            if (force) applied += retryUnread();
        } finally {
            scanLock.unlock();
        }
        return Op.ok(found == 0 && applied == 0 ? "새로 들어온 파일이 없습니다." : "새 파일 " + found + "개 수집, " + applied + "개 자동 등록");
    }

    private static boolean candidate(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.isHidden(file)) return false;
        String name = file.getFileName().toString();
        if (name.startsWith("~$") || name.startsWith(".") || name.startsWith("~")) return false;
        if (SKIP_EXT.contains(Txt.ext(name))) return false;
        long size = Files.size(file);
        return size > 0 && size <= MAX_BYTES;
    }

    private String ingest(Path file, long size, long modified) throws IOException {
        String name = file.getFileName().toString();
        String sha = FileStore.sha256(file);
        String id = Txt.newId();
        Map<String, Object> dupInbox = db.row("SELECT InboxId, SourcePath, State FROM Inbox WHERE Sha256=? AND State IN ('대기','실패','등록됨') ORDER BY State='등록됨' DESC LIMIT 1", sha);
        if (dupInbox != null && !"등록됨".equals(dupInbox.get("State")) && !Files.isRegularFile(Path.of((String) dupInbox.get("SourcePath")))) {
            // 기다리던 항목의 파일이 자리를 옮겨 다시 들어온 경우: 같은 항목으로 잇는다.
            db.tx(conn -> Db.update(conn, "UPDATE Inbox SET SourcePath=?, FileName=?, ModifiedAt=?, State='대기', AppliedNote='' WHERE InboxId=?",
                file.toString(), name, modified, dupInbox.get("InboxId")));
            return (String) dupInbox.get("InboxId");
        }
        Map<String, Object> dupFile = db.row("SELECT c.Title FROM Attachments a JOIN Cases c ON c.CaseId=a.CaseId WHERE a.Sha256=? AND a.StorageState='Present' LIMIT 1", sha);
        if (dupInbox != null || dupFile != null) {
            String note = dupFile != null ? "같은 파일이 이미 '" + dupFile.get("Title") + "' 건에 있습니다." : "같은 파일이 이미 수집함에 있습니다.";
            Path now = settle(file);
            db.tx(conn -> Db.update(conn, """
                INSERT INTO Inbox(InboxId, SourcePath, FileName, SizeBytes, ModifiedAt, Sha256, DetectedAt, State, AppliedNote)
                VALUES (?,?,?,?,?,?,?,'제외',?)""", id, now.toString(), name, size, modifiedOf(now, modified), sha, Kst.stamp(), note));
            return null;
        }
        TextReader.Result r = reader.read(file, name);
        Map<String, Object> suggestion = suggest(name, r.text());
        db.tx(conn -> Db.update(conn, """
            INSERT INTO Inbox(InboxId, SourcePath, FileName, SizeBytes, ModifiedAt, Sha256, DetectedAt, State, Encrypted, ReadState, ReadMethod, ReadNote, Body, Suggestion)
            VALUES (?,?,?,?,?,?,?,'대기',?,?,?,?,?,?)""", id, file.toString(), name, size, modified, sha, Kst.stamp(),
            r.encrypted(), r.state(), r.method(), r.note(), r.text().isBlank() ? null : r.text(), toJson(suggestion)));
        storeRich(id, r.html());
        return id;
    }

    private static long modifiedOf(Path file, long fallback) {
        try { return Files.getLastModifiedTime(file).toMillis(); } catch (IOException e) { return fallback; }
    }

    /** 암호화되어 못 읽은 문서는 '지금 수집'을 누를 때 한 번 더 읽어 본다. 스스로 되풀이하지 않는다. */
    private int retryUnread() {
        int applied = 0;
        for (Map<String, Object> row : db.rows("SELECT InboxId FROM Inbox WHERE State='대기' AND ReadState='암호문'")) {
            String id = (String) row.get("InboxId");
            reread(id);
            if (autoApply(id)) applied++;
        }
        return applied;
    }

    private void storeRich(String id, String html) {
        db.tx(conn -> {
            Db.update(conn, "DELETE FROM InboxRich WHERE InboxId=?", id);
            if (!Txt.blank(html)) Db.update(conn, "INSERT INTO InboxRich(InboxId, Html) VALUES (?,?)", id, html);
            return null;
        });
    }

    /** 화면에 보여 줄 모양. */
    public String html(String id) {
        Map<String, Object> rich = db.row("SELECT Html FROM InboxRich WHERE InboxId=?", id);
        if (rich != null) return (String) rich.get("Html");
        String body = text(id);
        return body == null ? null : local.audit.extract.Rich.fromText(body);
    }

    public Op reread(String id) {
        Map<String, Object> row = db.row("SELECT * FROM Inbox WHERE InboxId=? AND State='대기'", id);
        if (row == null) return Op.fail(404, "수집 항목을 찾을 수 없음");
        Path file = Path.of((String) row.get("SourcePath"));
        if (!Files.isRegularFile(file)) {
            db.tx(conn -> Db.update(conn, "UPDATE Inbox SET State='실패', AppliedNote='원래 위치에 파일이 없습니다.' WHERE InboxId=?", id));
            return Op.fail(404, "원래 위치에 파일이 없습니다.");
        }
        TextReader.Result r = reader.read(file, (String) row.get("FileName"));
        Map<String, Object> suggestion = suggest((String) row.get("FileName"), r.text());
        db.tx(conn -> Db.update(conn, "UPDATE Inbox SET Encrypted=?, ReadState=?, ReadMethod=?, ReadNote=?, Body=?, Suggestion=? WHERE InboxId=?",
            r.encrypted(), r.state(), r.method(), r.note(), r.text().isBlank() ? null : r.text(), toJson(suggestion), id));
        storeRich(id, r.html());
        return r.ok() ? Op.ok("내용을 읽었습니다.") : Op.fail(409, Txt.blank(r.note()) ? "내용을 읽지 못했습니다." : r.note());
    }

    // ───────────── 제안 ─────────────

    private Map<String, Object> suggest(String name, String text) {
        Map<String, Object> s = DocAnalyzer.analyze(name, text);
        String caseNo = Txt.str(s.get("caseNo"));
        Map<String, Object> match = caseNo.isEmpty() ? null : db.row("SELECT CaseId, Title FROM Cases WHERE DocumentNo=?", caseNo);
        String why = "감사번호 " + caseNo;
        if (match == null) {
            String hay = (Txt.stem(name) + "\n" + (text.length() > 3000 ? text.substring(0, 3000) : text)).replaceAll("\\s+", "");
            List<Map<String, Object>> hits = new ArrayList<>();
            for (Map<String, Object> c : db.rows("SELECT CaseId, Title FROM Cases WHERE PurgedAt IS NULL AND Phase<>'종결'")) {
                String t = Txt.str(c.get("Title")).replaceAll("\\s+", "");
                if (t.length() >= 6 && hay.contains(t)) hits.add(c);
            }
            if (hits.size() == 1) { match = hits.get(0); why = "건 제목이 문서에 나옴"; }
        }
        s.put("matchCaseId", match == null ? "" : match.get("CaseId"));
        s.put("matchTitle", match == null ? "" : match.get("Title"));
        s.put("matchWhy", match == null ? "" : why);
        return s;
    }

    private String toJson(Map<String, Object> map) {
        try { return json.writeValueAsString(map); } catch (Exception e) { return "{}"; }
    }

    private Map<String, Object> fromJson(Object text) {
        try {
            return Txt.blank(text) ? new LinkedHashMap<>() : json.readValue(text.toString(), new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object o) { return o instanceof List<?> l ? (List<Map<String, Object>>) l : new ArrayList<>(); }

    public List<Map<String, Object>> list(String state) {
        String where = "대기".equals(state) ? "State IN ('대기','실패')" : "State IN ('등록됨','제외')";
        List<Map<String, Object>> rows = db.rows("SELECT * FROM Inbox WHERE " + where + " ORDER BY DetectedAt DESC LIMIT 200");
        for (Map<String, Object> r : rows) {
            Map<String, Object> s = fromJson(r.remove("Suggestion"));
            r.put("S", s);
            r.put("ActionCount", listOf(s.get("actions")).size());
            r.put("DisciplineCount", listOf(s.get("discipline")).size());
            String body = (String) r.remove("Body");
            r.put("HasText", !Txt.blank(body));
            r.put("Preview", body == null ? "" : body.length() > 600 ? body.substring(0, 600) + "…" : body);
            r.put("DetectedDisplay", Kst.display((String) r.get("DetectedAt")));
            long size = ((Number) r.get("SizeBytes")).longValue();
            r.put("SizeText", size < 1024 ? size + " B" : size < 1048576 ? (size / 1024) + " KB" : String.format("%.1f MB", size / 1048576.0));
        }
        return rows;
    }

    public int pendingCount() {
        return db.read(conn -> Db.intOf(conn, "SELECT COUNT(*) FROM Inbox WHERE State='대기'"));
    }

    public String text(String id) {
        Map<String, Object> row = db.row("SELECT Body FROM Inbox WHERE InboxId=?", id);
        return row == null ? null : (String) row.get("Body");
    }

    // ───────────── 등록 ─────────────

    /** 설정에 따라 사람 손 없이 등록한다. 등록했으면 true. */
    private boolean autoApply(String id) {
        String mode = settings.autoMode();
        if ("off".equals(mode)) return false;
        Map<String, Object> row = db.row("SELECT * FROM Inbox WHERE InboxId=? AND State='대기'", id);
        if (row == null) return false;
        Map<String, Object> s = fromJson(row.get("Suggestion"));
        boolean read = "읽음".equals(row.get("ReadState")) && !Txt.blank(row.get("Body"));
        String type = Txt.str(s.get("docType"));
        boolean matched = !Txt.blank(s.get("matchCaseId"));
        Map<String, String> plan = new HashMap<>();
        plan.put("kind", Txt.str(s.get("kind")));
        if (matched) {
            plan.put("target", Txt.str(s.get("matchCaseId")));
            if ("full".equals(mode)) { plan.put("withReply", "1"); plan.put("withDiscipline", "1"); }
        } else if ("full".equals(mode) && read && "결과".equals(type) && !Txt.blank(s.get("title"))) {
            plan.put("target", "new");
            plan.put("withActions", "1");
            plan.put("useBody", "1");
        } else if ("full".equals(mode) && read && "징계".equals(type) && !listOf(s.get("discipline")).isEmpty()) {
            plan.put("target", "none");
            plan.put("withDiscipline", "1");
        } else {
            return false;
        }
        Op op = apply(id, plan, true);
        if (!op.ok) log.info("자동 등록 보류 {}: {}", row.get("FileName"), op.message);
        return op.ok;
    }

    /**
     * 수집 항목을 등록한다.
     * plan: target(new | none | 건 식별자), kind, title, targetName, year, category, summary,
     *       withActions, withDiscipline, withReply, useBody
     */
    public Op apply(String id, Map<String, String> plan, boolean auto) {
        Map<String, Object> row = db.row("SELECT * FROM Inbox WHERE InboxId=? AND State='대기'", id);
        if (row == null) return Op.fail(404, "수집 항목을 찾을 수 없거나 이미 처리했습니다.");
        Path file = Path.of((String) row.get("SourcePath"));
        if (!Files.isRegularFile(file)) return Op.fail(404, "원래 위치에 파일이 없습니다. 제외하거나 파일을 다시 넣으세요.");
        Map<String, Object> s = fromJson(row.get("Suggestion"));
        String body = Txt.str(row.get("Body"));
        String name = (String) row.get("FileName");
        String target = Txt.str(plan.get("target"));
        String kind = CaseService.KINDS.contains(plan.get("kind")) ? plan.get("kind") : "증빙";
        List<String> notes = new ArrayList<>();
        String caseId = null;
        Map<String, Object> richRow = db.row("SELECT Html FROM InboxRich WHERE InboxId=?", id);
        String html = richRow == null ? null : (String) richRow.get("Html");
        LocalDate today = Kst.today();
        String docDate = Txt.blank(s.get("docDate")) ? today.toString() : Txt.str(s.get("docDate"));

        if ("new".equals(target)) {
            String title = pick(plan.get("title"), s.get("title"), Txt.stem(name));
            String targetName = pick(plan.get("targetName"), s.get("target"), "미정");
            Integer year = Txt.intOrNull(pick(plan.get("year"), s.get("year"), Integer.toString(today.getYear())));
            String category = pick(plan.get("category"), s.get("category"), "업무진단");
            String summary = pick(plan.get("summary"), s.get("summary"), "");
            Op made = cases.register(year == null ? today.getYear() : year, category, Txt.fit(title, 120, false), Txt.fit(targetName, 120, false),
                "", "", Txt.fit(summary, 160, false), "1".equals(plan.get("useBody")) ? Txt.fit(body, 20000, true) : "", settings.defaultRetention());
            if (!made.ok) return made;
            caseId = made.id;
            if ("1".equals(plan.get("useBody")) && html != null) cases.setBodyHtml(caseId, html);
            notes.add("새 건 초안 " + made.extra);
        } else if (Txt.isId(target)) {
            caseId = target;
        } else if (!"none".equals(target)) {
            return Op.bad("붙일 건을 고르세요.");
        }

        if (caseId != null) {
            TextReader.Result pre = new TextReader.Result(Txt.str(row.get("ReadState")), Txt.str(row.get("ReadMethod")), Txt.str(row.get("ReadNote")),
                body, Integer.valueOf(1).equals(row.get("Encrypted")), html);
            Op added;
            try (InputStream in = Files.newInputStream(file)) {
                added = cases.addFile(caseId, null, kind, name, name, in, pre);
            } catch (IOException e) {
                added = Op.fail(503, "파일을 읽지 못했습니다.");
            }
            if (!added.ok) return "new".equals(target) ? Op.ok("건 초안은 만들었지만 파일은 붙이지 못했습니다: " + added.message, caseId) : added;
            notes.add(kind + " 추가");

            if ("1".equals(plan.get("withActions"))) {
                int n = 0;
                String dept = pick(plan.get("targetName"), s.get("target"), "미정");
                for (Map<String, Object> a : listOf(s.get("actions"))) {
                    Op op = cases.addAction(caseId, null, Txt.str(a.get("title")), Txt.str(a.get("type")), pick(null, a.get("dept"), dept),
                        pick(null, a.get("requestedOn"), docDate), pick(null, a.get("dueOn"), today.plusDays(settings.replyDays()).toString()), Txt.str(a.get("content")));
                    if (op.ok) n++;
                }
                if (n > 0) notes.add("조치요구 " + n + "개");
            }
            if ("1".equals(plan.get("withReply")) && "회신".equals(s.get("docType")) && !body.isBlank()) {
                Map<String, Object> detail = cases.get(caseId);
                List<Map<String, Object>> open = new ArrayList<>();
                for (Map<String, Object> a : listOf(detail.get("Actions"))) if (!"이행완료".equals(a.get("ActionState"))) open.add(a);
                Map<String, Object> hit = null;
                Object seq = s.get("replySeq");
                for (Map<String, Object> a : open) if (seq != null && seq.toString().equals(Txt.str(a.get("Sequence")))) hit = a;
                if (hit == null && open.size() == 1) hit = open.get(0);
                if (hit != null) {
                    Op op = cases.recordReply(caseId, null, (String) hit.get("ActionId"), Txt.fit("[수집한 회신 문서: " + name + "]\n" + body, 20000, true));
                    if (op.ok) notes.add("조치 " + hit.get("Sequence") + " 회신 기록");
                }
            }
        }

        if ("1".equals(plan.get("withDiscipline"))) {
            int n = 0;
            String ref = caseId == null ? "" : caseId;
            for (Map<String, Object> d : listOf(s.get("discipline"))) {
                Map<String, String> in = new HashMap<>();
                for (Map.Entry<String, Object> e : d.entrySet()) in.put(e.getKey(), Txt.str(e.getValue()));
                String vDate = pick(null, d.get("violationDate"), pick(null, d.get("sanctionDate"), docDate));
                String remark = Txt.str(d.get("remark"));
                if (Txt.blank(d.get("violationDate"))) remark = (remark + " 위반일자 미확인(자동 수집)").strip();
                if (Txt.blank(d.get("violationText"))) in.put("violationText", "수집 문서 확인 필요: " + name);
                in.put("violationDate", vDate);
                in.put("remark", Txt.fit(remark, 500, false));
                in.put("year", vDate.substring(0, 4));
                in.put("status", "초안");
                in.put("caseRef", ref);
                in.put("personName", Txt.fit(in.get("personName"), 40, false));
                in.put("violationText", Txt.fit(in.get("violationText"), 500, true));
                for (String k : List.of("employeeNo", "position")) in.put(k, Txt.fit(in.get(k), 40, false));
                for (String k : List.of("department", "sanctionTerm", "decisionBody", "docNo")) in.put(k, Txt.fit(in.get(k), 80, false));
                in.put("ruleText", Txt.fit(in.get("ruleText"), 240, true));
                if (discipline.save(in).ok) n++;
            }
            if (n > 0) notes.add("징계기록 초안 " + n + "개");
            else if (caseId == null) return Op.bad("문서에서 등재할 징계기록을 찾지 못했습니다.");
        }
        if (notes.isEmpty()) return Op.bad("등록할 내용이 없습니다.");

        Path settled = settle(file);
        String note = (auto ? "자동 등록: " : "등록: ") + String.join(", ", notes);
        String finalCase = caseId;
        db.tx(conn -> Db.update(conn, "UPDATE Inbox SET State='등록됨', AppliedCaseId=?, AppliedNote=?, Body=NULL, Suggestion=NULL, SourcePath=?, ModifiedAt=? WHERE InboxId=?",
            finalCase, note, settled.toString(), modifiedOf(settled, ((Number) row.get("ModifiedAt")).longValue()), id));
        storeRich(id, null);
        return Op.ok(note, caseId);
    }

    private static String pick(String chosen, Object suggested, String fallback) {
        if (!Txt.blank(chosen)) return chosen.strip();
        return Txt.blank(suggested) ? fallback : suggested.toString().strip();
    }

    /** 이 프로그램의 수집함 폴더에 있던 파일만 '처리완료'로 옮긴다. 다른 폴더의 파일은 건드리지 않는다. */
    private Path settle(Path file) {
        try {
            if (!settings.moveProcessed() || !paths.inbox.equals(file.toAbsolutePath().normalize().getParent())) return file;
            Path dir = paths.inbox.resolve(DONE_DIR);
            Files.createDirectories(dir);
            String name = file.getFileName().toString();
            Path target = dir.resolve(name);
            for (int i = 2; Files.exists(target); i++) target = dir.resolve(Txt.stem(name) + " (" + i + ")" + Txt.ext(name));
            Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
            seen.remove(file.toString());
            return target;
        } catch (IOException | RuntimeException e) {
            return file;
        }
    }

    public Op exclude(String id) {
        int n = db.tx(conn -> Db.update(conn, "UPDATE Inbox SET State='제외', AppliedNote='등록하지 않음', Body=NULL, Suggestion=NULL WHERE InboxId=? AND State IN ('대기','실패')", id));
        if (n == 1) storeRich(id, null);
        return n == 1 ? Op.ok("수집함에서 뺐습니다. 원래 파일은 그대로 있습니다.") : Op.fail(404, "수집 항목을 찾을 수 없음");
    }

    /** 브라우저에서 올린 파일을 수집함 폴더에 넣고 바로 처리한다. */
    public Op upload(String originalName, InputStream in) {
        String name = FileStore.safeName(originalName);
        try {
            Path target = paths.inbox.resolve(name);
            for (int i = 2; Files.exists(target); i++) target = paths.inbox.resolve(Txt.stem(name) + " (" + i + ")" + Txt.ext(name));
            Files.copy(in, target);
        } catch (IOException e) {
            return Op.fail(503, "파일을 수집함에 넣지 못했습니다.");
        }
        return scan(true);
    }
}
