package local.audit.extract;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import local.audit.Kst;
import local.audit.Txt;

/**
 * 읽은 글자에서 등록에 쓸 항목을 찾아 제안을 만든다.
 * 규칙으로 찾는 것이라 틀릴 수 있다. 그래서 자동 등록은 언제나 '초안'까지만 한다.
 */
public final class DocAnalyzer {
    private DocAnalyzer() { }

    public static final List<String> SANCTIONS = Arrays.asList("견책", "감봉", "정직", "강등", "해임", "파면", "경고", "주의");
    private static final Pattern DATE = Pattern.compile("(20\\d\\d|19\\d\\d)\\s*[.\\-/년]\\s*(\\d{1,2})\\s*[.\\-/월]\\s*(\\d{1,2})");
    private static final Pattern CASE_NO = Pattern.compile("(?<!\\d)(20\\d\\d)-(\\d{2})-(\\d{2,4})(?!\\d)");
    private static final String BULLET = "[\\s□■○●◎◇◆▶▷※·•\\-–*\\d.)(]*";

    public static Map<String, Object> analyze(String fileName, String text) {
        String body = text == null ? "" : text;
        String stem = Txt.stem(fileName == null ? "" : fileName);
        String top = body.length() > 1500 ? body.substring(0, 1500) : body;
        String all = stem + "\n" + body;
        Map<String, Object> s = new LinkedHashMap<>();

        List<Map<String, Object>> discipline = disciplineRows(body);
        if (discipline.isEmpty()) {
            Map<String, Object> one = disciplineSingle(body);
            if (one != null) discipline.add(one);
        }
        List<Map<String, Object>> actions = actionRows(body);
        if (actions.isEmpty()) actions = actionLines(body);

        String docType;
        String head = stem + "\n" + top;
        if (!discipline.isEmpty() && (head.contains("징계") || head.contains("인사위원회") || actions.isEmpty())) docType = "징계";
        else if (Pattern.compile("회신|조치\\s*결과|이행\\s*결과|조치\\s*계획|이행\\s*계획|조치\\s*현황").matcher(head).find()
            && !Pattern.compile("결과\\s*보고|결과서|결과\\s*통보").matcher(stem).find()) docType = "회신";
        else if (Pattern.compile("감사\\s*결과|진단\\s*결과|결과\\s*보고|결과서|감사\\s*보고|진단\\s*보고|처분\\s*요구|지적\\s*사항").matcher(all).find()) docType = "결과";
        else docType = "기타";
        if (!"징계".equals(docType)) discipline = new ArrayList<>();
        s.put("docType", docType);
        s.put("kind", switch (docType) { case "결과" -> "결과원문"; case "회신" -> "회신첨부"; default -> "증빙"; });

        String title = label(body, "제\\s*목|건\\s*명|감사\\s*명|진단\\s*명|감사\\s*사항|진단\\s*사항", 120);
        if (title.isEmpty()) title = firstLine(body, 120);
        if (title.isEmpty()) title = Txt.fit(stem.replace('_', ' '), 120, false);
        s.put("title", title);
        s.put("target", label(body, "감사\\s*대상|진단\\s*대상|대상\\s*부서|대상\\s*기관|수감\\s*부서|피감\\s*부서|수신|대\\s*상", 120));
        s.put("refNo", label(body, "문서\\s*번호|시행\\s*번호|관리\\s*번호", 80));
        s.put("category", Pattern.compile("준법|컴플라이언스|내부\\s*통제|법규\\s*준수").matcher(head).find() ? "준법감사" : "업무진단");

        LocalDate docDate = firstDate(label(body, "시행\\s*일자?|작성\\s*일자?|보고\\s*일자?|감사\\s*기간|진단\\s*기간|일\\s*자", 80));
        if (docDate == null) docDate = firstDate(top);
        Matcher y = Pattern.compile("(?<!\\d)(20\\d\\d)(?!\\d)").matcher(stem);
        int year = y.find() ? Integer.parseInt(y.group(1)) : docDate != null ? docDate.getYear() : Kst.today().getYear();
        if (year < 1990 || year > 2100) year = Kst.today().getYear();
        s.put("year", year);
        s.put("docDate", docDate == null ? "" : docDate.toString());

        String summary = label(body, "감사\\s*결과|진단\\s*결과|종합\\s*의견|총\\s*평|결\\s*론|요\\s*약|주요\\s*내용", 160);
        if (summary.isEmpty() && "결과".equals(docType)) summary = Txt.fit(title, 160, false);
        s.put("summary", summary);

        Matcher no = CASE_NO.matcher(all);
        s.put("caseNo", no.find() ? no.group() : "");
        Matcher seq = Pattern.compile("(?:조치\\s*요구|지적\\s*사항|순번|연번)\\s*(?:제\\s*)?(\\d{1,2})\\s*(?:호|번)?").matcher(head);
        s.put("replySeq", "회신".equals(docType) && seq.find() ? Integer.valueOf(seq.group(1)) : null);

        LocalDate due = firstDate(label(body, "회신\\s*기한|조치\\s*기한|제출\\s*기한|이행\\s*기한|회신\\s*요청일", 80));
        String target = Txt.str(s.get("target"));
        for (Map<String, Object> a : actions) {
            if (Txt.blank(a.get("dept"))) a.put("dept", target);
            if (Txt.blank(a.get("dueOn")) && due != null) a.put("dueOn", due.toString());
            if (Txt.blank(a.get("requestedOn")) && docDate != null) a.put("requestedOn", docDate.toString());
        }
        s.put("actions", "결과".equals(docType) || "기타".equals(docType) ? actions : new ArrayList<>());
        s.put("discipline", discipline);
        return s;
    }

