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
    private final local.audit.work.WorkReports reports;
    private final local.audit.extract.TextReader reader;
    private final local.audit.Paths2 paths;

    private final local.audit.LinkService links;
    public WorkController(WorkService work, local.audit.work.WorkReports reports, local.audit.extract.TextReader reader, local.audit.Paths2 paths, local.audit.LinkService links) {
        this.links = links;
        this.work = work;
        this.reports = reports;
        this.reader = reader;
        this.paths = paths;
    }

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
        model.addAttribute("todayText", Kst.today().format(java.time.format.DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", java.util.Locale.KOREAN)));
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

    /** 계획 대기함: 아직 하기로 정하지 않은 일. 업무 목록·부하 계산에는 들어가지 않는다. */
    @GetMapping("/work/plans")
    public String plans(Model model) {
        common(model);
        List<WorkItem> all = new java.util.ArrayList<>(work.ofKind("plan"));
        String t = Kst.today().toString();
        all.sort(java.util.Comparator.comparing((WorkItem x) -> Txt.blank(x.tick) ? "9" : x.tick).thenComparing(x -> Txt.str(x.created), java.util.Comparator.reverseOrder()));
        model.addAttribute("plans", all);
        model.addAttribute("duePlans", all.stream().filter(x -> !Txt.blank(x.tick) && x.tick.compareTo(t) <= 0).toList());
        model.addAttribute("waitPlans", all.stream().filter(x -> Txt.blank(x.tick) || x.tick.compareTo(t) > 0).toList());
        return "work-plans";
    }

    /** 연간 정기 현황에서 바로 정기 업무를 만든다. 고른 달마다 이행 칸이 생긴다. */
    @PostMapping("/work/annual/new")
    public Object annualNew(@RequestParam Map<String, String> p, HttpServletRequest req) {
        Op op = work.change("정기 업무 추가", () -> {
            Op c = work.create("work", p);
            if (!c.ok) return c;
            Map<String, String> u = new java.util.HashMap<>();
            u.put("cycle", "months");
            u.put("monthsSent", "1");
            boolean any = false;
            for (int m = 1; m <= 12; m++) if ("1".equals(p.get("m" + m))) { u.put("m" + m, "1"); any = true; }
            if (!any) for (int m = 1; m <= 12; m++) u.put("m" + m, "1");
            Op up = work.update(c.id, u);
            return up.ok ? Op.ok("정기 업무 「" + Txt.str(p.get("title")).strip() + "」를 더했습니다.", c.id) : up;
        });
        return Web.reply(req, op, null);
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
    public String notes(@RequestParam(required = false) String cat, @RequestParam(required = false) String q,
                        @RequestParam(required = false) Integer page, Model model) {
        common(model);
        String c = Txt.str(cat);
        model.addAttribute("cat", c);
        model.addAttribute("q", Txt.str(q));
        model.addAttribute("groups", work.noteGroups(q, 5));
        if (!c.isEmpty()) model.addAttribute("v", work.notePage(c, q, page == null ? 1 : page, 30));
        model.addAttribute("inbox", work.ofKind("inbox"));
        model.addAttribute("plans", work.ofKind("plan"));
        return "work-notes";
    }

    /** 올린 문서를 읽어 글자와 모양(표·그림·서식)을 돌려준다. */
    private String[] readDocument(MultipartFile upload) throws IOException {
        String name = Txt.str(upload.getOriginalFilename());
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile(paths.tmp, "doc", Txt.ext(name).isEmpty() ? ".txt" : Txt.ext(name));
        try {
            upload.transferTo(tmp);
            local.audit.extract.TextReader.Result res = reader.read(tmp, name);
            if (!res.ok() || Txt.blank(res.text())) return new String[] { null, null, "파일에서 글을 읽지 못했습니다. " + Txt.str(res.note()) };
            String html = res.html() != null ? res.html() : local.audit.extract.Rich.fromText(res.text());
            return new String[] { res.text(), html, Txt.stem(name) };
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }

    @PostMapping("/work/notes/import")
    public Object noteImport(@RequestParam(required = false) MultipartFile upload, @RequestParam(required = false) String title,
                             @RequestParam(required = false) String ntype, HttpServletRequest req) throws IOException {
        if (upload == null || upload.isEmpty()) return Web.reply(req, Op.bad("가져올 문서를 고르세요."), null);
        if (upload.getSize() > 150L * 1024 * 1024) return Web.reply(req, Op.bad("파일이 너무 큽니다."), null);
        String[] d = readDocument(upload);
        if (d[0] == null) return Web.reply(req, Op.bad(d[2]), null);
        String t = Txt.blank(title) ? d[2] : title;
        Op op = work.change("문서로 노트 만들기", () -> work.noteFromDocument(t, ntype, d[0], d[1]));
        return Web.reply(req, op, op.ok ? "/work/item/" + op.id : null);
    }

    @PostMapping("/work/item/{id}/import")
    public Object itemImport(@PathVariable String id, @RequestParam(required = false) MultipartFile upload,
                             @RequestParam(required = false) String mode, HttpServletRequest req) throws IOException {
        if (upload == null || upload.isEmpty()) return Web.reply(req, Op.bad("가져올 문서를 고르세요."), null);
        String[] d = readDocument(upload);
        if (d[0] == null) return Web.reply(req, Op.bad(d[2]), null);
        Op op = work.change("문서 가져오기", () -> work.importIntoItem(id, d[0], d[1], "replace".equals(mode)));
        return Web.reply(req, op, op.ok ? "/work/item/" + id : null);
    }

    @GetMapping("/work/item/{id}")
    public String item(@PathVariable String id, Model model) {
        common(model);
        WorkItem x = work.get(id);
        model.addAttribute("x", x);
        if (x != null) {
            model.addAttribute("stages", work.config().type(x.typeId).stages);
            model.addAttribute("kids", work.kids(x.id));
            model.addAttribute("linkedCases", links.casesFor("work", x.id));
            model.addAttribute("caseChoices", links.caseChoices());
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

    @GetMapping("/work/timeline")
    public String timeline(@RequestParam(required = false) String start, @RequestParam(required = false) String scale,
                           @RequestParam(required = false) String owner, Model model) {
        common(model);
        model.addAttribute("owner", Txt.str(owner));
        model.addAttribute("v", reports.timeline(start, scale, owner));
        return "work-timeline";
    }

    @GetMapping("/work/insight")
    public String insight(Model model) {
        common(model);
        model.addAttribute("v", reports.insight());
        model.addAttribute("R", reports);
        return "work-insight";
    }

    @GetMapping("/work/report/weekly")
    public String weekly(Model model) {
        common(model);
        model.addAttribute("v", reports.weekly());
        return "work-weekly";
    }

    /** 다음 달 업무계획 초안을 글 파일로. */
    @GetMapping("/work/report/month/export")
    public ResponseEntity<byte[]> monthPlan() {
        java.time.YearMonth ym = java.time.YearMonth.from(Kst.today()).plusMonths(1);
        return Web.plain("월간업무계획-" + ym.toString().replace("-", "") + ".txt", reports.monthPlan(), true);
    }

    @GetMapping("/work/meetings")
    public String meetings(@RequestParam(required = false) String tab, @RequestParam(required = false) String id, Model model) {
        common(model);
        String t = "issue".equals(tab) || "new".equals(tab) ? tab : "meet";
        model.addAttribute("tab", t);
        List<WorkItem> ms = reports.meetings();
        model.addAttribute("meetings", ms);
        WorkItem cur = id == null ? (ms.isEmpty() ? null : ms.get(0)) : work.get(id);
        model.addAttribute("cur", cur);
        if (cur != null) {
            model.addAttribute("decisions", local.audit.work.WorkReports.decisions(cur.content));
            model.addAttribute("actions", local.audit.work.WorkReports.actions(cur.content));
            model.addAttribute("made", reports.fromMeeting(cur.id));
        }
        model.addAttribute("issues", reports.issues());
        model.addAttribute("R", reports);
        return "work-meetings";
    }

    @PostMapping("/work/meetings/new")
    public Object addMeeting(@RequestParam Map<String, String> p, @RequestParam(required = false) MultipartFile upload, HttpServletRequest req) throws IOException {
        String text = p.get("text");
        if (upload != null && !upload.isEmpty()) {
            String name = Txt.str(upload.getOriginalFilename());
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile(paths.tmp, "meet", Txt.ext(name).isEmpty() ? ".txt" : Txt.ext(name));
            try {
                upload.transferTo(tmp);
                local.audit.extract.TextReader.Result res = reader.read(tmp, name);
                if (!res.ok() || Txt.blank(res.text())) return Web.reply(req, Op.bad("파일에서 글을 읽지 못했습니다. " + Txt.str(res.note())), null);
                text = (Txt.blank(text) ? "" : text + "\n\n") + res.text();
                if (Txt.blank(p.get("title"))) p.put("title", Txt.stem(name));
            } finally {
                java.nio.file.Files.deleteIfExists(tmp);
            }
        }
        String body = text;
        Op op = work.change("회의록 넣기", () -> reports.addMeeting(p, body));
        return Web.reply(req, op, op.ok ? "/work/meetings?id=" + op.id : null);
    }

    @PostMapping("/work/meetings/{id}/actions")
    public Object meetingActions(@PathVariable String id, @RequestParam(required = false) List<String> line,
                                 @RequestParam(required = false) String ownerId, HttpServletRequest req) {
        return Web.reply(req, work.change("회의 조치 업무로", () -> reports.actionsToWork(id, line, ownerId)), null);
    }

    @PostMapping("/work/issues/new")
    public Object addIssue(@RequestParam Map<String, String> p, HttpServletRequest req) {
        return Web.reply(req, work.change("쟁점 등록", () -> reports.addIssue(p)), null);
    }

    @PostMapping("/work/issues/scan")
    public Object scanIssues(HttpServletRequest req) {
        return Web.reply(req, work.change("쟁점 다시 훑기", reports::scanIssues), null);
    }

    @PostMapping("/work/issues/{id}/state")
    public Object issueState(@PathVariable String id, @RequestParam(required = false) String state, HttpServletRequest req) {
        return Web.reply(req, work.change("쟁점 상태", () -> reports.issueStatus(id, Txt.str(state))), null);
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
        return Web.reply(req, work.change("빠른 추가", () -> work.quickAdd(text, me)), null);
    }

    @PostMapping("/work/new/{kind}")
    public Object create(@PathVariable String kind, @RequestParam Map<String, String> p, HttpServletRequest req) {
        Op op = work.change(local.audit.work.Work.kindName(kind) + " 추가", () -> work.create(kind, p));
        boolean open = "1".equals(p.get("open"));
        return Web.reply(req, op, op.ok && open ? "/work/item/" + op.id : null);
    }

    @PostMapping("/work/item/{id}")
    public Object update(@PathVariable String id, @RequestParam Map<String, String> p, HttpServletRequest req) {
        return Web.reply(req, work.change("내용 저장", () -> work.update(id, p)), null);
    }

    @PostMapping("/work/item/{id}/{what}")
    public Object action(@PathVariable String id, @PathVariable String what, @RequestParam Map<String, String> p, HttpServletRequest req) {
        Op op = work.change(ACTION_NAMES.getOrDefault(what, "작업"), () -> switch (what) {
            case "status" -> work.status(id, Txt.str(p.get("status")));
            case "stage" -> work.stage(id, Txt.intOrNull(p.get("stage")) == null ? -1 : Txt.intOrNull(p.get("stage")));
            case "todo" -> work.todo(id, Txt.str(p.get("op")), Txt.intOrNull(p.get("i")), p.get("text"));
            case "log" -> work.log(id, p.get("text"));
            case "check" -> work.check(id, Txt.intOrNull(p.get("y")) == null ? Kst.today().getYear() : Txt.intOrNull(p.get("y")),
                Txt.intOrNull(p.get("m")) == null ? 0 : Txt.intOrNull(p.get("m")));
            case "delete" -> work.delete(id);
            case "towork" -> work.toWork(id);
            default -> Op.fail(404, "없는 작업입니다.");
        });
        String go = null;
        if (op.ok && "delete".equals(what)) go = Txt.blank(p.get("back")) ? "/work/list" : p.get("back");
        if (op.ok && "towork".equals(what)) go = "/work/item/" + op.id;
        return Web.reply(req, op, go);
    }

    private static final Map<String, String> ACTION_NAMES = Map.of("status", "상태 변경", "stage", "단계 변경", "todo", "체크리스트",
        "log", "진행 기록", "check", "정기 이행 표시", "delete", "지우기", "towork", "업무로 옮기기");

    /** 되돌리기 (Ctrl+Z). */
    @PostMapping("/work/undo")
    public Object undo(HttpServletRequest req) {
        return Web.reply(req, work.undo(), null);
    }

    @PostMapping("/work/settings/basics")
    public Object basics(@RequestParam Map<String, String> p, HttpServletRequest req) {
        return Web.reply(req, work.change("기본 설정", () -> work.saveBasics(p)), null);
    }

    @PostMapping("/work/settings/{what}")
    public Object entry(@PathVariable String what, @RequestParam Map<String, String> p, HttpServletRequest req) {
        return Web.reply(req, work.change("설정", () -> work.saveEntry(what, p)), null);
    }

    @PostMapping("/work/import")
    public Object importJson(@RequestParam(required = false) MultipartFile upload, @RequestParam(required = false) String mode, HttpServletRequest req) throws IOException {
        if (upload == null || upload.isEmpty()) return Web.reply(req, Op.bad("예전 프로그램에서 내보낸 JSON 파일을 고르세요."), null);
        if (upload.getSize() > 100L * 1024 * 1024) return Web.reply(req, Op.bad("파일이 너무 큽니다."), null);
        byte[] bytes = upload.getBytes();
        return Web.reply(req, work.change("옮겨 오기", () -> work.importJson(bytes, "merge".equals(mode))), null);
    }
}
