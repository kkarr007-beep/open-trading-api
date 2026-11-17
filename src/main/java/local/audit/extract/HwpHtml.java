package local.audit.extract;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bindata.EmbeddedBinaryData;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.ControlType;
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlContainer;
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlPicture;
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlRectangle;
import kr.dogfoot.hwplib.object.bodytext.control.gso.GsoControl;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.ParagraphList;
import kr.dogfoot.hwplib.object.bodytext.paragraph.charshape.CharPositionShapeIdPair;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPChar;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharNormal;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharType;
import kr.dogfoot.hwplib.object.docinfo.CharShape;
import kr.dogfoot.hwplib.object.docinfo.ParaShape;
import kr.dogfoot.hwplib.reader.HWPReader;

/**
 * 한글 5.0(.hwp, 바이너리) 문서를 화면용 HTML로. 표는 합친 칸(행·열 합치기)을 원본대로, 표 안의 표와 표 안의 그림도 넣는다.
 * 글자 굵기·기울임·밑줄·취소선·크기·색과 문단 정렬을 옮긴다. 글꼴·쪽 모양·도형 위치는 옮기지 않는다.
 */
public final class HwpHtml {
    private HwpHtml() { }

    private static final int MAX = 6_000_000;
    private static final int MAX_IMAGES_TOTAL = 24_000_000;

    public static String convert(Path file) {
        try {
            return convert(HWPReader.fromFile(file.toString()));
        } catch (Exception | Error e) {
            return null;
        }
    }

    public static String convert(HWPFile doc) {
        try {
            HwpHtml.Walker w = new HwpHtml.Walker(doc);
            for (Section s : doc.getBodyText().getSectionList()) {
                for (Paragraph p : s.getParagraphs()) w.paragraph(p);
                if (w.out.length() > MAX) break;
            }
            String html = w.out.toString();
            return html.isBlank() || html.length() > MAX ? null : html;
        } catch (Exception | Error e) {
            return null;
        }
    }

    static final class Walker {
        final HWPFile doc;
        final StringBuilder out = new StringBuilder();
        final Map<Integer, EmbeddedBinaryData> bins = new HashMap<>();
        int imageBytes;

