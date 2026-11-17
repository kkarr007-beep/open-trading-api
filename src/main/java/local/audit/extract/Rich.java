package local.audit.extract;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/**
 * 문서를 화면에 보여 줄 모양(HTML)으로 바꾼다. 표는 표로, 그림은 그림으로, 문단·굵게·글자 크기·정렬·자간을 옮긴다.
 * 원문의 HTML을 그대로 쓰지 않고 여기서 새로 지으므로 스크립트가 섞여 들어오지 않는다.
 * 쪽 나눔, 글꼴, 장평, 그림 위치 같은 것은 옮기지 않는다. 원문 그대로는 '열기'로 본다.
 */
public final class Rich {
    private Rich() { }

    private static final int MAX_HTML = 6_000_000;
    /** 그림 한 장의 원본 한도. 넘거나 브라우저가 못 여는 형식이면 줄여서 다시 담는다. */
    private static final int MAX_IMAGE = 30_000_000;
    /** 화면에 담는 그림 전체 한도(줄인 뒤 기준). */
    private static final int MAX_IMAGES_TOTAL = 24_000_000;
    private static final int IMAGE_EDGE = 1400;

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> { if (c >= 0x20 || c == '\t') sb.append(c); }
            }
        }
        return sb.toString();
    }

    /** 글자만 있을 때: 탭으로 나뉜 줄이 이어지면 표로, 나머지는 문단으로. */
    public static String fromText(String text) {
        if (text == null || text.isBlank()) return "";
        StringBuilder sb = new StringBuilder(text.length() + 256);
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        int i = 0;
        while (i < lines.length && sb.length() < MAX_HTML) {
            String line = lines[i];
            if (line.contains("\t")) {
                int j = i, cols = 0;
                while (j < lines.length && lines[j].contains("\t")) { cols = Math.max(cols, lines[j].split("\t", -1).length); j++; }
                if (j - i >= 2 || cols >= 3) {
                    sb.append("<div class=\"tw\"><table>");
                    for (int k = i; k < j; k++) {
                        String[] cells = lines[k].split("\t", -1);
                        sb.append("<tr>");
                        for (int c = 0; c < cols; c++) sb.append(k == i ? "<th>" : "<td>").append(esc(c < cells.length ? cells[c].strip() : "")).append(k == i ? "</th>" : "</td>");
                        sb.append("</tr>");
                    }
                    sb.append("</table></div>");
                    i = j;
                    continue;
                }
            }
            if (line.isBlank()) sb.append("<div class=\"gap\"></div>");
            else sb.append("<div class=\"para\">").append(esc(line.replace('\t', ' '))).append("</div>");
            i++;
        }
        return sb.toString();
    }

    /** 평문 docx, pptx, hwpx, hwp만. 다른 형식이나 실패는 null을 돌려주고, 부르는 쪽이 fromText를 쓴다. */
    public static String fromFile(Path file, String ext) {
        if (".hwp".equals(ext)) return HwpHtml.convert(file);
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Walker w = new Walker(zip);
            switch (ext) {
                case ".docx" -> w.run("word/document.xml", "word/_rels/document.xml.rels", "word/", Mode.DOCX);
                case ".pptx" -> {
                    TreeMap<String, ZipEntry> slides = new TreeMap<>(TextReader::naturalCompare);
                    for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
                        ZipEntry en = e.nextElement();
                        if (en.getName().matches("ppt/slides/slide\\d+\\.xml")) slides.put(en.getName(), en);
                    }
                    int n = 0;
                    for (String name : slides.keySet()) {
                        w.root.append("<section class=\"slide\"><div class=\"slide-no\">").append(++n).append("</div>");
                        w.run(name, name.replace("ppt/slides/", "ppt/slides/_rels/") + ".rels", "ppt/slides/", Mode.PPTX);
                        w.root.append("</section>");
                    }
                }
                case ".hwpx" -> {
                    w.hwpxHeader();
                    TreeMap<String, ZipEntry> sections = new TreeMap<>(TextReader::naturalCompare);
                    for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
                        ZipEntry en = e.nextElement();
                        if (en.getName().matches("Contents/section\\d+\\.xml")) sections.put(en.getName(), en);
                    }
                    for (String name : sections.keySet()) w.run(name, null, "", Mode.HWPX);
                }
                default -> { return null; }
            }
            String html = w.root.toString();
            return html.isBlank() || html.length() > MAX_HTML ? null : html;
        } catch (Exception e) {
            return null;
        }
    }

    private enum Mode { DOCX, PPTX, HWPX }

    private static final class Walker {
        final ZipFile zip;
        /** 문서 전체. 표 칸 안에 있을 때는 out이 그 칸의 글을 가리킨다. */
        final StringBuilder root = new StringBuilder();
        StringBuilder out = root;
        final Deque<StringBuilder> outs = new ArrayDeque<>();
        final Deque<Table> tables = new ArrayDeque<>();
        final Deque<Cell> cells = new ArrayDeque<>();
        final Map<String, String> charStyles = new HashMap<>();
        final Map<String, String> paraAligns = new HashMap<>();
        int imageBytes;

        Walker(ZipFile zip) { this.zip = zip; }

        static XMLInputFactory factory() {
            XMLInputFactory f = XMLInputFactory.newInstance();
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            return f;
        }

        static String attr(XMLStreamReader xr, String local) {
            for (int i = 0; i < xr.getAttributeCount(); i++) if (xr.getAttributeLocalName(i).equals(local)) return xr.getAttributeValue(i);
            return null;
        }

        static boolean on(XMLStreamReader xr) {
            String v = attr(xr, "val");
            return v == null || !(v.equals("0") || v.equals("false") || v.equals("none"));
        }

        static double num(String s, double fallback) {
            try { return s == null ? fallback : Double.parseDouble(s); } catch (NumberFormatException e) { return fallback; }
        }

        static String size(double pt) { return pt < 6 || pt > 60 ? "" : "font-size:" + Math.round(Math.max(8, Math.min(30, pt)) * 10) / 10.0 + "pt;"; }

        static String align(String v) {
            if (v == null) return "";
            return switch (v.toLowerCase()) {
                case "center", "ctr" -> "text-align:center;";
                case "right", "end", "r" -> "text-align:right;";
                case "both", "justify", "distribute", "just" -> "text-align:justify;";
                default -> "";
            };
        }

        /** 한글(hwpx)의 글자 모양과 문단 모양은 header.xml에 번호로 모여 있다. */
        void hwpxHeader() throws Exception {
            ZipEntry entry = zip.getEntry("Contents/header.xml");
            if (entry == null) return;
            try (InputStream in = zip.getInputStream(entry)) {
                XMLStreamReader xr = factory().createXMLStreamReader(in);
                String charId = null, paraId = null;
                StringBuilder style = null;
                while (xr.hasNext()) {
                    int ev = xr.next();
                    if (ev == XMLStreamConstants.START_ELEMENT) {
                        String n = xr.getLocalName();
                        if (n.equals("charPr")) {
                            charId = attr(xr, "id");
                            style = new StringBuilder(size(num(attr(xr, "height"), 0) / 100.0));
                        } else if (charId != null && style != null) {
                            if (n.equals("bold")) style.append("font-weight:700;");
                            else if (n.equals("italic")) style.append("font-style:italic;");
                            else if (n.equals("underline") && !"NONE".equals(attr(xr, "type"))) style.append("text-decoration:underline;");
                            else if (n.equals("spacing")) {
                                double v = num(attr(xr, "hangul"), 0);
                                if (v != 0 && Math.abs(v) <= 50) style.append("letter-spacing:").append(v / 100.0).append("em;");
                            }
                        } else if (n.equals("paraPr")) paraId = attr(xr, "id");
                        else if (n.equals("align") && paraId != null) paraAligns.put(paraId, align(attr(xr, "horizontal")));
                    } else if (ev == XMLStreamConstants.END_ELEMENT) {
                        if (xr.getLocalName().equals("charPr") && charId != null) { charStyles.put(charId, style.toString()); charId = null; }
                        else if (xr.getLocalName().equals("paraPr")) paraId = null;
                    }
                }
                xr.close();
            }
        }

        Map<String, String> rels(String relsName, String base) {
            Map<String, String> map = new HashMap<>();
            ZipEntry entry = relsName == null ? null : zip.getEntry(relsName);
            if (entry == null) return map;
            try (InputStream in = zip.getInputStream(entry)) {
                XMLStreamReader xr = factory().createXMLStreamReader(in);
                while (xr.hasNext()) {
                    if (xr.next() == XMLStreamConstants.START_ELEMENT && xr.getLocalName().equals("Relationship")) {
                        String target = attr(xr, "Target"), id = attr(xr, "Id");
                        if (target == null || id == null || "External".equals(attr(xr, "TargetMode"))) continue;
                        String path = target.startsWith("/") ? target.substring(1) : base + target;
                        Deque<String> parts = new ArrayDeque<>();
                        for (String p : path.split("/")) {
                            if (p.equals("..")) { if (!parts.isEmpty()) parts.removeLast(); }
                            else if (!p.isEmpty() && !p.equals(".")) parts.addLast(p);
                        }
                        map.put(id, String.join("/", parts));
                    }
                }
                xr.close();
            } catch (Exception ignored) { }
            return map;
        }

        void image(String entryName) {
            ZipEntry entry = entryName == null ? null : zip.getEntry(entryName);
            if (entry == null || entry.getSize() > MAX_IMAGE) { out.append(picNote(entryName)); return; }
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] bytes = in.readNBytes(MAX_IMAGE + 1);
                if (bytes.length > MAX_IMAGE) { out.append(picNote(entryName)); return; }
                out.append(imgTag(bytes, entryName));
            } catch (Exception e) {
                out.append(picNote(entryName));
            }
        }

        /** 그림을 화면에 넣는다. 크거나 브라우저가 못 여는 형식(BMP·TIFF 등)은 줄이거나 바꿔서 담는다. */
        String imgTag(byte[] bytes, String name) {
            String[] enc = Rich.encodeImage(bytes, name);
            if (enc == null || imageBytes + enc[1].length() > MAX_IMAGES_TOTAL) return picNote(name);
            imageBytes += enc[1].length();
            return "<img alt=\"그림\" src=\"data:" + enc[0] + ";base64," + enc[1] + "\">";
        }

        String hwpxImage(String id) {
            if (id == null) return null;
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
                String name = e.nextElement().getName();
                if (name.startsWith("BinData/" + id + ".")) return name;
            }
            return null;
        }

        void run(String name, String relsName, String base, Mode mode) throws Exception {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) return;
            Map<String, String> rel = rels(relsName, base);
            byte[] xml;
            try (InputStream in = zip.getInputStream(entry)) { xml = in.readAllBytes(); }
            XMLStreamReader xr = factory().createXMLStreamReader(new ByteArrayInputStream(xml));
            boolean inText = false, inRunProps = false, inParaProps = false, paraOpen = false, spanOpen = false;
            String runStyle = "", paraStyle = "";
            StringBuilder para = new StringBuilder();
            Deque<String> open = new ArrayDeque<>();
            while (xr.hasNext() && root.length() < MAX_HTML) {
                int ev = xr.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String n = xr.getLocalName();
                    switch (n) {
                        case "p" -> {
                            paraOpen = true;
                            paraStyle = mode == Mode.HWPX ? paraAligns.getOrDefault(attr(xr, "paraPrIDRef"), "") : "";
                            para.setLength(0);
                        }
                        case "pPr" -> {
                            inParaProps = true;
                            if (mode == Mode.PPTX) paraStyle += align(attr(xr, "algn"));
                        }
                        case "jc" -> { if (inParaProps && mode == Mode.DOCX) paraStyle += align(attr(xr, "val")); }
                        case "pStyle" -> {
                            String v = attr(xr, "val");
                            if (v != null && v.toLowerCase().matches("(heading|title|제목).*")) paraStyle += "font-weight:700;font-size:13pt;";
                        }
                        case "r", "run" -> {
                            runStyle = mode == Mode.HWPX ? charStyles.getOrDefault(attr(xr, "charPrIDRef"), "") : "";
                        }
                        case "rPr" -> {
                            inRunProps = true;
                            if (mode == Mode.PPTX) {
                                if ("1".equals(attr(xr, "b"))) runStyle += "font-weight:700;";
                                if ("1".equals(attr(xr, "i"))) runStyle += "font-style:italic;";
                                runStyle += size(num(attr(xr, "sz"), 0) / 100.0);
                                double sp = num(attr(xr, "spc"), 0) / 100.0;
                                if (sp != 0 && Math.abs(sp) <= 10) runStyle += "letter-spacing:" + sp + "pt;";
                            }
                        }
                        case "b" -> { if (inRunProps && mode == Mode.DOCX && on(xr)) runStyle += "font-weight:700;"; }
                        case "i" -> { if (inRunProps && mode == Mode.DOCX && on(xr)) runStyle += "font-style:italic;"; }
                        case "u" -> { if (inRunProps && mode == Mode.DOCX && on(xr)) runStyle += "text-decoration:underline;"; }
                        case "sz" -> { if (inRunProps && mode == Mode.DOCX) runStyle += size(num(attr(xr, "val"), 0) / 2.0); }
                        case "spacing" -> {
                            if (inRunProps && mode == Mode.DOCX) {
                                double sp = num(attr(xr, "val"), 0) / 20.0;
                                if (sp != 0 && Math.abs(sp) <= 10) runStyle += "letter-spacing:" + sp + "pt;";
                            }
                        }
                        case "t" -> {
                            inText = true;
                            if (!runStyle.isEmpty()) { para.append("<span style=\"").append(runStyle).append("\">"); spanOpen = true; }
                        }
                        case "tab" -> para.append(" ");
                        case "br", "lineBreak" -> para.append("<br>");
                        case "tbl" -> {
                            flush(para, paraStyle, false);
                            tables.push(new Table(mode));
                            open.push("tbl");
                        }
                        case "tr" -> { if (!tables.isEmpty()) tables.peek().rows.add(new java.util.ArrayList<>()); }
                        case "tc" -> {
                            if (tables.isEmpty()) break;
                            Table t = tables.peek();
                            if (t.rows.isEmpty()) t.rows.add(new java.util.ArrayList<>());
                            Cell c = new Cell();
                            if (mode == Mode.PPTX) {
                                c.colspan = (int) Math.max(1, Math.min(200, num(attr(xr, "gridSpan"), 1)));
                                c.rowspan = (int) Math.max(1, Math.min(200, num(attr(xr, "rowSpan"), 1)));
                                c.skip = "1".equals(attr(xr, "hMerge")) || "1".equals(attr(xr, "vMerge"));
                            }
                            t.rows.get(t.rows.size() - 1).add(c);
                            cells.push(c);
                            outs.push(out);
                            out = c.html;
                            open.push("tc");
                        }
                        case "gridSpan" -> { if (mode == Mode.DOCX && !cells.isEmpty()) cells.peek().colspan = (int) Math.max(1, Math.min(200, num(attr(xr, "val"), 1))); }
                        case "vMerge" -> { if (mode == Mode.DOCX && !cells.isEmpty()) cells.peek().vmerge = "restart".equals(attr(xr, "val")) ? "restart" : "cont"; }
                        case "cellSpan" -> {
                            if (!cells.isEmpty()) {
                                cells.peek().colspan = (int) Math.max(1, Math.min(200, num(attr(xr, "colSpan"), 1)));
                                cells.peek().rowspan = (int) Math.max(1, Math.min(200, num(attr(xr, "rowSpan"), 1)));
                            }
                        }
                        case "blip" -> {
                            StringBuilder keep = new StringBuilder(para);
                            flush(para, paraStyle, false);
                            para.setLength(0);
                            if (keep.length() == 0) { /* 그림만 있는 문단 */ }
                            image(rel.get(attr(xr, "embed")));
                        }
                        case "img" -> {
                            if (mode == Mode.HWPX) { flush(para, paraStyle, false); image(hwpxImage(attr(xr, "binaryItemIDRef"))); }
                        }
                        default -> { }
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS && inText) {
                    para.append(esc(xr.getText()));
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String n = xr.getLocalName();
                    switch (n) {
                        case "t" -> {
                            inText = false;
                            if (spanOpen) { para.append("</span>"); spanOpen = false; }
                        }
                        case "rPr" -> inRunProps = false;
                        case "pPr" -> inParaProps = false;
                        case "p" -> {
                            if (paraOpen) flush(para, paraStyle, true);
                            paraOpen = false;
                        }
                        case "tc" -> {
                            if (cells.isEmpty()) break;
                            if (paraOpen && para.length() > 0) flush(para, paraStyle, false);
                            cells.pop();
                            out = outs.isEmpty() ? root : outs.pop();
                            if (!open.isEmpty()) open.pop();
                        }
                        case "tr" -> { }
                        case "tbl" -> {
                            if (tables.isEmpty()) break;
                            out.append(tables.pop().html());
                            if (!open.isEmpty()) open.pop();
                        }
                        default -> { }
                    }
                }
            }
            xr.close();
        }

        void flush(StringBuilder para, String style, boolean keepEmpty) {
            if (para.length() == 0) {
                if (keepEmpty) out.append("<div class=\"gap\"></div>");
                return;
            }
            out.append(style.isEmpty() ? "<div class=\"para\">" : "<div class=\"para\" style=\"" + style + "\">").append(para).append("</div>");
            para.setLength(0);
        }
    }

    /** 표 칸 하나: 글, 합친 칸 수, 워드의 세로 합치기 표시. */
    static final class Cell {
        final StringBuilder html = new StringBuilder();
        int colspan = 1, rowspan = 1;
        String vmerge;
        boolean skip;
    }

    /** 표 하나. 칸을 다 모은 뒤 합친 칸을 원본처럼 계산해 HTML로 낸다. 표 안의 표도 칸 글 안에 그대로 들어간다. */
    static final class Table {
        final Mode mode;
        final java.util.List<java.util.List<Cell>> rows = new java.util.ArrayList<>();
        Table(Mode mode) { this.mode = mode; }

        String html() {
            // 워드: 세로 합치기(vMerge)를 같은 열의 첫 칸 rowspan으로 바꾼다
            Map<Integer, Cell> restart = new HashMap<>();
            for (java.util.List<Cell> row : rows) {
                int col = 0;
                for (Cell c : row) {
                    if ("cont".equals(c.vmerge)) {
                        Cell top = restart.get(col);
                        if (top != null) { top.rowspan++; c.skip = true; }
                    } else if ("restart".equals(c.vmerge)) restart.put(col, c);
                    else restart.remove(col);
                    col += c.colspan;
                }
            }
            StringBuilder sb = new StringBuilder("<div class=\"tw\"><table>");
            for (java.util.List<Cell> row : rows) {
                sb.append("<tr>");
                for (Cell c : row) {
                    if (c.skip) continue;
                    sb.append("<td");
                    if (c.colspan > 1) sb.append(" colspan=\"").append(c.colspan).append('"');
                    if (c.rowspan > 1) sb.append(" rowspan=\"").append(c.rowspan).append('"');
                    sb.append('>').append(c.html).append("</td>");
                }
                sb.append("</tr>");
            }
            return sb.append("</table></div>").toString();
        }
    }

    static String picNote(String name) {
        String ext = name == null ? "" : Txt_ext(name);
        boolean vector = ext.equals(".wmf") || ext.equals(".emf") || ext.equals(".svg");
        return "<span class=\"pic\">[그림" + (vector ? " (" + ext.substring(1).toUpperCase() + " 형식 — 원본 '열기'로 보세요)" : "") + "]</span>";
    }

    private static String Txt_ext(String name) {
        int d = name.lastIndexOf('.');
        return d < 0 ? "" : name.substring(d).toLowerCase();
    }

    /**
     * 그림 바이트를 화면용으로. PNG·JPEG·GIF는 작으면 그대로, 크면 긴 변 1400px로 줄인다.
     * BMP·TIFF처럼 브라우저가 못 여는 형식은 PNG·JPEG로 바꾼다. 읽지 못하면 null.
     */
    public static String[] encodeImage(byte[] bytes, String name) {
        String ext = name == null ? "" : Txt_ext(name);
        String direct = bytes.length > 4 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' ? "image/png"
            : bytes.length > 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 ? "image/jpeg"
            : bytes.length > 3 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' ? "image/gif" : null;
        if (direct != null && bytes.length <= 700_000) return new String[] { direct, Base64.getEncoder().encodeToString(bytes) };
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) return direct != null && bytes.length <= 3_000_000 ? new String[] { direct, Base64.getEncoder().encodeToString(bytes) } : null;
            int w = img.getWidth(), h = img.getHeight();
            double k = Math.min(1.0, (double) IMAGE_EDGE / Math.max(w, h));
            int nw = Math.max(1, (int) Math.round(w * k)), nh = Math.max(1, (int) Math.round(h * k));
            boolean alpha = img.getColorModel().hasAlpha();
            java.awt.image.BufferedImage dst = new java.awt.image.BufferedImage(nw, nh,
                alpha ? java.awt.image.BufferedImage.TYPE_INT_ARGB : java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = dst.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            if (!alpha) { g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, nw, nh); }
            g.drawImage(img, 0, 0, nw, nh, null);
            g.dispose();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            String type = alpha ? "png" : "jpeg";
            javax.imageio.ImageIO.write(dst, type, bo);
            return new String[] { "image/" + type, Base64.getEncoder().encodeToString(bo.toByteArray()) };
        } catch (Exception | Error e) {
            return null;
        }
    }

    /** 미리보기 창 안에 넣는 한 쪽짜리 문서. */
    public static String page(String title, String body) {
        return "<!DOCTYPE html><html lang=\"ko\"><head><meta charset=\"utf-8\"><title>" + esc(title) + "</title><style>" + CSS
            + "</style></head><body class=\"rich\">" + (body == null || body.isBlank() ? "<p class=\"none\">읽은 내용이 없습니다.</p>" : body) + "</body></html>";
    }

    public static final String CSS = """
        body.rich{margin:0;padding:18px 20px;background:#fff;color:#1d1d1f;font:14px/1.65 "Malgun Gothic","맑은 고딕","Apple SD Gothic Neo",sans-serif;overflow-wrap:anywhere}
        .rich .para{margin:0 0 2px;white-space:pre-wrap;overflow-wrap:anywhere}
        .rich .gap{height:.7em}
        .rich .tw{overflow-x:auto;margin:8px 0;max-width:100%}
        .rich table{border-collapse:collapse;max-width:100%;font-size:13px}
        .rich th,.rich td{border:1px solid #c9c9d1;padding:5px 8px;vertical-align:top;text-align:left;overflow-wrap:break-word;word-break:keep-all;min-width:3.2em}
        .rich td .para,.rich th .para{overflow-wrap:break-word;word-break:keep-all}
        .rich th{background:#f1f1f5;font-weight:650}
        .rich td .para{margin:0}
        .rich img{max-width:100%;height:auto;display:block;margin:8px 0}
        .rich .pic{color:#8a8a90;font-size:12px}
        .rich .slide{border:1px solid #dcdce2;border-radius:10px;padding:12px 16px;margin:0 0 12px}
        .rich .slide-no{color:#8a8a90;font-size:11px;font-weight:700}
        .rich .none{color:#8a8a90}
        """;
}
