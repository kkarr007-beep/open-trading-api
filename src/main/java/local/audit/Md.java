package local.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 예전 업무·일정 관리의 노트 글(마크다운)을 화면용 HTML로 바꾼다.
 * 제목(#), 목록(-, 1.), 체크(- [ ]), 인용(>), 표(| 칸 |), 굵게·기울임·취소선·강조·코드, [[연결]], #태그.
 */
public final class Md {
    private Md() { }

    /** 마크다운으로 쓴 글로 보이는지. 표·제목·목록 표시가 있으면 그렇다고 본다. */
    public static boolean looksLike(String s) {
        if (s == null) return false;
        return Pattern.compile("(?m)^(#{1,3}\\s|\\s*[-*]\\s|\\s*\\d+\\.\\s|>\\s?|\\s*\\|.*\\|\\s*$)").matcher(s).find()
            || s.contains("**") || s.contains("[[");
    }

    static String inl(String s) {
        String h = Rich_esc(s);
        h = h.replaceAll("\\[\\[([^\\]|]+)(?:\\|([^\\]]+))?\\]\\]", "<u>$1</u>");
        h = h.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>");
        h = h.replaceAll("(?<![*\\w])\\*([^*\\s][^*]*)\\*", "<i>$1</i>");
        h = h.replaceAll("~~([^~]+)~~", "<s>$1</s>");
        h = h.replaceAll("==([^=]+)==", "<mark>$1</mark>");
        h = h.replaceAll("`([^`]+)`", "<code>$1</code>");
        h = h.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "<u>$1</u>");
        h = h.replaceAll("(^|\\s)#([가-힣\\w/\\-]+)", "$1<span style=\"color:#4f5bd5\">#$2</span>");
        return h;
    }

    private static String Rich_esc(String s) { return local.audit.extract.Rich.esc(s); }

    public static String toHtml(String src) {
        if (src == null || src.isBlank()) return "";
        String[] lines = src.replace("\r\n", "\n").split("\n", -1);
        StringBuilder h = new StringBuilder();
        List<String> tbl = new ArrayList<>();
        String inList = null;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.strip().startsWith("|")) {
                if (inList != null) { h.append("</").append(inList).append('>'); inList = null; }
                tbl.add(l.strip());
                continue;
            }
            if (!tbl.isEmpty()) { table(h, tbl); tbl.clear(); }
            Matcher m;
            if ((m = Pattern.compile("^(#{1,4})\\s+(.*)").matcher(l)).matches()) {
                if (inList != null) { h.append("</").append(inList).append('>'); inList = null; }
                int n = m.group(1).length() + 1;
                h.append("<h").append(n).append('>').append(inl(m.group(2))).append("</h").append(n).append('>');
            } else if ((m = Pattern.compile("^>\\s?(.*)").matcher(l)).matches()) {
                if (inList != null) { h.append("</").append(inList).append('>'); inList = null; }
                h.append("<blockquote>").append(inl(m.group(1).replaceFirst("^\\[!\\w+\\]\\s*", ""))).append("</blockquote>");
            } else if ((m = Pattern.compile("^\\s*[-*]\\s+\\[( |x|X)\\]\\s+(.*)").matcher(l)).matches()) {
                if (!"ul".equals(inList)) { if (inList != null) h.append("</").append(inList).append('>'); h.append("<ul>"); inList = "ul"; }
                h.append("<li>").append(" ".equals(m.group(1)) ? "☐ " : "☑ ").append(inl(m.group(2))).append("</li>");
            } else if ((m = Pattern.compile("^\\s*[-*•]\\s+(.*)").matcher(l)).matches()) {
                if (!"ul".equals(inList)) { if (inList != null) h.append("</").append(inList).append('>'); h.append("<ul>"); inList = "ul"; }
                h.append("<li>").append(inl(m.group(1))).append("</li>");
            } else if ((m = Pattern.compile("^\\s*\\d+[.)]\\s+(.*)").matcher(l)).matches()) {
                if (!"ol".equals(inList)) { if (inList != null) h.append("</").append(inList).append('>'); h.append("<ol>"); inList = "ol"; }
                h.append("<li>").append(inl(m.group(1))).append("</li>");
            } else if (l.strip().matches("-{3,}|\\*{3,}")) {
                if (inList != null) { h.append("</").append(inList).append('>'); inList = null; }
                h.append("<hr>");
            } else if (l.isBlank()) {
                if (inList != null) { h.append("</").append(inList).append('>'); inList = null; }
                h.append("<p><br></p>");
            } else {
                if (inList != null) { h.append("</").append(inList).append('>'); inList = null; }
                h.append("<p>").append(inl(l)).append("</p>");
            }
        }
        if (!tbl.isEmpty()) table(h, tbl);
        if (inList != null) h.append("</").append(inList).append('>');
        return h.toString();
    }

    private static void table(StringBuilder h, List<String> rows) {
        h.append("<table>");
        int r = 0;
        for (String row : rows) {
            if (row.matches("^\\|?\\s*:?-{2,}.*")) continue;
            String body = row.replaceAll("^\\||\\|$", "");
            h.append("<tr>");
            for (String c : body.split("\\|", -1)) {
                String tag = r == 0 ? "th" : "td";
                h.append('<').append(tag).append('>').append(inl(c.strip())).append("</").append(tag).append('>');
            }
            h.append("</tr>");
            r++;
        }
        h.append("</table>");
    }
}
