package local.audit.work;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import local.audit.Kst;
import local.audit.Op;
import local.audit.Txt;

/** 타임라인, 인사이트, 주간 회의 자료, 월간 업무계획, 회의·쟁점. 예전 HTML 판의 동작을 옮겼다. */
@Service
public class WorkReports {
    private final WorkService work;
    private final WorkStore store;

    public WorkReports(WorkService work, WorkStore store) {
        this.work = work;
        this.store = store;
    }

    static String today() { return Kst.today().toString(); }

    // ───── 타임라인 ─────
    public record Tick(double left, double width, String label, boolean off) { }
    public record Bar(WorkItem item, double left, double width, int pct, String tone, String range, boolean visible) { }

    public Map<String, Object> timeline(String startText, String scale, String owner) {
        String sc = List.of("day", "week", "month").contains(Txt.str(scale)) ? scale : "week";
        int days = "day".equals(sc) ? 42 : "week".equals(sc) ? 98 : 366;
        LocalDate t = Kst.today();
        LocalDate start = Work.date(startText);
        if (start == null) start = "month".equals(sc) ? t.withDayOfMonth(1).minusMonths(1) : Work.weekStart(t).minusDays(7);
        List<Tick> ticks = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate d = start.plusDays(i);
            boolean show = "day".equals(sc) || ("week".equals(sc) && d.getDayOfWeek().getValue() == 1) || ("month".equals(sc) && d.getDayOfMonth() == 1);
            boolean off = !"month".equals(sc) && Work.isOff(d);
            if (!show && !off) continue;
            int span = show ? ("week".equals(sc) ? 7 : "month".equals(sc) ? d.lengthOfMonth() : 1) : 1;
            String label = !show ? "" : "month".equals(sc) ? d.getMonthValue() + "월" : "week".equals(sc) ? d.getMonthValue() + "/" + d.getDayOfMonth() : String.valueOf(d.getDayOfMonth());
            ticks.add(new Tick(i * 100.0 / days, Math.min(span, days - i) * 100.0 / days, label, off));
        }
        List<WorkItem> list = work.all().stream()
            .filter(x -> x.isWork() && (!x.start.isEmpty() || x.hasDue()))
            .filter(x -> Txt.blank(owner) || owner.equals(x.ownerId))
            .sorted(Comparator.comparing(x -> x.start.isEmpty() ? x.due : x.start)).toList();
        List<Bar> bars = new ArrayList<>();
        String s0 = start.toString();
        for (WorkItem x : list) {
            String s = x.start.isEmpty() ? x.due : x.start, e = x.hasDue() ? x.due : x.start;
            if (e.compareTo(s) < 0) e = s;
            long a = Work.diff(s0, s), l = Math.max(1, Work.diff(s, e) + 1);
            boolean vis = a + l > 0 && a < days;
            double left = Math.max(0, a) * 100.0 / days;
            double right = Math.min(days, a + l) * 100.0 / days;
            String tone = x.isDone() ? "done" : WorkService.lateDays(x) > 0 ? "late" : "open";
            bars.add(new Bar(x, left, Math.max(0.6, right - left), work.percent(x), tone, Work.md(s) + " ~ " + Work.md(e), vis));
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("scale", sc);
        v.put("start", s0);
        v.put("end", start.plusDays(days - 1).toString());
        v.put("range", Work.md(s0) + " ~ " + Work.md(start.plusDays(days - 1).toString()));
        long tl = Work.diff(s0, t.toString());
        v.put("todayLeft", tl >= 0 && tl < days ? (tl + 0.5) * 100.0 / days : -1);
        int step = "day".equals(sc) ? 14 : "week".equals(sc) ? 28 : 92;
        v.put("prev", start.minusDays(step).toString());
        v.put("next", start.plusDays(step).toString());
        v.put("ticks", ticks);
        v.put("bars", bars);
        return v;
    }

    // ───── 인사이트 ─────
    public record Bars(String name, Map<String, Long> values) { }

