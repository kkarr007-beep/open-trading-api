package local.audit.work;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 월간 업무보고서 읽기. 보고서에는 표가 두 개 있다.
 *  - 지난달 업무 현황: 구분(업무진단·내부통제·부정예방 …) / 추진항목 / 주요내용
 *  - 다음달 업무 일정: 같은 칸
 * 문서 읽기(한글·워드·PDF)가 만든 HTML에서 표를 찾아 칸을 나누고, 합친 칸(구분)은 아래 줄까지 채운다.
 * 주요내용 속 날짜(12.5~12.20, 12월 2주, 상순 …)는 시작·끝 날로 바꾼다.
 */
public final class MonthlyReport {
    private MonthlyReport() { }

    public record Row(String cat, String item, String detail, String start, String due) { }
    public record Parsed(int year, int month, List<Row> plan, List<Row> status, String note) { }

    // ───── HTML 표 읽기(이 프로그램의 문서 읽기가 만든 단순한 HTML 기준) ─────

    record Cell(String text, int colspan, int rowspan) { }
    record Table(List<List<Cell>> rows, String before) { }

    static final Pattern TAG = Pattern.compile("<(/?)(table|tr|td|th)\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    static String plain(String html) {
        return html.replaceAll("(?i)<br\\s*/?>|</div>|</p>|</li>", "\n").replaceAll("<[^>]+>", "")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
            .replaceAll("[ \\t\\u00a0]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{2,}", "\n").strip();
    }

    static int attr(String tag, String name) {
        Matcher m = Pattern.compile(name + "\\s*=\\s*\"?(\\d+)", Pattern.CASE_INSENSITIVE).matcher(tag);
        return m.find() ? Math.max(1, Math.min(50, Integer.parseInt(m.group(1)))) : 1;
    }

    /** 맨 바깥 표들. 표 안의 표는 칸 글로 합친다. */
    static List<Table> tables(String html) {
        List<Table> out = new ArrayList<>();
        Matcher m = TAG.matcher(html);
        int depth = 0, last = 0, cellStart = -1;
        String cellTag = null;
        List<List<Cell>> rows = null;
        List<Cell> row = null;
        String before = "";
        while (m.find()) {
            boolean close = !m.group(1).isEmpty();
            String t = m.group(2).toLowerCase();
            if (t.equals("table")) {
                if (!close) {
                    if (depth == 0) { before = plain(html.substring(last, m.start())); rows = new ArrayList<>(); }
                    depth++;
                } else {
                    depth--;
                    if (depth == 0 && rows != null) { out.add(new Table(rows, tail(before, 200))); rows = null; last = m.end(); }
                }
                continue;
            }
            if (depth != 1) continue;   // 안쪽 표의 줄·칸은 바깥 칸 글에 들어간다
            if (t.equals("tr") && !close) { row = new ArrayList<>(); rows.add(row); }
            else if ((t.equals("td") || t.equals("th")) && !close) { cellStart = m.end(); cellTag = m.group(); }
            else if ((t.equals("td") || t.equals("th")) && close && cellStart >= 0 && row != null) {
                row.add(new Cell(plain(html.substring(cellStart, m.start())), attr(cellTag, "colspan"), attr(cellTag, "rowspan")));
                cellStart = -1;
            }
        }
        return out;
    }

    static String tail(String s, int n) { return s.length() <= n ? s : s.substring(s.length() - n); }

    /** 합친 칸을 펼쳐 네모 칸 표로. 세로로 합친 칸의 글은 아래 줄에도 채운다. */
    static List<String[]> grid(Table t) {
        List<String[]> g = new ArrayList<>();
        java.util.Map<Integer, String[]> carry = new java.util.HashMap<>();   // 열 → {남은 줄 수, 글}
        int width = 0;
        for (List<Cell> r : t.rows()) { int w = 0; for (Cell c : r) w += c.colspan(); width = Math.max(width, w); }
        width = Math.min(width + 8, 60);
        for (List<Cell> r : t.rows()) {
            String[] line = new String[width];
            int c = 0;
            java.util.Iterator<Cell> it = r.iterator();
            while (c < width) {
                String[] cr = carry.get(c);
                if (cr != null) {
                    line[c] = cr[1];
                    int left = Integer.parseInt(cr[0]) - 1;
                    if (left <= 0) carry.remove(c); else cr[0] = String.valueOf(left);
                    c++;
                    continue;
                }
                if (!it.hasNext()) break;
                Cell cell = it.next();
                for (int k = 0; k < cell.colspan() && c + k < width; k++) {
                    line[c + k] = k == 0 ? cell.text() : "";
                    if (cell.rowspan() > 1) carry.put(c + k, new String[] { String.valueOf(cell.rowspan() - 1), k == 0 ? cell.text() : "" });
                }
                c += cell.colspan();
            }
            g.add(line);
        }
        return g;
    }

