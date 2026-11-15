package local.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import local.audit.extract.DocAnalyzer;
import local.audit.extract.OfficeBridge;
import local.audit.extract.TextReader;

class CoreTest {
    @TempDir Path dir;
    Paths2 paths;
    Db db;
    Settings settings;
    Chain chain;
    FileStore files;
    TextReader reader;
    CaseService cases;
    DisciplineService discipline;
    RetentionService retention;
    InboxService inbox;

    @BeforeEach
    void setUp() throws Exception {
        Kst.clock = Clock.fixed(Instant.parse("2026-11-16T03:00:00Z"), ZoneOffset.UTC);
        paths = new Paths2(dir.toString());
        db = new Db(paths);
        settings = new Settings(db);
        chain = new Chain(db);
        files = new FileStore(paths);
        reader = new TextReader(new OfficeBridge(paths), settings);
        cases = new CaseService(db, chain, settings, files, reader);
        discipline = new DisciplineService(db, chain, settings);
        retention = new RetentionService(db, chain, settings, files, paths, discipline);
        inbox = new InboxService(db, paths, settings, reader, cases, discipline);
    }

    @AfterEach
    void tearDown() { Kst.clock = Clock.systemUTC(); }

    private String newCase(Integer retention) {
        Op op = cases.register(2026, "업무진단", "예시 진단", "예시조직", "", "", "결과 한 줄", "본문", retention);
        assertTrue(op.ok, op.message);
        return op.id;
    }

    private static void zip(Path file, Map<String, String> entries) throws Exception {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
    }

    @Test
    void documentNumbersCountUpPerYearAndCategory() {
        assertEquals("2026-01-01", cases.peekDocumentNo(2026, "업무진단"));
        newCase(null);
        assertEquals("2026-01-02", cases.peekDocumentNo(2026, "업무진단"));
        assertEquals("2026-02-01", cases.peekDocumentNo(2026, "준법감사"));
    }

    @Test
    void confirmNeedsRetentionAndCloseNeedsFinishedActions() {
        String id = newCase(null);
        assertFalse(cases.confirm(id, null).ok);
        assertTrue(cases.changeRetention(id, null, 5, null).ok);
        Op action = cases.addAction(id, null, "지적 하나", "지적", "예시조직", "2026-11-01", "2026-11-10", "내용");
        assertTrue(action.ok);
        assertTrue(cases.confirm(id, null).ok);
        assertEquals("조치중", cases.get(id).get("Phase"));
        assertFalse(cases.close(id, null, false).ok);
        assertTrue(cases.recordReply(id, null, action.extra, "회신").ok);
        assertTrue(cases.verifyAction(id, null, action.extra, "확인").ok);
        assertTrue(cases.close(id, null, false).ok);
        assertFalse(cases.changeRetention(id, null, 7, "").ok, "확정 뒤 연한 변경은 사유 필요");
        assertTrue(cases.changeRetention(id, null, 7, "규정 변경").ok);
    }

    @Test
    void staleVersionIsRejected() {
        String id = newCase(null);
        assertTrue(cases.saveResult(id, 1, "한 줄", "본문").ok);
        assertEquals(409, cases.saveResult(id, 1, "다른 창", "본문").status);
    }

    @Test
    void editingHistoryInTheDatabaseIsDetectedAndFreezesWrites() {
        String id = newCase(null);
        assertTrue(chain.verify());
        db.tx(conn -> Db.update(conn, "UPDATE History SET Summary='고친 줄' WHERE Seq=1"));
        assertFalse(chain.verify());
        assertEquals(503, cases.saveResult(id, null, "한 줄", "").status);
        assertTrue(chain.reseal("나", "시험").ok);
        assertTrue(chain.verify());
        assertTrue(cases.saveResult(id, null, "한 줄", "").ok);
    }

    @Test
    void expiryRemovesBodiesFilesAndReadTextButKeepsListAndHistory() throws Exception {
        String id = newCase(3);
        Op file = cases.addFile(id, null, "증빙", "", "메모.txt", new ByteArrayInputStream("찾을 내용".getBytes(StandardCharsets.UTF_8)), null);
        assertTrue(file.ok, file.message);
        cases.awaitReads();
        assertEquals("찾을 내용", cases.fileText(id, file.extra));
        assertEquals(1, ((List<?>) cases.list(null, null, null, false, false, "찾을 내용", null, null, 1, 50).get("Items")).size());
        cases.addAction(id, null, "지적", "지적", "예시조직", "2026-11-01", "2026-11-10", "조치 내용");
        assertTrue(cases.confirm(id, null).ok);
        String held = newCase(3);
        cases.confirm(held, null);
        cases.setHold(held, null, "분쟁 중");

        Kst.clock = Clock.fixed(Instant.parse("2029-11-16T03:00:00Z"), ZoneOffset.UTC);
        assertTrue(retention.purgeDue().ok);
        Map<String, Object> c = cases.get(id);
        assertEquals("자료삭제됨", c.get("DisplayState"));
        assertNull(c.get("Body"));
        assertEquals("결과 한 줄", c.get("SummaryLine"));
        assertNull(cases.fileText(id, file.extra));
        Map<String, Object> att = cases.attachment(id, file.extra);
        assertEquals("Deleted", att.get("StorageState"));
        assertEquals("메모.txt", att.get("DisplayName"));
        assertEquals(0, Files.walk(paths.files).filter(Files::isRegularFile).count());
        assertNull(((List<Map<String, Object>>) c.get("Actions")).get(0).get("Content"));
        assertFalse(((List<?>) c.get("History")).isEmpty());
        assertEquals("확정", cases.get(held).get("DisplayState"), "보류 중인 건은 지우지 않는다");
        assertNotNull(cases.get(held).get("Body"));
        assertTrue(chain.verify());
    }