        Walker(HWPFile doc) {
            this.doc = doc;
            List<EmbeddedBinaryData> list = doc.getBinData().getEmbeddedBinaryDataList();
            for (EmbeddedBinaryData b : list) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)BIN([0-9A-F]{4})").matcher(b.getName());
                if (m.find()) bins.put(Integer.parseInt(m.group(1), 16), b);
            }
        }

        String charStyle(long shapeId) {
            List<CharShape> shapes = doc.getDocInfo().getCharShapeList();
            if (shapeId < 0 || shapeId >= shapes.size()) return "";
            CharShape cs = shapes.get((int) shapeId);
            StringBuilder st = new StringBuilder();
            double pt = cs.getBaseSize() / 100.0;
            if (pt >= 6 && pt <= 60 && Math.abs(pt - 10) > 0.05) st.append("font-size:").append(Math.round(Math.max(8, Math.min(30, pt)) * 10) / 10.0).append("pt;");
            var pr = cs.getProperty();
            if (pr.isBold()) st.append("font-weight:700;");
            if (pr.isItalic()) st.append("font-style:italic;");
            String deco = "";
            if (pr.getUnderLineSort() != null && !pr.getUnderLineSort().name().equalsIgnoreCase("None")) deco += " underline";
            if (pr.isStrikeLine()) deco += " line-through";
            if (!deco.isEmpty()) st.append("text-decoration:").append(deco.strip()).append(';');
            var c = cs.getCharColor();
            if (c != null && (c.getR() + c.getG() + c.getB()) > 0) st.append(String.format("color:#%02x%02x%02x;", c.getR(), c.getG(), c.getB()));
            if (pr.isSuperScript()) st.append("vertical-align:super;font-size:smaller;");
            if (pr.isSubScript()) st.append("vertical-align:sub;font-size:smaller;");
            return st.toString();
        }

        String paraStyle(int shapeId) {
            List<ParaShape> shapes = doc.getDocInfo().getParaShapeList();
            if (shapeId < 0 || shapeId >= shapes.size()) return "";
            ParaShape ps = shapes.get(shapeId);
            StringBuilder st = new StringBuilder();
            var al = ps.getProperty1().getAlignment();
            if (al != null) switch (al) {
                case Center -> st.append("text-align:center;");
                case Right -> st.append("text-align:right;");
                case Justify, Distribute, Divide -> st.append("text-align:justify;");
                default -> { }
            }
            int left = ps.getLeftMargin();
            if (left > 0) st.append("padding-left:").append(Math.min(20, Math.round(left / 2000.0 * 10) / 10.0)).append("em;");
            return st.toString();
        }

        void paragraphs(ParagraphList list) {
            if (list == null) return;
            for (Paragraph p : list) paragraph(p);
        }

        /** 문단 하나. 글자 사이에 표·그림이 있으면 그 자리에 넣는다. */
        void paragraph(Paragraph p) {
            if (out.length() > MAX) return;
            StringBuilder para = new StringBuilder();
            String pStyle = paraStyle(p.getHeader().getParaShapeId());
            List<CharPositionShapeIdPair> shapes = p.getCharShape() == null ? List.of() : p.getCharShape().getPositonShapeIdPairList();
            List<Control> controls = p.getControlList() == null ? List.of() : p.getControlList();
            int ctrlIdx = 0;
            long pos = 0;
            int shapeIdx = -1;
            String cur = null;
            if (p.getText() != null) {
                for (HWPChar ch : p.getText().getCharList()) {
                    while (shapeIdx + 1 < shapes.size() && shapes.get(shapeIdx + 1).getPosition() <= pos) {
                        shapeIdx++;
                        String st = charStyle(shapes.get(shapeIdx).getShapeId());
                        if (!st.equals(cur)) {
                            if (cur != null && !cur.isEmpty()) para.append("</span>");
                            cur = st;
                            if (!st.isEmpty()) para.append("<span style=\"").append(st).append("\">");
                        }
                    }
                    if (ch.getType() == HWPCharType.Normal) {
                        try { para.append(Rich.esc(((HWPCharNormal) ch).getCh())); } catch (Exception ignored) { }
                    } else if (ch.getType() == HWPCharType.ControlExtend) {
                        Control c = ctrlIdx < controls.size() ? controls.get(ctrlIdx) : null;
                        ctrlIdx++;
                        if (c != null) {
                            String inner = control(c);
                            if (!inner.isEmpty()) {
                                if (inner.startsWith("<div class=\"tw\">")) {
                                    // 표는 문단 밖에: 앞 글을 먼저 내보낸다
                                    if (cur != null && !cur.isEmpty()) para.append("</span>");
                                    flush(para, pStyle);
                                    out.append(inner);
                                    if (cur != null && !cur.isEmpty()) para.append("<span style=\"").append(cur).append("\">");
                                } else para.append(inner);
                            }
                        }
                    } else if (ch.getCode() == 10) para.append("<br>");
                    else if (ch.getCode() == 9) para.append("&emsp;");
                    pos += ch.getCharSize();
                }
            }
            if (cur != null && !cur.isEmpty()) para.append("</span>");
            if (para.toString().replaceAll("<[^>]*>", "").isBlank() && !para.toString().contains("<img")) {
                if (para.length() == 0 || !para.toString().contains("<")) out.append("<div class=\"gap\"></div>");
                return;
            }
            flush(para, pStyle);
        }

        void flush(StringBuilder para, String style) {
            if (para.length() == 0) return;
            out.append(style.isEmpty() ? "<div class=\"para\">" : "<div class=\"para\" style=\"" + style + "\">").append(para).append("</div>");
            para.setLength(0);
        }

        String control(Control c) {
            if (c.getType() == ControlType.Table) return table((ControlTable) c);
            if (c.getType() == ControlType.Gso && c instanceof GsoControl g) return gso(g);
            return "";
        }

        String gso(GsoControl g) {
            if (g instanceof ControlPicture pic) return picture(pic);
            if (g instanceof ControlContainer box) {
                StringBuilder sb = new StringBuilder();
                for (GsoControl child : box.getChildControlList()) sb.append(gso(child));
                return sb.toString();
            }
            if (g instanceof ControlRectangle r && r.getTextBox() != null) {
                StringBuilder save = new StringBuilder(out);
                out.setLength(0);
                paragraphs(r.getTextBox().getParagraphList());
                String inner = out.toString();
                out.setLength(0);
                out.append(save);
                return inner.isBlank() ? "" : "<div class=\"tw\"><table><tr><td>" + inner + "</td></tr></table></div>";
            }
            return "";
        }

        String picture(ControlPicture pic) {
            try {
                int id = pic.getShapeComponentPicture().getPictureInfo().getBinItemID();
                var list = doc.getDocInfo().getBinDataList();
                int binId = id;
                String ext = "";
                if (id >= 1 && id <= list.size()) {
                    binId = list.get(id - 1).getBinDataID();
                    ext = "." + list.get(id - 1).getExtensionForEmbedding();
                }
                EmbeddedBinaryData b = bins.get(binId);
                if (b == null || b.getData() == null) return Rich.picNote(ext);
                byte[] data = b.getData();
                String[] enc = Rich.encodeImage(data, b.getName());
                if (enc == null) {
                    // 압축된 채로 온 그림이면 풀어서 한 번 더
                    try {
                        java.util.zip.Inflater inf = new java.util.zip.Inflater(true);
                        inf.setInput(data);
                        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[65536];
                        while (!inf.finished()) { int n = inf.inflate(buf); if (n == 0) break; bo.write(buf, 0, n); }
                        enc = Rich.encodeImage(bo.toByteArray(), b.getName());
                    } catch (Exception ignored) { }
                }
                if (enc == null || imageBytes + enc[1].length() > MAX_IMAGES_TOTAL) return Rich.picNote(b.getName());
                imageBytes += enc[1].length();
                return "<img alt=\"그림\" src=\"data:" + enc[0] + ";base64," + enc[1] + "\">";
            } catch (Exception e) {
                return Rich.picNote("");
            }
        }

        /** 표: 한글은 합쳐져 사라진 칸을 아예 담지 않으므로 행·열 합치기 수를 그대로 쓴다. */
        String table(ControlTable t) {
            StringBuilder save = new StringBuilder(out);
            StringBuilder sb = new StringBuilder("<div class=\"tw\"><table>");
            for (Row row : t.getRowList()) {
                sb.append("<tr>");
                for (Cell cell : row.getCellList()) {
                    var h = cell.getListHeader();
                    sb.append("<td");
                    if (h.getColSpan() > 1) sb.append(" colspan=\"").append(Math.min(200, h.getColSpan())).append('"');
                    if (h.getRowSpan() > 1) sb.append(" rowspan=\"").append(Math.min(200, h.getRowSpan())).append('"');
                    sb.append('>');
                    out.setLength(0);
                    paragraphs(cell.getParagraphList());
                    sb.append(out);
                    sb.append("</td>");
                }
                sb.append("</tr>");
            }
            out.setLength(0);
            out.append(save);
            return sb.append("</table></div>").toString();
        }
    }
}