    static final Pattern H_CAT = Pattern.compile("구\\s*분|분\\s*야|영\\s*역|부\\s*문|카테고리");
    static final Pattern H_ITEM = Pattern.compile("추진\\s*항목|추진\\s*과제|과\\s*제|업무\\s*명|항\\s*목|세부\\s*업무");
    static final Pattern H_DETAIL = Pattern.compile("주요\\s*내용|추진\\s*내용|세부\\s*내용|내\\s*용|실\\s*적|추진\\s*현황");
    static final Pattern H_DATE = Pattern.compile("일\\s*정|기\\s*간|기\\s*한|추진\\s*일|완료\\s*예정|시\\s*기");

    /** 표 하나를 줄 목록으로. 머리 줄을 찾지 못하면 null. */
    static List<Row> rowsOf(Table t, int year, int month) {
        List<String[]> g = grid(t);
        int head = -1, ci = -1, ii = -1, di = -1, ti = -1;
        for (int r = 0; r < Math.min(4, g.size()) && head < 0; r++) {
            String[] line = g.get(r);
            int c1 = -1, c2 = -1, c3 = -1, c4 = -1;
            for (int c = 0; c < line.length; c++) {
                String v = line[c] == null ? "" : line[c].replaceAll("\\s+", "");
                if (v.isEmpty() || v.length() > 12) continue;
                if (c2 < 0 && H_ITEM.matcher(v).find()) c2 = c;
                else if (c3 < 0 && H_DETAIL.matcher(v).find()) c3 = c;
                else if (c4 < 0 && H_DATE.matcher(v).find()) c4 = c;
                else if (c1 < 0 && H_CAT.matcher(v).find()) c1 = c;
            }
            if (c2 >= 0 && c3 >= 0) { head = r; ci = c1; ii = c2; di = c3; ti = c4; }
        }
        if (head < 0) return null;
        if (ci < 0 && ii > 0) ci = 0;   // 구분 이름이 비어 있어도 첫 칸을 구분으로 본다
        List<Row> out = new ArrayList<>();
        String lastCat = "";
        for (int r = head + 1; r < g.size(); r++) {
            String[] line = g.get(r);
            String cat = ci >= 0 && line[ci] != null ? line[ci].replace('\n', ' ').strip() : "";
            String item = ii >= 0 && line[ii] != null ? line[ii].strip() : "";
            String detail = di >= 0 && line[di] != null ? line[di].strip() : "";
            String when = ti >= 0 && line[ti] != null ? line[ti].strip() : "";
            if (item.isEmpty() && detail.isEmpty()) continue;
            if (H_ITEM.matcher(item.replaceAll("\\s+", "")).matches()) continue;   // 쪽이 바뀌며 되풀이된 머리 줄
            if (cat.isEmpty()) cat = lastCat; else lastCat = cat;
            if (item.isEmpty()) { item = firstLine(detail); }
            LocalDate[] range = range(when + " " + detail, year, month);
            out.add(new Row(cat.isEmpty() ? "기타" : cat, item.replace('\n', ' ').strip(), detail,
                range[0] == null ? "" : range[0].toString(), range[1] == null ? "" : range[1].toString()));
        }
        return out;
    }

    static String firstLine(String s) {
        String f = s.split("\n")[0].strip();
        return f.length() > 60 ? f.substring(0, 60) : f;
    }

    // ───── 날짜 ─────