    @Test
    void disciplineExpiryErasesNameButKeepsSequence() {
        Map<String, String> in = new HashMap<>(Map.of("year", "2020", "personName", "홍길동", "violationDate", "2020-01-02",
            "violationText", "예시 위반", "sanctionType", "견책", "sanctionDate", "2020-02-01", "status", "확정"));
        Op op = discipline.save(in);
        assertTrue(op.ok, op.message);
        assertEquals("홍*동", discipline.open(op.id).get("PersonMasked"));
        assertEquals(1, discipline.purgeDue());
        Map<String, Object> row = discipline.open(op.id);
        assertEquals("삭제됨", row.get("PersonName"));
        assertEquals("", row.get("ViolationText"));
        assertEquals(1, row.get("Seq"));
    }

    @Test
    void readsOfficeFilesAndSpotsEncryptedOnes() throws Exception {
        Path docx = dir.resolve("a.docx");
        zip(docx, Map.of("word/document.xml",
            "<w:document xmlns:w='x'><w:body><w:p><w:r><w:t>첫 줄</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>가</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>나</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>"));
        TextReader.Result r = reader.read(docx, "a.docx");
        assertTrue(r.ok());
        assertTrue(r.text().startsWith("첫 줄"));
        assertTrue(r.text().contains("가 \t나"));

        Path xlsx = dir.resolve("b.xlsx");
        zip(xlsx, Map.of(
            "xl/sharedStrings.xml", "<sst><si><t>성명</t></si><si><t>홍길동</t></si></sst>",
            "xl/styles.xml", "<styleSheet><cellXfs><xf numFmtId='0'/><xf numFmtId='14'/></cellXfs></styleSheet>",
            "xl/worksheets/sheet1.xml", "<worksheet><sheetData><row r='1'><c r='A1' t='s'><v>0</v></c></row><row r='2'><c r='A2' t='s'><v>1</v></c><c r='C2' s='1'><v>46000</v></c></row></sheetData></worksheet>"));
        assertEquals("성명\n홍길동\t\t2025-12-09", reader.read(xlsx, "b.xlsx").text());

        Path locked = dir.resolve("c.docx");
        Files.write(locked, "<## NASCA DRM FILE - VER1.00 ##>....................".getBytes(StandardCharsets.UTF_8));
        TextReader.Result enc = reader.read(locked, "c.docx");
        assertTrue(enc.encrypted());
        assertFalse(enc.ok());
        assertEquals("", enc.text());
    }

    @Test
    void analyzerFindsCaseFieldsActionsAndDisciplineRows() {
        Map<String, Object> s = DocAnalyzer.analyze("2026 예시 결과보고.docx", """
            업무진단 결과보고
            제목: 예시조직 구매업무 진단
            감사대상: 예시조직 구매팀
            시행일자: 2026. 11. 2.
            감사결과: 분리가 지켜지지 않음
            회신기한: 2026-12-15
            1. [지적] 요청자와 검수자가 같은 사례
            2. [권고] 분기별 자체 점검
            """);
        assertEquals("결과", s.get("docType"));
        assertEquals("예시조직 구매업무 진단", s.get("title"));
        assertEquals("예시조직 구매팀", s.get("target"));
        assertEquals(2026, s.get("year"));
        List<Map<String, Object>> actions = (List<Map<String, Object>>) s.get("actions");
        assertEquals(2, actions.size());
        assertEquals("권고", actions.get(1).get("type"));
        assertEquals("2026-12-15", actions.get(0).get("dueOn"));

        Map<String, Object> d = DocAnalyzer.analyze("징계대장.xlsx", "사번\t성명\t부서\t위반일자\t위반내용\t징계종류\nA-1\t홍길동\t예시부서\t2026-01-05\t예시 위반\t감봉 1개월\n");
        assertEquals("징계", d.get("docType"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) d.get("discipline");
        assertEquals(1, rows.size());
        assertEquals("감봉", rows.get(0).get("sanctionType"));
    }

    @Test
    void inboxCreatesDraftThenAttachesReplyToTheSameCase() throws Exception {
        Files.writeString(paths.inbox.resolve("2026 결과보고.txt"), """
            진단 결과보고
            제목: 예시조직 구매업무 진단
            감사대상: 예시조직
            감사결과: 분리가 지켜지지 않음
            1. [지적] 요청자와 검수자가 같은 사례
            """);
        assertTrue(inbox.scan(true).ok);
        List<Map<String, Object>> all = db.rows("SELECT * FROM Cases");
        assertEquals(1, all.size());
        assertEquals("초안", all.get(0).get("Phase"));
        String id = (String) all.get(0).get("CaseId");
        assertTrue(Files.exists(paths.inbox.resolve(InboxService.DONE_DIR).resolve("2026 결과보고.txt")));

        Files.writeString(paths.inbox.resolve("조치결과 회신.txt"), "조치결과 회신\n예시조직 구매업무 진단 건에 대해 분리 조치함\n");
        inbox.scan(true);
        Map<String, Object> c = cases.get(id);
        assertEquals(2, ((List<?>) c.get("Files")).size());
        assertEquals("회신수신", ((List<Map<String, Object>>) c.get("Actions")).get(0).get("ActionState"));

        // 같은 파일을 다시 넣으면 또 등록하지 않는다
        Files.writeString(paths.inbox.resolve("조치결과 회신 사본.txt"), "조치결과 회신\n예시조직 구매업무 진단 건에 대해 분리 조치함\n");
        inbox.scan(true);
        assertEquals(2, ((List<?>) cases.get(id).get("Files")).size());
        assertEquals(0, inbox.pendingCount());
    }
}
