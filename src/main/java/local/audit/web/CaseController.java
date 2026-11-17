package local.audit.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import local.audit.CaseService;
import local.audit.DisciplineService;
import local.audit.FileStore;
import local.audit.Kst;
import local.audit.Op;
import local.audit.Txt;

@Controller
public class CaseController {
    private final CaseService cases;
    private final DisciplineService discipline;
    private final FileStore files;
    private final local.audit.work.WorkService work;
    private final local.audit.rule.RuleService rules;
    private final local.audit.RichTexts rich;

    public CaseController(CaseService cases, DisciplineService discipline, FileStore files,
                          local.audit.work.WorkService work, local.audit.rule.RuleService rules, local.audit.RichTexts rich) {
        this.rich = rich;
        this.work = work;
        this.rules = rules;
        this.cases = cases;
        this.discipline = discipline;
        this.files = files;
    }

    @GetMapping("/")
    public String home() { return "redirect:/work"; }

    @GetMapping("/todo")
    public String todo(Model model) {
        model.addAttribute("data", cases.todos());
        return "todo";
    }

    @GetMapping("/cases")
    public String list(@RequestParam Map<String, String> q, Model model) {
        model.addAttribute("result", query(q));
        model.addAttribute("q", q);
        model.addAttribute("years", cases.years());
        model.addAttribute("filtered", q.keySet().stream().anyMatch(k -> java.util.Arrays.asList("year", "category", "status", "overdue", "dueSoon", "text").contains(k) && !Txt.blank(q.get(k))));
        return "cases";
    }

    private Map<String, Object> query(Map<String, String> q) {
        Integer page = Txt.intOrNull(q.get("page")), size = Txt.intOrNull(q.get("pageSize"));
        return cases.list(Txt.intOrNull(q.get("year")), q.get("category"), q.get("status"), "true".equals(q.get("overdue")), "true".equals(q.get("dueSoon")),
            q.get("text"), q.get("sort"), q.get("dir"), page == null ? 1 : page, size == null ? 50 : size);
    }

    @GetMapping("/cases/export")
    @SuppressWarnings("unchecked")
    public ResponseEntity<byte[]> export(@RequestParam Map<String, String> q) {
        return Web.csv("감사건-" + Kst.today() + ".csv", cases.exportCsv((List<Map<String, Object>>) query(q).get("All")));
    }

    @GetMapping("/cases/new")
    public String fresh(Model model) {
        int year = Kst.today().getYear();
        model.addAttribute("year", year);
        model.addAttribute("previewNo", cases.peekDocumentNo(year, "업무진단"));
        return "case-new";
    }

    @GetMapping("/cases/new/preview")
    @ResponseBody
    public String preview(@RequestParam(required = false) Integer year, @RequestParam(required = false) String category) {
        return year == null ? "" : cases.peekDocumentNo(year, category);
    }

    @PostMapping("/cases/new")
    public ResponseEntity<?> create(@RequestParam Map<String, String> p, HttpServletRequest req) {
        Integer year = Txt.intOrNull(p.get("year"));
        Op op = year == null ? Op.bad(CaseService.BAD) : cases.register(year, p.get("category"), p.get("title"), p.get("target"), p.get("shelf"),
            p.get("binder"), p.get("summary"), p.get("body"), Txt.intOrNull(p.get("retentionYears")));
        if (op.ok && !Txt.blank(p.get("bodyHtml"))) cases.setBodyHtml(op.id, local.audit.HtmlClean.clean(p.get("bodyHtml")));
        return Web.reply(req, op, op.ok ? "/cases/" + op.id : null);
    }

    @GetMapping("/cases/{id}")
    public String detail(@PathVariable String id, Model model) {
        Map<String, Object> item = cases.get(id);
        model.addAttribute("item", item);
        if (item != null) model.addAttribute("discipline", discipline.forCase(id));
        model.addAttribute("today", Kst.today().toString());
        return "case-detail";
    }

    @GetMapping("/cases/{id}/print")
    public String print(@PathVariable String id, Model model) {
        model.addAttribute("item", cases.get(id));
        return "case-print";
    }

