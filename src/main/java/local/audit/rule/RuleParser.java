package local.audit.rule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 붙여 넣은 사규 글을 조·부칙·별첨으로 가른다. 예전 '사규 관리 시스템 v4'의 규칙을 옮겼다.
 * 조 하나는 Map(id, number, title, chapter, section, content, tags, difficult, crossRefs, supp, attach)로 돌려준다.
 */
public final class RuleParser {
    private RuleParser() { }

    public static final String CIRC = "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳";
    static final Pattern RE_ART = Pattern.compile("^\\s*제\\s*(\\d+)\\s*조(?:\\s*의\\s*(\\d+))?\\s*(?:\\(([^)]{0,40})\\)|（([^）]{0,40})）|【([^】]{0,40})】|\\[([^\\]]{0,40})\\])?\\s*(.*)$");
    static final Pattern RE_DIV = Pattern.compile("^\\s*제\\s*(\\d+)\\s*(편|장|절|관)\\s*(.*)$");
    static final Pattern RE_SUPP = Pattern.compile("^\\s*부\\s*칙\\s*(?:[<(（〈][^>)）〉]*[>)）〉])?\\s*(.*)$");
    static final Pattern REF_TAIL = Pattern.compile("^(?:에|의|를|을|및|와|과|는|은|이|가|에서|부터|까지|또는|내지|·|,|，|ㆍ|제\\s*\\d+\\s*[항호]|각\\s*호|단서|본문|전단|후단|에\\s*따|의\\s*규정|에\\s*의|에도|로|으로|중)");
    static final Pattern RE_ATT = Pattern.compile("^[\\[<(（〈【]?\\s*(별\\s*첨|별\\s*표|별\\s*지|붙\\s*임|서\\s*식|부\\s*표)\\s*(?:제\\s*)?(\\d+)?\\s*(?:호|번)?\\s*[\\]>)）〉】]?\\s*[.:·\\-–)]?\\s*(.*)$");

    /** 굵게·밑줄 같은 꾸밈 표시를 뗀 글. 조·부칙·별첨 판정은 이것으로 한다. */
    public static String mdStrip(String t) {
        return String.valueOf(t == null ? "" : t)
            .replaceFirst("^[ \\t]*>{2,3}[ \\t]*", "")
            .replaceAll("\\*\\*([^*\\n]{1,300})\\*\\*", "$1")
            .replaceAll("__([^_\\n]{1,300})__", "$1")
            .replaceAll("~~([^~\\n]{1,300})~~", "$1")
            .replaceAll("==([^=\\n]{1,300})==", "$1")
            .replaceAll("[*_~=]{2,}", "");
    }

    static String artNum(String n, String sub) { return "제" + n + "조" + (sub != null && !sub.isEmpty() ? "의" + sub : ""); }

    /** 제5조의2 → 5.02 */
    public static Double parseArtNo(String s) {
        Matcher m = Pattern.compile("제\\s*(\\d+)\\s*조(?:\\s*의\\s*(\\d+))?").matcher(s == null ? "" : s);
        if (!m.find()) return null;
        return Integer.parseInt(m.group(1)) + (m.group(2) != null ? Integer.parseInt(m.group(2)) / 100.0 : 0);
    }

    static String suppTag(String line) {
        String t = "";
        Matcher m = Pattern.compile("[<(（〈\\[【]\\s*([^>)）〉\\]】]{1,40})\\s*[>)）〉\\]】]").matcher(line);
        if (m.find()) t = m.group(1);
        if (t.isEmpty()) {
            Matcher d = Pattern.compile("((?:19|20)\\d{2}\\s*[.\\-/년]\\s*\\d{1,2}\\s*[.\\-/월]\\s*\\d{1,2})").matcher(line);
            if (d.find()) t = d.group(1);
        }
        t = t.replaceAll("\\s+", "").replaceAll("[년월]", ".").replaceFirst("일$", "").replaceFirst("[.\\-/]+$", "");
        Matcher d2 = Pattern.compile("((?:19|20)\\d{2}[.\\-/]\\d{1,2}[.\\-/]\\d{1,2})").matcher(t);
        return d2.find() ? d2.group(1) : t.substring(0, Math.min(20, t.length()));
    }

