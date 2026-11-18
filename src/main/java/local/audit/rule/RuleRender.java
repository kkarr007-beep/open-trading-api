package local.audit.rule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 조 본문을 화면용 HTML로 바꾼다. 글은 모두 이스케이프한 뒤 정해진 꾸밈만 태그로 바꾼다.
 * 표 칸 기호: &lt; 왼쪽과 합침, ^ 위와 합침, ⏎ 칸 안 줄바꿈.
 */
public final class RuleRender {
    private RuleRender() { }

    static final String TBL_L = "<", TBL_U = "^", TBL_BR = "⏎";
    static final Pattern REF = Pattern.compile("(?:「([^」]{2,40})」|『([^』]{2,40})』)?\\s?(제\\s?\\d+\\s?조(?:\\s?의\\s?\\d+)?)(?:\\s?(제\\s?\\d+\\s?항))?");

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 굵게·밑줄·취소선·강조를 태그로. 태그 안은 건드리지 않는다. */
    static String mdSpan(String html) {
        StringBuilder out = new StringBuilder();
        for (String seg : html.split("(?=<)|(?<=>)")) {
            if (seg.startsWith("<")) { out.append(seg); continue; }
            out.append(seg.replaceAll("\\*\\*([^*\\n]{1,200})\\*\\*", "<b>$1</b>")
                .replaceAll("__([^_\\n]{1,200})__", "<u>$1</u>")
                .replaceAll("~~([^~\\n]{1,200})~~", "<s>$1</s>")
                .replaceAll("==([^=\\n]{1,200})==", "<em>$1</em>"));
        }
        return out.toString();
    }

    /** 찾은 말에 형광 표시. 태그 밖 글자에만 한다. */
    public static String highlight(String html, List<String> terms) {
        if (terms == null || terms.isEmpty()) return html;
        List<String> ts = new ArrayList<>();
        for (String t : terms) if (t != null && !t.isBlank()) ts.add(Pattern.quote(esc(t.strip())));
        if (ts.isEmpty()) return html;
        ts.sort((a, b) -> b.length() - a.length());
        Pattern p = Pattern.compile("(" + String.join("|", ts) + ")", Pattern.CASE_INSENSITIVE);
        StringBuilder out = new StringBuilder();
        for (String seg : html.split("(?=<)|(?<=>)")) {
            if (seg.startsWith("<")) { out.append(seg); continue; }
            out.append(p.matcher(seg).replaceAll("<mark>$1</mark>"));
        }
        return out.toString();
    }