    @PostMapping("/cases/{id}/{what}")
    public ResponseEntity<?> write(@PathVariable String id, @PathVariable String what, @RequestParam Map<String, String> p,
                                   @RequestParam(required = false) MultipartFile upload, HttpServletRequest req) throws IOException {
        Integer v = Txt.intOrNull(p.get("version"));
        String att = p.get("attachmentId");
        Op op = switch (what) {
            case "save" -> {
                Integer year = Txt.intOrNull(p.get("year"));
                yield year == null ? Op.bad(CaseService.BAD)
                    : cases.saveCase(id, v, year, p.get("category"), p.get("title"), p.get("target"), p.get("owner"), p.get("shelf"), p.get("binder"));
            }
            case "result" -> {
                Op r = cases.saveResult(id, v, p.get("summary"), p.get("body"));
                if (r.ok && p.containsKey("bodyHtml")) cases.setBodyHtml(id, local.audit.HtmlClean.clean(p.get("bodyHtml")));
                yield r;
            }
            case "retention" -> cases.changeRetention(id, v, Txt.intOrNull(p.get("years")), p.get("reason"));
            case "confirm" -> cases.confirm(id, v);
            case "cancel" -> cases.cancelConfirm(id, v, p.get("reason"));
            case "close" -> cases.close(id, v, "true".equals(p.get("noActionConfirmed")));
            case "action" -> {
                Op r = cases.addAction(id, v, p.get("title"), p.get("type"), p.get("dept"), p.get("requestedOn"), p.get("dueOn"), p.get("content"));
                if (r.ok && !Txt.blank(p.get("contentHtml"))) { String aid = cases.lastActionId(id); if (aid != null) rich.put("action:" + aid, p.get("contentHtml")); }
                yield r;
            }
            case "reply", "verify" -> {
                Op r = "reply".equals(what) ? cases.recordReply(id, v, p.get("actionId"), p.get("reply")) : cases.verifyAction(id, v, p.get("actionId"), p.get("check"));
                String h = "reply".equals(what) ? p.get("replyHtml") : p.get("checkHtml");
                if (r.ok && !Txt.blank(h)) { String eid = cases.lastEntryId(p.get("actionId")); if (eid != null) rich.put("entry:" + eid, h); }
                yield r;
            }
            case "upload" -> {
                if (upload == null || upload.isEmpty()) yield Op.bad("올릴 파일을 고르세요.");
                try (InputStream in = upload.getInputStream()) {
                    yield cases.addFile(id, v, p.get("kind"), p.get("displayName"), upload.getOriginalFilename(), in, null);
                }
            }
            case "remove" -> cases.removeDraftFile(id, v, att);
            case "early" -> cases.earlyDeleteFile(id, v, att, p.get("reason"));
            case "delete" -> cases.deleteFileAny(id, v, att, p.get("reason"));
            case "hash" -> cases.verifyFileHash(id, att);
            case "reread" -> cases.reread(id, att);
            case "fill" -> cases.fillBodyFromFile(id, v, att);
            case "hold" -> cases.setHold(id, v, p.get("reason"));
            case "unhold" -> cases.releaseHold(id, v, p.get("reason"));
            case "open" -> openLocally(id, att);
            default -> Op.fail(404, "없는 작업입니다.");
        };
        return Web.reply(req, op, null);
    }