    record Att(String number, String title, int no) { }

    static Att attInfo(String line, int n) {
        if (line.length() > 90) return null;
        Matcher m = RE_ATT.matcher(line);
        if (!m.matches()) return null;
        String kind = m.group(1).replaceAll("\\s+", "");
        int num = m.group(2) != null ? Integer.parseInt(m.group(2)) : n;
        String rest = m.group(3) == null ? "" : m.group(3).strip();
        if (!rest.isEmpty() && Pattern.compile("^(에|의|를|을|와|과|및|에서|에\\s*따|과\\s*같|와\\s*같|이\\s*라|부터|까지|중)").matcher(rest).find()) return null;
        if (rest.length() > 60) return null;
        if (m.group(2) == null && rest.isEmpty()) return null;
        return new Att(kind + num, rest, num);
    }

    record Head(String number, String title, String rest, double no) { }

    static Head headingInfo(String line, Double prevNo) {
        Matcher m = RE_ART.matcher(line);
        if (!m.matches()) return null;
        String title = firstNonNull(m.group(3), m.group(4), m.group(5), m.group(6)).strip();
        String rest = m.group(7) == null ? "" : m.group(7).strip();
        double no = Integer.parseInt(m.group(1)) + (m.group(2) != null ? Integer.parseInt(m.group(2)) / 100.0 : 0);
        if (title.isEmpty()) {
            if (!rest.isEmpty() && REF_TAIL.matcher(rest).find()) return null;
            if (!rest.isEmpty() && prevNo != null && Math.floor(no) != Math.floor(prevNo) + 1 && no != prevNo) return null;
        } else if (Pattern.compile("^(에|의|를|을|및|와|과|에서|에\\s*따)").matcher(rest).find()) return null;
        if (prevNo != null && no < prevNo && title.isEmpty()) return null;
        return new Head(artNum(m.group(1), m.group(2)), title, rest, no);
    }

    private static String firstNonNull(String... s) {
        for (String x : s) if (x != null) return x;
        return "";
    }

    /** 표 한 줄: | 칸1 | 칸2 | */
    public static boolean isTblRow(String t) {
        String s = t == null ? "" : t.strip();
        return s.matches("^\\|.*\\|$") && s.split("\\|", -1).length >= 4;
    }

    /** 탭으로 나뉜 줄(엑셀·한글에서 복사)도 표로 본다. */
    static String tsvToTbl(String t) {
        StringBuilder sb = new StringBuilder();
        for (String l : t.split("\n", -1)) {
            if (sb.length() > 0) sb.append('\n');
            if (l.indexOf('\t') < 0) { sb.append(l); continue; }
            String[] cs = l.split("\t", -1);
            int filled = 0;
            for (String c : cs) if (!c.strip().isEmpty()) filled++;
            if (filled >= 2) {
                StringBuilder row = new StringBuilder("|");
                for (String c : cs) row.append(' ').append(c.strip().replaceAll("\\s+", " ").replace('|', '/')).append(" |");
                sb.append(row);
            } else {
                sb.append(String.join(" ", java.util.Arrays.stream(cs).map(String::strip).toList()));
            }
        }
        return sb.toString();
    }