    static final Pattern MD_RANGE = Pattern.compile("(\\d{1,2})\\s*[./월]\\s*(\\d{1,2})\\s*일?\\s*[~\\-–∼]\\s*(?:(\\d{1,2})\\s*[./월]\\s*)?(\\d{1,2})\\s*일?");
    static final Pattern MD_ONE = Pattern.compile("(?<![\\d.])(\\d{1,2})\\s*[./]\\s*(\\d{1,2})(?![\\d.])|(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일");
    static final Pattern WEEK = Pattern.compile("(?:(\\d{1,2})\\s*월\\s*)?(\\d)\\s*주\\s*(?:차)?(?:\\s*[~\\-–]\\s*(\\d)\\s*주\\s*(?:차)?)?");
    static final Pattern PART = Pattern.compile("(?:(\\d{1,2})\\s*월\\s*)?(상순|중순|하순|초|말)(?![가-힣])");   // '12월 중'은 그 달 전체

    static LocalDate day(int y, int m, int d) {
        try { YearMonth ym = YearMonth.of(y, m); return ym.atDay(Math.max(1, Math.min(d, ym.lengthOfMonth()))); } catch (Exception e) { return null; }
    }

    /** 글 속 날짜를 [시작, 끝]으로. 찾지 못하면 그 달 전체. */
    static LocalDate[] range(String text, int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        String s = text == null ? "" : text;
        Matcher m = MD_RANGE.matcher(s);
        if (m.find()) {
            int m1 = Integer.parseInt(m.group(1)), d1 = Integer.parseInt(m.group(2));
            int m2 = m.group(3) != null ? Integer.parseInt(m.group(3)) : m1, d2 = Integer.parseInt(m.group(4));
            if (m1 >= 1 && m1 <= 12 && m2 >= 1 && m2 <= 12) {
                int y1 = yearFor(m1, year, month), y2 = yearFor(m2, year, month);
                LocalDate a = day(y1, m1, d1), b = day(y2, m2, d2);
                if (a != null && b != null && !b.isBefore(a)) return new LocalDate[] { a, b };
            }
        }
        m = WEEK.matcher(s);
        if (m.find()) {
            int mm = m.group(1) != null ? Integer.parseInt(m.group(1)) : month;
            int w1 = Integer.parseInt(m.group(2)), w2 = m.group(3) != null ? Integer.parseInt(m.group(3)) : w1;
            if (mm >= 1 && mm <= 12 && w1 >= 1 && w1 <= 6 && w2 >= w1 && w2 <= 6) {
                // 'N주'는 달력의 N번째 주(월요일 시작, 그 달 안의 날만). 월간 계획 화면의 주 칸과 같다.
                List<LocalDate[]> ws = monthWeeks(YearMonth.of(yearFor(mm, year, month), mm));
                LocalDate a = ws.get(Math.min(w1, ws.size()) - 1)[0], b = ws.get(Math.min(w2, ws.size()) - 1)[1];
                return new LocalDate[] { a, b };
            }
        }
        m = PART.matcher(s);
        if (m.find()) {
            int mm = m.group(1) != null ? Integer.parseInt(m.group(1)) : month;
            if (mm >= 1 && mm <= 12) {
                int yy = yearFor(mm, year, month);
                String p = m.group(2);
                int a = p.equals("하순") || p.equals("말") ? 21 : p.equals("중순") ? 11 : 1;
                int b = p.equals("하순") || p.equals("말") ? 31 : p.equals("중순") ? 20 : 10;
                return new LocalDate[] { day(yy, mm, a), day(yy, mm, b) };
            }
        }
        m = MD_ONE.matcher(s);
        if (m.find()) {
            int mm = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(3)), dd = Integer.parseInt(m.group(2) != null ? m.group(2) : m.group(4));
            if (mm >= 1 && mm <= 12 && dd >= 1 && dd <= 31) {
                LocalDate d = day(yearFor(mm, year, month), mm, dd);
                if (d != null) return new LocalDate[] { d, d };
            }
        }
        return new LocalDate[] { ym.atDay(1), ym.atEndOfMonth() };
    }

    /** 그 달의 주(월요일 시작). 첫 주·끝 주는 그 달 안 날짜만. */
    public static List<LocalDate[]> monthWeeks(YearMonth ym) {
        List<LocalDate[]> out = new ArrayList<>();
        LocalDate d = ym.atDay(1), end = ym.atEndOfMonth();
        while (!d.isAfter(end)) {
            LocalDate to = d.plusDays(7 - d.getDayOfWeek().getValue());
            if (to.isAfter(end)) to = end;
            out.add(new LocalDate[] { d, to });
            d = to.plusDays(1);
        }
        return out;
    }

    /** 12월 보고서의 '1월'은 다음 해로. */
    static int yearFor(int m, int year, int month) {
        if (month >= 11 && m <= 2) return year + 1;
        if (month <= 2 && m >= 11) return year - 1;
        return year;
    }

    static final Pattern YM = Pattern.compile("(20\\d{2})\\s*[년.]\\s*(\\d{1,2})\\s*월?");
    static final Pattern M_ONLY = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*월");

    /** 문서에서 일정 달을 찾는다: 일정 표 앞 글 → 문서 첫머리 → 오늘의 다음 달. */
    static YearMonth planMonth(String text, String beforePlan, LocalDate today) {
        Matcher m = YM.matcher(beforePlan);
        if (m.find()) { int mm = Integer.parseInt(m.group(2)); if (mm >= 1 && mm <= 12) return YearMonth.of(Integer.parseInt(m.group(1)), mm); }
        m = M_ONLY.matcher(beforePlan);
        Integer found = null;
        while (m.find()) { int mm = Integer.parseInt(m.group(1)); if (mm >= 1 && mm <= 12) found = mm; }
        if (found != null) {
            YearMonth next = YearMonth.from(today).plusMonths(1);
            int y = next.getYear();
            if (found == 1 && next.getMonthValue() == 12) y++;
            if (found == 12 && next.getMonthValue() == 1) y--;
            return YearMonth.of(y, found);
        }
        m = YM.matcher(text.substring(0, Math.min(400, text.length())));
        if (m.find()) {
            int mm = Integer.parseInt(m.group(2));
            if (mm >= 1 && mm <= 12) return YearMonth.of(Integer.parseInt(m.group(1)), mm).plusMonths(1);   // 'N월 업무보고' → 다음 달 일정
        }
        return YearMonth.from(today).plusMonths(1);
    }

    static final Pattern IS_STATUS = Pattern.compile("현\\s*황|실\\s*적|추진\\s*결과|결\\s*과|지난\\s*달|전\\s*월");
    static final Pattern IS_PLAN = Pattern.compile("일\\s*정|계\\s*획|예\\s*정|다음\\s*달|익\\s*월|차\\s*월");

    public static Parsed parse(String html, String text, LocalDate today) {
        List<Table> tables = html == null ? List.of() : tables(html);
        Table statusT = null, planT = null;
        List<Table> usable = new ArrayList<>();
        for (Table t : tables) if (rowsOf(t, today.getYear(), today.getMonthValue()) != null) usable.add(t);
        for (Table t : usable) {
            String b = t.before() + " " + (t.rows().isEmpty() ? "" : "");
            boolean st = IS_STATUS.matcher(lastLine(b)).find(), pl = IS_PLAN.matcher(lastLine(b)).find();
            if (pl && planT == null) planT = t;
            else if (st && statusT == null) statusT = t;
        }
        // 제목으로 못 가르면 순서로: 앞 = 현황, 뒤 = 일정
        for (Table t : usable) {
            if (t == statusT || t == planT) continue;
            if (statusT == null && usable.size() > 1 && usable.indexOf(t) == 0) statusT = t;
            else if (planT == null) planT = t;
            else if (statusT == null) statusT = t;
        }
        YearMonth pm = planMonth(text == null ? "" : text, planT == null ? "" : planT.before(), today);
        YearMonth sm = pm.minusMonths(1);
        List<Row> plan = planT == null ? List.of() : rowsOf(planT, pm.getYear(), pm.getMonthValue());
        List<Row> status = statusT == null ? List.of() : rowsOf(statusT, sm.getYear(), sm.getMonthValue());
        String note = planT == null && statusT == null ? "보고서에서 '구분·추진항목·주요내용' 칸이 있는 표를 찾지 못했습니다."
            : planT == null ? "다음달 업무 일정 표를 찾지 못했습니다. 현황 표만 읽었습니다." : "";
        return new Parsed(pm.getYear(), pm.getMonthValue(), plan, status, note);
    }

    static String lastLine(String s) {
        String[] ls = s.strip().split("\n");
        // 표 바로 위 두 줄(제목 줄)만 본다
        return ls.length == 0 ? "" : (ls.length > 1 ? ls[ls.length - 2] + " " : "") + ls[ls.length - 1];
    }
}
