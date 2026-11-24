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

    private LibraryService library;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setLibrary(LibraryService library) { this.library = library; }

    /** 백업 폴더가 기록 폴더(감사기록) 안이면 true. 그 폴더나 디스크가 망가지면 백업도 함께 사라진다. */
    public boolean backupInsideHome() {
        try { return Path.of(settings.get("backupFolder", paths.backup.toString())).toAbsolutePath().normalize().startsWith(paths.home); }
        catch (RuntimeException e) { return true; }
    }

    public String backupFolder() { return settings.get("backupFolder", paths.backup.toString()); }

    /** 백업에서 되돌리기를 예약한다(다음에 켤 때 바꾼다). 예약 전에 지금 상태를 한 번 더 백업한다. */
    public Op requestRestore(String folder) {
        if (Txt.blank(folder)) return Op.bad("되돌릴 백업 폴더를 고르거나 적으세요.");
        Path src;
        try { src = Path.of(folder.strip().replace("\"", "")).toAbsolutePath().normalize(); } catch (RuntimeException e) { return Op.bad("폴더 위치를 확인하세요."); }
        if (src.equals(paths.home)) return Op.bad("지금 쓰는 감사기록 폴더 자신은 고를 수 없습니다.");
        String why = Restore.check(src);
        if (why != null) return Op.bad(why);
        Op before = backup(backupFolder(), false);
        try { Restore.request(paths.home, src); } catch (IOException e) { return Op.fail(500, "되돌리기를 예약하지 못했습니다: " + e.getMessage()); }
        return Op.ok("되돌리기를 예약했습니다. 프로그램을 닫았다가 다시 켜면 '" + src.getFileName() + "' 시점으로 바뀝니다."
            + (before.ok ? " 혹시 몰라 지금 기록도 먼저 백업해 두었습니다." : " (지금 기록 백업은 실패했지만, 되돌릴 때 지금 기록을 따로 남겨 둡니다.)"));
    }

    public Op cancelRestore() {
        try { Restore.cancel(paths.home); return Op.ok("되돌리기 예약을 취소했습니다."); } catch (IOException e) { return Op.fail(500, e.getMessage()); }
    }

    public String pendingRestore() { return Restore.pending(paths.home); }

    public RetentionService(Db db, Chain chain, Settings settings, FileStore files, Paths2 paths, DisciplineService discipline) {
        this.db = db;
        this.chain = chain;
        this.settings = settings;
        this.files = files;
        this.paths = paths;
        this.discipline = discipline;
    }

    /** 켜져 있는 동안 한 시간마다. 설정에서 자동 삭제를 켠 경우에만 지운다. 자료 휴지통은 30일 지난 것을 비운다. */
    @Scheduled(initialDelay = 20_000, fixedDelay = 3_600_000)
    public void tick() {
        try {
            if (settings.autoPurge()) purgeDue();
            autoBackup();
            if (library != null) library.purgeTrash(LibraryService.TRASH_DAYS);
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
                // 편집기로 꾸민 모양도 함께 지운다
                Db.update(conn, "DELETE FROM CaseRich WHERE CaseId=?", caseId);
                Db.update(conn, "DELETE FROM RichText WHERE Owner IN (SELECT 'action:' || ActionId FROM Actions WHERE CaseId=?)", caseId);
                Db.update(conn, "DELETE FROM RichText WHERE Owner IN (SELECT 'entry:' || f.FulfillmentId FROM Fulfillments f JOIN Actions a ON a.ActionId=f.ActionId WHERE a.CaseId=?)", caseId);
                Db.update(conn, "UPDATE Actions SET Content=NULL WHERE CaseId=?", caseId);
                Db.update(conn, "UPDATE Fulfillments SET ReplyBody=NULL, CheckBody=NULL WHERE ActionId IN (SELECT ActionId FROM Actions WHERE CaseId=?)", caseId);
                Db.update(conn, "DELETE FROM AttachmentText WHERE AttachmentId IN (SELECT AttachmentId FROM Attachments WHERE CaseId=?)", caseId);
                Db.update(conn, "UPDATE Inbox SET Body=NULL, Suggestion=NULL WHERE AppliedCaseId=?", caseId);
                Db.update(conn, "DELETE FROM CaseRich WHERE CaseId=?", caseId);
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

    public static final int AUTO_BACKUP_KEEP = 7;

    /** 하루에 한 번, 기록이 있을 때만. 자동 백업은 최근 7개만 남긴다. 직접 만든 백업은 지우지 않는다. */
    void autoBackup() {
        // 기록 종류(업무·사규·자료·감사 건 …)와 상관없이 하루 한 번. 이력 지문에 문제가 있어도 백업은 한다(그때가 더 필요하다).
        if (!settings.autoBackup()) return;
        String today = Kst.today().toString();
        if (today.equals(settings.get("lastAutoBackup", ""))) return;
        String folder = settings.get("backupFolder", paths.backup.toString());
        Op op = backup(folder, true);
        settings.set("lastAutoBackup", today);
        if (!op.ok) { log.warn("자동 백업 실패: {}", op.message); return; }
        try (Stream<Path> list = Files.list(Path.of(folder))) {
            List<Path> autos = list.filter(p -> Files.isDirectory(p) && p.getFileName().toString().matches("감사기록-백업-\\d{8}-\\d{6}-자동"))
                .sorted().toList();
            for (int i = 0; i < autos.size() - AUTO_BACKUP_KEEP; i++) {
                try (Stream<Path> walk = Files.walk(autos.get(i))) {
                    for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.info("옛 자동 백업 정리 건너뜀: {}", e.toString());
        }
    }

    public Op backup(String folder) { return backup(folder, false); }

    /** data 파일과 files 폴더를 지정한 폴더 아래 날짜 이름으로 복사한다. 인터넷으로 보내지 않는다. */
    public Op backup(String folder, boolean auto) {
        Path root = Txt.blank(folder) ? paths.backup : Path.of(folder.strip()).toAbsolutePath().normalize();
        if (root.startsWith(paths.files) || root.startsWith(paths.data)) return Op.bad("data, files 폴더 안에는 백업하지 않습니다.");
        String id = Kst.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path dest = root.resolve("감사기록-백업-" + id + (auto ? "-자동" : ""));
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
                Db.update(conn, "INSERT INTO Backups(BackupId, At, Location, State, Note) VALUES (?,?,?,?,?)", id, Kst.stamp(), dest.toString(), "완료", (auto ? "자동 · " : "") + "파일 " + count + "개");
                return Chain.append(conn, null, auto ? null : settings.owner(), "백업", "백업", id, (auto ? "자동 백업 완료. 파일 " : "백업 완료. 파일 ") + count + "개");
            });
            if (!auto) settings.set("backupFolder", root.toString());
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
