package local.audit.work;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import local.audit.Db;
import local.audit.Kst;
import local.audit.Op;
import local.audit.Txt;

/** 업무·일정의 규칙. 예전 HTML 판의 동작을 그대로 옮겼다. */
@Service
public class WorkService {
    private final WorkStore store;
    private final Db db;

    public WorkService(WorkStore store, Db db) {
        this.store = store;
        this.db = db;
    }

    public WorkConfig config() { return store.config(); }

    /** 되돌릴 수 있게 남기고 작업한다. 실패하면 남긴 것을 버린다. */
    public synchronized Op change(String label, java.util.function.Supplier<Op> work) {
        store.snapshot(label);
        Op op;
        try {
            op = work.get();
        } catch (RuntimeException e) {
            store.dropSnapshot();
            throw e;
        }
        if (!op.ok) store.dropSnapshot();
        return op;
    }

    public Op undo() {
        String label = store.undo();
        return label == null ? Op.bad("되돌릴 작업이 없습니다.") : Op.ok("되돌렸습니다: " + label);
    }

    public String undoLabel() { return store.undoLabel(); }
    public List<WorkItem> all() { return store.all(); }
    public WorkItem get(String id) { return store.get(id); }

    static String today() { return Kst.today().toString(); }

    // ───── 판정 ─────
    public static boolean isOpen(WorkItem x) { return x.isWork() && !"done".equals(x.status) && !"hold".equals(x.status); }

    public static long lateDays(WorkItem x) {
        boolean open = isOpen(x) || ("dir".equals(x.kind) && !x.isDone());
        if (!open || !x.hasDue()) return 0;
        long d = Work.diff(x.due, today());
        return Math.max(0, d);
    }

    String lastAct(WorkItem x) {
        String last = x.created;
        for (WorkItem.Entry e : x.log) if (e.d != null && e.d.compareTo(Txt.str(last)) > 0) last = e.d;
        return Txt.str(last);
    }

    public long stallDays(WorkItem x) {
        if (!x.isWork() || !"doing".equals(x.status)) return 0;
        String last = lastAct(x);
        if (last.isEmpty()) return 0;
        long d = Work.diff(last, today());
        return d >= config().stalled ? d : 0;
    }

    public List<WorkItem> kids(String id) {
        return all().stream().filter(x -> x.isWork() && id.equals(x.parentId)).toList();
    }

    public double progress(WorkItem x) { return progress(x, 0); }

    private double progress(WorkItem x, int depth) {
        List<WorkItem> k = depth > 8 ? List.of() : kids(x.id);
        if (!k.isEmpty()) return k.stream().mapToDouble(c -> progress(c, depth + 1)).sum() / k.size();
        if (x.isDone()) return 1;
        List<String> st = config().type(x.typeId).stages;
        if (!st.isEmpty()) return Math.min(1, (double) x.stage / st.size());
        if (!x.todos.isEmpty()) return (double) x.getTodoDone() / x.todos.size();
        return "todo".equals(x.status) ? 0 : "review".equals(x.status) ? .8 : .5;
    }

    public int percent(WorkItem x) { return (int) Math.round(progress(x) * 100); }

    /** 정기 업무의 그달 칸 상태: na 해당 없음 · done 이행 · miss 지난 달 미이행 · doing 이번 달 · plan 예정. */
    public static String cellState(WorkItem x, int y, int m) {
        if (!"months".equals(x.cycle) || !x.months.contains(m) || (x.fromY != null && y < x.fromY) || (x.toY != null && y > x.toY)) return "na";
        if (truthy(x.checks.get(y + "-" + m))) return "done";
        LocalDate now = Kst.today();
        int Y = now.getYear(), M = now.getMonthValue();
        return y < Y || (y == Y && m < M) ? "miss" : (y == Y && m == M) ? "doing" : "plan";
    }