    /** "제목: 값" 또는 표의 "제목<탭>값" 꼴에서 값을 꺼낸다. */
    static String label(String text, String names, int max) {
        Matcher m = Pattern.compile("(?m)^" + BULLET + "(?:" + names + ")\\s*(?:[:：]|\\t)\\s*(.+)$").matcher(text);
        while (m.find()) {
            String v = m.group(1).split("\t")[0].strip();
            if (!v.isEmpty()) return Txt.fit(v, max, false);
        }
        return "";
    }

    static String firstLine(String text, int max) {
        for (String line : text.split("\n")) {
            String v = line.strip();
            if (v.length() >= 4 && v.length() <= 90 && !v.contains("\t") && !v.matches("[\\d\\s.\\-/:]+")) return Txt.fit(v, max, false);
        }
        return "";
    }

    public static LocalDate firstDate(String text) {
        if (text == null) return null;
        Matcher m = DATE.matcher(text);
        while (m.find()) {
            try {
                return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            } catch (RuntimeException ignored) { }
        }
        return null;
    }

    public static String dateText(String cell) {
        LocalDate d = firstDate(cell);
        if (d != null) return d.toString();
        return cell != null && cell.strip().matches("\\d{4}-\\d{2}-\\d{2}") ? cell.strip() : "";
    }

    static String actionType(String word) {
        if (word == null) return "";
        if (word.contains("권고")) return "권고";
        if (word.contains("개선")) return "개선요구";
        if (word.contains("지적") || word.contains("시정") || word.contains("주의") || word.contains("통보")) return "지적";
        return "";
    }

    /** 머리글이 있는 표(엑셀이나 문서 안의 표)에서 열 위치를 찾는다. */
    private static Map<String, Integer> header(String line, Map<String, String> columns, int need) {
        String[] cells = line.split("\t", -1);
        if (cells.length < need) return null;
        Map<String, Integer> found = new LinkedHashMap<>();
        for (int i = 0; i < cells.length; i++) {
            String c = cells[i].replaceAll("\\s", "");
            if (c.isEmpty() || c.length() > 12) continue;
            for (Map.Entry<String, String> e : columns.entrySet()) {
                if (!found.containsKey(e.getKey()) && c.matches(e.getValue())) { found.put(e.getKey(), i); break; }
            }
        }
        return found.size() >= need ? found : null;
    }

    private static String cell(String[] cells, Map<String, Integer> cols, String key) {
        Integer i = cols.get(key);
        return i == null || i >= cells.length ? "" : cells[i].strip();
    }

