package local.audit.web;

import java.io.IOException;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import local.audit.Kst;
import local.audit.Op;
import local.audit.Txt;
import local.audit.work.Work;
import local.audit.work.WorkItem;
import local.audit.work.WorkService;

/** 업무·일정 화면. */
@Controller
public class WorkController {
    private final WorkService work;

    public WorkController(WorkService work) { this.work = work; }

    private void common(Model model) {
        model.addAttribute("cfg", work.config());
        model.addAttribute("W", work);
        model.addAttribute("STATUS", Work.STATUS);
        model.addAttribute("PRI", Work.PRI);
        model.addAttribute("CYCLE", Work.CYCLE);
        model.addAttribute("REPEAT", Work.REPEAT);
        model.addAttribute("EVENT", Work.EVENT);
        model.addAttribute("NOTE", Work.NOTE);
        model.addAttribute("today", Kst.today().toString());
    }

    @GetMapping("/work")
    public String today(Model model) {
        common(model);
        model.addAttribute("v", work.todayView(7));
        return "work-today";
    }

    @GetMapping("/work/list")
    public String list(@RequestParam Map<String, String> q, Model model) {
        common(model);
        WorkService.Filter f = filter(q);
        model.addAttribute("q", q);
        model.addAttribute("filtered", f.any());
        model.addAttribute("rows", work.list(f));
        return "work-list";
    }

    private static WorkService.Filter filter(Map<String, String> q) {
        return new WorkService.Filter(q.get("text"), q.get("status"), q.get("owner"), q.get("type"), q.get("project"), q.get("scope"), q.get("sort"));
    }

    /** 목록을 CSV로. 지금 거른 조건 그대로 내려받는다. */
    @GetMapping("/work/export")
    public ResponseEntity<byte[]> exportCsv(@RequestParam Map<String, String> q) {
        return Web.csv("업무목록-" + Kst.today() + ".csv", work.exportCsv(work.list(filter(q))));
    }

