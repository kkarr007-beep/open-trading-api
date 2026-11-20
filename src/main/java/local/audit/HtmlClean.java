package local.audit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 편집기·붙여 넣기·문서에서 온 HTML을 저장해도 안전한 모양으로 다듬는다.
 * 허락한 태그(문단, 글자 꾸밈, 목록, 표 — 표 안의 표 포함, 그림)와 허락한 서식(글자색, 배경색, 크기, 글꼴, 정렬, 들여쓰기, 테두리)만 남긴다.
 * 스크립트, 이벤트 속성, 외부 주소를 부르는 그림·스타일은 모두 버린다.
 */
public final class HtmlClean {
    private HtmlClean() { }

    public static final int MAX = 6_000_000;

    private static final Set<String> TAGS = Set.of("p", "div", "br", "span", "b", "strong", "i", "em", "u", "s", "del", "ins", "sub", "sup",
        "mark", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "blockquote", "pre", "code", "table", "caption", "colgroup", "col",
        "thead", "tbody", "tfoot", "tr", "th", "td", "img", "a", "hr", "figure", "figcaption", "section");
    private static final Set<String> VOID = Set.of("br", "img", "col", "hr");
    private static final Set<String> KEEP_CLASS = Set.of("para", "b", "p", "h", "m", "t", "gap", "img", "muted", "ctbl", "ctblw", "xref");
    private static final Map<String, String> RENAME = Map.of("strike", "s", "font", "span", "center", "div", "article", "div", "header", "div",
        "footer", "div", "main", "div", "nav", "div", "dl", "div", "dd", "div");
    private static final Set<String> DROP_WITH_CONTENT = Set.of("script", "style", "head", "title", "xml", "noscript", "iframe", "object",
        "embed", "template", "svg", "math", "select", "option", "button", "textarea");
    private static final Set<String> CSS = Set.of("color", "background-color", "font-size", "font-weight", "font-style", "font-family",
        "text-decoration", "text-decoration-line", "text-align", "vertical-align", "padding-left", "margin-left", "text-indent",
        "line-height", "letter-spacing", "width", "min-width", "border", "border-top", "border-bottom", "border-left", "border-right",
        "border-collapse", "border-color", "border-style", "border-width", "padding", "white-space", "list-style-type", "height", "table-layout", "margin-top", "margin-bottom", "tab-size");

    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->|<!\\[[^]]*]>|<\\?[^>]*>|<!DOCTYPE[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9:_-]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*)>");
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z_:][-a-zA-Z0-9_:.]*)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+)))?");

    public static String clean(String html) {
        if (html == null || html.isBlank()) return "";
        String s = html.length() > MAX * 2 ? html.substring(0, MAX * 2) : html;
        s = COMMENT.matcher(s).replaceAll("");
        for (String t : DROP_WITH_CONTENT) s = s.replaceAll("(?is)<" + t + "\\b[^>]*>.*?</" + t + "\\s*>", "").replaceAll("(?is)<" + t + "\\b[^>]*/?>", "");
        StringBuilder out = new StringBuilder(s.length());
        Deque<String> open = new ArrayDeque<>();
        Matcher m = TAG.matcher(s);
        int at = 0;
        while (m.find()) {
            text(out, s.substring(at, m.start()));
            at = m.end();
            String name = m.group(2).toLowerCase(Locale.ROOT);
            if (name.contains(":")) continue; // o:p, w:… 같은 문서 프로그램 표시
            name = RENAME.getOrDefault(name, name);
            if (!TAGS.contains(name)) continue;
            boolean closing = !m.group(1).isEmpty();
            if (closing) {
                if (VOID.contains(name) || !open.contains(name)) continue;
                while (!open.isEmpty()) {
                    String top = open.pop();
                    out.append("</").append(top).append('>');
                    if (top.equals(name)) break;
                }
                continue;
            }
            String attrs = attrs(m.group(2).toLowerCase(Locale.ROOT), name, m.group(3));
            if (attrs == null) continue;
            // 표 칸·줄은 표 안에서만 연다
            if ((name.equals("td") || name.equals("th")) && !open.contains("tr")) continue;
            if (name.equals("tr") && !open.contains("table")) continue;
            out.append('<').append(name).append(attrs).append('>');
            if (!VOID.contains(name)) open.push(name);
            if (out.length() > MAX) break;
        }
        if (out.length() <= MAX) text(out, s.substring(at));
        while (!open.isEmpty()) out.append("</").append(open.pop()).append('>');
        return out.length() > MAX ? out.substring(0, MAX) : out.toString();
    }

    private static void text(StringBuilder out, String t) {
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '<') out.append("&lt;");
            else if (c == '>') out.append("&gt;");
            else if (c == '&') {
                int semi = t.indexOf(';', i);
                if (semi > i && semi - i <= 10 && t.substring(i + 1, semi).matches("#?[a-zA-Z0-9]+")) out.append('&');
                else out.append("&amp;");
            } else if (c == '"') out.append("&quot;");
            else if (c >= 0x20 || c == '\n' || c == '\t') out.append(c);
        }
    }

    private static String attrs(String original, String name, String raw) {
        StringBuilder a = new StringBuilder();
        StringBuilder style = new StringBuilder();
        Matcher m = ATTR.matcher(raw == null ? "" : raw);
        while (m.find()) {
            String k = m.group(1).toLowerCase(Locale.ROOT);
            String v = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4) != null ? m.group(4) : "";
            v = v.replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
            switch (k) {
                case "style" -> style.append(css(v));
                case "class" -> {
                    // 사규 글의 단계 표시(본문·항·호·목)와 표 꼴은 남긴다. 그 밖의 class는 버린다.
                    StringBuilder c = new StringBuilder();
                    for (String t : v.trim().split("\\s+")) if (KEEP_CLASS.contains(t)) c.append(c.length() == 0 ? "" : " ").append(t);
                    if (c.length() > 0) a.append(" class=\"").append(c).append('"');
                }
                case "colspan", "rowspan" -> {
                    if ((name.equals("td") || name.equals("th")) && v.matches("\\d{1,3}") && Integer.parseInt(v) > 1 && Integer.parseInt(v) <= 200)
                        a.append(' ').append(k).append("=\"").append(v).append('"');
                }
                case "span" -> { if (name.equals("col") && v.matches("\\d{1,2}")) a.append(" span=\"").append(v).append('"'); }
                case "align" -> { if (v.matches("(?i)left|center|right|justify")) style.append("text-align:").append(v.toLowerCase(Locale.ROOT)).append(';'); }
                case "valign" -> { if (v.matches("(?i)top|middle|bottom")) style.append("vertical-align:").append(v.toLowerCase(Locale.ROOT)).append(';'); }
                case "width" -> { if (v.matches("\\d{1,4}(px|%)?") && !name.equals("img")) style.append("width:").append(v.matches("\\d+") ? v + "px" : v).append(';'); }
                case "bgcolor" -> { if (v.matches("#?[0-9a-fA-F]{3,8}|[a-zA-Z]{3,20}")) style.append("background-color:").append(v).append(';'); }
                case "color" -> { if (original.equals("font") && v.matches("#?[0-9a-fA-F]{3,8}|[a-zA-Z]{3,20}")) style.append("color:").append(v).append(';'); }
                case "face" -> { if (original.equals("font") && v.matches("[ 가-힣a-zA-Z0-9,'\"-]{1,60}")) style.append("font-family:").append(v.replace('"', '\'')).append(';'); }
                case "size" -> {
                    if (original.equals("font") && v.matches("[1-7]")) {
                        String[] px = { "", "10px", "13px", "16px", "18px", "24px", "32px", "48px" };
                        style.append("font-size:").append(px[Integer.parseInt(v)]).append(';');
                    }
                }
                case "src" -> {
                    if (!name.equals("img")) break;
                    if (!v.matches("(?s)data:image/(png|jpe?g|gif|webp|bmp);base64,[A-Za-z0-9+/=\\s]+") || v.length() > 2_000_000) return null;
                    a.append(" src=\"").append(v.replaceAll("\\s", "")).append('"');
                }
                case "alt", "title" -> { if (!v.isEmpty()) a.append(' ').append(k).append("=\"").append(esc(v)).append('"'); }
                case "href" -> {
                    if (name.equals("a") && (v.matches("https?://[^\\s\"'<>]{1,500}") || v.matches("/[^\\s\"'<>:]{0,500}") || v.matches("#[\\w-]{1,100}")))
                        a.append(" href=\"").append(esc(v)).append('"');
                }
                default -> { }
            }
        }
        if (name.equals("img") && a.indexOf(" src=") < 0) return null;
        if (style.length() > 0) a.append(" style=\"").append(esc(style.toString())).append('"');
        return a.toString();
    }

    /** 허락한 서식만 남긴다. */
    static String css(String style) {
        StringBuilder out = new StringBuilder();
        for (String decl : style.split(";")) {
            int c = decl.indexOf(':');
            if (c <= 0) continue;
            String k = decl.substring(0, c).strip().toLowerCase(Locale.ROOT);
            String v = decl.substring(c + 1).strip();
            if (!CSS.contains(k) || v.isEmpty() || v.length() > 120) continue;
            String low = v.toLowerCase(Locale.ROOT);
            if (low.contains("url(") || low.contains("expression") || low.contains("javascript") || low.contains("@") || v.contains("\\")
                || v.contains("<") || v.contains(">") || v.contains("\"")) continue;
            if (low.startsWith("mso-") || low.contains("windowtext")) v = v.replaceAll("(?i)windowtext", "currentColor");
            if (k.equals("font-size") && !v.matches("[\\d.]{1,5}(px|pt|em|rem|%)|x*-?small|medium|x*-?large|smaller|larger")) continue;
            out.append(k).append(':').append(v).append(';');
        }
        return out.toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** HTML에서 글자만. 칸은 탭, 줄·문단은 줄바꿈. 찾기·내보내기·글자 칸에 쓴다. */
    public static String toText(String html) {
        if (html == null || html.isBlank()) return "";
        String s = COMMENT.matcher(html).replaceAll("");
        for (String t : DROP_WITH_CONTENT) s = s.replaceAll("(?is)<" + t + "\\b[^>]*>.*?</" + t + "\\s*>", "");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n")
            .replaceAll("(?i)</(td|th)\\s*>", "\t")
            .replaceAll("(?i)</(p|div|li|tr|h[1-6]|blockquote|pre|table|figure|section|caption)\\s*>", "\n")
            .replaceAll("(?i)<li\\b[^>]*>", "- ")
            .replaceAll("(?i)<img\\b[^>]*>", "[그림]")
            .replaceAll("<[^>]*>", "");
        s = unescape(s).replace('\u00a0', ' ');
        StringBuilder out = new StringBuilder();
        for (String line : s.split("\n", -1)) out.append(line.replaceAll("\t+$", "").replaceAll("[ ]+$", "")).append('\n');
        return out.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

    public static String unescape(String s) {
        Matcher m = Pattern.compile("&(#x[0-9a-fA-F]{1,6}|#\\d{1,7}|[a-zA-Z]{2,8});").matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String e = m.group(1), r;
            try {
                r = e.startsWith("#x") ? new String(Character.toChars(Integer.parseInt(e.substring(2), 16)))
                    : e.startsWith("#") ? new String(Character.toChars(Integer.parseInt(e.substring(1))))
                    : switch (e) { case "lt" -> "<"; case "gt" -> ">"; case "amp" -> "&"; case "quot" -> "\""; case "apos" -> "'"; case "nbsp" -> " "; default -> m.group(); };
            } catch (RuntimeException x) { r = m.group(); }
            m.appendReplacement(sb, Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 글자만 있는 내용을 편집기에 넣을 HTML로. 탭으로 나뉜 줄은 표로 만든다. */
    public static String fromText(String text) {
        return local.audit.extract.Rich.fromText(text);
    }
}
