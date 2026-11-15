package local.audit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * 이전 판(C# 서버판)의 데이터 폴더에서 건, 조치, 자료, 징계기록, 이력을 가져온다.
 * 이 프로그램에 아직 건과 징계기록이 하나도 없을 때만 한다. 원래 폴더는 읽기만 한다.
 */
@Service
public class LegacyImport {
    private final Db db;
    private final Paths2 paths;
    private final FileStore files;
    private final Settings settings;
    private final CaseService cases;
    private final Chain chain;

    public LegacyImport(Db db, Paths2 paths, FileStore files, Settings settings, CaseService cases, Chain chain) {
        this.db = db;
        this.paths = paths;
        this.files = files;
        this.settings = settings;
        this.cases = cases;
        this.chain = chain;
    }

    public Op run(String folder) {
        if (Txt.blank(folder)) return Op.bad("이전 판의 '감사기록' 폴더 경로를 적으세요. 그 안에 data와 files 폴더가 있습니다.");
        Path root;
        try {
            root = Path.of(folder.strip()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return Op.bad("폴더 경로를 확인하세요.");
        }
        Path oldDb = root.resolve("data").resolve("audit.db");
        if (!Files.isRegularFile(oldDb)) oldDb = root.resolve("audit.db");
        if (!Files.isRegularFile(oldDb)) return Op.bad("그 폴더에서 data\\audit.db를 찾지 못했습니다.");
        if (oldDb.equals(paths.db)) return Op.bad("지금 쓰는 데이터 폴더입니다.");
        if (db.read(c -> Db.intOf(c, "SELECT (SELECT COUNT(*) FROM Cases) + (SELECT COUNT(*) FROM Discipline)")) > 0)
            return Op.bad("이미 건이나 징계기록이 있습니다. 가져오기는 비어 있을 때만 합니다.");
        Path oldFiles = root.resolve("files");
        Path copy = paths.tmp.resolve("legacy-" + Txt.newId() + ".db");
        List<String[]> toRead = new ArrayList<>();
        try {
            // 원본을 건드리지 않도록 복사본을 연다. WAL에 남은 내용도 함께 본다.
            Files.copy(oldDb, copy, StandardCopyOption.REPLACE_EXISTING);
            for (String suffix : new String[] { "-wal", "-shm" }) {
                Path side = oldDb.resolveSibling(oldDb.getFileName() + suffix);
                if (Files.isRegularFile(side)) Files.copy(side, copy.resolveSibling(copy.getFileName() + suffix), StandardCopyOption.REPLACE_EXISTING);
            }
            try (Connection old = DriverManager.getConnection("jdbc:sqlite:" + copy)) {
                return db.run(conn -> move(old, conn, oldFiles, toRead));
            }
        } catch (IOException | SQLException | RuntimeException e) {
            return Op.fail(503, "가져오지 못했습니다. 이전 판의 데이터 형식을 확인하세요. (" + e.getMessage() + ")");
        } finally {
            Chain.stampOverride.remove();
            for (String suffix : new String[] { "", "-wal", "-shm" }) {
                try { Files.deleteIfExists(copy.resolveSibling(copy.getFileName() + suffix)); } catch (IOException ignored) { }
            }
            chain.verify();
            for (String[] pair : toRead) cases.reread(pair[0], pair[1]);
        }
    }

    private Op move(Connection old, Connection conn, Path oldFiles, List<String[]> toRead) throws SQLException {
        Map<String, String> users = new HashMap<>();
        for (Map<String, Object> u : Db.rows(old, "SELECT UserId, DisplayName FROM Users")) users.put((String) u.get("UserId"), (String) u.get("DisplayName"));
        int nCases = 0, nFiles = 0, nMissing = 0, nPeople = 0;
        for (Map<String, Object> c : Db.rows(old, "SELECT c.*, r.DocumentNo, r.SummaryLine, r.Body, r.ConfirmerUserId FROM Cases c LEFT JOIN Results r ON r.CaseId=c.CaseId")) {
            Db.update(conn, """
                INSERT INTO Cases(CaseId, Year, Category, Title, Target, Owner, Shelf, BinderNo, DocumentNo, SummaryLine, Body, Confirmer,
                  RetentionYears, FirstConfirmedOn, ConfirmedOn, EverConfirmed, Hold, HoldReason, HoldSetAt, HoldReleasedAt, Phase, PurgedAt,
                  BodiesClearedAt, Version, CreatedAt)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                c.get("CaseId"), c.get("Year"), c.get("Category"), c.get("Title"), c.get("Target"), users.getOrDefault(c.get("OwnerUserId"), ""),
                c.get("Shelf"), c.get("BinderNo"), Txt.str(c.get("DocumentNo")), Txt.str(c.get("SummaryLine")), c.get("Body"),
                users.get(c.get("ConfirmerUserId")), c.get("RetentionYears"), c.get("FirstConfirmedOn"), c.get("ConfirmedOn"), c.get("EverConfirmed"),
                c.get("Hold"), c.get("HoldReason"), c.get("HoldSetAt"), c.get("HoldReleasedAt"), c.get("Phase"), c.get("PurgedAt"),
                c.get("BodiesClearedAt"), c.get("Version"), c.get("CreatedAt"));
            nCases++;
        }
        for (Map<String, Object> a : Db.rows(old, "SELECT * FROM Actions"))
            Db.update(conn, "INSERT INTO Actions(ActionId, CaseId, Sequence, Title, Type, TargetDept, RequestedOn, ReplyDueOn, Content, ActionState) VALUES (?,?,?,?,?,?,?,?,?,?)",
                a.get("ActionId"), a.get("CaseId"), a.get("Sequence"), a.get("Title"), a.get("Type"), a.get("TargetDept"), a.get("RequestedOn"),
                a.get("ReplyDueOn"), a.get("Content"), a.get("ActionState"));
        for (Map<String, Object> f : Db.rows(old, "SELECT * FROM Fulfillments"))
            Db.update(conn, "INSERT INTO Fulfillments(FulfillmentId, ActionId, At, Actor, FromState, ToState, Kind, ReplyBody, CheckBody, HistorySeq) VALUES (?,?,?,?,?,?,?,?,?,?)",
                f.get("FulfillmentId"), f.get("ActionId"), f.get("At"), users.getOrDefault(f.get("ActorId"), ""), f.get("FromState"), f.get("ToState"),
                f.get("Kind"), f.get("ReplyBody"), f.get("CheckBody"), f.get("HistorySeq"));
        for (Map<String, Object> a : Db.rows(old, "SELECT * FROM Attachments")) {
            String caseId = (String) a.get("CaseId"), id = (String) a.get("AttachmentId"), name = (String) a.get("DisplayName");
            String state = "Deleted".equals(a.get("StorageState")) ? "Deleted" : "Missing";
            FileStore.Stored stored = null;
            String key = (String) a.get("StorageKey");
            if (!"Deleted".equals(state) && key != null && key.matches("c/[0-9a-f]{32}/a/[0-9a-f]{32}")) {
                Path source = oldFiles.resolve(key.replace('/', java.io.File.separatorChar));
                if (Files.isRegularFile(source)) {
                    try (InputStream in = Files.newInputStream(source)) {
                        stored = files.save(caseId, id, Txt.ext(name), in);
                        state = "Present";
                    } catch (IOException e) {
                        stored = null;
                    }
                }
            }
            if ("Present".equals(state)) { nFiles++; toRead.add(new String[] { caseId, id }); }
            else if ("Missing".equals(state)) nMissing++;
            Db.update(conn, """
                INSERT INTO Attachments(AttachmentId, CaseId, Kind, DisplayName, StoragePath, SizeBytes, Sha256, RegisteredAt, RegisteredBy, DeletedAt, StorageState, ReadState)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""", id, caseId, a.get("Kind"), name, stored == null ? null : stored.key(), stored == null ? 0 : stored.size(),
                stored == null ? null : stored.sha256(), a.get("RegisteredAt"), users.getOrDefault(a.get("RegisteredBy"), ""), a.get("DeletedAt"), state,
                "Present".equals(state) ? "안읽음" : "Deleted".equals(state) ? "삭제됨" : "안읽음");
        }
        try {
            for (Map<String, Object> d : Db.rows(old, "SELECT * FROM Discipline ORDER BY Seq")) {
                Db.update(conn, """
                    INSERT INTO Discipline(DisciplineId, Seq, CaseId, Year, EmployeeNo, PersonName, Department, Position, ViolationDate, ViolationText,
                      RuleText, SanctionType, SanctionDate, SanctionTerm, DecisionBody, DocNo, Status, Remark, Hold, RetentionYears, ConfirmedOn, Version, CreatedAt, CreatedBy)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    d.get("DisciplineId"), d.get("Seq"), d.get("CaseId"), d.get("Year"), d.get("EmployeeNo"), d.get("PersonName"), d.get("Department"),
                    d.get("Position"), d.get("ViolationDate"), d.get("ViolationText"), d.get("RuleText"), d.get("SanctionType"), d.get("SanctionDate"),
                    d.get("SanctionTerm"), d.get("DecisionBody"), d.get("DocNo"), d.get("Status"), d.get("Remark"), d.get("Hold"), d.get("RetentionYears"),
                    d.get("ConfirmedOn"), d.get("Version"), d.get("CreatedAt"), users.getOrDefault(d.get("CreatedBy"), ""));
                nPeople++;
            }
        } catch (SQLException e) {
            if (nPeople > 0) throw e; // 징계기록 표가 없는 더 옛 판은 건너뛴다
        }
        try {
            for (Map<String, Object> r : Db.rows(old, "SELECT * FROM CategoryCodes"))
                Db.update(conn, "UPDATE CategoryCodes SET Code=?, Used=? WHERE Category=?", "x" + r.get("Code"), r.get("Used"), r.get("Category"));
            Db.update(conn, "UPDATE CategoryCodes SET Code=SUBSTR(Code,2)");
            Db.update(conn, "DELETE FROM DocCounters");
            for (Map<String, Object> r : Db.rows(old, "SELECT * FROM DocCounters"))
                Db.update(conn, "INSERT INTO DocCounters(Year, Code, LastSeq) VALUES (?,?,?)", r.get("Year"), r.get("Code"), r.get("LastSeq"));
        } catch (SQLException ignored) {
            // 문서번호 표가 없는 더 옛 판
        }
        // 이력은 원래 시각과 순서 그대로 옮기고, 지문은 이 프로그램의 것으로 새로 잇는다.
        long offset = Long.parseLong(Settings.meta(conn, "chain.seq", "0"));
        Map<Long, Long> moved = new HashMap<>();
        for (Map<String, Object> h : Db.rows(old, "SELECT * FROM History ORDER BY Seq")) {
            Chain.stampOverride.set((String) h.get("At"));
            String actor = h.get("ActorId") == null ? null : users.getOrDefault(h.get("ActorId"), "");
            long seq = Chain.append(conn, (String) h.get("CaseId"), actor, (String) h.get("Action"), (String) h.get("TargetKind"),
                (String) h.get("TargetId"), Txt.str(h.get("SummarySafe")));
            long oldSeq = ((Number) h.get("Seq")).longValue();
            if (seq != oldSeq) moved.put(oldSeq, seq);
        }
        for (Map.Entry<Long, Long> e : moved.entrySet())
            Db.update(conn, "UPDATE Fulfillments SET HistorySeq=? WHERE HistorySeq=?", -e.getValue(), e.getKey());
        Db.update(conn, "UPDATE Fulfillments SET HistorySeq=-HistorySeq WHERE HistorySeq<0");
        Chain.stampOverride.remove();
        String note = "이전 판에서 가져옴: 건 " + nCases + "개, 파일 " + nFiles + "개" + (nMissing > 0 ? "(원문 없음 " + nMissing + "개)" : "") + ", 징계기록 " + nPeople + "개";
        Chain.append(conn, null, settings.owner(), "등록", "가져오기", null, note + (offset > 0 ? "" : ". 이력 지문은 새로 이음"));
        return Op.ok(note + ". 가져온 파일의 내용은 이어서 읽습니다.");
    }
}
