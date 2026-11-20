package local.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 원본 보기: PDF → 쪽 그림 + 글자 층, 맡기기·붙이기, 형식 제한. */
class OriginalViewTest {
    @TempDir Path dir;

    private Path pdf(int pages) throws Exception {
        Path f = dir.resolve("in.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= pages; i++) {
                PDPage pg = new PDPage(PDRectangle.A4);
                doc.addPage(pg);
                try (PDPageContentStream cs = new PDPageContentStream(doc, pg)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    cs.newLineAtOffset(72, 760);
                    cs.showText("Article " + i + " audit rule page");
                    cs.endText();
                }
            }
            doc.save(f.toFile());
        }
        return f;
    }

    private static Map<String, Object> waitDone(OriginalService o, String key) throws Exception {
        for (int i = 0; i < 200; i++) {
            Map<String, Object> r = o.info(key);
            if (r != null && !"만드는 중".equals(r.get("State"))) return r;
            Thread.sleep(50);
        }
        return o.info(key);
    }

    @Test
    void pdfBecomesPageImagesWithTextLayer() throws Exception {
        Paths2 paths = new Paths2(dir.resolve("home").toString());
        OriginalService o = new OriginalService(paths, new Db(paths));
        assertTrue(o.request("doc-abc123", pdf(2), "규정.pdf").ok);
        Map<String, Object> r = waitDone(o, "doc-abc123");
        assertEquals("완료", r.get("State"));
        assertEquals(2, ((Number) r.get("Pages")).intValue());
        assertNotNull(o.page("doc-abc123", 1));
        assertNotNull(o.page("doc-abc123", 2));
        assertNotNull(o.pdf("doc-abc123"));
        String text = o.textJson("doc-abc123");
        assertTrue(text.contains("Article 2 audit rule page"), text);
        assertTrue(text.contains("\"x\":12."), text); // 72pt / 595pt ≈ 12.1%
        o.delete("doc-abc123");
        assertFalse(Files.exists(paths.home.resolve("originals/doc-abc123")));
    }

    @Test
    void stashThenAdoptForRule() throws Exception {
        Paths2 paths = new Paths2(dir.resolve("home").toString());
        OriginalService o = new OriginalService(paths, new Db(paths));
        String token = o.stash(pdf(1), "사규.pdf");
        assertTrue(Txt.isId(token));
        assertTrue(o.adopt(token, "rule-r_abc").ok);
        assertEquals("완료", waitDone(o, "rule-r_abc").get("State"));
        assertFalse(o.adopt(token, "rule-r_abc").ok, "한 번 붙인 표는 다시 못 씀");
    }

    @Test
    void limitsFormatsAndKeys() throws Exception {
        Paths2 paths = new Paths2(dir.resolve("home").toString());
        OriginalService o = new OriginalService(paths, new Db(paths));
        assertTrue(OriginalService.supports("a.HWP") && OriginalService.supports("b.xlsx") && OriginalService.supports("c.pdf"));
        assertFalse(OriginalService.supports("d.zip") || OriginalService.supports("e.txt"));
        assertFalse(OriginalService.validKey("doc-../x") || OriginalService.validKey("x-abc"));
        Path z = Files.writeString(dir.resolve("a.zip"), "x");
        assertFalse(o.request("doc-abc123", z, "a.zip").ok);
        // 한글 파일인데 변환 프로그램이 없으면 이유를 남기고 '실패'
        Path h = Files.writeString(dir.resolve("a.hwp"), "x");
        if (OriginalService.sofficePath() == null && !System.getProperty("os.name").toLowerCase().contains("win")) {
            assertTrue(o.request("doc-hwp123", h, "a.hwp").ok);
            Map<String, Object> r = waitDone(o, "doc-hwp123");
            assertEquals("실패", r.get("State"));
            assertTrue(String.valueOf(r.get("Note")).contains("LibreOffice"), String.valueOf(r.get("Note")));
        }
    }
}