    static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        String s = v.toString();
        return !s.isEmpty() && !"false".equals(s) && !"0".equals(s);
    }

    /** 기한 뱃지 글: 지연 3일 · 오늘 · 내일 · 11/20(목). */
    public String dueText(WorkItem x) {
        if (!x.hasDue()) return "";
        if (x.isDone()) return Work.md(x.due);
        long late = lateDays(x);
        if (late > 0) return "지연 " + late + "일";
        long d = Work.diff(today(), x.due);
        return d == 0 ? "오늘" : d == 1 ? "내일" : Work.md(x.due);
    }

    public String dueTone(WorkItem x) {
        if (!x.hasDue() || x.isDone()) return "";
        if (lateDays(x) > 0) return "bad";
        long d = Work.diff(today(), x.due);
        return d <= 1 ? "warn" : "";
    }

    /** 이름 끝 두 글자. 좁은 칸에 담당자를 보일 때 쓴다. */
    public String ownerShort(String id) {
        String n = config().owner(id).n;
        return n.length() <= 2 ? n : n.substring(n.length() - 2);
    }

    public String ownerName(String id) { return config().owner(id).n; }
    public String typeName(String id) { return config().type(id).n; }
    public String projectName(String id) { WorkConfig.Project p = config().project(id); return p == null ? "" : p.n; }
    public String stageName(WorkItem x) {
        List<String> st = config().type(x.typeId).stages;
        return x.stage > 0 && x.stage <= st.size() ? x.stage + "/" + st.size() + " " + st.get(x.stage - 1) : "";
    }
    public String cycleLabel(WorkItem x) { return Work.cycleLabel(x); }

    /** 편집기와 화면에 넣을 내용 모양. 꾸민 모양이 없으면 글자에서 만든다. 예전 노트의 마크다운(표·제목·목록)은 그 모양대로. */
    public String contentHtml(WorkItem x) {
        if (x == null) return "";
        if (!Txt.blank(x.html)) return x.html;
        return textView(x.content);
    }

    public String memoHtml(WorkItem x) {
        if (x == null) return "";
        if (!Txt.blank(x.memoHtml)) return x.memoHtml;
        return textView(x.memo);
    }

    static String textView(String t) {
        if (Txt.blank(t)) return "";
        return local.audit.Md.looksLike(t) ? local.audit.Md.toHtml(t) : local.audit.extract.Rich.fromText(t);
    }
    public String md(String d) { return Work.md(d); }

    // ───── 기록 ─────
    static void addLog(WorkItem x, String t) { x.log.add(new WorkItem.Entry(today(), t)); }

    /** 상태를 바꾸고, 완료면 단계를 끝으로 두고 반복 업무를 다음 차례로 만든다. 새로 만든 항목을 돌려준다. */
    WorkItem setStatus(WorkItem x, String s) {
        if (s.equals(x.status)) return null;
        String prev = x.status;
        x.status = s;
        addLog(x, "상태 " + Work.statusName(prev) + " → " + Work.statusName(s));
        if ("done".equals(s)) {
            x.doneAt = today();
            List<String> st = config().type(x.typeId).stages;
            if (!st.isEmpty()) x.stage = st.size();
            return spawnRepeat(x);
        }
        x.doneAt = "";
        return null;
    }

    WorkItem spawnRepeat(WorkItem x) {
        if (Txt.blank(x.repeat) || !x.isWork()) return null;
        String base = x.hasDue() ? x.due : today();
        LocalDate b = Work.date(base);
        LocalDate n = switch (x.repeat) {
            case "daily" -> b.plusDays(1);
            case "weekly" -> b.plusWeeks(1);
            case "weekdays" -> { LocalDate d = b.plusDays(1); while (d.getDayOfWeek().getValue() >= 6) d = d.plusDays(1); yield d; }
            default -> b.plusMonths(1);
        };
        WorkItem c = copy(x);
        c.id = Txt.newId();
        c.status = "todo";
        c.stage = 0;
        c.doneAt = "";
        c.log = new ArrayList<>(List.of(new WorkItem.Entry(today(), "반복 생성 (" + Work.cycleLabel(x) + ")")));
        c.comments = new ArrayList<>();
        c.due = n.toString();
        c.due0 = c.due;
        c.dueLog = new ArrayList<>();
        c.start = x.start.isEmpty() ? "" : Work.date(x.start).plusDays(Work.diff(base, c.due)).toString();
        c.todos = x.todos.stream().map(t -> new WorkItem.Todo(t.t, false)).collect(Collectors.toCollection(ArrayList::new));
        c.created = today();
        store.all().add(c);
        return c;
    }

    static void setDue(WorkItem x, String d) {
        if (Txt.str(x.due).equals(d)) return;
        if (x.hasDue() && !d.isEmpty()) x.dueLog.add(new WorkItem.DueChange(today(), x.due, d));
        if (x.due0.isEmpty()) x.due0 = d;
        addLog(x, "기한 " + (x.hasDue() ? x.due : "없음") + " → " + (d.isEmpty() ? "없음" : d));
        x.due = d;
    }

    static WorkItem copy(WorkItem x) {
        try {
            return WorkStore.fix(Work.JSON.readValue(Work.JSON.writeValueAsString(x), WorkItem.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ───── 빠른 추가: @담당 #태그 !높음 +과제 날짜(오늘/내일/모레/요일/다음주/11/20/2026-11-20/3일후) ─────
    public record Quick(String title, List<String> tags, String pri, String ownerId, String due, String projectId) { }

    public Quick parseQuick(String s) {
        WorkConfig c = config();
        List<String> tags = new ArrayList<>();
        String[] pri = { "mid" }, owner = { "" }, proj = { "" };
        String t = " " + s + " ";
        t = replace(t, "\\s@(\\S+)", m -> {
            for (WorkConfig.Owner o : c.owners) if (o.n.equals(m.group(1)) || o.n.contains(m.group(1))) { owner[0] = o.id; return " "; }
            return m.group();
        });
        t = replace(t, "\\s\\+(\\S+)", m -> {
            for (WorkConfig.Project p : c.projects) if (p.n.contains(m.group(1))) { proj[0] = p.id; return " "; }
            return m.group();
        });
        t = replace(t, "\\s#([^\\s#]+)", m -> { tags.add(m.group(1)); return " "; });
        t = replace(t, "\\s!(높음|긴급|급함|high|1)(?=\\s|$)", m -> { pri[0] = "high"; return " "; });
        t = replace(t, "\\s!(낮음|low|3)(?=\\s|$)", m -> { pri[0] = "low"; return " "; });
        t = replace(t, "\\s!(보통|mid|2)(?=\\s|$)", m -> " ");
        LocalDate T = Kst.today();
        String due = "";
        String wd = "월화수목금토일";
        Object[][] rules = {
            { "\\s(오늘)(까지)?\\s", (java.util.function.Function<Matcher, LocalDate>) m -> T },
            { "\\s(내일)(까지)?\\s", (java.util.function.Function<Matcher, LocalDate>) m -> T.plusDays(1) },
            { "\\s(모레)(까지)?\\s", (java.util.function.Function<Matcher, LocalDate>) m -> T.plusDays(2) },
            { "\\s(\\d+)일\\s?(후|뒤)\\s", (java.util.function.Function<Matcher, LocalDate>) m -> T.plusDays(Long.parseLong(m.group(1))) },
            { "\\s(다음\\s?주|차주)\\s?([월화수목금토일])(요일)?(까지)?\\s",
                (java.util.function.Function<Matcher, LocalDate>) m -> Work.weekStart(T).plusDays(7 + wd.indexOf(m.group(2))) },
            { "\\s(다음\\s?주|차주)\\s", (java.util.function.Function<Matcher, LocalDate>) m -> Work.weekStart(T).plusDays(7) },
            { "\\s(이번\\s?주\\s?)?([월화수목금토일])요일(까지)?\\s", (java.util.function.Function<Matcher, LocalDate>) m -> {
                LocalDate d = Work.weekStart(T).plusDays(wd.indexOf(m.group(2)));
                if (d.isBefore(T) && m.group(1) == null) d = d.plusDays(7);
                return d;
            } },
            { "\\s(이번\\s?주|금주)(까지)?\\s", (java.util.function.Function<Matcher, LocalDate>) m -> Work.weekStart(T).plusDays(4) },
            { "\\s(월말|이번\\s?달)(까지)?\\s", (java.util.function.Function<Matcher, LocalDate>) m -> YearMonth.from(T).atEndOfMonth() },
            { "\\s(\\d{4})-(\\d{1,2})-(\\d{1,2})(까지)?\\s", (java.util.function.Function<Matcher, LocalDate>) m ->
                safeDate(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))) },
            { "\\s(\\d{1,2})[/.](\\d{1,2})(까지)?\\s", (java.util.function.Function<Matcher, LocalDate>) m -> {
                LocalDate d = safeDate(T.getYear(), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
                if (d != null && d.isBefore(T.minusDays(60))) d = d.plusYears(1);
                return d;
            } },
            { "\\s(\\d{1,2})월\\s?(\\d{1,2})일(까지)?\\s", (java.util.function.Function<Matcher, LocalDate>) m ->
                safeDate(T.getYear(), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))) } };
        for (Object[] r : rules) {
            Matcher m = Pattern.compile((String) r[0]).matcher(t);
            if (m.find()) {
                @SuppressWarnings("unchecked")
                LocalDate d = ((java.util.function.Function<Matcher, LocalDate>) r[1]).apply(m);
                if (d != null) {
                    due = d.toString();
                    t = t.substring(0, m.start()) + " " + t.substring(m.end());
                    break;
                }
            }
        }
        String title = t.replaceAll("\\s+", " ").strip();
        if (title.isEmpty()) title = s.strip();
        return new Quick(title, tags, pri[0], owner[0], due, proj[0]);
    }

    private static LocalDate safeDate(int y, int m, int d) {
        try { return LocalDate.of(y, m, d); } catch (RuntimeException e) { return null; }
    }

    private static String replace(String text, String regex, java.util.function.Function<Matcher, String> fn) {
        Matcher m = Pattern.compile(regex).matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(fn.apply(m)));
        m.appendTail(sb);
        return sb.toString();
    }

    // ───── 만들기·고치기 ─────
    WorkItem fresh(String kind) {
        WorkItem x = WorkStore.fix(new WorkItem());
        x.kind = Work.KIND.containsKey(kind) ? kind : "work";
        x.created = today();
        return x;
    }

    public Op quickAdd(String text, String ownerDefault) {
        String s = Txt.clean(text, 300, false);
        if (Txt.blank(s)) return Op.bad("한 줄로 적어 주세요.");
        Quick q = parseQuick(s);
        WorkItem x = fresh("work");
        x.title = Txt.fit(q.title(), 200, false);
        x.tags = new ArrayList<>(q.tags());
        x.pri = q.pri();
        x.ownerId = !q.ownerId().isEmpty() ? q.ownerId() : Txt.blank(ownerDefault) ? "ow0" : ownerDefault;
        x.due = q.due();
        x.due0 = q.due();
        x.projectId = q.projectId();
        addLog(x, "등록");
        store.add(x);
        return Op.ok("「" + x.title + "」를 추가했습니다" + (x.hasDue() ? " · 기한 " + Work.md(x.due) : "") + ".", x.id);
    }

    public Op create(String kind, Map<String, String> p) {
        String title = Txt.clean(p.get("title"), 200, false);
        if (Txt.blank(title)) return Op.bad("제목을 적어 주세요.");
        WorkItem x = fresh(kind);
        x.title = title;
        if (!Txt.blank(p.get("ownerId"))) x.ownerId = p.get("ownerId");
        if ("event".equals(x.kind)) {
            x.date = Work.date(p.get("date")) == null ? today() : p.get("date");
            x.t1 = Txt.str(p.get("t1"));
            x.t2 = Txt.str(p.get("t2"));
            x.cat = Work.EVENT.containsKey(p.get("cat")) ? p.get("cat") : "etc";
            x.place = Txt.fit(p.get("place"), 120, false);
        } else if ("dir".equals(x.kind)) {
            x.from = Txt.fit(p.get("from"), 120, false);
            x.due = Work.date(p.get("due")) == null ? "" : p.get("due");
            x.due0 = x.due;
            x.content = Txt.fit(p.get("content"), 4000, true);
            if (!Txt.blank(p.get("contentHtml"))) x.html = local.audit.HtmlClean.clean(p.get("contentHtml"));
        } else if ("note".equals(x.kind)) {
            x.ntype = Work.NOTE.containsKey(p.get("ntype")) ? p.get("ntype") : "doc";
        } else if ("plan".equals(x.kind)) {
            x.when = Txt.fit(p.get("when"), 60, false);
        } else if ("work".equals(x.kind)) {
            x.due = Work.date(p.get("due")) == null ? "" : p.get("due");
            x.due0 = x.due;
            addLog(x, "등록");
        }
        store.add(x);
        return Op.ok("「" + x.title + "」를 만들었습니다.", x.id);
    }

    /** 상세 화면의 입력 칸 저장. 보낸 칸만 바꾼다. */
    public Op update(String id, Map<String, String> p) {
        WorkItem x = get(id);
        if (x == null) return Op.fail(404, "항목을 찾을 수 없습니다.");
        List<WorkItem> changed = new ArrayList<>(List.of(x));
        if (p.containsKey("title")) {
            String t = Txt.clean(p.get("title"), 200, false);
            if (Txt.blank(t)) return Op.bad("제목을 적어 주세요.");
            x.title = t;
        }
        String content = p.containsKey("content") ? Txt.clean(p.get("content"), 100_000, true) : null;
        if (p.containsKey("content") && content == null) return Op.bad("메모가 너무 깁니다.");
        if (content != null) x.content = content;
        if (p.containsKey("contentHtml")) { String h = local.audit.HtmlClean.clean(p.get("contentHtml")); x.html = h.isBlank() ? null : h; }
        if (p.containsKey("memoHtml")) { String h = local.audit.HtmlClean.clean(p.get("memoHtml")); x.memoHtml = h.isBlank() ? null : h; }
        if (p.containsKey("ownerId") && !p.get("ownerId").equals(x.ownerId)) {
            addLog(x, "담당 " + config().owner(x.ownerId).n + " → " + config().owner(p.get("ownerId")).n);
            x.ownerId = p.get("ownerId");
        }
        if (p.containsKey("pri") && Work.PRI.containsKey(p.get("pri"))) x.pri = p.get("pri");
        if (p.containsKey("typeId")) x.typeId = p.get("typeId");
        if (p.containsKey("projectId")) x.projectId = Txt.str(p.get("projectId"));
        if (p.containsKey("start")) x.start = Work.date(p.get("start")) == null ? "" : p.get("start");
        if (p.containsKey("due")) setDue(x, Work.date(p.get("due")) == null ? "" : p.get("due"));
        if (p.containsKey("cycle") && Work.CYCLE.containsKey(p.get("cycle"))) x.cycle = p.get("cycle");
        if (p.containsKey("monthsSent")) {
            List<Integer> ms = new ArrayList<>();
            for (int m = 1; m <= 12; m++) if ("1".equals(p.get("m" + m))) ms.add(m);
            x.months = ms;
        }
        if (p.containsKey("target")) { Integer n = Txt.intOrNull(p.get("target")); x.target = n == null ? 0 : Math.max(0, Math.min(999, n)); }
        if (p.containsKey("repeat") && Work.REPEAT.containsKey(p.get("repeat"))) x.repeat = p.get("repeat");
        if (p.containsKey("parentId")) x.parentId = id.equals(p.get("parentId")) ? "" : Txt.str(p.get("parentId"));
        if (p.containsKey("tags")) {
            x.tags = new ArrayList<>();
            for (String t : Txt.str(p.get("tags")).split("[,，\\s]+")) if (!t.isBlank()) x.tags.add(t.replace("#", "").strip());
        }
        if (p.containsKey("next")) x.next = Txt.fit(p.get("next"), 300, false);
        if (p.containsKey("from")) x.from = Txt.fit(p.get("from"), 120, false);
        // 일정
        if (p.containsKey("date")) x.date = Work.date(p.get("date")) == null ? x.date : p.get("date");
        if (p.containsKey("t1")) x.t1 = Txt.fit(p.get("t1"), 5, false);
        if (p.containsKey("t2")) x.t2 = Txt.fit(p.get("t2"), 5, false);
        if (p.containsKey("cat") && Work.EVENT.containsKey(p.get("cat"))) x.cat = p.get("cat");
        if (p.containsKey("place")) x.place = Txt.fit(p.get("place"), 120, false);
        if (p.containsKey("memo")) x.memo = Txt.fit(p.get("memo"), 20_000, true);
        // 노트·계획
        if (p.containsKey("ntype") && Work.NOTE.containsKey(p.get("ntype"))) x.ntype = p.get("ntype");
        if (p.containsKey("when")) x.when = Txt.fit(p.get("when"), 60, false);
        if (p.containsKey("tick")) x.tick = Work.date(p.get("tick")) == null ? "" : p.get("tick");
        if (p.containsKey("status") && Work.STATUS.containsKey(p.get("status"))) {
            WorkItem n = setStatus(x, p.get("status"));
            if (n != null) changed.add(n);
        }
        store.save(changed);
        String msg = changed.size() > 1 ? "저장했습니다. 반복 업무를 " + Work.md(changed.get(1).due) + "로 다시 만들었습니다." : "저장했습니다.";
        return Op.ok(msg, x.id);
    }

    public Op status(String id, String s) {
        WorkItem x = get(id);
        if (x == null) return Op.fail(404, "항목을 찾을 수 없습니다.");
        if (!Work.STATUS.containsKey(s)) return Op.bad("없는 상태입니다.");
        WorkItem n = setStatus(x, s);
        store.save(n == null ? List.of(x) : List.of(x, n));
        return Op.ok("「" + x.title + "」 " + Work.statusName(s) + (n != null ? " · 다음 차례 " + Work.md(n.due) : ""), x.id);
    }

    public Op stage(String id, int stage) {
        WorkItem x = get(id);
        if (x == null) return Op.fail(404, "항목을 찾을 수 없습니다.");
        List<String> st = config().type(x.typeId).stages;
        if (stage < 0 || stage > st.size()) return Op.bad("없는 단계입니다.");
        x.stage = stage;
        addLog(x, "단계 → " + (stage == 0 ? "시작 전" : stage + ". " + st.get(stage - 1)));
        if ("todo".equals(x.status) && stage > 0) x.status = "doing";
        store.save(x);
        return Op.ok("단계를 바꿨습니다.", x.id);
    }

    public Op todo(String id, String what, Integer index, String text) {
        WorkItem x = get(id);
        if (x == null) return Op.fail(404, "항목을 찾을 수 없습니다.");
        switch (what) {
            case "add" -> {
                String t = Txt.clean(text, 4000, true);
                if (Txt.blank(t)) return Op.bad("항목을 적어 주세요.");
                for (String line : t.split("\n")) if (!line.isBlank()) x.todos.add(new WorkItem.Todo(line.replaceFirst("^\\s*[-*•□☐]\\s*", "").strip(), false));
            }
            case "toggle" -> { if (index == null || index < 0 || index >= x.todos.size()) return Op.bad("없는 항목입니다."); x.todos.get(index).d = !x.todos.get(index).d; }
            case "remove" -> { if (index == null || index < 0 || index >= x.todos.size()) return Op.bad("없는 항목입니다."); x.todos.remove((int) index); }
            case "clean" -> x.todos.removeIf(t -> t.d);
            default -> { return Op.fail(404, "없는 작업입니다."); }
        }
        store.save(x);
        return Op.ok("체크리스트를 고쳤습니다.", x.id);
    }

    public Op log(String id, String text) {
        WorkItem x = get(id);
        if (x == null) return Op.fail(404, "항목을 찾을 수 없습니다.");
        String t = Txt.clean(text, 1000, false);
        if (Txt.blank(t)) return Op.bad("진행 한 줄을 적어 주세요.");
        addLog(x, t);
        store.save(x);
        return Op.ok("진행 기록을 남겼습니다.", x.id);
    }

    /** 연간 정기 현황의 한 칸: 그달 이행 여부를 바꾼다. */
    public Op check(String id, int y, int m) {
        WorkItem x = get(id);
        if (x == null) return Op.fail(404, "항목을 찾을 수 없습니다.");
        if (m < 1 || m > 12) return Op.bad("월을 확인하세요.");
        String k = y + "-" + m;
        if (truthy(x.checks.get(k))) {
            x.checks.remove(k);
            addLog(x, y + "년 " + m + "월 이행 취소");
        } else {
            x.checks.put(k, today());
            addLog(x, y + "년 " + m + "월 이행");
        }
        store.save(x);
        return Op.ok("「" + x.title + "」 " + m + "월 " + (x.checks.containsKey(k) ? "이행" : "이행 취소"), x.id);
    }

    public Op delete(String id) {
        WorkItem x = get(id);
        if (x == null) return Op.fail(404, "항목을 찾을 수 없습니다.");
        List<WorkItem> changed = new ArrayList<>();
        for (WorkItem k : kids(id)) { k.parentId = ""; changed.add(k); }
        store.save(changed);
        store.remove(x);
        return Op.ok("「" + x.title + "」를 지웠습니다.");
    }

    /** 지시·생각 담기·계획 대기를 업무로 옮긴다. 원래 항목은 남겨 두고 연결한다. */
    public Op toWork(String id) {
        WorkItem x = get(id);
        if (x == null) return Op.fail(404, "항목을 찾을 수 없습니다.");
        WorkItem w = fresh("work");
        w.title = x.title;
        w.content = x.content;
        w.ownerId = x.ownerId;
        w.due = x.due;
        w.due0 = x.due;
        w.srcId = x.id;
        addLog(w, "등록 (" + Work.kindName(x.kind) + "에서)");
        store.all().add(w);
        if ("dir".equals(x.kind)) {
            addLog(x, "업무로 옮김");
            x.status = "doing";
            store.save(List.of(x, w));
        } else {
            store.save(w);
            store.remove(x);
        }
        return Op.ok("업무로 옮겼습니다.", w.id);
    }

    // ───── 화면 묶음 ─────
    public Map<String, Object> todayView(int auditDays) {
        String T = today();
        LocalDate t = Kst.today();
        String ws = Work.weekStart(t).toString(), we = Work.weekStart(t).plusDays(6).toString();
        List<WorkItem> works = all().stream().filter(WorkItem::isWork).toList();
        List<WorkItem> open = works.stream().filter(WorkService::isOpen).toList();
        Map<String, Object> v = new LinkedHashMap<>();
        List<WorkItem> late = open.stream().filter(x -> lateDays(x) > 0).sorted(Comparator.comparingLong(WorkService::lateDays).reversed()).toList();
        List<WorkItem> due = open.stream().filter(x -> T.equals(x.due)).toList();
        List<WorkItem> week = open.stream().filter(x -> x.due.compareTo(T) > 0 && x.due.compareTo(we) <= 0).sorted(Comparator.comparing(x -> x.due)).toList();
        String me = config().me;
        List<WorkItem> mine = open.stream().filter(x -> me.equals(x.ownerId) && ((x.hasDue() && x.due.compareTo(T) <= 0) || "doing".equals(x.status) || "high".equals(x.pri)))
            .sorted(Comparator.comparing(x -> x.hasDue() ? x.due : "9")).limit(12).toList();
        List<WorkItem> stall = open.stream().filter(x -> stallDays(x) > 0).toList();
        List<WorkItem> ext = open.stream().filter(x -> "ext".equals(x.status) && !stall.contains(x)).toList();
        List<WorkItem> events = all().stream().filter(x -> "event".equals(x.kind) && T.equals(x.date))
            .sorted(Comparator.comparing(x -> Txt.blank(x.t1) ? "99" : x.t1)).toList();
        List<WorkItem> dirs = all().stream().filter(x -> "dir".equals(x.kind) && !x.isDone()).toList();
        List<WorkItem> ticks = all().stream().filter(x -> "plan".equals(x.kind) && !Txt.blank(x.tick) && x.tick.compareTo(T) <= 0).toList();
        int Y = t.getYear(), M = t.getMonthValue();
        List<WorkItem> reg = works.stream().filter(x -> "months".equals(x.cycle) && x.months.contains(M)).toList();
        long regDone = reg.stream().filter(x -> truthy(x.checks.get(Y + "-" + M))).count();
        List<WorkItem> wkAll = works.stream().filter(x -> x.hasDue() && x.due.compareTo(ws) >= 0 && x.due.compareTo(we) <= 0).toList();
        long wkDone = wkAll.stream().filter(WorkItem::isDone).count();
        List<WorkItem> lateDue = new ArrayList<>(late);
        lateDue.addAll(due);
        v.put("late", late);
        v.put("due", due);
        v.put("lateDue", lateDue.size() > 12 ? lateDue.subList(0, 12) : lateDue);
        v.put("week", week.size() > 12 ? week.subList(0, 12) : week);
        v.put("mine", mine);
        v.put("stall", stall);
        v.put("ext", ext);
        v.put("events", events);
        v.put("dirs", dirs);
        v.put("ticks", ticks);
        v.put("inbox", all().stream().filter(x -> "inbox".equals(x.kind)).toList());
        v.put("month", M);
        v.put("reg", reg.size());
        v.put("regDone", regDone);
        v.put("weekPct", wkAll.isEmpty() ? 0 : Math.round(wkDone * 100.0 / wkAll.size()));
        v.put("weekAll", wkAll.size());
        v.put("weekDone", wkDone);
        v.put("weekRange", Work.md(ws) + " ~ " + Work.md(we));
        v.put("holiday", Work.holiday(T));
        v.put("meName", config().owner(me).n);
        v.put("audit", auditDue("", Kst.today().plusDays(auditDays).toString()));
        return v;
    }

    /** 감사 건의 조치요구 회신기한. 일정 화면과 달력에 함께 보인다. */
    public List<Map<String, Object>> auditDue(String from, String to) {
        return db.rows("""
            SELECT c.CaseId, c.Title AS CaseTitle, c.Target, a.ActionId, a.Sequence, a.Title, a.ReplyDueOn, a.ActionState
            FROM Actions a JOIN Cases c ON c.CaseId=a.CaseId
            WHERE a.ActionState<>'이행완료' AND c.PurgedAt IS NULL AND a.ReplyDueOn>=? AND a.ReplyDueOn<=?
            ORDER BY a.ReplyDueOn, c.CaseId, a.Sequence""", from, to);
    }

    public record Filter(String q, String status, String owner, String type, String project, String scope, String sort) {
        public boolean any() {
            return !Txt.blank(q) || !Txt.blank(status) || !Txt.blank(owner) || !Txt.blank(type) || !Txt.blank(project)
                || (!Txt.blank(scope) && !"open".equals(scope));
        }
    }

    public List<WorkItem> list(Filter f) {
        String q = Txt.str(f.q()).strip().toLowerCase();
        String scope = Txt.blank(f.scope()) ? "open" : f.scope();
        List<WorkItem> out = new ArrayList<>();
        for (WorkItem x : all()) {
            if (!x.isWork()) continue;
            if ("open".equals(scope) && !isOpen(x)) continue;
            if ("late".equals(scope) && lateDays(x) == 0) continue;
            if ("done".equals(scope) && !x.isDone()) continue;
            if ("mine".equals(scope) && (!isOpen(x) || !config().me.equals(x.ownerId))) continue;
            if (!Txt.blank(f.status()) && !f.status().equals(x.status)) continue;
            if (!Txt.blank(f.owner()) && !f.owner().equals(x.ownerId)) continue;
            if (!Txt.blank(f.type()) && !f.type().equals(x.typeId)) continue;
            if (!Txt.blank(f.project()) && !f.project().equals(x.projectId)) continue;
            if (!q.isEmpty()) {
                String hay = (x.title + " " + x.content + " " + x.next + " " + String.join(" ", x.tags) + " " + config().owner(x.ownerId).n).toLowerCase();
                if (!hay.contains(q.replace("#", ""))) continue;
            }
            out.add(x);
        }
        Comparator<WorkItem> byDue = Comparator.comparing(x -> x.hasDue() ? x.due : "9999");
        Comparator<WorkItem> cmp = switch (Txt.str(f.sort())) {
            case "pri" -> Comparator.<WorkItem>comparingInt(x -> "high".equals(x.pri) ? 0 : "mid".equals(x.pri) ? 1 : 2).thenComparing(byDue);
            case "owner" -> Comparator.<WorkItem, String>comparing(x -> config().owner(x.ownerId).n).thenComparing(byDue);
            case "title" -> Comparator.comparing(x -> x.title);
            case "created" -> Comparator.<WorkItem, String>comparing(x -> x.created).reversed();
            default -> byDue;
        };
        out.sort(cmp);
        return out;
    }

    public Map<String, List<WorkItem>> board(String owner) {
        Map<String, List<WorkItem>> cols = new LinkedHashMap<>();
        for (String k : Work.STATUS.keySet()) cols.put(k, new ArrayList<>());
        String since = Kst.today().minusDays(14).toString();
        for (WorkItem x : all()) {
            if (!x.isWork() || x.cycle.equals("months") || x.cycle.equals("count")) continue;
            if (!Txt.blank(owner) && !owner.equals(x.ownerId)) continue;
            if (x.isDone() && x.doneAt.compareTo(since) < 0) continue;
            cols.get(x.status).add(x);
        }
        for (List<WorkItem> l : cols.values()) l.sort(Comparator.comparing(x -> x.hasDue() ? x.due : "9999"));
        return cols;
    }

    public record Day(LocalDate date, boolean inMonth, boolean today, boolean off, String holiday,
                      List<WorkItem> events, List<WorkItem> items, List<Map<String, Object>> audit) {
        public int getDay() { return date.getDayOfMonth(); }
        public String getYmd() { return date.toString(); }
        public int size() { return events.size() + items.size() + audit.size(); }
    }

    public List<List<Day>> calendar(YearMonth ym, String owner) {
        LocalDate first = Work.weekStart(ym.atDay(1));
        LocalDate last = ym.atEndOfMonth();
        LocalDate end = last.plusDays(7 - last.getDayOfWeek().getValue());
        Map<String, List<Map<String, Object>>> audit = new LinkedHashMap<>();
        for (Map<String, Object> r : auditDue(first.toString(), end.toString())) {
            audit.computeIfAbsent(Txt.str(r.get("ReplyDueOn")).substring(0, Math.min(10, Txt.str(r.get("ReplyDueOn")).length())), k -> new ArrayList<>()).add(r);
        }
        String T = today();
        List<List<Day>> weeks = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(end); d = d.plusDays(7)) {
            List<Day> row = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                LocalDate day = d.plusDays(i);
                String ds = day.toString();
                List<WorkItem> ev = new ArrayList<>(), it = new ArrayList<>();
                for (WorkItem x : all()) {
                    if (!Txt.blank(owner) && !owner.equals(x.ownerId)) continue;
                    if ("event".equals(x.kind) && ds.equals(x.date)) ev.add(x);
                    else if ((x.isWork() || "dir".equals(x.kind)) && ds.equals(x.due)) it.add(x);
                }
                ev.sort(Comparator.comparing(x -> Txt.blank(x.t1) ? "99" : x.t1));
                row.add(new Day(day, day.getMonthValue() == ym.getMonthValue(), ds.equals(T), Work.isOff(day), Work.holiday(ds),
                    ev, it, audit.getOrDefault(ds, List.of())));
            }
            weeks.add(row);
        }
        return weeks;
    }

    public record AnnualRow(WorkItem item, List<String> cells, String done) { }

    public Map<String, Object> annual(int y, String owner) {
        Map<String, Object> v = new LinkedHashMap<>();
        Map<String, List<AnnualRow>> groups = new LinkedHashMap<>();
        int tot = 0, dn = 0;
        for (String[] g : new String[][] { { "months", "정기 업무" }, { "count", "횟수 목표" }, { "always", "연중 상시" } }) {
            List<AnnualRow> rows = new ArrayList<>();
            for (WorkItem x : all()) {
                if (!x.isWork() || !g[0].equals(x.cycle)) continue;
                if (!Txt.blank(owner) && !owner.equals(x.ownerId)) continue;
                List<String> cells = new ArrayList<>();
                int cnt = 0;
                for (int m = 1; m <= 12; m++) {
                    String s;
                    if ("months".equals(g[0])) {
                        s = cellState(x, y, m);
                        if (!"na".equals(s)) { tot++; if ("done".equals(s)) { dn++; cnt++; } }
                    } else if ("count".equals(g[0])) {
                        s = truthy(x.checks.get(y + "-" + m)) ? "done" : "plan";
                        if ("done".equals(s)) cnt++;
                    } else s = "always";
                    cells.add(s);
                }
                String done = "months".equals(g[0]) ? cnt + "/" + x.months.size() : "count".equals(g[0]) ? cnt + "/" + x.target : "";
                rows.add(new AnnualRow(x, cells, done));
            }
            if (!rows.isEmpty()) groups.put(g[1], rows);
        }
        v.put("groups", groups);
        v.put("rate", tot == 0 ? 0 : Math.round(dn * 100.0 / tot));
        return v;
    }

    public record TeamRow(WorkConfig.Owner owner, List<WorkItem> items, long late, long ext, long doing, long done30, boolean over,
                          int barOpen, int barExt, int barLate) { }

    public List<TeamRow> team() {
        List<TeamRow> out = new ArrayList<>();
        String since = Kst.today().minusDays(30).toString();
        for (WorkConfig.Owner o : config().owners) {
            if (!o.on) continue;
            List<WorkItem> L = all().stream().filter(x -> isOpen(x) && o.id.equals(x.ownerId))
                .sorted(Comparator.comparing(x -> x.hasDue() ? x.due : "9")).toList();
            long late = L.stream().filter(x -> lateDays(x) > 0).count();
            long ext = L.stream().filter(x -> "ext".equals(x.status)).count();
            long doing = L.stream().filter(x -> "doing".equals(x.status)).count();
            long done30 = all().stream().filter(x -> x.isWork() && o.id.equals(x.ownerId) && x.isDone() && x.doneAt.compareTo(since) >= 0).count();
            double base = o.cap > 0 ? o.cap : Math.max(6, L.size());
            int bo = (int) Math.min(100, (L.size() - late - ext) / base * 100), be = (int) Math.min(100, ext / base * 100), bl = (int) Math.min(100, late / base * 100);
            out.add(new TeamRow(o, L, late, ext, doing, done30, o.cap > 0 && L.size() > o.cap, Math.max(0, bo), be, bl));
        }
        return out;
    }

    public List<WorkItem> ofKind(String... kinds) {
        Set<String> ks = Set.of(kinds);
        return all().stream().filter(x -> ks.contains(x.kind)).toList();
    }

    // ───── 노트: 종류별, 최신순 ─────
    public record NoteRow(WorkItem item, String date, String snippet) { }
    public record NoteGroup(String key, String name, int count, List<NoteRow> rows) { }

    static String noteDate(WorkItem x) {
        String d = !Txt.blank(x.mdate) ? x.mdate : Txt.str(x.created);
        if (d.isEmpty() && !x.log.isEmpty()) d = x.log.get(x.log.size() - 1).d;
        return d;
    }

    NoteRow noteRow(WorkItem x) {
        String t = Txt.str(x.content).replaceAll("\\[\\[([^\\]|]+)(\\|[^\\]]*)?\\]\\]", "$1").replaceAll("[#>*|`=~\\-]+", " ").replaceAll("\\s+", " ").strip();
        return new NoteRow(x, noteDate(x), t.length() > 120 ? t.substring(0, 120) + "…" : t);
    }

    public static String noteType(WorkItem x) {
        if ("issue".equals(x.kind)) return "issue";
        return Work.NOTE.containsKey(x.ntype) ? x.ntype : "doc";
    }

    /** 모든 노트를 종류별로 나눠 최신순으로. */
    public List<NoteGroup> noteGroups(String q, int perGroup) {
        String k = Txt.str(q).strip().toLowerCase();
        Map<String, List<WorkItem>> by = new LinkedHashMap<>();
        for (String t : Work.NOTE.keySet()) by.put(t, new ArrayList<>());
        by.put("issue", new ArrayList<>());
        for (WorkItem x : all()) {
            if (!"note".equals(x.kind) && !"issue".equals(x.kind)) continue;
            if (!k.isEmpty() && !(x.title + " " + x.content + " " + String.join(" ", x.tags)).toLowerCase().contains(k)) continue;
            by.get(noteType(x)).add(x);
        }
        List<NoteGroup> out = new ArrayList<>();
        for (var e : by.entrySet()) {
            List<WorkItem> l = e.getValue();
            l.sort(Comparator.comparing(WorkService::noteDate).reversed());
            List<NoteRow> rows = l.stream().limit(perGroup <= 0 ? Integer.MAX_VALUE : perGroup).map(this::noteRow).toList();
            out.add(new NoteGroup(e.getKey(), "issue".equals(e.getKey()) ? "쟁점" : Work.NOTE.get(e.getKey()), l.size(), rows));
        }
        return out;
    }

    /** 한 종류의 노트를 달별로 묶어 쪽 단위로. 쌓여도 한 화면이 길어지지 않게 30건씩 끊는다. */
    public Map<String, Object> notePage(String type, String q, int page, int size) {
        NoteGroup g = noteGroups(q, 0).stream().filter(x -> x.key().equals(type)).findFirst().orElse(null);
        Map<String, Object> v = new LinkedHashMap<>();
        if (g == null) return v;
        int pages = Math.max(1, (g.rows().size() + size - 1) / size);
        int p = Math.max(1, Math.min(page, pages));
        List<NoteRow> slice = g.rows().subList(Math.min(g.rows().size(), (p - 1) * size), Math.min(g.rows().size(), p * size));
        Map<String, List<NoteRow>> months = new LinkedHashMap<>();
        for (NoteRow r : slice) {
            String m = r.date().length() >= 7 ? r.date().substring(0, 4) + "년 " + Integer.parseInt(r.date().substring(5, 7)) + "월" : "날짜 없음";
            months.computeIfAbsent(m, z -> new ArrayList<>()).add(r);
        }
        v.put("group", g);
        v.put("months", months);
        v.put("page", p);
        v.put("pages", pages);
        return v;
    }

    /** 문서에서 읽은 내용으로 노트를 만든다. */
    public Op noteFromDocument(String title, String ntype, String text, String html) {
        WorkItem x = fresh("note");
        x.title = Txt.fit(title, 200, false);
        if (Txt.blank(x.title)) return Op.bad("제목을 적어 주세요.");
        x.ntype = Work.NOTE.containsKey(ntype) ? ntype : "doc";
        x.content = Txt.fit(text, 400_000, true);
        String h = local.audit.HtmlClean.clean(html);
        x.html = h.isBlank() ? null : h;
        store.add(x);
        return Op.ok("문서를 읽어 노트를 만들었습니다.", x.id);
    }

    /** 문서 내용을 이 항목 내용에 붙이거나 바꾼다. */
    public Op importIntoItem(String id, String text, String html, boolean replace) {
        WorkItem x = get(id);
        if (x == null) return Op.fail(404, "항목을 찾을 수 없습니다.");
        String h = local.audit.HtmlClean.clean(html);
        if (replace) { x.content = Txt.fit(text, 400_000, true); x.html = h.isBlank() ? null : h; }
        else {
            String before = contentHtml(x);
            x.content = Txt.fit((x.content.isBlank() ? "" : x.content + "\n\n") + text, 400_000, true);
            x.html = before.isBlank() ? (h.isBlank() ? null : h) : before + "<hr>" + h;
        }
        addLog(x, "문서에서 내용 " + (replace ? "바꿈" : "붙임"));
        store.save(x);
        return Op.ok(replace ? "문서 내용으로 바꿨습니다." : "문서 내용을 아래에 붙였습니다.", x.id);
    }

    /** 찾기: 모든 종류의 항목에서 제목·내용·태그. */
    public List<WorkItem> search(String term, int limit) {
        String q = Txt.str(term).strip().toLowerCase();
        if (q.isEmpty()) return List.of();
        return all().stream().filter(x -> (x.title + " " + x.content + " " + x.next + " " + Txt.str(x.memo) + " " + Txt.str(x.from) + " " + String.join(" ", x.tags))
            .toLowerCase().contains(q)).limit(limit).toList();
    }

    public int openDirectives() { return (int) all().stream().filter(x -> "dir".equals(x.kind) && !x.isDone()).count(); }
    public int urgentCount() {
        String T = today();
        return (int) all().stream().filter(x -> isOpen(x) && (lateDays(x) > 0 || T.equals(x.due))).count();
    }

    // ───── 설정 ─────
    public Op saveBasics(Map<String, String> p) {
        WorkConfig c = config();
        String part = Txt.clean(p.get("partName"), 40, false);
        Integer st = Txt.intOrNull(p.get("stalled"));
        if (Txt.blank(part) || st == null || st < 1 || st > 90) return Op.bad("파트 이름과 정체 판정 일수(1~90)를 확인하세요.");
        c.partName = part;
        c.stalled = st;
        if (c.owners.stream().anyMatch(o -> o.id.equals(p.get("me")))) c.me = p.get("me");
        store.saveConfig(c);
        return Op.ok("기본 설정을 저장했습니다.");
    }

    /** 담당자·유형·과제 한 줄 저장. id가 비어 있으면 새로 붙인다. */
    public Op saveEntry(String what, Map<String, String> p) {
        WorkConfig c = config();
        String name = Txt.clean(p.get("n"), 40, false);
        if (Txt.blank(name)) return Op.bad("이름을 적어 주세요.");
        String id = Txt.str(p.get("id"));
        boolean on = !"0".equals(p.get("on"));
        switch (what) {
            case "owner" -> {
                Integer cap = Txt.intOrNull(p.get("cap"));
                WorkConfig.Owner o = c.owners.stream().filter(z -> z.id.equals(id)).findFirst().orElse(null);
                if (o == null) { o = new WorkConfig.Owner(nextId("ow", c.owners.stream().map(z -> z.id).toList()), name, 6); c.owners.add(o); }
                o.n = name;
                o.cap = cap == null ? o.cap : Math.max(0, Math.min(99, cap));
                o.on = "ow0".equals(o.id) || on;
            }
            case "type" -> {
                WorkConfig.Type t = c.types.stream().filter(z -> z.id.equals(id)).findFirst().orElse(null);
                if (t == null) { t = new WorkConfig.Type(nextId("ty", c.types.stream().map(z -> z.id).toList()), name, List.of()); c.types.add(t); }
                t.n = name;
                List<String> st = new ArrayList<>();
                for (String s : Txt.str(p.get("stages")).split("[,，>→]")) if (!s.isBlank()) st.add(s.strip());
                if (st.size() > 12) return Op.bad("단계는 12개까지입니다.");
                t.stages = st;
            }
            case "project" -> {
                WorkConfig.Project pj = c.projects.stream().filter(z -> z.id.equals(id)).findFirst().orElse(null);
                if (pj == null) { pj = new WorkConfig.Project(nextId("pj", c.projects.stream().map(z -> z.id).toList()), name); c.projects.add(pj); }
                pj.n = name;
                pj.on = on;
            }
            default -> { return Op.fail(404, "없는 작업입니다."); }
        }
        store.saveConfig(c);
        return Op.ok("저장했습니다.");
    }

    static String nextId(String prefix, List<String> used) {
        int n = 1;
        Set<String> u = new HashSet<>(used);
        while (u.contains(prefix + n)) n++;
        return prefix + n;
    }

    // ───── 옮겨 오기·내보내기 ─────
    /** 예전 HTML 판에서 '내보내기'로 받은 JSON을 읽는다. merge면 없는 항목만 더한다. */
    public Op importJson(byte[] bytes, boolean merge) {
        JsonNode root;
        try {
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).replace("﻿", "");
            root = Work.JSON.readTree(text);
        } catch (Exception e) {
            return Op.bad("JSON 파일을 읽지 못했습니다.");
        }
        if (root == null || !root.has("items") || !root.get("items").isArray()) {
            if (root != null && root.has("regulations")) return Op.bad("사규 관리 파일입니다. 사규 화면의 '옮겨 오기'에 넣으세요.");
            return Op.bad("업무·일정 관리 데이터 형식이 아닙니다.");
        }
        List<WorkItem> items = new ArrayList<>();
        int bad = 0;
        for (JsonNode n : root.get("items")) {
            try { items.add(WorkStore.fix(Work.JSON.treeToValue(n, WorkItem.class))); } catch (Exception e) { bad++; }
        }
        WorkConfig incoming;
        try {
            incoming = root.has("config") ? Work.JSON.treeToValue(root.get("config"), WorkConfig.class) : null;
        } catch (Exception e) {
            incoming = null;
        }
        if (incoming == null) incoming = config();
        WorkConfig base = WorkConfig.defaults("나", Kst.today().getYear());
        if (incoming.types.isEmpty()) incoming.types = base.types;
        if (incoming.owners.isEmpty()) incoming.owners = base.owners;
        String tail = bad > 0 ? " 읽지 못한 항목 " + bad + "건은 건너뛰었습니다." : "";
        if (!merge) {
            store.replaceAll(items, incoming);
            return Op.ok("옮겨 왔습니다 · " + items.size() + "건." + tail);
        }
        WorkConfig c = config();
        Map<String, WorkItem> mine = new LinkedHashMap<>();
        for (WorkItem x : all()) mine.put(x.id, x);
        int added = 0, replaced = 0;
        List<WorkItem> changed = new ArrayList<>();
        for (WorkItem x : items) {
            WorkItem have = mine.get(x.id);
            if (have == null) { store.all().add(x); changed.add(x); added++; }
            else if (x.log.size() > have.log.size()) {
                int i = store.all().indexOf(have);
                store.all().set(i, x);
                changed.add(x);
                replaced++;
            }
        }
        for (WorkConfig.Owner o : incoming.owners) if (c.owners.stream().noneMatch(z -> z.id.equals(o.id))) c.owners.add(o);
        for (WorkConfig.Type t : incoming.types) if (c.types.stream().noneMatch(z -> z.id.equals(t.id))) c.types.add(t);
        for (WorkConfig.Project p : incoming.projects) if (c.projects.stream().noneMatch(z -> z.id.equals(p.id))) c.projects.add(p);
        store.save(changed);
        store.saveConfig(c);
        return Op.ok("합쳤습니다 · 새 항목 " + added + "건, 더 최근 기록으로 바꾼 항목 " + replaced + "건." + tail);
    }

    public String exportJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("v", "2");
        out.put("app", "team-task-v2");
        out.put("items", all());
        out.put("config", config());
        try {
            return Work.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String exportCsv(List<WorkItem> list) {
        WorkConfig c = config();
        StringBuilder sb = new StringBuilder("﻿");
        String[] head = { "제목", "상태", "단계", "담당", "유형", "과제", "우선순위", "시작", "기한", "최초기한", "완료일", "주기", "반복", "태그", "다음행동", "진척%", "체크리스트", "등록일", "메모" };
        sb.append(String.join(",", java.util.Arrays.stream(head).map(Txt::csv).toList())).append("\r\n");
        for (WorkItem x : list) {
            List<String> st = c.type(x.typeId).stages;
            WorkConfig.Project pj = c.project(x.projectId);
            Object[] row = { x.title, x.getStatusName(), x.stage > 0 && x.stage <= st.size() ? st.get(x.stage - 1) : "", c.owner(x.ownerId).n,
                c.type(x.typeId).n, pj == null ? "" : pj.n, x.getPriName(), x.start, x.due, x.due0, x.doneAt, Work.cycleLabel(x),
                Work.REPEAT.getOrDefault(x.repeat, ""), String.join(" ", x.tags), x.next, percent(x), x.getTodoDone() + "/" + x.todos.size(),
                x.created, x.content };
            sb.append(String.join(",", java.util.Arrays.stream(row).map(Txt::csv).toList())).append("\r\n");
        }
        return sb.toString();
    }
}
