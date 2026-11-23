package local.audit.extract;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * PDF 안의 표를 찾아 HTML 표로 다시 그린다(워드가 없을 때 쓰는 방법).
 * PDF에는 '표'라는 구조가 없고 선과 글자 위치만 있으므로, 테두리 선이 이루는 칸을 계산하고 글자를 그 칸에 넣는다.
 * 테두리가 있는 표는 잘 맞고, 선 없는 표는 표로 잡지 못해 예전처럼 글 줄로 남는다.
 * 표를 하나도 찾지 못하면 null을 돌려준다(그때는 지금처럼 글만 보여 준다).
 */
public final class PdfTables {
    private PdfTables() { }

    static final float TOL = 2.5f;
    static final int MAX_PAGES = 300;

    record Seg(float a, float b, float at) { }            // 가로선: x a~b, y at / 세로선: y a~b, x at
    record Word(String text, float x, float y, float h) { }

    public static String html(Path file) {
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            StringBuilder out = new StringBuilder();
            int tables = 0, pages = Math.min(doc.getNumberOfPages(), MAX_PAGES);
            for (int i = 0; i < pages; i++) {
                PDPage page = doc.getPage(i);
                Page pg = new Page(doc, page, i);
                tables += pg.render(out);
                if (i < pages - 1) out.append("<div class=\"gap\"></div>");
            }
            return tables == 0 ? null : out.toString();
        } catch (Exception | StackOverflowError e) {
            return null;
        }
    }

    /** 한 쪽: 선을 모으고, 표 칸을 만들고, 글자를 칸이나 본문 줄에 넣는다. */
    static final class Page {
        final List<Seg> hs = new ArrayList<>(), vs = new ArrayList<>();
        final List<Word> words = new ArrayList<>();
        final List<Integer> lineBreaks = new ArrayList<>();   // words 안에서 줄이 바뀌는 자리
        final float top, left;

        Page(PDDocument doc, PDPage page, int index) throws IOException {
            PDRectangle box = page.getCropBox();
            top = box.getUpperRightY();
            left = box.getLowerLeftX();
            if (page.getRotation() % 360 == 0) new Lines(page).processPage(page);   // 돌린 쪽은 선을 쓰지 않고 글만
            PDFTextStripper st = new PDFTextStripper() {
                @Override protected void writeString(String text, List<TextPosition> ps) {
                    if (text == null || text.isBlank() || ps.isEmpty()) return;
                    TextPosition f = ps.get(0);
                    float h = Math.max(4f, f.getHeightDir());
                    // 글자 사이가 크게 벌어진 곳에서 나눈다(칸 사이에 빈칸 없이 붙어 나오는 PDF가 있음)
                    StringBuilder cur = new StringBuilder();
                    float cx = f.getXDirAdj(), cy = f.getYDirAdj(), prevEnd = f.getXDirAdj();
                    for (TextPosition t : ps) {
                        float x = t.getXDirAdj();
                        if (cur.length() > 0 && x - prevEnd > h * 0.9f) {
                            words.add(new Word(cur.toString(), cx, cy, h));
                            cur.setLength(0);
                        }
                        if (cur.length() == 0) { cx = x; cy = t.getYDirAdj(); }
                        cur.append(t.getUnicode());
                        prevEnd = x + t.getWidthDirAdj();
                    }
                    if (cur.length() > 0) words.add(new Word(cur.toString(), cx, cy, h));
                }
                @Override protected void writeLineSeparator() { lineBreaks.add(words.size()); }
            };
            st.setSortByPosition(true);
            st.setStartPage(index + 1);
            st.setEndPage(index + 1);
            st.getText(doc);
        }

        /** 선 모으기: 그린 선과 얇은 사각형(표 테두리를 채운 사각형으로 그리는 PDF가 많음). */
        final class Lines extends PDFGraphicsStreamEngine {
            final List<float[]> path = new ArrayList<>();
            Point2D cur = new Point2D.Float();
            Lines(PDPage p) { super(p); }
            float X(double x) { return (float) x - left; }
            float Y(double y) { return top - (float) y; }
            @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
                path.add(new float[] { X(p0.getX()), Y(p0.getY()), X(p1.getX()), Y(p1.getY()) });
                path.add(new float[] { X(p1.getX()), Y(p1.getY()), X(p2.getX()), Y(p2.getY()) });
                path.add(new float[] { X(p2.getX()), Y(p2.getY()), X(p3.getX()), Y(p3.getY()) });
                path.add(new float[] { X(p3.getX()), Y(p3.getY()), X(p0.getX()), Y(p0.getY()) });
            }
            @Override public void moveTo(float x, float y) { cur = new Point2D.Float(x, y); }
            @Override public void lineTo(float x, float y) {
                path.add(new float[] { X(cur.getX()), Y(cur.getY()), X(x), Y(y) });
                cur = new Point2D.Float(x, y);
            }
            @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { cur = new Point2D.Float(x3, y3); }
            @Override public Point2D getCurrentPoint() { return cur; }
            @Override public void closePath() { }
            @Override public void endPath() { path.clear(); }
            @Override public void strokePath() { take(false); }
            @Override public void fillPath(int windingRule) { take(true); }
            @Override public void fillAndStrokePath(int windingRule) { take(false); }
            @Override public void drawImage(PDImage pdImage) { }
            @Override public void clip(int windingRule) { }
            @Override public void shadingFill(COSName shadingName) { }
            void take(boolean filled) {
                for (float[] s : path) {
                    float x1 = s[0], y1 = s[1], x2 = s[2], y2 = s[3];
                    if (Math.abs(y1 - y2) < 1.2f && Math.abs(x1 - x2) > 3) hs.add(new Seg(Math.min(x1, x2), Math.max(x1, x2), (y1 + y2) / 2));
                    else if (Math.abs(x1 - x2) < 1.2f && Math.abs(y1 - y2) > 3) vs.add(new Seg(Math.min(y1, y2), Math.max(y1, y2), (x1 + x2) / 2));
                }
                // 채운 얇은 사각형은 가운데 선 하나로 본다(위 네 변 중 긴 두 변이 이미 들어감)
                path.clear();
            }
        }

        /** 표 하나: 칸 경계와 위치. */
        static final class Grid {
            float[] ys, xs;
            List<Seg> h, v;
            float x0, y0, x1, y1;
            boolean hasH(float y, float xa, float xb) {
                float mid = (xa + xb) / 2;
                for (Seg s : h) if (Math.abs(s.at() - y) <= TOL && s.a() - TOL <= mid && s.b() + TOL >= mid) return true;
                return false;
            }
            boolean hasV(float x, float ya, float yb) {
                float mid = (ya + yb) / 2;
                for (Seg s : v) if (Math.abs(s.at() - x) <= TOL && s.a() - TOL <= mid && s.b() + TOL >= mid) return true;
                return false;
            }
            int row(float y) { for (int i = 0; i < ys.length - 1; i++) if (y >= ys[i] - 1 && y < ys[i + 1] + 1) return i; return -1; }
            int col(float x) { for (int i = 0; i < xs.length - 1; i++) if (x >= xs[i] - 1 && x < xs[i + 1] + 1) return i; return -1; }
        }

        static List<Seg> merge(List<Seg> in) {
            List<Seg> s = new ArrayList<>(in);
            s.sort(Comparator.comparing(Seg::at).thenComparing(Seg::a));
            List<Seg> out = new ArrayList<>();
            for (Seg g : s) {
                Seg last = out.isEmpty() ? null : out.get(out.size() - 1);
                if (last != null && Math.abs(last.at() - g.at()) <= 1.5f && g.a() <= last.b() + TOL)
                    out.set(out.size() - 1, new Seg(last.a(), Math.max(last.b(), g.b()), (last.at() + g.at()) / 2));
                else out.add(g);
            }
            return out;
        }

        static float[] uniq(List<Float> v) {
            v.sort(Float::compare);
            List<Float> out = new ArrayList<>();
            for (float f : v) if (out.isEmpty() || f - out.get(out.size() - 1) > TOL * 1.5f) out.add(f);
            float[] a = new float[out.size()];
            for (int i = 0; i < a.length; i++) a[i] = out.get(i);
            return a;
        }

        /** 서로 닿는 가로·세로선 묶음을 표 하나로 본다. */
        List<Grid> grids() {
            List<Seg> h = merge(hs), v = merge(vs);
            int n = h.size() + v.size();
            int[] parent = new int[n];
            for (int i = 0; i < n; i++) parent[i] = i;
            for (int i = 0; i < h.size(); i++) for (int j = 0; j < v.size(); j++) {
                Seg a = h.get(i), b = v.get(j);
                if (b.at() >= a.a() - TOL && b.at() <= a.b() + TOL && a.at() >= b.a() - TOL && a.at() <= b.b() + TOL) union(parent, i, h.size() + j);
            }
            java.util.Map<Integer, Grid> by = new java.util.LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                int r = find(parent, i);
                Grid g = by.computeIfAbsent(r, k -> { Grid x = new Grid(); x.h = new ArrayList<>(); x.v = new ArrayList<>(); return x; });
                if (i < h.size()) g.h.add(h.get(i)); else g.v.add(v.get(i - h.size()));
            }
            List<Grid> out = new ArrayList<>();
            for (Grid g : by.values()) {
                if (g.h.size() < 2 || g.v.size() < 2) continue;
                List<Float> ys = new ArrayList<>(), xs = new ArrayList<>();
                for (Seg s : g.h) ys.add(s.at());
                for (Seg s : g.v) xs.add(s.at());
                g.ys = uniq(ys); g.xs = uniq(xs);
                if (g.ys.length < 2 || g.xs.length < 2) continue;
                g.x0 = g.xs[0]; g.x1 = g.xs[g.xs.length - 1]; g.y0 = g.ys[0]; g.y1 = g.ys[g.ys.length - 1];
                if (g.x1 - g.x0 < 30 || g.y1 - g.y0 < 8) continue;               // 밑줄·상자 장식은 표가 아님
                if ((g.ys.length - 1) * (g.xs.length - 1) < 2) continue;           // 칸 하나짜리 상자는 표로 보지 않음
                out.add(g);
            }
            out.sort(Comparator.comparing(g -> g.y0));
            return out;
        }

        static int find(int[] p, int i) { while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; } return i; }
        static void union(int[] p, int a, int b) { p[find(p, a)] = find(p, b); }

        /** 쪽 하나를 HTML로. 돌려주는 값은 찾은 표 수. */
        int render(StringBuilder out) {
            List<Grid> grids = grids();
            // 글자 → 표 칸 또는 본문 줄
            List<List<StringBuilder>> cells = new ArrayList<>();
            List<float[]> lastPos = new ArrayList<>();
            for (Grid g : grids) {
                List<StringBuilder> c = new ArrayList<>();
                for (int i = 0; i < (g.ys.length - 1) * (g.xs.length - 1); i++) c.add(new StringBuilder());
                cells.add(c);
                lastPos.add(new float[(g.ys.length - 1) * (g.xs.length - 1)]);
            }
            List<Object[]> blocks = new ArrayList<>();   // {y, html}
            StringBuilder line = new StringBuilder();
            float lineY = -1;
            int wi = 0, lb = 0;
            for (Word w : words) {
                while (lb < lineBreaks.size() && lineBreaks.get(lb) <= wi) {
                    if (line.length() > 0) { blocks.add(new Object[] { lineY, "<div class=\"para\">" + Rich.esc(line.toString().strip()) + "</div>" }); line.setLength(0); }
                    lb++;
                }
                wi++;
                float cx = w.x() + 1, cy = w.y() - w.h() / 2;
                int gi = -1;
                for (int k = 0; k < grids.size(); k++) {
                    Grid g = grids.get(k);
                    if (cx >= g.x0 - 1 && cx <= g.x1 + 1 && cy >= g.y0 - 1 && cy <= g.y1 + 1) { gi = k; break; }
                }
                if (gi < 0) {
                    if (line.length() == 0) lineY = w.y();
                    else line.append(' ');
                    line.append(w.text());
                    continue;
                }
                Grid g = grids.get(gi);
                int r = g.row(cy), c = g.col(cx);
                if (r < 0 || c < 0) continue;
                int idx = r * (g.xs.length - 1) + c;
                StringBuilder cell = cells.get(gi).get(idx);
                float prevY = lastPos.get(gi)[idx];
                if (cell.length() > 0) cell.append(Math.abs(w.y() - prevY) > w.h() * 0.6f ? "\n" : " ");
                cell.append(w.text());
                lastPos.get(gi)[idx] = w.y();
            }
            if (line.length() > 0) blocks.add(new Object[] { lineY, "<div class=\"para\">" + Rich.esc(line.toString().strip()) + "</div>" });
            for (int k = 0; k < grids.size(); k++) blocks.add(new Object[] { grids.get(k).y0, table(grids.get(k), cells.get(k)) });
            blocks.sort(Comparator.comparing(b -> (Float) b[0]));
            for (Object[] b : blocks) out.append(b[1]);
            return grids.size();
        }

        /** 칸 경계 선이 없는 곳은 합친 칸으로 본다. */
        static String table(Grid g, List<StringBuilder> text) {
            int rows = g.ys.length - 1, cols = g.xs.length - 1;
            boolean[][] used = new boolean[rows][cols];
            StringBuilder sb = new StringBuilder("<div class=\"tw\"><table>");
            for (int r = 0; r < rows; r++) {
                sb.append("<tr>");
                for (int c = 0; c < cols; c++) {
                    if (used[r][c]) continue;
                    int cs = 1, rs = 1;
                    while (c + cs < cols && !g.hasV(g.xs[c + cs], g.ys[r], g.ys[r + 1])) cs++;
                    outer:
                    while (r + rs < rows) {
                        for (int k = c; k < c + cs; k++) if (g.hasH(g.ys[r + rs], g.xs[k], g.xs[k + 1])) break outer;
                        rs++;
                    }
                    StringBuilder t = new StringBuilder();
                    for (int rr = r; rr < r + rs; rr++) for (int cc = c; cc < c + cs; cc++) {
                        used[rr][cc] = true;
                        String part = text.get(rr * cols + cc).toString().strip();
                        if (!part.isEmpty()) { if (t.length() > 0) t.append('\n'); t.append(part); }
                    }
                    sb.append("<td").append(cs > 1 ? " colspan=\"" + cs + "\"" : "").append(rs > 1 ? " rowspan=\"" + rs + "\"" : "").append(">");
                    if (t.length() == 0) sb.append("<div class=\"gap\"></div>");
                    else for (String l : t.toString().split("\n")) sb.append("<div class=\"para\">").append(Rich.esc(l)).append("</div>");
                    sb.append("</td>");
                }
                sb.append("</tr>");
            }
            return sb.append("</table></div>").toString();
        }
    }
}