    private static final Map<String, String> DISCIPLINE_COLS = new LinkedHashMap<>();
    private static final Map<String, String> ACTION_COLS = new LinkedHashMap<>();
    static {
        DISCIPLINE_COLS.put("employeeNo", "사번|사원번호|직원번호");
        DISCIPLINE_COLS.put("personName", "성명|이름|대상자|징계대상자");
        DISCIPLINE_COLS.put("department", "부서|소속|부서명|소속부서");
        DISCIPLINE_COLS.put("position", "직위|직급|직책");
        DISCIPLINE_COLS.put("violationDate", "위반일자?|발생일자?|행위일자?|비위일자?");
        DISCIPLINE_COLS.put("violationText", "위반내용|비위내용|징계사유|비위사실|위반사항|사유");
        DISCIPLINE_COLS.put("ruleText", "관련규정|근거규정|적용규정|위반규정");
        DISCIPLINE_COLS.put("sanctionType", "징계종류|징계양정|양정|처분|징계처분|징계");
        DISCIPLINE_COLS.put("sanctionDate", "징계일자?|처분일자?|의결일자?");
        DISCIPLINE_COLS.put("sanctionTerm", "징계기간|처분기간|기간");
        DISCIPLINE_COLS.put("decisionBody", "결정기관|의결기관|징계기관");
        DISCIPLINE_COLS.put("docNo", "근거문서번호|문서번호|근거문서");
        DISCIPLINE_COLS.put("remark", "비고");
        ACTION_COLS.put("type", "유형|구분|처분종류|조치구분|종류");
        ACTION_COLS.put("title", "제목|지적사항|조치요구사항|요구사항|건명|지적제목|조치요구|내용요약");
        ACTION_COLS.put("dept", "대상부서|조치부서|소관부서|담당부서|부서");
        ACTION_COLS.put("dueOn", "회신기한|조치기한|이행기한|기한|제출기한");
        ACTION_COLS.put("requestedOn", "요구일자?|통보일자?");
        ACTION_COLS.put("content", "내용|상세내용|지적내용|조치내용");
    }

    static List<Map<String, Object>> disciplineRows(String text) {
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Integer> cols = null;
        for (String line : text.split("\n")) {
            if (!line.contains("\t")) { if (cols != null && line.isBlank()) cols = null; continue; }
            Map<String, Integer> h = header(line, DISCIPLINE_COLS, 3);
            if (h != null && h.containsKey("personName") && h.containsKey("sanctionType")) { cols = h; continue; }
            if (cols == null) continue;
            String[] cells = line.split("\t", -1);
            String name = cell(cells, cols, "personName");
            String type = sanction(cell(cells, cols, "sanctionType"));
            if (name.isEmpty() || name.length() > 40 || type.isEmpty()) continue;
            Map<String, Object> d = new LinkedHashMap<>();
            for (String key : DISCIPLINE_COLS.keySet()) d.put(key, cell(cells, cols, key));
            d.put("sanctionType", type);
            d.put("violationDate", dateText(cell(cells, cols, "violationDate")));
            d.put("sanctionDate", dateText(cell(cells, cols, "sanctionDate")));
            list.add(d);
            if (list.size() >= 500) break;
        }
        return list;
    }

    /** 징계대장의 머리글 줄이면 열 위치를, 아니면 null. */
    public static Map<String, Integer> disciplineHeader(String line) {
        Map<String, Integer> h = header(line, DISCIPLINE_COLS, 3);
        return h != null && h.containsKey("personName") && h.containsKey("sanctionType") ? h : null;
    }

    public static String sanction(String text) {
        if (text == null) return "";
        for (String t : SANCTIONS) if (text.contains(t)) return t;
        return "";
    }