    /** 「다른 사규」 제N조, 제N조 제M항 → 해당 조문으로 가는 연결. resolve가 주소를 돌려준다. */
    static String linkRefs(String html, Function<String[], String> resolve) {
        if (resolve == null) return html;
        StringBuilder out = new StringBuilder();
        for (String seg : html.split("(?=<)|(?<=>)")) {
            if (seg.startsWith("<")) { out.append(seg); continue; }
            Matcher m = REF.matcher(seg);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String reg = m.group(1) != null ? m.group(1) : m.group(2);
                String href = resolve.apply(new String[] { reg, m.group(3) });
                String rep = href == null ? m.group() : "<a class=\"xref\" href=\"" + esc(href) + "\">" + m.group() + "</a>";
                m.appendReplacement(sb, Matcher.quoteReplacement(rep));
            }
            m.appendTail(sb);
            out.append(sb);
        }
        return out.toString();
    }

    /** 편집기로 꾸민 조문에도 조 참조 연결을 단다. 이미 연결된 곳은 건드리지 않는다. */
    public static String linkRefsHtml(String html, Function<String[], String> resolve) {
        if (resolve == null || html.contains("<a ")) return html;
        return linkRefs(html, resolve);
    }

    static String cell(String raw, List<String> terms, Function<String[], String> resolve) {
        return highlight(mdSpan(linkRefs(esc(raw), resolve)), terms);
    }

    static List<String> tblCells(String t) {
        String s = t.strip().replaceFirst("^\\|", "").replaceFirst("\\|$", "");
        List<String> out = new ArrayList<>();
        for (String c : s.split("\\|", -1)) out.add(c.strip());
        return out;
    }

    public static String content(String content, List<String> terms, Function<String[], String> resolve) {
        List<RuleParser.Para> ps = RuleParser.structure(content);
        if (ps.isEmpty()) return "<div class=\"para muted\">(내용 없음)</div>";
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < ps.size()) {
            RuleParser.Para p = ps.get(i);
            if ("t".equals(p.lvl())) {
                List<List<String>> rows = new ArrayList<>();
                while (i < ps.size() && "t".equals(ps.get(i).lvl())) { rows.add(tblCells(ps.get(i).text())); i++; }
                int w = rows.stream().mapToInt(List::size).max().orElse(0);
                for (List<String> r : rows) while (r.size() < w) r.add("");
                boolean head = rows.size() > 1 && rows.get(0).stream().noneMatch(TBL_U::equals) && rows.get(0).stream().anyMatch(c -> !c.isEmpty())
                    && rows.get(0).stream().allMatch(c -> TBL_L.equals(c) || (!c.isEmpty() && c.length() <= 26));
                boolean[][] cv = new boolean[rows.size()][w];
                List<String> trs = new ArrayList<>();
                for (int r = 0; r < rows.size(); r++) {
                    StringBuilder tds = new StringBuilder();
                    for (int c = 0; c < w; c++) {
                        if (cv[r][c]) continue;
                        String v = rows.get(r).get(c);
                        if (TBL_L.equals(v) || TBL_U.equals(v)) { cv[r][c] = true; continue; }
                        int cs = 1;
                        while (c + cs < w && TBL_L.equals(rows.get(r).get(c + cs))) cs++;
                        int rs = 1;
                        while (r + rs < rows.size() && TBL_U.equals(rows.get(r + rs).get(c))) rs++;
                        for (int a = 0; a < rs; a++) for (int b = 0; b < cs; b++) cv[r + a][c + b] = true;
                        String tg = head && r == 0 ? "th" : "td";
                        tds.append('<').append(tg).append(cs > 1 ? " colspan=\"" + cs + "\"" : "").append(rs > 1 ? " rowspan=\"" + rs + "\"" : "").append('>')
                            .append(String.join("<br>", Arrays.stream(cell(v, terms, resolve).split(TBL_BR, -1)).toList()))
                            .append("</").append(tg).append('>');
                    }
                    trs.add("<tr>" + tds + "</tr>");
                }
                out.append("<div class=\"para t\"><div class=\"ctblw\"><table class=\"ctbl\">");
                if (head) out.append("<thead>").append(trs.get(0)).append("</thead><tbody>").append(String.join("", trs.subList(1, trs.size()))).append("</tbody>");
                else out.append("<tbody>").append(String.join("", trs)).append("</tbody>");
                out.append("</table></div></div>");
                continue;
            }
            if ("gap".equals(p.lvl())) { out.append("<div class=\"para gap\"></div>"); i++; continue; }
            if ("img".equals(p.lvl())) {
                Matcher mm = Pattern.compile("^!\\[([^\\]]*)\\]\\((data:image/(?:png|jpe?g|gif|webp|bmp);base64,[A-Za-z0-9+/=]+)\\)").matcher(p.text());
                if (mm.find()) {
                    out.append("<div class=\"para img\"><figure><img src=\"").append(mm.group(2)).append("\" alt=\"").append(esc(mm.group(1))).append("\" loading=\"lazy\">")
                        .append(mm.group(1).isEmpty() ? "" : "<figcaption>" + esc(mm.group(1)) + "</figcaption>").append("</figure></div>");
                }
                i++;
                continue;
            }
            String cls = "para " + p.lvl() + ("c".equals(p.al()) ? " c" : "r".equals(p.al()) ? " r" : "");
            String style = p.ind() > 0 && "b".equals(p.lvl()) ? " style=\"padding-left:" + (p.ind() * 1.6) + "em\"" : "";
            out.append("<div class=\"").append(cls).append('"').append(style).append('>').append(cell(p.text(), terms, resolve)).append("</div>");
            i++;
        }
        return out.toString();
    }

    /** 글 비교: 낱말 단위로 지운 것은 del, 더한 것은 ins로 표시한다. */
    public static String diff(String before, String after) {
        List<String> a = tokens(before), b = tokens(after);
        if ((long) a.size() * b.size() > 4_000_000L) {
            return "<del>" + esc(before) + "</del>\n<ins>" + esc(after) + "</ins>";
        }
        int n = a.size(), m = b.size();
        int[][] L = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) for (int j = m - 1; j >= 0; j--)
            L[i][j] = a.get(i).equals(b.get(j)) ? L[i + 1][j + 1] + 1 : Math.max(L[i + 1][j], L[i][j + 1]);
        StringBuilder out = new StringBuilder();
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) { out.append(esc(a.get(i))); i++; j++; }
            else if (L[i + 1][j] >= L[i][j + 1]) { out.append("<del>").append(esc(a.get(i))).append("</del>"); i++; }
            else { out.append("<ins>").append(esc(b.get(j))).append("</ins>"); j++; }
        }
        while (i < n) out.append("<del>").append(esc(a.get(i++))).append("</del>");
        while (j < m) out.append("<ins>").append(esc(b.get(j++))).append("</ins>");
        return out.toString().replace("</del><del>", "").replace("</ins><ins>", "");
    }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\s+|[^\\s]+").matcher(s == null ? "" : s);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** 앞뒤를 줄인 한 토막. 찾은 말 주변을 보여 줄 때 쓴다. */
    public static String snippet(String text, List<String> terms, int width) {
        String plain = RuleParser.mdStrip(text == null ? "" : text).replaceAll("\\|", " ").replaceAll("\\s+", " ").strip();
        int at = -1;
        String low = plain.toLowerCase();
        for (String t : terms) {
            int k = low.indexOf(t.toLowerCase());
            if (k >= 0 && (at < 0 || k < at)) at = k;
        }
        int from = Math.max(0, at < 0 ? 0 : at - width / 3);
        int to = Math.min(plain.length(), from + width);
        String cut = (from > 0 ? "…" : "") + plain.substring(from, to) + (to < plain.length() ? "…" : "");
        return highlight(esc(cut), terms);
    }
}
