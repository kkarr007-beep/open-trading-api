package local.audit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Statement;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 보존연한 만료 처리와 백업. */
@Service
public class RetentionService {
    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);
    private final Db db;
    private final Chain chain;
    private final Settings settings;
    private final FileStore files;
    private final Paths2 paths;
    private final DisciplineService discipline;

    public RetentionService(Db db, Chain chain, Settings settings, FileStore files, Paths2 paths, DisciplineService discipline) {
        this.db = db;
        this.chain = chain;
        this.settings = settings;
        this.files = files;
        this.paths = paths;
        this.discipline = discipline;
    }

    /** 켜져 있는 동안 한 시간마다. 설정에서 자동 삭제를 켠 경우에만 지운다. */
    @Scheduled(initialDelay = 20_000, fixedDelay = 3_600_000)
    public void tick() {
        try {
            if (settings.autoPurge()) purgeDue();
        } catch (RuntimeException e) {
            log.warn("보존 삭제 처리 실패: {}", e.toString());
        }
    }

    /** 만료된 건의 본문과 파일을 지운다. 목록 정보, 결과 한 줄, 이력은 남긴다. */
    public Op purgeDue() {
        if (!chain.intact()) return Op.fail(503, CaseService.FROZEN);
        int done = 0, partial = 0;
        List<Map<String, Object>> cases = db.rows("SELECT * FROM Cases WHERE PurgedAt IS NULL AND Hold=0 AND FirstConfirmedOn IS NOT NULL AND RetentionYears IS NOT NULL");
        for (Map<String, Object> c : cases) {
            if (!CaseService.due(c)) continue;
            String caseId = (String) c.get("CaseId");
            List<String> failed = new ArrayList<>();
            int removed = 0;
            for (Map<String, Object> f : db.rows("SELECT AttachmentId, StoragePath FROM Attachments WHERE CaseId=? AND StorageState<>'Deleted'", caseId)) {
                String id = (String) f.get("AttachmentId");
                if (files.delete((String) f.get("StoragePath"))) {
                    db.tx(conn -> {
                        Db.update(conn, "DELETE FROM AttachmentText WHERE AttachmentId=?", id);
                        return Db.update(conn, "UPDATE Attachments SET StorageState='Deleted', DeletedAt=?, StoragePath=NULL, ReadState='삭제됨', ReadNote='' WHERE AttachmentId=?", Kst.stamp(), id);
                    });
                    removed++;
                } else failed.add(id);
            }
            boolean complete = failed.isEmpty();
            int count = removed;
            db.tx(conn -> {
                String at = Kst.stamp();
                boolean first = c.get("BodiesClearedAt") == null;
                Db.update(conn, "UPDATE Actions SET Content=NULL WHERE CaseId=?", caseId);
                Db.update(conn, "UPDATE Fulfillments SET ReplyBody=NULL, CheckBody=NULL WHERE ActionId IN (SELECT ActionId FROM Actions WHERE CaseId=?)", caseId);
                Db.update(conn, "DELETE FROM AttachmentText WHERE AttachmentId IN (SELECT AttachmentId FROM Attachments WHERE CaseId=?)", caseId);
                Db.update(conn, "UPDATE Inbox SET Body=NULL, Suggestion=NULL WHERE AppliedCaseId=?", caseId);
                Db.update(conn, "UPDATE Cases SET Body=NULL, BodiesClearedAt=COALESCE(BodiesClearedAt, ?), PurgedAt=?, Version=Version+1 WHERE CaseId=?",
                    at, complete ? at : null, caseId);
                if (complete) Chain.append(conn, caseId, null, "자료삭제", "건", caseId, "보존 만료 삭제. 본문과 파일 " + count + "개");
                else if (first) Chain.append(conn, caseId, null, "자료삭제", "건", caseId, "보존 만료 본문 삭제. 파일 일부는 지우지 못해 다시 시도함");
                return null;
            });
            if (complete) done++; else partial++;
        }
        int people = discipline.purgeDue();
        if (done + partial + people > 0) vacuum();
        return Op.ok("보존 만료 처리: 건 " + done + "개 삭제" + (partial > 0 ? ", " + partial + "개는 파일이 열려 있어 다음에 다시 시도" : "") + ", 징계기록 " + people + "개");
    }

    public void vacuum() {
        try {
            db.read(conn -> {
                try (Statement st = conn.createStatement()) {
                    st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                    st.execute("VACUUM");
                }
                return null;
            });
        } catch (RuntimeException e) {
            log.info("정리 건너뜀: {}", e.toString());
        }
    }

    /** data 파일과 files 폴더를 지정한 폴더 아래 날짜 이름으로 복사한다. 인터넷으로 보내지 않는다. */
    public Op backup(String folder) {
        Path root = Txt.blank(folder) ? paths.backup : Path.of(folder.strip()).toAbsolutePath().normalize();
        if (root.startsWith(paths.files) || root.startsWith(paths.data)) return Op.bad("data, files 폴더 안에는 백업하지 않습니다.");
        String id = Kst.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path dest = root.resolve("감사기록-백업-" + id);
        try {
            Files.createDirectories(dest.resolve("data"));
            Path dbCopy = dest.resolve("data").resolve("audit.db");
            db.read(conn -> {
                try (Statement st = conn.createStatement()) {
                    st.execute("VACUUM INTO '" + dbCopy.toString().replace("'", "''") + "'");
                }
                return null;
            });
            int n = 0;
            try (Stream<Path> walk = Files.walk(paths.files)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    Path target = dest.resolve("files").resolve(paths.files.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(target);
                    else { Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING); n++; }
                }
            }
            int count = n;
            db.tx(conn -> {
                Db.update(conn, "INSERT INTO Backups(BackupId, At, Location, State, Note) VALUES (?,?,?,?,?)", id, Kst.stamp(), dest.toString(), "완료", "파일 " + count + "개");
                return Chain.append(conn, null, settings.owner(), "백업", "백업", id, "백업 완료. 파일 " + count + "개");
            });
            settings.set("backupFolder", root.toString());
            return Op.ok("백업을 마쳤습니다: " + dest);
        } catch (IOException | RuntimeException e) {
            db.tx(conn -> Db.update(conn, "INSERT OR REPLACE INTO Backups(BackupId, At, Location, State, Note) VALUES (?,?,?,?,?)", id, Kst.stamp(), dest.toString(), "실패", "복사 중 오류"));
            return Op.fail(503, "백업이 완료되지 않았습니다. 미완료 복사본은 사용하지 마세요. (" + e.getMessage() + ")");
        }
    }

    public List<Map<String, Object>> recentBackups() {
        List<Map<String, Object>> rows = db.rows("SELECT * FROM Backups ORDER BY At DESC LIMIT 10");
        for (Map<String, Object> r : rows) r.put("AtDisplay", Kst.display((String) r.get("At")));
        return rows;
    }
}
