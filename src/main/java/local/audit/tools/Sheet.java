package local.audit.tools;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/**
 * 표 파일(xlsx · xls · csv)이나 붙여 넣은 표를 '행 → 칸 글자' 목록으로 읽는다.
 * xlsx는 압축 안의 XML을 직접 읽는다(추가 라이브러리 없이). 날짜·시간 숫자는 그대로 두고, 읽는 쪽에서 바꾼다.
 */
public final class Sheet {
    public static final int MAX_ROWS = 100_000;
    public static final int MAX_COLS = 80;

    /** 시트 이름과 행들. */
    public record Table(String name, List<List<String>> rows) { }

    private Sheet() { }

    public static List<Table> read(String fileName, byte[] bytes) throws IOException {
        String n = fileName == null ? "" : fileName.toLowerCase();
        if (n.endsWith(".xlsx") || n.endsWith(".xlsm") || isZip(bytes)) return xlsx(bytes);
        if (n.endsWith(".xls")) return xls(bytes);
        return List.of(new Table("표", text(decode(bytes))));
    }

    static boolean isZip(byte[] b) { return b.length > 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4; }

    /** UTF-8로 읽히지 않으면 한글 윈도 인코딩(MS949)으로 읽는다. */
    public static String decode(byte[] bytes) {
        int start = bytes.length >= 3 && (bytes[0] & 0xff) == 0xEF && (bytes[1] & 0xff) == 0xBB && (bytes[2] & 0xff) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, start, bytes.length - start)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("MS949"));
        }
    }

    /** 탭 또는 쉼표로 나뉜 글. 따옴표 안의 줄바꿈·구분자는 그대로 둔다. */
    public static List<List<String>> text(String s) {
        String first = s.lines().findFirst().orElse("");
        char sep = first.chars().filter(c -> c == '\t').count() >= first.chars().filter(c -> c == ',').count() && first.indexOf('\t') >= 0 ? '\t' : ',';
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') { cell.append('"'); i++; } else quoted = false;
                } else cell.append(c);
            } else if (c == '"' && cell.length() == 0) quoted = true;
            else if (c == sep) { row.add(cell.toString()); cell.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0);
                rows.add(row); row = new ArrayList<>();
                if (rows.size() >= MAX_ROWS) return rows;
            } else cell.append(c);
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
        return rows;
    }

    static List<Table> xls(byte[] bytes) throws IOException {
        List<Table> out = new ArrayList<>();
        try (org.apache.poi.hssf.usermodel.HSSFWorkbook wb = new org.apache.poi.hssf.usermodel.HSSFWorkbook(new ByteArrayInputStream(bytes))) {
            org.apache.poi.ss.usermodel.DataFormatter fmt = new org.apache.poi.ss.usermodel.DataFormatter();
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                org.apache.poi.ss.usermodel.Sheet sh = wb.getSheetAt(s);
                List<List<String>> rows = new ArrayList<>();
                for (int r = 0; r <= sh.getLastRowNum() && rows.size() < MAX_ROWS; r++) {
                    org.apache.poi.ss.usermodel.Row row = sh.getRow(r);
                    List<String> cells = new ArrayList<>();
                    if (row != null) {
                        for (int c = 0; c < Math.min(row.getLastCellNum(), MAX_COLS); c++) {
                            org.apache.poi.ss.usermodel.Cell cell = row.getCell(c);
                            if (cell == null) { cells.add(""); continue; }
                            if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
                                && org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                                cells.add(num(cell.getNumericCellValue()));
                            } else cells.add(fmt.formatCellValue(cell));
                        }
                    }
                    rows.add(cells);
                }
                out.add(new Table(sh.getSheetName(), rows));
            }
        }
        return out;
    }

    static String num(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return Double.toString(d);
    }

    // ---- xlsx ----

    static List<Table> xlsx(byte[] bytes) throws IOException {
        Map<String, byte[]> parts = new HashMap<>();
        long total = 0;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (!name.startsWith("xl/") || !name.endsWith(".xml") && !name.endsWith(".rels")) continue;
                byte[] data = zin.readNBytes(200_000_000);
                total += data.length;
                if (total > 600_000_000L) throw new IOException("표 파일이 너무 큽니다.");
                parts.put(name, data);
            }
        }
        if (!parts.containsKey("xl/workbook.xml")) throw new IOException("엑셀(xlsx) 파일이 아닙니다.");
        List<String> shared = sharedStrings(parts.get("xl/sharedStrings.xml"));
        Map<String, String> rels = rels(parts.get("xl/_rels/workbook.xml.rels"));
        List<Table> out = new ArrayList<>();
        for (String[] sh : sheets(parts.get("xl/workbook.xml"))) {
            String target = rels.get(sh[1]);
            if (target == null) continue;
            String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
            byte[] data = parts.get(path);
            if (data != null) out.add(new Table(sh[0], sheetRows(data, shared)));
        }
        return out;
    }

    static XMLStreamReader xml(byte[] data) throws IOException {
        try {
            XMLInputFactory f = XMLInputFactory.newFactory();
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            InputStream in = new ByteArrayInputStream(data);
            return f.createXMLStreamReader(in);
        } catch (Exception e) {
            throw new IOException("엑셀 내용을 읽지 못했습니다.", e);
        }
    }

    static List<String> sharedStrings(byte[] data) throws IOException {
        List<String> out = new ArrayList<>();
        if (data == null) return out;
        try {
            XMLStreamReader r = xml(data);
            StringBuilder cur = null;
            boolean inT = false, inRph = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String n = r.getLocalName();
                    if ("si".equals(n)) cur = new StringBuilder();
                    else if ("rPh".equals(n)) inRph = true;
                    else if ("t".equals(n)) inT = true;
                } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                    if (inT && !inRph && cur != null) cur.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String n = r.getLocalName();
                    if ("t".equals(n)) inT = false;
                    else if ("rPh".equals(n)) inRph = false;
                    else if ("si".equals(n) && cur != null) { out.add(cur.toString()); cur = null; }
                }
            }
        } catch (javax.xml.stream.XMLStreamException e) {
            throw new IOException("엑셀 글자 표를 읽지 못했습니다.", e);
        }
        return out;
    }

    static Map<String, String> rels(byte[] data) throws IOException {
        Map<String, String> out = new HashMap<>();
        if (data == null) return out;
        try {
            XMLStreamReader r = xml(data);
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && "Relationship".equals(r.getLocalName()))
                    out.put(r.getAttributeValue(null, "Id"), r.getAttributeValue(null, "Target"));
            }
        } catch (javax.xml.stream.XMLStreamException e) {
            throw new IOException("엑셀 구성을 읽지 못했습니다.", e);
        }
        return out;
    }

    static List<String[]> sheets(byte[] data) throws IOException {
        List<String[]> out = new ArrayList<>();
        try {
            XMLStreamReader r = xml(data);
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && "sheet".equals(r.getLocalName())) {
                    String rid = null;
                    for (int i = 0; i < r.getAttributeCount(); i++) if ("id".equals(r.getAttributeLocalName(i))) rid = r.getAttributeValue(i);
                    out.add(new String[] { r.getAttributeValue(null, "name"), rid });
                }
            }
        } catch (javax.xml.stream.XMLStreamException e) {
            throw new IOException("엑셀 시트 목록을 읽지 못했습니다.", e);
        }
        return out;
    }

    /** 'BC12' → 열 번호(0부터). */
    static int col(String ref) {
        int c = 0;
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch < 'A' || ch > 'Z') break;
            c = c * 26 + (ch - 'A' + 1);
        }
        return c - 1;
    }

    static List<List<String>> sheetRows(byte[] data, List<String> shared) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try {
            XMLStreamReader r = xml(data);
            List<String> row = null;
            int rowNo = 0, colNo = -1;
            String type = null;
            StringBuilder val = new StringBuilder();
            boolean inV = false, inT = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "row" -> {
                            String rn = r.getAttributeValue(null, "r");
                            int target = rn == null ? rowNo + 1 : Integer.parseInt(rn);
                            while (rows.size() < target - 1 && rows.size() < MAX_ROWS) rows.add(new ArrayList<>());
                            row = new ArrayList<>();
                            rowNo = target;
                            colNo = -1;
                        }
                        case "c" -> {
                            String ref = r.getAttributeValue(null, "r");
                            colNo = ref == null ? colNo + 1 : col(ref);
                            type = r.getAttributeValue(null, "t");
                            val.setLength(0);
                        }
                        case "v" -> inV = true;
                        case "t" -> inT = true;
                        default -> { }
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                    if (inV || inT) val.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "v" -> inV = false;
                        case "t" -> inT = false;
                        case "c" -> {
                            if (row != null && colNo >= 0 && colNo < MAX_COLS) {
                                String v = val.toString();
                                if ("s".equals(type)) {
                                    try { v = shared.get(Integer.parseInt(v.trim())); } catch (RuntimeException e) { v = ""; }
                                } else if ("b".equals(type)) v = "1".equals(v) ? "TRUE" : "FALSE";
                                else if (type == null || "n".equals(type)) v = tidyNumber(v);
                                while (row.size() < colNo) row.add("");
                                row.add(v);
                            }
                        }
                        case "row" -> {
                            if (row != null && rows.size() < MAX_ROWS) rows.add(row);
                            row = null;
                        }
                        default -> { }
                    }
                }
            }
        } catch (javax.xml.stream.XMLStreamException | NumberFormatException e) {
            throw new IOException("엑셀 시트를 읽지 못했습니다.", e);
        }
        return rows;
    }

    /** '45971' '1.5E5' '0.4375' 같은 숫자 글자를 보기 좋게. */
    static String tidyNumber(String v) {
        if (v == null || v.isBlank()) return "";
        try {
            double d = Double.parseDouble(v.trim());
            return num(d);
        } catch (NumberFormatException e) {
            return v;
        }
    }
}
