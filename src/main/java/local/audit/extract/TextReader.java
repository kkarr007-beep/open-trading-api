package local.audit.extract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import local.audit.Settings;
import local.audit.Txt;

/**
 * 파일에서 글자를 읽어 낸다.
 * 1) 평문 파일은 이 프로그램이 직접 읽는다 (docx, xlsx, pptx, hwpx, hwp, doc, xls, ppt, pdf, txt, csv …).
 * 2) 문서보안(DRM)으로 암호화된 파일은 직접 풀지 않는다. 포털에 로그인된 이 컴퓨터의
 *    워드·엑셀·파워포인트·한글에게 열게 하고, 그 프로그램이 보여 주는 글자만 받아 온다.
 */
@Component
public class TextReader {
    private static final Logger log = LoggerFactory.getLogger(TextReader.class);
    /** 저장하는 글자 수 한도. */
    public static final int MAX_CHARS = 400_000;
    private static final long MAX_DIRECT_BYTES = 150L * 1024 * 1024;

    public record Result(String state, String method, String note, String text, boolean encrypted, String html) {
        public Result(String state, String method, String note, String text, boolean encrypted) { this(state, method, note, text, encrypted, null); }
        public boolean ok() { return "읽음".equals(state); }
    }

    private final OfficeBridge office;
    private final Settings settings;

    public TextReader(OfficeBridge office, Settings settings) {
        this.office = office;
        this.settings = settings;
    }

    public Result read(Path file, String name) {
        String ext = Txt.ext(name);
        boolean encrypted = false;
        try {
            if (!Files.isRegularFile(file)) return new Result("실패", "", "파일이 없습니다.", "", false);
            byte[] head = head(file, 512);
            encrypted = looksEncrypted(head, ext);
            if (!encrypted) {
                if (Files.size(file) > MAX_DIRECT_BYTES) return new Result("미지원", "", "파일이 커서 내용을 읽지 않았습니다.", "", false);
                String text = direct(file, ext, head);
                if (text == null) return new Result("미지원", "", "내용을 읽지 않는 형식입니다.", "", false);
                text = tidy(text);
                if (text.isBlank()) return new Result("읽음", "직접", "글자가 없는 문서입니다(스캔본일 수 있음).", "", false);
                if (".pdf".equals(ext)) {
                    String[] how = new String[1];
                    String html = pdfHtml(file, how);
                    return new Result("읽음", how[0] == null ? "직접" : how[0], "", text, false, html);
                }
                return new Result("읽음", "직접", "", text, false, Rich.fromFile(file, ext));
            }
        } catch (Exception e) {
            log.info("직접 읽기 실패 {}: {}", name, e.toString());
            if (!encrypted) {
                // 형식이 어긋난 파일. 사무용 프로그램에게 한 번 더 맡겨 본다.
                Result viaApp = viaOffice(file, ext, false);
                if (viaApp != null && viaApp.ok()) return viaApp;
                return new Result("실패", "", "파일을 읽지 못했습니다: " + brief(e), "", false);
            }
        }
        Result viaApp = viaOffice(file, ext, true);
        if (viaApp != null) return viaApp;
        return new Result("암호문", "", "문서보안으로 암호화된 파일입니다. " + office.unavailableReason(ext), "", true);
    }

    private Result viaOffice(Path file, String ext, boolean encrypted) {
        if (!settings.office() || !office.supports(ext)) return null;
        OfficeBridge.Outcome out = office.read(file, ext);
        if (out.ok()) {
            String text = tidy(out.text());
            // 인가 프로그램이 암호문을 그대로 돌려준 경우는 읽은 것으로 치지 않는다.
            if (looksLikeCipherText(text))
                return new Result("암호문", out.app(), "포털 로그인이 끊겼거나 열람 권한이 없어 내용이 풀리지 않았습니다.", "", true);
            return new Result("읽음", out.app(), encrypted ? "암호화 문서를 " + out.app() + "(으)로 열어 읽었습니다." : "", text, encrypted);
        }
        return new Result(encrypted ? "암호문" : "실패", out.app(), out.error(), "", encrypted);
    }