    /** 이 컴퓨터의 기본 프로그램으로 원문을 연다. 암호화된 문서는 그 프로그램과 문서보안이 열림 여부를 정한다. */
    private Op openLocally(String caseId, String attachmentId) {
        Map<String, Object> a = cases.attachment(caseId, attachmentId);
        Path path = a == null ? null : files.resolve((String) a.get("StoragePath"));
        if (path == null || !Files.isRegularFile(path)) return Op.fail(404, "파일이 제자리에 없습니다.");
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", path.toString()).start();
            else if (os.contains("mac")) new ProcessBuilder("open", path.toString()).start();
            else new ProcessBuilder("xdg-open", path.toString()).start();
            return Op.ok("이 컴퓨터의 프로그램으로 열었습니다.");
        } catch (IOException e) {
            return Op.fail(503, "열지 못했습니다. 위치를 복사해 직접 여세요.");
        }
    }

    @GetMapping("/cases/{id}/files/{att}")
    public ResponseEntity<?> download(@PathVariable String id, @PathVariable String att, @RequestParam(required = false) String dl) {
        Map<String, Object> a = cases.attachment(id, att);
        Path path = a == null || !"Present".equals(a.get("StorageState")) ? null : files.resolve((String) a.get("StoragePath"));
        if (path == null || !Files.isRegularFile(path)) return ResponseEntity.notFound().build();
        String name = (String) a.get("DisplayName");
        boolean encrypted = Integer.valueOf(1).equals(a.get("Encrypted"));
        boolean inline = dl == null && !encrypted && !"download".equals(FileStore.openKind(name));
        String type = encrypted ? "application/octet-stream" : inline && "text".equals(FileStore.openKind(name)) ? "text/plain; charset=utf-8" : FileStore.contentType(name);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, Web.disposition(inline, name))
            .contentType(MediaType.parseMediaType(type)).body(new FileSystemResource(path));
    }

    @GetMapping("/cases/{id}/files/{att}/text")
    public ResponseEntity<byte[]> text(@PathVariable String id, @PathVariable String att, @RequestParam(required = false) String dl) {
        Map<String, Object> a = cases.attachment(id, att);
        return Web.plain(a == null ? "내용.txt" : Txt.stem((String) a.get("DisplayName")) + "-읽은내용.txt", cases.fileText(id, att), dl != null);
    }

    /** 전체 찾기: 건, 자료(파일명과 읽은 내용), 징계기록을 한 번에 찾는다. */
    @GetMapping("/search")
    @SuppressWarnings("unchecked")
    public String search(@RequestParam(required = false) String q, Model model) {
        String term = q == null ? "" : q.strip();
        model.addAttribute("searchQuery", term);
        model.addAttribute("nav", "search");
        if (term.isEmpty()) return "search";
        List<Map<String, Object>> found = (List<Map<String, Object>>) cases.list(null, null, null, false, false, term, "year", "desc", 1, 25).get("Items");
        java.util.Map<String, Map<String, Object>> filesById = new java.util.LinkedHashMap<>();
        for (Map<String, Object> f : cases.searchFiles(null, null, null, term)) filesById.put((String) f.get("AttachmentId"), f);
        for (Map<String, Object> f : cases.searchFiles(term, null, null, null)) filesById.putIfAbsent((String) f.get("AttachmentId"), f);
        List<Map<String, Object>> fileHits = new java.util.ArrayList<>(filesById.values());
        List<Map<String, Object>> people = discipline.list(term, null, null);
        model.addAttribute("caseHits", found);
        model.addAttribute("fileHits", fileHits.size() > 30 ? fileHits.subList(0, 30) : fileHits);
        model.addAttribute("peopleHits", people.size() > 30 ? people.subList(0, 30) : people);
        model.addAttribute("workHits", work.search(term, 30));
        model.addAttribute("W", work);
        model.addAttribute("ruleHits", rules.search(term, null, 30));
        model.addAttribute("ruleTerms", String.join("|", local.audit.rule.RuleService.terms(term)));
        return "search";
    }

    /** 읽은 내용을 표·그림을 살린 모양으로 보여 준다. 미리보기 창 안에 들어간다. */
    @GetMapping("/cases/{id}/files/{att}/view")
    public ResponseEntity<byte[]> view(@PathVariable String id, @PathVariable String att, @RequestParam(required = false) String dl) {
        if (dl != null) return text(id, att, dl);
        Map<String, Object> a = cases.attachment(id, att);
        return Web.html(local.audit.extract.Rich.page(a == null ? "읽은 내용" : (String) a.get("DisplayName"), cases.fileHtml(id, att)));
    }

    @GetMapping("/files")
    public String fileSearch(@RequestParam Map<String, String> q, Model model) {
        model.addAttribute("q", q);
        model.addAttribute("files", cases.searchFiles(q.get("name"), q.get("kind"), q.get("caseTitle"), q.get("body")));
        return "files";
    }
}