    static String normalizeText(String t) {
        String s = tsvToTbl(String.valueOf(t == null ? "" : t).replace("\r\n", "\n").replace('\r', '\n'));
        s = s.replace(' ', ' ').replace('　', ' ').replace("\t", "  ");
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            Matcher m = Pattern.compile("^([ ]*)(.*)$").matcher(line);
            if (sb.length() > 0) sb.append('\n');
            if (m.matches()) sb.append(m.group(1)).append(m.group(2).replaceAll("[ ]{2,}", " "));
            else sb.append(line);
        }
        return sb.toString().replaceAll("([^\\s\\d][^\\s][.다。])\\s+(제\\s*\\d+\\s*조(?:\\s*의\\s*\\d+)?\\s*[(（【\\[][^)）】\\]]{1,30}[)）】\\]])", "$1\n$2");
    }

    static String splitParas(String content) {
        return content.replaceAll("([.다:)])[ \\t]*([" + CIRC + "])", "$1\n$2").replaceAll("\n{3,}", "\n\n").strip();
    }

    static int seq;
    static String uid() { return "a_" + Long.toString(System.currentTimeMillis(), 36) + Integer.toString(++seq & 0xfffff, 36) + Integer.toString((int) (Math.random() * 46656), 36); }

    public static List<Map<String, Object>> parse(String text) { return parse(text, false); }

    private static List<Map<String, Object>> parse(String raw, boolean retry) {
        String text = normalizeText(raw);
        List<Map<String, Object>> arts = new ArrayList<>();
        List<String> pre = new ArrayList<>();
        String chapter = "", section = "", sTag = "", sChap = "";
        boolean supp = false, att = false;
        Double prevNo = null;
        int sIdx = 0, attN = 0;
        Map<String, Object> cur = null;
        List<String> lines = null;
        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.replaceFirst("\\s+$", "");
            String tl = line.strip();
            if (tl.isEmpty()) { if (cur != null) lines.add(""); continue; }
            String pl = mdStrip(tl);

            Matcher sp = RE_SUPP.matcher(pl);
            if (sp.matches() && pl.length() < 60 && !pl.startsWith("부칙에")) {
                push(arts, cur, lines);
                supp = true; att = false; prevNo = null; sIdx++; sTag = suppTag(pl);
                sChap = "부칙" + (!sTag.isEmpty() ? " <" + sTag + ">" : (sIdx > 1 ? " " + sIdx : ""));
                cur = art(sChap, sp.group(1) == null ? "" : sp.group(1).strip(), sChap, "");
                cur.put("supp", true); cur.put("sIdx", sIdx); cur.put("sTag", sTag);
                lines = new ArrayList<>();
                continue;
            }
            Att at = attInfo(pl, attN + 1);
            if (at != null && (att || supp || !arts.isEmpty())) {
                push(arts, cur, lines);
                att = true; supp = false; prevNo = null; attN = at.no();
                cur = art(at.number(), at.title(), "별첨", "");
                cur.put("attach", true);
                lines = new ArrayList<>();
                continue;
            }
            Matcher dv = RE_DIV.matcher(pl);
            if (!att && dv.matches() && dv.group(3).length() < 40 && !REF_TAIL.matcher(dv.group(3)).find() && !dv.group(3).matches(".*[다.]$")) {
                String name = ("제" + dv.group(1) + dv.group(2) + " " + dv.group(3)).strip();
                if ("장".equals(dv.group(2)) || "편".equals(dv.group(2))) { chapter = name; section = ""; } else section = name;
                continue;
            }
            Head h = att ? null : headingInfo(pl, prevNo);
            if (h != null) {
                String rest = h.rest();
                if (!rest.isEmpty()) {
                    boolean em = Pattern.compile("^[ \\t]*(\\*\\*|__)").matcher(tl).find();
                    Matcher cut = Pattern.compile("^[ \\t]*(?:\\*\\*|__)*[ \\t]*제[ \\t]*\\d+[ \\t]*조(?:[ \\t]*의[ \\t]*\\d+)?[ \\t]*(?:[(（【\\[][^)）】\\]]{0,40}[)）】\\]])?[ \\t]*"
                        + (em ? "(?:\\*\\*|__)*[ \\t]*" : "")).matcher(tl);
                    if (cut.find()) {
                        String r0 = tl.substring(cut.end());
                        if (mdStrip(r0).replaceAll("\\s+", "").equals(rest.replaceAll("\\s+", ""))) rest = r0;
                    }
                }
                if (cur != null && Boolean.TRUE.equals(cur.get("supp")) && String.join("", lines).isBlank() && sChap.equals(cur.get("number"))) cur = null;
                else push(arts, cur, lines);
                prevNo = h.no();
                cur = art((supp ? "부칙 " : "") + h.number(), h.title(), supp ? sChap : chapter, supp ? "" : section);
                if (supp) { cur.put("supp", true); cur.put("sIdx", sIdx); cur.put("sTag", sTag); }
                lines = new ArrayList<>();
                if (!rest.isEmpty()) lines.add(rest);
                continue;
            }
            if (cur != null) lines.add(line); else pre.add(line);
        }
        push(arts, cur, lines);
        if (sIdx > 1) {
            for (Map<String, Object> a : arts) {
                String num = String.valueOf(a.get("number"));
                if (Boolean.TRUE.equals(a.get("supp")) && num.startsWith("부칙 제")) {
                    String tag = String.valueOf(a.getOrDefault("sTag", ""));
                    a.put("number", "부칙" + (!tag.isEmpty() ? "<" + tag + ">" : String.valueOf(a.get("sIdx"))) + " " + num.substring(3));
                }
            }
        }
        boolean anyArt = arts.stream().anyMatch(a -> String.valueOf(a.get("number")).matches("^제\\d+조.*"));
        if (!anyArt && !retry) {
            StringBuilder plain = new StringBuilder();
            for (String l : text.split("\n", -1)) plain.append(mdStrip(l)).append('\n');
            String plainAll = plain.toString();
            if (Pattern.compile("(?m)^[ \\t]*제[ \\t]*\\d+[ \\t]*조").matcher(plainAll).find() && !plainAll.strip().equals(text.strip())) return parse(plainAll, true);
        }
        String preText = String.join("\n", pre);
        if (arts.isEmpty() && !pre.isEmpty()) {
            Map<String, Object> a = art("본문", "", "", "");
            a.put("content", splitParas(preText));
            arts.add(a);
        } else if (!preText.isBlank()) {
            Map<String, Object> a = art("전문", "", "", "");
            a.put("content", preText.strip());
            arts.add(0, a);
        }
        return arts;
    }

    private static Map<String, Object> art(String number, String title, String chapter, String section) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", uid());
        a.put("number", number);
        a.put("title", title);
        a.put("chapter", chapter);
        if (!section.isEmpty()) a.put("section", section);
        a.put("content", "");
        a.put("tags", new ArrayList<>());
        a.put("difficult", false);
        a.put("crossRefs", new ArrayList<>());
        return a;
    }

    private static void push(List<Map<String, Object>> arts, Map<String, Object> cur, List<String> lines) {
        if (cur == null) return;
        cur.put("content", splitParas(String.join("\n", lines)));
        arts.add(cur);
    }

    /** 조 본문 한 줄의 꼴: p 항(①) · h 호(1.) · m 목(가.) · b 그 밖 · t 표 · img 그림. */
    public record Para(String lvl, String text, int ind, String al) { }

    public static List<Para> structure(String content) {
        List<Para> out = new ArrayList<>();
        for (String l0 : String.valueOf(content == null ? "" : content).split("\n", -1)) {
            String l = l0.replace("\t", "  ");
            Matcher sp = Pattern.compile("^[ ]*").matcher(l);
            int ind = sp.find() ? Math.min(8, sp.group().length() / 2) : 0;
            String t = l.strip();
            if (t.isEmpty()) {
                // 빈 줄은 문단 사이 간격으로 남긴다(맨 앞·겹친 빈 줄은 뺌)
                if (!out.isEmpty() && !"gap".equals(out.get(out.size() - 1).lvl())) out.add(new Para("gap", "", 0, ""));
                continue;
            }
            String al = "";
            Matcher am = Pattern.compile("^(>>>|>>)\\s*").matcher(t);
            if (am.find()) { al = ">>>".equals(am.group(1)) ? "r" : "c"; t = t.substring(am.end()); }
            String pt = mdStrip(t);
            if (pt.matches("^!\\[[^\\]]*\\]\\(data:image/.*")) { out.add(new Para("img", pt, ind, al)); continue; }
            if (isTblRow(pt)) { out.add(new Para("t", t, ind, al)); continue; }
            String lvl = "b";
            if (!pt.isEmpty() && CIRC.indexOf(pt.charAt(0)) >= 0) lvl = "p";
            else if (pt.matches("^\\(\\d+\\).*")) lvl = "p";
            else if (pt.matches("^\\d+\\.\\s.*")) lvl = "h";
            else if (pt.matches("^[가-하]\\.\\s.*")) lvl = "m";
            out.add(new Para(lvl, t, ind, al));
        }
        while (!out.isEmpty() && "gap".equals(out.get(out.size() - 1).lvl())) out.remove(out.size() - 1);
        return out;
    }
}