    static byte[] head(Path file, int n) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(n);
        }
    }

    /** 확장자가 말하는 형식과 실제 첫머리가 다르면 암호화된 것으로 본다. */
    public static boolean looksEncrypted(byte[] head, String ext) {
        String ascii = new String(head, StandardCharsets.ISO_8859_1);
        if (ascii.contains("NASCA DRM") || ascii.contains("<## NASCA") || ascii.contains("DRMONE")
            || ascii.contains("FasooSecureContainer") || ascii.contains("SCDSA00") || ascii.contains("DOCUMENTSAFER"))
            return true;
        boolean zip = head.length >= 4 && head[0] == 'P' && head[1] == 'K';
        boolean ole = head.length >= 8 && (head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF && (head[2] & 0xFF) == 0x11 && (head[3] & 0xFF) == 0xE0;
        boolean pdf = ascii.contains("%PDF-");
        return switch (ext) {
            case ".docx", ".xlsx", ".pptx", ".hwpx" -> !zip && !ole; // 암호가 걸린 오피스 문서는 OLE 그릇에 담긴다
            case ".doc", ".xls", ".ppt", ".hwp" -> !ole && !zip && !ascii.startsWith("{\\rtf") && !ascii.stripLeading().startsWith("<");
            case ".pdf" -> !pdf;
            default -> false;
        };
    }

    static boolean looksLikeCipherText(String text) {
        if (text.length() < 40) return false;
        String start = text.substring(0, Math.min(200, text.length()));
        if (start.contains("NASCA DRM") || start.contains("DRMONE") || start.contains("FasooSecureContainer")) return true;
        int odd = 0;
        int n = Math.min(2000, text.length());
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '�' || (c < 0x20 && c != '\n' && c != '\t') || (c >= 0xE000 && c <= 0xF8FF)) odd++;
        }
        return odd > n / 5;
    }

    private String direct(Path file, String ext, byte[] head) throws Exception {
        boolean zip = head.length >= 2 && head[0] == 'P' && head[1] == 'K';
        switch (ext) {
            case ".docx": return zip ? zipXml(file, List.of("word/document.xml"), "word/(footnotes|endnotes)\\.xml") : poi(file);
            case ".pptx": return zip ? zipXml(file, List.of(), "ppt/slides/slide\\d+\\.xml") : poi(file);
            case ".hwpx": return zipXml(file, List.of(), "Contents/section\\d+\\.xml");
            case ".xlsx": return zip ? xlsx(file) : poi(file);
            case ".doc": case ".xls": case ".ppt":
                if (zip) return direct(file, ext + "x", head);
                if (new String(head, StandardCharsets.ISO_8859_1).startsWith("{\\rtf")) return null;
                return poi(file);
            case ".hwp": return hwp(file);
            case ".pdf": return pdf(file);
            case ".txt": case ".csv": case ".tsv": case ".md": case ".log": case ".json": case ".eml":
                return decode(Files.readAllBytes(file));
            case ".htm": case ".html": case ".xml":
                return decode(Files.readAllBytes(file)).replaceAll("(?s)<(script|style)[^>]*>.*?</\\1>", " ")
                    .replaceAll("(?i)<br\\s*/?>|</p>|</tr>|</div>|</li>|</h\\d>", "\n").replaceAll("<[^>]+>", " ")
                    .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
            default: return null;
        }
    }

    public static String decode(byte[] bytes) {
        int skip = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE)
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, skip, bytes.length - skip)).toString();
        } catch (CharacterCodingException e) {
            for (String name : new String[] { "x-windows-949", "EUC-KR" }) {
                try {
                    return new String(bytes, Charset.forName(name));
                } catch (RuntimeException unsupported) {
                    // 이 런타임에 없는 문자표면 다음 것으로
                }
            }
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    private static XMLInputFactory xmlFactory() {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f;
    }

    /** docx·pptx·hwpx는 문단(p), 글자(t), 표 칸(tc), 표 줄(tr)의 이름이 같다. */
    static String zipXml(Path file, List<String> exact, String pattern) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            TreeMap<String, ZipEntry> picked = new TreeMap<>(TextReader::naturalCompare);
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
                ZipEntry entry = e.nextElement();
                if (exact.contains(entry.getName()) || entry.getName().matches(pattern)) picked.put(entry.getName(), entry);
            }
            for (ZipEntry entry : picked.values()) {
                try (InputStream in = zip.getInputStream(entry)) {
                    flow(in, sb);
                }
                sb.append('\n');
                if (sb.length() > MAX_CHARS) break;
            }
        }
        return sb.toString();
    }

    static int naturalCompare(String a, String b) {
        String da = a.replaceAll("\\D", ""), dbb = b.replaceAll("\\D", "");
        if (!da.isEmpty() && !dbb.isEmpty() && a.replaceAll("\\d", "").equals(b.replaceAll("\\d", ""))) {
            int c = Long.compare(Long.parseLong(da), Long.parseLong(dbb));
            if (c != 0) return c;
        }
        return a.compareTo(b);
    }

    private static void flow(InputStream in, StringBuilder sb) throws Exception {
        XMLStreamReader xr = xmlFactory().createXMLStreamReader(in);
        int cellDepth = 0;
        boolean inText = false;
        while (xr.hasNext()) {
            int ev = xr.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String n = xr.getLocalName();
                if (n.equals("t")) inText = true;
                else if (n.equals("tc")) cellDepth++;
                else if (n.equals("tab")) sb.append(cellDepth > 0 ? ' ' : '\t');
                else if (n.equals("br") || n.equals("lineBreak")) sb.append(cellDepth > 0 ? ' ' : '\n');
            } else if (ev == XMLStreamConstants.CHARACTERS && inText) {
                sb.append(xr.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                String n = xr.getLocalName();
                if (n.equals("t")) inText = false;
                else if (n.equals("p")) sb.append(cellDepth > 0 ? ' ' : '\n');
                else if (n.equals("tc")) { cellDepth--; sb.append('\t'); }
                else if (n.equals("tr")) sb.append('\n');
            }
            if (sb.length() > MAX_CHARS) break;
        }
        xr.close();
    }

    /** 엑셀. 한 줄이 한 행, 칸은 탭으로 나눈다. 날짜 서식의 숫자는 날짜로 바꾼다. */
    public static String xlsx(Path file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            List<String> shared = new ArrayList<>();
            ZipEntry ss = zip.getEntry("xl/sharedStrings.xml");
            if (ss != null) {
                try (InputStream in = zip.getInputStream(ss)) {
                    XMLStreamReader xr = xmlFactory().createXMLStreamReader(in);
                    StringBuilder cur = null;
                    boolean inT = false, phonetic = false;
                    while (xr.hasNext()) {
                        int ev = xr.next();
                        if (ev == XMLStreamConstants.START_ELEMENT) {
                            String n = xr.getLocalName();
                            if (n.equals("si")) cur = new StringBuilder();
                            else if (n.equals("rPh")) phonetic = true;
                            else if (n.equals("t")) inT = true;
                        } else if (ev == XMLStreamConstants.CHARACTERS && inT && !phonetic && cur != null) {
                            cur.append(xr.getText());
                        } else if (ev == XMLStreamConstants.END_ELEMENT) {
                            String n = xr.getLocalName();
                            if (n.equals("si") && cur != null) { shared.add(cur.toString()); cur = null; }
                            else if (n.equals("rPh")) phonetic = false;
                            else if (n.equals("t")) inT = false;
                        }
                    }
                    xr.close();
                }
            }
            Set<Integer> dateStyles = dateStyles(zip);
            TreeMap<String, ZipEntry> sheets = new TreeMap<>(TextReader::naturalCompare);
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
                ZipEntry entry = e.nextElement();
                if (entry.getName().matches("xl/worksheets/sheet\\d+\\.xml")) sheets.put(entry.getName(), entry);
            }
            for (ZipEntry entry : sheets.values()) {
                try (InputStream in = zip.getInputStream(entry)) {
                    XMLStreamReader xr = xmlFactory().createXMLStreamReader(in);
                    int col = 0, cellCol = 0, style = -1;
                    String type = "";
                    StringBuilder val = null;
                    boolean rowHasText = false;
                    StringBuilder line = new StringBuilder();
                    while (xr.hasNext()) {
                        int ev = xr.next();
                        if (ev == XMLStreamConstants.START_ELEMENT) {
                            String n = xr.getLocalName();
                            if (n.equals("row")) { col = 0; line.setLength(0); rowHasText = false; }
                            else if (n.equals("c")) {
                                type = Txt.str(xr.getAttributeValue(null, "t"));
                                Integer s = Txt.intOrNull(xr.getAttributeValue(null, "s"));
                                style = s == null ? -1 : s;
                                String ref = xr.getAttributeValue(null, "r");
                                cellCol = ref == null ? col : colIndex(ref);
                            } else if (n.equals("v") || n.equals("t")) val = new StringBuilder();
                        } else if (ev == XMLStreamConstants.CHARACTERS && val != null) {
                            val.append(xr.getText());
                        } else if (ev == XMLStreamConstants.END_ELEMENT) {
                            String n = xr.getLocalName();
                            if ((n.equals("v") || n.equals("t")) && val != null) {
                                String raw = val.toString();
                                val = null;
                                String text = raw;
                                if (type.equals("s")) {
                                    Integer idx = Txt.intOrNull(raw);
                                    text = idx != null && idx >= 0 && idx < shared.size() ? shared.get(idx) : "";
                                } else if (type.isEmpty() || type.equals("n")) {
                                    text = number(raw, dateStyles.contains(style));
                                }
                                while (col < cellCol && col < 200) { line.append('\t'); col++; }
                                line.append(text.replace('\n', ' ').replace('\t', ' '));
                                if (!text.isBlank()) rowHasText = true;
                            } else if (n.equals("row")) {
                                if (rowHasText) sb.append(line).append('\n');
                            }
                        }
                        if (sb.length() > MAX_CHARS) break;
                    }
                    xr.close();
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    static int colIndex(String ref) {
        int n = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c < 'A' || c > 'Z') break;
            n = n * 26 + (c - 'A' + 1);
        }
        return Math.max(0, n - 1);
    }

    static String number(String raw, boolean date) {
        try {
            double d = Double.parseDouble(raw);
            if (date && d >= 1 && d < 2958466) return LocalDate.of(1899, 12, 30).plusDays((long) Math.floor(d)).toString();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
            return raw;
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static Set<Integer> dateStyles(ZipFile zip) throws Exception {
        Set<Integer> result = new HashSet<>();
        ZipEntry entry = zip.getEntry("xl/styles.xml");
        if (entry == null) return result;
        Set<Integer> dateFormats = new HashSet<>();
        for (int i = 14; i <= 22; i++) dateFormats.add(i);
        for (int i = 27; i <= 36; i++) dateFormats.add(i);
        for (int i = 45; i <= 47; i++) dateFormats.add(i);
        for (int i = 50; i <= 58; i++) dateFormats.add(i);
        try (InputStream in = zip.getInputStream(entry)) {
            XMLStreamReader xr = xmlFactory().createXMLStreamReader(in);
            boolean inCellXfs = false;
            int index = 0;
            while (xr.hasNext()) {
                int ev = xr.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String n = xr.getLocalName();
                    if (n.equals("numFmt")) {
                        Integer id = Txt.intOrNull(xr.getAttributeValue(null, "numFmtId"));
                        String code = Txt.str(xr.getAttributeValue(null, "formatCode")).replaceAll("\"[^\"]*\"|\\[[^\\]]*\\]|\\\\.", "").toLowerCase();
                        if (id != null && (code.contains("y") || code.contains("d") || code.contains("년") || code.contains("월"))) dateFormats.add(id);
                    } else if (n.equals("cellXfs")) { inCellXfs = true; index = 0; }
                    else if (n.equals("xf") && inCellXfs) {
                        Integer id = Txt.intOrNull(xr.getAttributeValue(null, "numFmtId"));
                        if (id != null && dateFormats.contains(id)) result.add(index);
                        index++;
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT && xr.getLocalName().equals("cellXfs")) {
                    inCellXfs = false;
                }
            }
            xr.close();
        }
        return result;
    }

    private static String poi(Path file) throws Exception {
        try (org.apache.poi.extractor.POITextExtractor ex = org.apache.poi.extractor.ExtractorFactory.createExtractor(file.toFile())) {
            return ex.getText();
        }
    }

    private static String hwp(Path file) throws Exception {
        kr.dogfoot.hwplib.object.HWPFile doc = kr.dogfoot.hwplib.reader.HWPReader.fromFile(file.toString());
        return kr.dogfoot.hwplib.tool.textextractor.TextExtractor.extract(doc,
            kr.dogfoot.hwplib.tool.textextractor.TextExtractMethod.InsertControlTextBetweenParagraphText);
    }

    static final int PDF_WORD_MAX_PAGES = 80;

    /**
     * PDF의 표를 살린 모양(HTML). PDF에는 표 구조가 없으므로
     * 1) 이 컴퓨터의 MS 워드가 PDF를 워드 문서로 바꾼 것을 읽고(표·합친 칸·들여쓰기가 가장 잘 남음),
     * 2) 워드가 없거나 실패하면 테두리 선과 글자 위치로 표를 직접 찾는다.
     * 둘 다 표를 못 찾으면 null(지금처럼 글만).
     */
    String pdfHtml(Path file, String[] how) {
        int pages = 0;
        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(file.toFile())) { pages = doc.getNumberOfPages(); }
        catch (Exception ignored) { }
        if (settings.office() && office.available() && pages > 0 && pages <= PDF_WORD_MAX_PAGES) {
            Path docx = office.pdfToDocx(file);
            if (docx != null) {
                try {
                    String h = Rich.fromFile(docx, ".docx");
                    if (h != null && !h.isBlank()) { how[0] = "직접·워드 표"; return h; }
                } finally {
                    try { Files.deleteIfExists(docx); } catch (IOException ignored) { }
                }
            }
        }
        String h = PdfTables.html(file);
        if (h != null) how[0] = "직접·표 찾기";
        return h;
    }

    private static String pdf(Path file) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(file.toFile())) {
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setEndPage(300);
            return stripper.getText(doc);
        }
    }

    public static String tidy(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(text.length(), MAX_CHARS));
        int blank = 0;
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String s = line.replaceAll("[\\p{Cntrl}&&[^\t]]", "").replace(' ', ' ').replaceAll("[ \\u3000]+$", "");
            if (s.isBlank()) {
                if (++blank > 1) continue;
                s = "";
            } else blank = 0;
            sb.append(s).append('\n');
            if (sb.length() >= MAX_CHARS) break;
        }
        String out = sb.toString().strip();
        return out.length() > MAX_CHARS ? out.substring(0, MAX_CHARS) : out;
    }

    private static String brief(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : (m.length() > 120 ? m.substring(0, 120) : m);
    }
}