    public Map<String, Object> insight() {
        List<WorkItem> W = work.all().stream().filter(WorkItem::isWork).toList();
        List<WorkItem> D = W.stream().filter(WorkItem::isDone).toList();
        List<WorkItem> WD = D.stream().filter(x -> x.hasDue() && !x.doneAt.isEmpty()).toList();
        long ont = WD.stream().filter(x -> x.doneAt.compareTo(x.due) <= 0).count();
        Integer rate = WD.isEmpty() ? null : (int) Math.round(ont * 100.0 / WD.size());
        Integer avg = D.isEmpty() ? null : (int) Math.round(D.stream().mapToLong(x -> Math.max(0, Work.diff(x.created, x.doneAt))).sum() / (double) D.size());
        long late = W.stream().filter(x -> WorkService.lateDays(x) > 0).count();
        long stall = W.stream().filter(x -> work.stallDays(x) > 0).count();
        LocalDate t = Kst.today();
        int Y = t.getYear(), M = t.getMonthValue(), pl = 0, pdn = 0;
        for (WorkItem x : W) {
            if (!"months".equals(x.cycle)) continue;
            for (int m : x.months) if (m <= M) { pl++; if (WorkService.truthy(x.checks.get(Y + "-" + m))) pdn++; }
        }
        List<Bars> months = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            YearMonth ym = YearMonth.from(t).minusMonths(i);
            String k = ym.toString();
            Map<String, Long> m = new LinkedHashMap<>();
            m.put("유입", W.stream().filter(x -> x.created.startsWith(k)).count());
            m.put("완료", D.stream().filter(x -> x.doneAt.startsWith(k)).count());
            months.add(new Bars(ym.getMonthValue() + "월", m));
        }
        List<Bars> owners = new ArrayList<>();
        for (WorkConfig.Owner o : work.config().owners) {
            if (!o.on || "ow0".equals(o.id)) continue;
            Map<String, Long> m = new LinkedHashMap<>();
            m.put("진행", W.stream().filter(x -> o.id.equals(x.ownerId) && WorkService.isOpen(x) && WorkService.lateDays(x) == 0).count());
            m.put("지연", W.stream().filter(x -> o.id.equals(x.ownerId) && WorkService.lateDays(x) > 0).count());
            m.put("완료", D.stream().filter(x -> o.id.equals(x.ownerId)).count());
            owners.add(new Bars(o.n, m));
        }
        List<Bars> types = new ArrayList<>();
        for (WorkConfig.Type ty : work.config().types) {
            long c = W.stream().filter(x -> ty.id.equals(x.typeId)).count();
            if (c > 0) types.add(new Bars(ty.n, Map.of("건수", c)));
        }
        List<String> tips = new ArrayList<>();
        if (late > 0) tips.add("기한이 지난 업무가 " + late + "건입니다. 기한을 다시 잡는 것이 먼저입니다.");
        if (rate != null && rate < 80) tips.add("기한 준수율이 " + rate + "%입니다. 계획 단계에서 기한을 여유 있게 잡으세요.");
        if (stall > 0) tips.add(work.config().stalled + "일 넘게 기록이 없는 업무가 " + stall + "건입니다. 다음 행동이 비어 있는지 보세요.");
        for (WorkConfig.Owner o : work.config().owners)
            if (o.cap > 0 && W.stream().filter(x -> o.id.equals(x.ownerId) && WorkService.isOpen(x)).count() > o.cap) tips.add(o.n + " 님의 담당 건수가 용량을 넘었습니다.");
        if (pl > 0 && pdn / (double) pl < .9) tips.add("올해 정기 업무 이행률이 " + Math.round(pdn * 100.0 / pl) + "%입니다. 밀린 달을 확인하세요.");
        if (tips.isEmpty()) tips.add("지연·정체가 관리 범위 안입니다.");
        List<WorkItem> moved = W.stream().filter(x -> !x.dueLog.isEmpty()).sorted(Comparator.comparingInt((WorkItem x) -> x.dueLog.size()).reversed()).limit(8).toList();
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("rate", rate == null ? "-" : rate + "%");
        v.put("rateSub", "기한 있는 완료 " + WD.size() + "건 중 " + ont + "건");
        v.put("avg", avg == null ? "-" : avg + "일");
        v.put("regRate", pl == 0 ? "-" : Math.round(pdn * 100.0 / pl) + "%");
        v.put("regSub", M + "월까지 " + pdn + "/" + pl);
        v.put("lateStall", late + " · " + stall);
        v.put("tips", tips);
        v.put("months", months);
        v.put("owners", owners);
        v.put("types", types);
        v.put("max", maxOf(months, owners, types));
        v.put("moved", moved);
        return v;
    }

    private static long maxOf(List<Bars>... groups) {
        long m = 1;
        for (List<Bars> g : groups) for (Bars b : g) for (long x : b.values().values()) m = Math.max(m, x);
        return m;
    }

    public long postponedDays(WorkItem x) {
        String first = !x.due0.isEmpty() ? x.due0 : x.dueLog.isEmpty() ? x.due : x.dueLog.get(0).from;
        return Math.max(0, Work.diff(first, x.due));
    }

    // ───── 주간 회의 자료 · 월간 업무계획 ─────
    public Map<String, Object> weekly() {
        LocalDate t = Kst.today();
        String T = t.toString(), ws = Work.weekStart(t).toString(), we = Work.weekStart(t).plusDays(6).toString(), next = Work.weekStart(t).plusDays(13).toString();
        List<WorkItem> W = work.all().stream().filter(WorkItem::isWork).toList();
        Map<String, List<WorkItem>> sec = new LinkedHashMap<>();
        sec.put("이번 주 완료", W.stream().filter(x -> x.isDone() && x.doneAt.compareTo(ws) >= 0).toList());
        sec.put("지연", W.stream().filter(x -> WorkService.lateDays(x) > 0).sorted(Comparator.comparing(x -> x.due)).toList());
        sec.put("다음 1주 마감", W.stream().filter(x -> WorkService.isOpen(x) && x.hasDue() && x.due.compareTo(T) >= 0 && x.due.compareTo(next) <= 0)
            .sorted(Comparator.comparing(x -> x.due)).toList());
        sec.put("타 부서 대기 · 보고대기", W.stream().filter(x -> "ext".equals(x.status) || "review".equals(x.status)).toList());
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("title", "주간 업무 회의 자료 (" + Work.md(ws) + " ~ " + Work.md(we) + ")");
        v.put("sections", sec);
        v.put("dirs", work.all().stream().filter(x -> "dir".equals(x.kind) && !x.isDone()).toList());
        return v;
    }

    /** 다음 달 업무계획 초안(글). */
    public String monthPlan() {
        YearMonth ym = YearMonth.from(Kst.today()).plusMonths(1);
        int M = ym.getMonthValue();
        String k = ym.toString();
        List<WorkItem> W = work.all().stream().filter(WorkItem::isWork).toList();
        List<WorkItem> reg = W.stream().filter(x -> "months".equals(x.cycle) && x.months.contains(M)).toList();
        List<WorkItem> due = W.stream().filter(x -> WorkService.isOpen(x) && x.due.startsWith(k)).toList();
        List<WorkItem> carry = W.stream().filter(x -> WorkService.isOpen(x) && x.hasDue() && x.due.compareTo(k + "-01") < 0).toList();
        List<WorkItem> always = W.stream().filter(x -> "always".equals(x.cycle) && WorkService.isOpen(x)).toList();
        List<WorkItem> events = work.all().stream().filter(x -> "event".equals(x.kind) && Txt.str(x.date).startsWith(k))
            .sorted(Comparator.comparing(x -> x.date)).toList();
        StringBuilder sb = new StringBuilder();
        sb.append(work.config().partName).append(' ').append(ym.getYear()).append("년 ").append(M).append("월 업무계획 (초안)\n작성일 ").append(today()).append("\n\n");
        section(sb, "1. 정기 업무", reg);
        section(sb, "2. 기한 도래 업무", due);
        section(sb, "3. 이월 업무", carry);
        section(sb, "4. 상시 업무", always);
        sb.append("5. 일정\n");
        if (events.isEmpty()) sb.append("  - 없음\n");
        for (WorkItem e : events) sb.append("  - ").append(Work.md(e.date)).append(' ').append(e.title).append('\n');
        return sb.toString();
    }

    private void section(StringBuilder sb, String head, List<WorkItem> list) {
        sb.append(head).append(" (").append(list.size()).append(")\n");
        if (list.isEmpty()) sb.append("  - 없음\n");
        for (WorkItem x : list)
            sb.append("  - ").append(x.title).append(" (").append(work.ownerName(x.ownerId)).append(x.hasDue() ? ", " + Work.md(x.due) : "").append(")\n");
        sb.append('\n');
    }

    // ───── 회의·쟁점 ─────
    static final Pattern ACT = Pattern.compile("(조치|담당|까지|하기로|요청|검토|보고|제출|회신|- \\[ \\])");

    public static List<String> decisions(String text) {
        List<String> out = new ArrayList<>();
        boolean in = false;
        String[] lines = Txt.str(text).split("\n");
        for (String l : lines) {
            String t = l.strip();
            if (t.matches("^(#+|□|■|\\d+\\.)?\\s*결정.*")) { in = true; continue; }
            if (in && t.matches("^(#+|□|■)\\s*.*") && !t.contains("결정")) in = false;
            if (in && !t.isEmpty()) out.add(l.replaceFirst("^[-*•\\s]+", ""));
        }
        if (out.isEmpty()) for (String l : lines) if (Pattern.compile("(하기로\\s*함|로\\s*확정|결정함)").matcher(l).find()) out.add(l.strip());
        return out.size() > 20 ? out.subList(0, 20) : out;
    }

    public static List<String> actions(String text) {
        Set<String> dec = new LinkedHashSet<>();
        for (String d : decisions(text)) dec.add(d.strip());
        List<String> out = new ArrayList<>();
        for (String l : Txt.str(text).split("\n")) {
            String s = l.strip();
            if (s.length() <= 3 || s.matches("^(#|□|■).*") || dec.contains(s.replaceFirst("^[-*•\\s]+", ""))) continue;
            if (!ACT.matcher(s).find() || Pattern.compile("결정사항\\s*없음|추후\\s*결정").matcher(s).find()) continue;
            String c = s.replaceFirst("^[-*□■•\\d.)\\s\\[\\]x]+", "").strip();
            if (!c.isEmpty()) out.add(c);
            if (out.size() >= 30) break;
        }
        return out;
    }

    public List<WorkItem> meetings() {
        return work.all().stream().filter(x -> "note".equals(x.kind) && "meet".equals(x.ntype))
            .sorted(Comparator.comparing((WorkItem x) -> Txt.blank(x.mdate) ? x.created : x.mdate).reversed()).toList();
    }

    public List<WorkItem> issues() { return work.all().stream().filter(x -> "issue".equals(x.kind)).toList(); }

    public List<WorkItem> fromMeeting(String id) { return work.all().stream().filter(x -> id.equals(x.srcId)).toList(); }

    public Op addMeeting(Map<String, String> p, String text) {
        String title = Txt.clean(p.get("title"), 200, false);
        if (Txt.blank(title)) return Op.bad("회의명을 적어 주세요.");
        String body = Txt.fit(text, 400_000, true);
        if (body.isBlank()) return Op.bad("회의록을 붙여 넣거나 파일을 고르세요.");
        WorkItem x = work.fresh("note");
        x.ntype = "meet";
        x.title = title;
        x.mdate = Work.date(p.get("mdate")) == null ? today() : p.get("mdate");
        x.org = Txt.fit(p.get("org"), 80, false);
        x.content = body;
        if (!Txt.blank(p.get("textHtml"))) x.html = local.audit.HtmlClean.clean(p.get("textHtml"));
        store.add(x);
        scanIssues();
        return Op.ok("회의록을 넣었습니다 · 조치 후보 " + actions(body).size() + "건.", x.id);
    }

    /** 고른 조치 후보를 업무로 만든다. */
    public Op actionsToWork(String meetId, List<String> lines, String ownerId) {
        WorkItem m = work.get(meetId);
        if (m == null) return Op.fail(404, "회의록을 찾을 수 없습니다.");
        if (lines == null || lines.isEmpty()) return Op.bad("업무로 올릴 줄을 고르세요.");
        List<WorkItem> made = new ArrayList<>();
        for (String line : lines) {
            if (Txt.blank(line)) continue;
            WorkService.Quick q = work.parseQuick(line);
            WorkItem w = work.fresh("work");
            w.title = Txt.fit(q.title(), 200, false);
            w.due = q.due();
            w.due0 = q.due();
            w.ownerId = !q.ownerId().isEmpty() ? q.ownerId() : Txt.blank(ownerId) ? "ow0" : ownerId;
            w.tags = new ArrayList<>(q.tags());
            w.srcId = m.id;
            WorkService.addLog(w, "등록 (회의: " + m.title + ")");
            store.all().add(w);
            made.add(w);
        }
        store.save(made);
        return Op.ok("업무 " + made.size() + "건을 만들었습니다.");
    }

    public Op addIssue(Map<String, String> p) {
        String title = Txt.clean(p.get("title"), 120, false);
        if (Txt.blank(title)) return Op.bad("쟁점 이름을 적어 주세요.");
        WorkItem x = work.fresh("issue");
        x.title = title;
        x.org = Txt.fit(p.get("org"), 80, false);
        x.status = "todo";
        store.add(x);
        scanIssues();
        return Op.ok("쟁점을 등록했습니다.", x.id);
    }

    /** 쟁점 상태: open 진행 · watch 지켜보기 · closed 종결. 예전 판과 같은 값을 쓴다. */
    public Op issueStatus(String id, String s) {
        WorkItem x = work.get(id);
        if (x == null || !"issue".equals(x.kind)) return Op.fail(404, "쟁점을 찾을 수 없습니다.");
        if (!List.of("open", "watch", "closed").contains(s)) return Op.bad("없는 상태입니다.");
        x.put("issueState", s);
        store.save(x);
        return Op.ok("쟁점 상태를 바꿨습니다.");
    }

    public static String issueState(WorkItem x) {
        Object s = x.other().get("issueState");
        if (s != null) return s.toString();
        return List.of("watch", "closed", "open").contains(x.status) ? x.status : "open";
    }

    /** 회의록에 쟁점 이름이 나오면 언급 이력을 붙인다. */
    public Op scanIssues() {
        List<WorkItem> M = meetings();
        List<WorkItem> changed = new ArrayList<>();
        for (WorkItem is : issues()) {
            List<Map<String, Object>> ms = new ArrayList<>();
            for (WorkItem m : M) {
                if (!m.content.contains(is.title) && !m.title.contains(is.title)) continue;
                String ln = "";
                for (String l : m.content.split("\n")) if (l.contains(is.title)) { ln = l.strip(); break; }
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("meetId", m.id);
                e.put("mdate", Txt.blank(m.mdate) ? m.created : m.mdate);
                e.put("t", ln.length() > 120 ? ln.substring(0, 120) : ln);
                ms.add(e);
            }
            ms.sort(Comparator.comparing(e -> Txt.str(e.get("mdate"))));
            is.put("mentions", ms);
            changed.add(is);
        }
        store.save(changed);
        return Op.ok("회의록을 다시 훑었습니다 · 쟁점 " + changed.size() + "건.");
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> mentions(WorkItem x) {
        Object m = x.other().get("mentions");
        return m instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }
}
