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
}