    /** 전체 데이터를 JSON으로. 예전 HTML 판에서도 그대로 읽힌다. */
    @GetMapping("/work/data/export")
    public ResponseEntity<byte[]> exportJson() {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, Web.disposition(false, "업무데이터-" + Kst.today() + ".json"))
            .contentType(MediaType.APPLICATION_JSON).body(work.exportJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @GetMapping("/work/board")
    public String board(@RequestParam(required = false) String owner, Model model) {
        common(model);
        model.addAttribute("owner", Txt.str(owner));
        model.addAttribute("cols", work.board(owner));
        return "work-board";
    }

    @GetMapping("/work/calendar")
    public String calendar(@RequestParam(required = false) String ym, @RequestParam(required = false) String owner, Model model) {
        common(model);
        YearMonth m;
        try { m = Txt.blank(ym) ? YearMonth.from(Kst.today()) : YearMonth.parse(ym); } catch (RuntimeException e) { m = YearMonth.from(Kst.today()); }
        model.addAttribute("ym", m.toString());
        model.addAttribute("ymLabel", m.getYear() + "년 " + m.getMonthValue() + "월");
        model.addAttribute("prev", m.minusMonths(1).toString());
        model.addAttribute("next", m.plusMonths(1).toString());
        model.addAttribute("owner", Txt.str(owner));
        model.addAttribute("weeks", work.calendar(m, owner));
        return "work-calendar";
    }

    @GetMapping("/work/annual")
    public String annual(@RequestParam(required = false) Integer y, @RequestParam(required = false) String owner, Model model) {
        common(model);
        int year = y == null || y < 2000 || y > 2100 ? Kst.today().getYear() : y;
        model.addAttribute("y", year);
        model.addAttribute("owner", Txt.str(owner));
        model.addAttribute("v", work.annual(year, owner));
        return "work-annual";
    }

    @GetMapping("/work/team")
    public String team(Model model) {
        common(model);
        model.addAttribute("rows", work.team());
        return "work-team";
    }

    @GetMapping("/work/directives")
    public String directives(Model model) {
        common(model);
        List<WorkItem> all = work.ofKind("dir");
        model.addAttribute("open", all.stream().filter(x -> !x.isDone()).toList());
        model.addAttribute("done", all.stream().filter(WorkItem::isDone).toList());
        return "work-directives";
    }

    @GetMapping("/work/notes")
    public String notes(Model model) {
        common(model);
        model.addAttribute("inbox", work.ofKind("inbox"));
        model.addAttribute("plans", work.ofKind("plan"));
        model.addAttribute("notes", work.ofKind("note", "issue").stream()
            .sorted(java.util.Comparator.comparing((WorkItem x) -> Txt.str(x.created)).reversed()).toList());
        return "work-notes";
    }

    @GetMapping("/work/item/{id}")
    public String item(@PathVariable String id, Model model) {
        common(model);
        WorkItem x = work.get(id);
        model.addAttribute("x", x);
        if (x != null) {
            model.addAttribute("stages", work.config().type(x.typeId).stages);
            model.addAttribute("kids", work.kids(x.id));
            model.addAttribute("parents", work.all().stream().filter(z -> z.isWork() && !z.id.equals(x.id) && WorkService.isOpen(z)).toList());
            List<Object[]> logs = new java.util.ArrayList<>();
            for (Map<String, Object> c : x.comments) logs.add(new Object[] { Txt.str(c.get("at")), "의견 · " + Txt.str(c.get("t")) });
            for (WorkItem.Entry e : x.log) logs.add(new Object[] { e.d, e.t });
            logs.sort((a, b) -> Txt.str(b[0]).compareTo(Txt.str(a[0])));
            model.addAttribute("logs", logs);
            WorkItem src = Txt.blank(x.srcId) ? null : work.get(x.srcId);
            model.addAttribute("src", src);
        }
        return "work-item";
    }

    @GetMapping("/work/settings")
    public String settings(Model model) {
        common(model);
        model.addAttribute("count", work.all().size());
        return "work-settings";
    }

    // ───────────── 저장 ─────────────

    @PostMapping("/work/quick")
    public Object quick(@RequestParam(required = false) String text, @RequestParam(required = false) String owner, HttpServletRequest req) {
        String me = "mine".equals(owner) ? work.config().me : owner;
        return Web.reply(req, work.quickAdd(text, me), null);
    }

    @PostMapping("/work/new/{kind}")
    public Object create(@PathVariable String kind, @RequestParam Map<String, String> p, HttpServletRequest req) {
        Op op = work.create(kind, p);
        boolean open = "1".equals(p.get("open"));
        return Web.reply(req, op, op.ok && open ? "/work/item/" + op.id : null);
    }

    @PostMapping("/work/item/{id}")
    public Object update(@PathVariable String id, @RequestParam Map<String, String> p, HttpServletRequest req) {
        return Web.reply(req, work.update(id, p), null);
    }

    @PostMapping("/work/item/{id}/{what}")
    public Object action(@PathVariable String id, @PathVariable String what, @RequestParam Map<String, String> p, HttpServletRequest req) {
        Op op = switch (what) {
            case "status" -> work.status(id, Txt.str(p.get("status")));
            case "stage" -> work.stage(id, Txt.intOrNull(p.get("stage")) == null ? -1 : Txt.intOrNull(p.get("stage")));
            case "todo" -> work.todo(id, Txt.str(p.get("op")), Txt.intOrNull(p.get("i")), p.get("text"));
            case "log" -> work.log(id, p.get("text"));
            case "check" -> work.check(id, Txt.intOrNull(p.get("y")) == null ? Kst.today().getYear() : Txt.intOrNull(p.get("y")),
                Txt.intOrNull(p.get("m")) == null ? 0 : Txt.intOrNull(p.get("m")));
            case "delete" -> work.delete(id);
            case "towork" -> work.toWork(id);
            default -> Op.fail(404, "없는 작업입니다.");
        };
        String go = null;
        if (op.ok && "delete".equals(what)) go = Txt.blank(p.get("back")) ? "/work/list" : p.get("back");
        if (op.ok && "towork".equals(what)) go = "/work/item/" + op.id;
        return Web.reply(req, op, go);
    }

    @PostMapping("/work/settings/basics")
    public Object basics(@RequestParam Map<String, String> p, HttpServletRequest req) {
        return Web.reply(req, work.saveBasics(p), null);
    }

    @PostMapping("/work/settings/{what}")
    public Object entry(@PathVariable String what, @RequestParam Map<String, String> p, HttpServletRequest req) {
        return Web.reply(req, work.saveEntry(what, p), null);
    }

    @PostMapping("/work/import")
    public Object importJson(@RequestParam(required = false) MultipartFile upload, @RequestParam(required = false) String mode, HttpServletRequest req) throws IOException {
        if (upload == null || upload.isEmpty()) return Web.reply(req, Op.bad("예전 프로그램에서 내보낸 JSON 파일을 고르세요."), null);
        if (upload.getSize() > 100L * 1024 * 1024) return Web.reply(req, Op.bad("파일이 너무 큽니다."), null);
        return Web.reply(req, work.importJson(upload.getBytes(), "merge".equals(mode)), null);
    }
}
