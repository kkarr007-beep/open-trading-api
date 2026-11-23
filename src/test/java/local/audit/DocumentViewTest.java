package local.audit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.ControlType;
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlPicture;
import kr.dogfoot.hwplib.object.bodytext.control.gso.GsoControlType;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.docinfo.BinData;
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;
import local.audit.extract.HwpHtml;
import local.audit.extract.Rich;

/** 문서 '읽은 내용' 모양: 합친 칸, 표 안의 표, 표 안의 그림. */
class DocumentViewTest {
    @TempDir Path dir;

    static byte[] png(int w, int h) throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.ORANGE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "bmp", bo);
        return bo.toByteArray();
    }

    static Paragraph text(Paragraph p, String s) throws Exception {
        if (p.getText() == null) p.createText();
        p.getText().addString(s);
        if (p.getCharShape() == null) p.createCharShape();
        p.getCharShape().addParaCharShape(0, 0);
        p.getHeader().setParaShapeId(0);
        return p;
    }

    static Cell cell(Row r, int col, int row, int colSpan, int rowSpan) {
        Cell c = r.addNewCell();
        c.getListHeader().setColIndex(col);
        c.getListHeader().setRowIndex(row);
        c.getListHeader().setColSpan(colSpan);
        c.getListHeader().setRowSpan(rowSpan);
        return c;
    }

    @Test
    void hwpKeepsMergedCellsNestedTableAndPictureInCell() throws Exception {
        HWPFile doc = BlankFileMaker.make();
        Paragraph p = doc.getBodyText().getSectionList().get(0).getParagraph(0);
        if (p.getText() == null) p.createText();
        p.getText().addExtendCharForTable();
        if (p.getControlList() == null) p.createControlList();
        ControlTable t = (ControlTable) p.addNewControl(ControlType.Table);
        // 1행: [가로 2칸 합침] / 2행: [세로 2칸 합침][안쪽 표] / 3행: [그림]
        Row r1 = t.addNewRow();
        text(cell(r1, 0, 0, 2, 1).getParagraphList().addNewParagraph(), "머리 합침");
        Row r2 = t.addNewRow();
        text(cell(r2, 0, 1, 1, 2).getParagraphList().addNewParagraph(), "세로 합침");
        Paragraph inner = cell(r2, 1, 1, 1, 1).getParagraphList().addNewParagraph();
        text(inner, "안쪽 앞");
        inner.getText().addExtendCharForTable();
        if (inner.getControlList() == null) inner.createControlList();
        ControlTable t2 = (ControlTable) inner.addNewControl(ControlType.Table);
        text(cell(t2.addNewRow(), 0, 0, 1, 1).getParagraphList().addNewParagraph(), "안쪽 칸");
        Row r3 = t.addNewRow();
        Paragraph picPara = cell(r3, 1, 2, 1, 1).getParagraphList().addNewParagraph();
        picPara.createText();
        picPara.getText().addExtendCharForGSO();
        picPara.createControlList();
        ControlPicture pic = (ControlPicture) picPara.addNewGsoControl(GsoControlType.Picture);
        pic.getShapeComponentPicture().getPictureInfo().setBinItemID(1);
        BinData bd = doc.getDocInfo().addNewBinData();
        bd.setBinDataID(1);
        bd.setExtensionForEmbedding("bmp");
        doc.getBinData().addNewEmbeddedBinaryData("BIN0001.bmp", png(3000, 1500), null);

        String html = HwpHtml.convert(doc);
        assertNotNull(html);
        assertTrue(html.contains("<td colspan=\"2\">"), html);
        assertTrue(html.contains("<td rowspan=\"2\">"), html);
        assertTrue(html.matches("(?s).*<td>.*안쪽 앞.*<table>.*안쪽 칸.*</table>.*</td>.*"), html);
        assertTrue(html.contains("<img alt=\"그림\" src=\"data:image/jpeg;base64,"), "BMP 그림은 줄여서 JPEG로");
    }

    static void zip(Path file, Map<String, String> entries) throws Exception {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
    }

    @Test
    void docxVerticalAndHorizontalMergesAndNestedTable() throws Exception {
        String W = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";
        String tc = "<w:tc><w:tcPr>%s</w:tcPr><w:p><w:r><w:t>%s</w:t></w:r></w:p>%s</w:tc>";
        String nested = "<w:tbl><w:tr>" + tc.formatted("", "안쪽", "") + "</w:tr></w:tbl><w:p/>";
        String body = "<w:tbl>"
            + "<w:tr>" + tc.formatted("<w:gridSpan w:val=\"2\"/>", "가로 합침", "") + "</w:tr>"
            + "<w:tr>" + tc.formatted("<w:vMerge w:val=\"restart\"/>", "세로 합침", "") + tc.formatted("", "바깥", nested) + "</w:tr>"
            + "<w:tr>" + tc.formatted("<w:vMerge/>", "", "") + tc.formatted("", "셋째 줄", "") + "</w:tr>"
            + "</w:tbl>";
        Path f = dir.resolve("t.docx");
        zip(f, Map.of("word/document.xml", "<w:document " + W + "><w:body>" + body + "</w:body></w:document>"));
        String html = Rich.fromFile(f, ".docx");
        assertNotNull(html);
        assertTrue(html.contains("<td colspan=\"2\">"), html);
        assertTrue(html.contains("<td rowspan=\"2\">"), html);
        assertTrue(html.contains("<table><tr><td><div class=\"para\">안쪽</div></td></tr></table>"), html);
        assertFalse(html.matches("(?s).*<tr><td><div class=\"gap\"></div></td><td>.*셋째.*"), "합쳐진 빈 칸은 내지 않음: " + html);
    }

    @Test
    void hwpxCellSpanAfterContentIsKept() throws Exception {
        String hp = "xmlns:hp=\"http://www.hancom.co.kr/hwpml/2011/paragraph\"";
        String cell = "<hp:tc><hp:subList><hp:p><hp:run><hp:t>%s</hp:t></hp:run></hp:p></hp:subList><hp:cellAddr colAddr=\"0\" rowAddr=\"0\"/><hp:cellSpan colSpan=\"%d\" rowSpan=\"%d\"/></hp:tc>";
        String sec = "<hs:sec xmlns:hs=\"http://www.hancom.co.kr/hwpml/2011/section\" " + hp + "><hp:p><hp:run><hp:tbl>"
            + "<hp:tr>" + cell.formatted("합친 머리", 2, 1) + "</hp:tr>"
            + "<hp:tr>" + cell.formatted("세로", 1, 2) + cell.formatted("오른쪽", 1, 1) + "</hp:tr>"
            + "<hp:tr>" + cell.formatted("아래", 1, 1) + "</hp:tr>"
            + "</hp:tbl></hp:run></hp:p></hs:sec>";
        Path f = dir.resolve("t.hwpx");
        zip(f, Map.of("Contents/section0.xml", sec));
        String html = Rich.fromFile(f, ".hwpx");
        assertNotNull(html);
        assertTrue(html.contains("<td colspan=\"2\"><div class=\"para\">합친 머리</div></td>"), html);
        assertTrue(html.contains("<td rowspan=\"2\"><div class=\"para\">세로</div></td>"), html);
    }

    @Test
    void docxKeepsIndentSpacingAndTabs() throws Exception {
        String W = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";
        String body = "<w:p><w:pPr><w:tabs><w:tab w:val=\"left\" w:pos=\"2000\"/></w:tabs><w:spacing w:before=\"240\" w:after=\"120\"/>"
            + "<w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr><w:r><w:t>①</w:t></w:r><w:r><w:tab/><w:t>내어 쓴 항</w:t></w:r></w:p>";
        Path f = dir.resolve("ind.docx");
        zip(f, Map.of("word/document.xml", "<w:document " + W + "><w:body>" + body + "</w:body></w:document>"));
        String html = Rich.fromFile(f, ".docx");
        assertTrue(html.contains("padding-left:36.0pt;") && html.contains("text-indent:-18.0pt;"), "들여쓰기·내어쓰기: " + html);
        assertTrue(html.contains("margin-top:12.0pt;") && html.contains("margin-bottom:6.0pt;"), "문단 위아래 간격: " + html);
        assertTrue(html.contains("①\t내어 쓴 항"), "탭은 탭 글자로, 탭 위치 정의는 글로 넣지 않음: " + html);
        String kept = HtmlClean.clean(html);
        assertTrue(kept.contains("padding-left:36.0pt") && kept.contains("text-indent:-18.0pt") && kept.contains("margin-top:12.0pt") && kept.contains("\t"), "저장해도 남음: " + kept);
    }

    @Test
    void hwpxKeepsParagraphMargins() throws Exception {
        String hh = "xmlns:hh=\"http://www.hancom.co.kr/hwpml/2011/head\" xmlns:hc=\"http://www.hancom.co.kr/hwpml/2011/core\"";
        String head = "<hh:head " + hh + "><hh:refList><hh:paraProperties><hh:paraPr id=\"3\"><hh:align horizontal=\"JUSTIFY\"/><hh:margin>"
            + "<hc:intent value=\"-1500\" unit=\"HWPUNIT\"/><hc:left value=\"3000\" unit=\"HWPUNIT\"/><hc:right value=\"0\"/>"
            + "<hc:prev value=\"600\"/><hc:next value=\"0\"/></hh:margin></hh:paraPr></hh:paraProperties></hh:refList></hh:head>";
        String hp = "xmlns:hp=\"http://www.hancom.co.kr/hwpml/2011/paragraph\"";
        String sec = "<hs:sec xmlns:hs=\"http://www.hancom.co.kr/hwpml/2011/section\" " + hp + "><hp:p paraPrIDRef=\"3\"><hp:run><hp:t>가.</hp:t><hp:tab/><hp:t>목 내용</hp:t></hp:run></hp:p></hs:sec>";
        Path f = dir.resolve("m.hwpx");
        zip(f, Map.of("Contents/header.xml", head, "Contents/section0.xml", sec));
        String html = Rich.fromFile(f, ".hwpx");
        assertTrue(html.contains("text-align:justify;") && html.contains("padding-left:30.0pt;") && html.contains("text-indent:-15.0pt;") && html.contains("margin-top:6.0pt;"), html);
        assertTrue(html.contains("가.\t목 내용"), html);
    }

    static Path pdfWithTable(Path f, boolean lines) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            for (int pg = 0; pg < 2; pg++) {
                org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
                doc.addPage(page);
                var font = new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
                try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                    float x0 = 72, y0 = 700, w = 150, h = 24;
                    if (lines) {
                        cs.setLineWidth(0.8f);
                        for (int r = 0; r <= 3; r++) { cs.moveTo(x0, y0 - r * h); cs.lineTo(x0 + 2 * w, y0 - r * h); }
                        cs.moveTo(x0, y0); cs.lineTo(x0, y0 - 3 * h);
                        cs.moveTo(x0 + 2 * w, y0); cs.lineTo(x0 + 2 * w, y0 - 3 * h);
                        cs.moveTo(x0 + w, y0); cs.lineTo(x0 + w, y0 - 2 * h);          // 마지막 줄은 두 칸을 합침
                        cs.stroke();
                    }
                    String[][] cells = { { "Item", "Amount" }, { "Daily", "20000" } };
                    for (int r = 0; r < 2; r++) for (int c = 0; c < 2; c++) {
                        cs.beginText(); cs.setFont(font, 11); cs.newLineAtOffset(x0 + 6 + c * w, y0 - 16 - r * h); cs.showText(cells[r][c] + (pg == 1 ? " p2" : "")); cs.endText();
                    }
                    cs.beginText(); cs.setFont(font, 11); cs.newLineAtOffset(x0 + 6, y0 - 16 - 2 * h); cs.showText("Merged note"); cs.endText();
                    cs.beginText(); cs.setFont(font, 11); cs.newLineAtOffset(x0, y0 + 30); cs.showText("Article 1 before table"); cs.endText();
                    cs.beginText(); cs.setFont(font, 11); cs.newLineAtOffset(x0, y0 - 3 * h - 30); cs.showText("Article 2 after table"); cs.endText();
                }
            }
            doc.save(f.toFile());
        }
        return f;
    }

    @Test
    void pdfTablesFromRuledLines() throws Exception {
        String html = local.audit.extract.PdfTables.html(pdfWithTable(dir.resolve("t.pdf"), true));
        assertNotNull(html);
        assertTrue(html.contains("<td><div class=\"para\">Item</div></td><td><div class=\"para\">Amount</div></td>"), html);
        assertTrue(html.contains("<td><div class=\"para\">Daily</div></td><td><div class=\"para\">20000</div></td>"), html);
        assertTrue(html.contains("<td colspan=\"2\"><div class=\"para\">Merged note</div></td>"), "선 없는 경계는 합친 칸: " + html);
        assertTrue(html.indexOf("Article 1") < html.indexOf("<table>") && html.indexOf("Article 2") > html.indexOf("</table>"), "표 앞뒤 글 순서: " + html);
        assertTrue(html.contains("Item p2"), "둘째 쪽 표도: " + html);
    }

    @Test
    void pdfWithoutLinesStaysPlainText() throws Exception {
        assertTrue(local.audit.extract.PdfTables.html(pdfWithTable(dir.resolve("n.pdf"), false)) == null, "선 없는 PDF는 지금처럼 글만");
    }
}