    static Map<String, Object> disciplineSingle(String text) {
        String name = label(text, "성\\s*명|징계\\s*대상자|대상자|이\\s*름", 40);
        if (!name.matches("[가-힣]{2,5}(\\s*\\(.*\\))?") && !name.matches("[A-Za-z][A-Za-z .]{1,38}")) return null;
        String type = sanction(label(text, "징계\\s*종류|징계\\s*양정|양\\s*정|처분\\s*내용|징계\\s*처분|의결\\s*내용|처\\s*분", 80));
        if (type.isEmpty()) return null;
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("employeeNo", label(text, "사\\s*번|사원\\s*번호|직원\\s*번호", 40));
        d.put("personName", name.replaceAll("\\s*\\(.*\\)$", ""));
        d.put("department", label(text, "소\\s*속|부\\s*서", 80));
        d.put("position", label(text, "직\\s*위|직\\s*급|직\\s*책", 40));
        d.put("violationDate", dateText(label(text, "위반\\s*일자?|발생\\s*일자?|행위\\s*일자?|비위\\s*일자?", 80)));
        d.put("violationText", label(text, "위반\\s*내용|비위\\s*내용|징계\\s*사유|비위\\s*사실|위반\\s*사항", 500));
        d.put("ruleText", label(text, "관련\\s*규정|근거\\s*규정|적용\\s*규정|위반\\s*규정", 240));
        d.put("sanctionType", type);
        d.put("sanctionDate", dateText(label(text, "징계\\s*일자?|처분\\s*일자?|의결\\s*일자?", 80)));
        d.put("sanctionTerm", label(text, "징계\\s*기간|처분\\s*기간", 80));
        d.put("decisionBody", label(text, "결정\\s*기관|의결\\s*기관|징계\\s*기관", 80));
        d.put("docNo", label(text, "근거\\s*문서\\s*번호|문서\\s*번호|시행\\s*번호", 80));
        d.put("remark", "");
        return d;
    }

    static List<Map<String, Object>> actionRows(String text) {
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Integer> cols = null;
        for (String line : text.split("\n")) {
            if (!line.contains("\t")) { if (cols != null && line.isBlank()) cols = null; continue; }
            Map<String, Integer> h = header(line, ACTION_COLS, 2);
            if (h != null && h.containsKey("title") && (h.containsKey("type") || h.containsKey("dueOn"))) { cols = h; continue; }
            if (cols == null) continue;
            String[] cells = line.split("\t", -1);
            String title = cell(cells, cols, "title");
            String type = actionType(cell(cells, cols, "type"));
            if (title.length() < 2 || (cols.containsKey("type") && type.isEmpty())) continue;
            list.add(action(type.isEmpty() ? "지적" : type, title, cell(cells, cols, "dept"),
                dateText(cell(cells, cols, "requestedOn")), dateText(cell(cells, cols, "dueOn")), cell(cells, cols, "content")));
            if (list.size() >= 100) break;
        }
        return list;
    }

    /** "[지적] 제목", "1. (권고) 제목", "개선요구: 제목" 같은 줄. */
    static List<Map<String, Object>> actionLines(String text) {
        List<Map<String, Object>> list = new ArrayList<>();
        Pattern p = Pattern.compile("^" + BULLET + "[\\[(<【〔]?\\s*(지적|개선\\s*요구|권고|시정\\s*요구|주의\\s*요구)\\s*(?:사항)?\\s*\\d*\\s*[\\])>】〕]?\\s*[:：.\\-]?\\s+(.{4,160})$");
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.contains("\t") || line.length() > 200) continue;
            Matcher m = p.matcher(line);
            if (!m.find()) continue;
            String title = m.group(2).strip();
            if (title.matches(".*(없음|해당\\s*없음)\\.?$")) continue;
            StringBuilder content = new StringBuilder();
            for (int j = i + 1; j < lines.length && j <= i + 12; j++) {
                String next = lines[j].strip();
                if (next.isEmpty() || lines[j].contains("\t") || p.matcher(next).find()) break;
                content.append(next).append('\n');
            }
            list.add(action(actionType(m.group(1)), title, "", "", "", content.toString().strip()));
            if (list.size() >= 50) break;
        }
        return list;
    }

    private static Map<String, Object> action(String type, String title, String dept, String requestedOn, String dueOn, String content) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", type.isEmpty() ? "지적" : type);
        a.put("title", Txt.fit(title, 120, false));
        a.put("dept", Txt.fit(dept, 120, false));
        a.put("requestedOn", requestedOn);
        a.put("dueOn", dueOn);
        a.put("content", Txt.fit(content, 20000, true));
        return a;
    }

    /** 제안에서 쓰는 열 이름. 화면이 순서대로 보여 준다. */
    public static List<String> disciplineKeys() { return Arrays.asList(DISCIPLINE_COLS.keySet().toArray(new String[0])); }
}
