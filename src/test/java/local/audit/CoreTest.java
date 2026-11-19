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
    void richViewKeepsTablesImagesAndTextShape() throws Exception {
        Path docx = dir.resolve("r.docx");
        Path png = dir.resolve("p.png");
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
        javax.imageio.ImageIO.write(img, "png", png.toFile());
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(docx))) {
            z.putNextEntry(new ZipEntry("word/document.xml"));
            z.write(("<w:document xmlns:w='w' xmlns:a='a' xmlns:r='r'><w:body>"
                + "<w:p><w:pPr><w:jc w:val='center'/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val='32'/><w:spacing w:val='-10'/></w:rPr><w:t>큰 제목 &lt;b&gt;</w:t></w:r></w:p>"
                + "<w:tbl><w:tr><w:tc><w:tcPr><w:gridSpan w:val='2'/></w:tcPr><w:p><w:r><w:t>합친 칸</w:t></w:r></w:p></w:tc></w:tr>"
                + "<w:tr><w:tc><w:p><w:r><w:t>가</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>나</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
                + "<w:p><w:r><w:drawing><a:blip r:embed='rId5'/></w:drawing></w:r></w:p></w:body></w:document>").getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("word/_rels/document.xml.rels"));
            z.write("<Relationships><Relationship Id='rId5' Target='media/image1.png'/></Relationships>".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("word/media/image1.png"));
            z.write(Files.readAllBytes(png));
            z.closeEntry();
        }
        TextReader.Result r = reader.read(docx, "r.docx");
        assertTrue(r.ok());
        String html = r.html();
        assertNotNull(html);
        assertTrue(html.contains("text-align:center"), html);
        assertTrue(html.contains("font-weight:700") && html.contains("font-size:16.0pt") && html.contains("letter-spacing:-0.5pt"), html);
        assertTrue(html.contains("큰 제목 &lt;b&gt;"), "원문의 꺾쇠는 글자로만 들어간다");
        assertTrue(html.contains("<td colspan=\"2\">") && html.contains("<table>"), html);
        assertTrue(html.contains("<img alt=\"그림\" src=\"data:image/png;base64,"), html);
        assertFalse(html.contains("<script"));

        String fromText = local.audit.extract.Rich.fromText("머리\n사번\t성명\nA-1\t<홍>\n끝");
        assertTrue(fromText.contains("<th>사번</th><th>성명</th>") && fromText.contains("<td>&lt;홍&gt;</td>"), fromText);

        String id = newCase(null);
        Op file = cases.addFile(id, null, "결과원문", "", "r.docx", Files.newInputStream(docx), null);
        cases.awaitReads();
        assertTrue(cases.fileHtml(id, file.extra).contains("<table>"));
        assertTrue(cases.fillBodyFromFile(id, null, file.extra).ok);
        assertEquals(true, cases.get(id).get("HasRichBody"));
        assertTrue(cases.saveResult(id, null, "한 줄", "손으로 고친 본문").ok);
        assertEquals(false, cases.get(id).get("HasRichBody"), "본문을 고치면 가져온 모양은 치운다");
    }

    @Test
    void disciplineCsvImportAddsRowsSkipsDuplicatesAndReportsBadLines() {
        String csv = "\uFEFF사번,성명,부서,위반일자,위반내용,징계종류,징계일자,상태\r\n"
            + "A-1,홍길동,예시부서,2026-01-05,\"쉼표, 있는 내용\",감봉 1개월,2026.02.01,확정\r\n"
            + "A-2,김예시,예시부서,20260110,예시 위반,견책,,\r\n"
            + "A-3,박예시,예시부서,,날짜 없음,견책,,\r\n";
        Op first = discipline.importRows(DisciplineService.parseCsv(csv));
        assertTrue(first.ok, first.message);
        assertTrue(first.message.startsWith("2건 등재") && first.message.contains("4번째 줄"), first.message);
        List<Map<String, Object>> rows = discipline.list(null, null, null);
        assertEquals(2, rows.size());
        Map<String, Object> hong = rows.get(1);
        assertEquals("감봉", hong.get("SanctionType"));
        assertEquals("1개월", hong.get("SanctionTerm"));
        assertEquals("확정", hong.get("Status"));
        assertEquals("쉼표, 있는 내용", hong.get("ViolationText"));
        assertEquals("2026-01-10", rows.get(0).get("ViolationDate"));
        Op again = discipline.importRows(DisciplineService.parseCsv(csv));
        assertTrue(again.message.contains("이미 있는 2건"), again.message);
        assertEquals(2, discipline.list(null, null, null).size());
        // 내보낸 CSV를 그대로 다시 넣어도 된다
        assertTrue(discipline.importRows(DisciplineService.parseCsv(discipline.exportCsv())).message.contains("이미 있는 2건"));
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

    @Test
    void deleteButtonRemovesFilesWithReasonRules() {
        String id = newCase(5);
        Op a = cases.addFile(id, null, "증빙", "", "잘못.txt", new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), null);
        assertTrue(a.ok, a.message);
        String att1 = (String) db.row("SELECT AttachmentId FROM Attachments WHERE CaseId=? AND DisplayName='잘못.txt'", id).get("AttachmentId");
        // 확정 전 초안: 사유 없이 지운다
        assertTrue(cases.deleteFileAny(id, null, att1, "").ok);
        assertEquals("Deleted", db.row("SELECT StorageState FROM Attachments WHERE AttachmentId=?", att1).get("StorageState"));
        Op b = cases.addFile(id, null, "증빙", "", "증빙.txt", new ByteArrayInputStream("y".getBytes(StandardCharsets.UTF_8)), null);
        assertTrue(b.ok, b.message);
        String att2 = (String) db.row("SELECT AttachmentId FROM Attachments WHERE CaseId=? AND DisplayName='증빙.txt'", id).get("AttachmentId");
        assertTrue(cases.confirm(id, null).ok);
        // 확정된 건: 사유가 있어야 지운다
        assertFalse(cases.deleteFileAny(id, null, att2, "").ok);
        assertTrue(cases.deleteFileAny(id, null, att2, "중복 등록").ok);
        assertEquals("Deleted", db.row("SELECT StorageState FROM Attachments WHERE AttachmentId=?", att2).get("StorageState"));
        assertTrue(chain.verify(), "이력 지문이 그대로 맞아야 함");
    }

    @Test
    void newCategoriesWorkAfterUpgradingOldTable() throws Exception {
        String first = newCase(5);
        // 1.6.0판까지의 건 표(구분 두 가지로 묶임)를 만들어 둔다
        db.tx(conn -> {
            try (java.sql.Statement st = conn.createStatement()) {
                String sql = (String) Db.row(conn, "SELECT sql FROM sqlite_master WHERE name='Cases'").get("sql");
                st.execute("ALTER TABLE Cases RENAME TO Cases_old");
                st.execute(sql.replace("Category TEXT NOT NULL,", "Category TEXT NOT NULL CHECK (Category IN ('업무진단','준법감사')),"));
                st.execute("INSERT INTO Cases SELECT * FROM Cases_old");
                st.execute("DROP TABLE Cases_old");
            }
            return null;
        });
        assertTrue(((String) db.row("SELECT sql FROM sqlite_master WHERE name='Cases'").get("sql")).contains("CHECK (Category IN"));
        Db again = new Db(paths);
        assertFalse(((String) again.row("SELECT sql FROM sqlite_master WHERE name='Cases'").get("sql")).contains("CHECK (Category IN"));
        assertNotNull(again.row("SELECT CaseId FROM Cases WHERE CaseId=?", first), "기존 건은 그대로");
        assertTrue(cases.addCategory("내부통제점검", "03").ok);
        assertFalse(cases.addCategory("다른것", "03").ok, "코드 중복");
        assertTrue(cases.categories().contains("내부통제점검"));
        assertEquals("2026-03-01", cases.peekDocumentNo(2026, "내부통제점검"));
        Op op = cases.register(2026, "내부통제점검", "통제 점검", "예시조직", "", "", "한 줄", "본문", 5);
        assertTrue(op.ok, op.message);
        assertFalse(cases.removeCategory("내부통제점검").ok, "번호를 발급한 구분은 못 뺌");
        assertTrue(cases.addCategory("임시", "09").ok);
        assertTrue(cases.removeCategory("임시").ok);
        assertTrue(chain.verify());
    }

    @Test
    void selectedCasesAreDeletedWholeWithReason() throws Exception {
        String a = newCase(5), b = newCase(5), held = newCase(5);
        Op f = cases.addFile(a, null, "증빙", "", "x.txt", new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), null);
        assertTrue(f.ok);
        cases.awaitReads();
        cases.addAction(a, null, "지적", "지적", "예시조직", "2026-11-01", "2026-11-10", "조치");
        cases.confirm(held, null);
        cases.setHold(held, null, "분쟁 중");
        assertFalse(cases.deleteCases(List.of(a), "").ok, "사유 필요");
        Op op = cases.deleteCases(List.of(a, b, held), "중복 등록");
        assertTrue(op.ok, op.message);
        assertTrue(op.message.contains("2개"), op.message);
        assertNull(db.row("SELECT 1 AS X FROM Cases WHERE CaseId=?", a));
        assertNull(db.row("SELECT 1 AS X FROM Attachments WHERE CaseId=?", a));
        assertNull(db.row("SELECT 1 AS X FROM Actions WHERE CaseId=?", a));
        assertNotNull(db.row("SELECT 1 AS X FROM Cases WHERE CaseId=?", held), "보류 건은 남음");
        assertNotNull(db.row("SELECT 1 AS X FROM History WHERE CaseId=? AND Action='건삭제'", a), "이력에 남음");
        assertTrue(chain.verify());
    }

    @Test
    void libraryStoresReadsFindsAndDeletes() throws Exception {
        LibraryService lib = new LibraryService(db, files, reader);
        String cat = (String) lib.categories().get(0).get("Id");
        Op added = lib.add(cat, "", "보안", "", "정보보안 지침.txt",
            new java.io.ByteArrayInputStream("제1조 비밀번호는 분기마다 바꾼다.".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(added.ok, added.message);
        lib.awaitReads();
        Map<String, Object> d = lib.get(added.id);
        assertEquals("읽음", d.get("ReadState"));
        Map<String, Object> found = lib.list("", "비밀번호 분기", 1, 20);
        assertEquals(1, found.get("total"));
        assertEquals(0, lib.list("", "없는말", 1, 20).get("total"));
        java.nio.file.Path stored = lib.file(added.id);
        assertTrue(java.nio.file.Files.exists(stored));
        assertTrue(lib.delete(added.id).ok);
        assertEquals(0, lib.total());
        assertFalse(java.nio.file.Files.exists(stored));
    }

    @Test
    void librarySubCategoriesListUnderParentAndKeepOldCategories() throws Exception {
        // 1.7.0판 표(ParentId 없음)에 분류가 하나 있던 상태에서 올라오는 경우
        db.tx(conn -> {
            local.audit.Db.update(conn, "DROP TABLE LibCategories");
            local.audit.Db.update(conn, "CREATE TABLE LibCategories (Id TEXT PRIMARY KEY, Name TEXT NOT NULL, Ord INTEGER NOT NULL DEFAULT 0)");
            local.audit.Db.update(conn, "INSERT INTO LibCategories(Id, Name, Ord) VALUES ('old1','보고서',1)");
            return null;
        });
        LibraryService lib = new LibraryService(db, files, reader);
        assertEquals(1, lib.tree().size());
        assertEquals("보고서", lib.tree().get(0).get("Name"));
        assertTrue(lib.category("save", Map.of("name", "주간 보고", "parentId", "old1")).ok);
        assertFalse(lib.category("save", Map.of("name", "주간 보고", "parentId", "old1")).ok);
        String sub = (String) ((List<Map<String, Object>>) lib.tree().get(0).get("children")).get(0).get("Id");
        Op added = lib.add(sub, "주간 회의 결과", "", "", "a.txt", new java.io.ByteArrayInputStream("내용".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(added.ok, added.message);
        lib.awaitReads();
        assertEquals(1, lib.list("old1", "", 1, 20).get("total"));
        assertEquals("보고서 › 주간 보고", lib.get(added.id).get("Category"));
        assertEquals(1, ((Number) lib.tree().get(0).get("Docs")).intValue());
        assertFalse(lib.category("delete", Map.of("id", "old1")).ok);
    }

    @Test
    void librarySkipsSameFileTwice() throws Exception {
        LibraryService lib = new LibraryService(db, files, reader);
        String cat = (String) lib.categories().get(0).get("Id");
        byte[] data = "같은 내용".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Op a = lib.add(cat, "", "", "", "a.txt", new java.io.ByteArrayInputStream(data));
        assertTrue(a.ok);
        Op b = lib.add(cat, "", "", "", "다른이름.txt", new java.io.ByteArrayInputStream(data));
        assertFalse(b.ok);
        assertEquals(409, b.status);
        assertEquals(a.id, b.extra);
        assertEquals(1, lib.total());
    }

    @Test
    void linksJoinCaseToOtherMenusBothWays() throws Exception {
        LibraryService lib = new LibraryService(db, files, reader);
        local.audit.board.BoardService board = new local.audit.board.BoardService(db);
        local.audit.work.WorkService work = new local.audit.work.WorkService(new local.audit.work.WorkStore(db, settings), db);
        local.audit.rule.RuleService rules = new local.audit.rule.RuleService(new local.audit.rule.RuleStore(db));
        local.audit.tools.CardCheck card = new local.audit.tools.CardCheck(db, settings);
        LinkService links = new LinkService(db, chain, settings, rules, lib, work, card, board);
        Op c = cases.register(2026, cases.categories().get(0), "연결 시험 건", "영업1팀", "", "", "한 줄", "본문", null);
        assertTrue(c.ok, c.message);
        String cat = (String) lib.categories().get(0).get("Id");
        Op d = lib.add(cat, "출장 지침", "", "", "출장.txt", new java.io.ByteArrayInputStream("출장비".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        lib.awaitReads();
        // 화면에서는 이름(추천 목록의 글)으로 고른다
        String label = links.picks("doc").get(0).label();
        Op a = links.add(c.id, "doc", label);
        assertTrue(a.ok, a.message);
        assertEquals("이미 연결된 항목입니다.", links.add(c.id, "doc", d.id).message, "같은 항목은 한 번만");
        assertFalse(links.add(c.id, "doc", "없는 자료").ok);
        assertFalse(links.add(c.id, "nope", d.id).ok);
        assertEquals(1, links.ofCase(c.id).size());
        assertEquals("/library/" + d.id, links.ofCase(c.id).get(0).href());
        assertEquals(1, links.casesFor("doc", d.id).size(), "자료 쪽에서 감사 건이 보임");
        assertEquals(1, links.counts("doc").get(d.id));
        assertTrue(links.remove(c.id, "doc", d.id).ok);
        assertTrue(links.ofCase(c.id).isEmpty());
        assertNotNull(db.row("SELECT 1 AS X FROM History WHERE CaseId=? AND Action='연결'", c.id), "연결이 이력에 남음");
        assertTrue(chain.verify(), "이력 사슬이 그대로");
    }

    @Test
    void orgListGathersFromDataAndRemembersRemovals() throws Exception {
        local.audit.board.BoardService board = new local.audit.board.BoardService(db);
        local.audit.work.WorkService work = new local.audit.work.WorkService(new local.audit.work.WorkStore(db, settings), db);
        OrgService orgs = new OrgService(db, settings, board, work);
        cases.register(2026, cases.categories().get(0), "가", "영업1팀", "", "", "한 줄", "본문", null);
        cases.register(2026, cases.categories().get(0), "나", "보상팀", "", "", "한 줄", "본문", null);
        assertTrue(orgs.orgs().containsAll(List.of("영업1팀", "보상팀")), "자료의 이름이 저절로 들어옴");
        assertTrue(orgs.save("보상팀\n보상팀\n 영업 1팀 ").ok);
        assertEquals(List.of("보상팀", "영업 1팀"), orgs.orgs(), "같은 이름은 하나, 지운 '영업1팀'은 다시 안 붙음");
        assertTrue(orgs.similar().isEmpty());
        assertTrue(orgs.gather().ok);
        assertTrue(orgs.orgs().contains("영업1팀"), "모으기는 지운 이름까지 다시 모음");
        assertEquals(List.of(List.of("영업 1팀", "영업1팀")), orgs.similar(), "띄어쓰기만 다른 이름을 묶어 보여 줌");
        assertFalse(orgs.people().isEmpty());
    }
}
