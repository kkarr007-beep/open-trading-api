package local.audit.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import local.audit.Paths2;
import local.audit.Txt;
import local.audit.extract.TextReader;
import local.audit.rule.RuleService;
import local.audit.rule.Rules;

/** 사규 화면. */
@Controller
public class RuleController {
    private final RuleService rules;
    private final TextReader reader;
    private final Paths2 paths;

    public RuleController(RuleService rules, TextReader reader, Paths2 paths) {
        this.rules = rules;
        this.reader = reader;
        this.paths = paths;
    }

    @GetMapping("/rules")
    public String list(Model model) {
        model.addAttribute("groups", rules.groups());
        model.addAttribute("stats", rules.stats());
        model.addAttribute("cats", rules.categories());
        return "rules";
    }

    @GetMapping("/rules/search")
    public String search(@RequestParam(required = false) String q, @RequestParam(required = false) String cat, Model model) {
        String term = Txt.str(q).strip();
        model.addAttribute("q", term);
        model.addAttribute("cat", Txt.str(cat));
        model.addAttribute("cats", rules.categories());
        model.addAttribute("hits", term.isEmpty() ? java.util.List.of() : rules.search(term, cat, 120));
        model.addAttribute("terms", String.join("|", RuleService.terms(term)));
        return "rule-search";
    }

    @GetMapping("/rules/marks")
    public String marks(Model model) {
        model.addAttribute("m", rules.marks());
        return "rule-marks";
    }

    @GetMapping("/rules/compare")
    public String compare(@RequestParam(required = false) String reg, @RequestParam(required = false) String v1,
                          @RequestParam(required = false) String v2, Model model) {
        model.addAttribute("regs", rules.regs().stream().filter(r -> Rules.maps(r, "versions").size() > 1).map(rules::row).toList());
        Map<String, Object> r = rules.reg(reg);
        model.addAttribute("reg", r);
        if (r != null) {
            var vs = Rules.maps(r, "versions");
            String a = Txt.blank(v1) && vs.size() > 1 ? Rules.str(vs.get(vs.size() - 2), "id") : Txt.str(v1);
            String b = Txt.blank(v2) ? Rules.str(Rules.curVer(r), "id") : v2;
            model.addAttribute("versions", vs);
            model.addAttribute("v1", a);
            model.addAttribute("v2", b);
            var rows = rules.compare(Rules.str(r, "id"), a, b);
            model.addAttribute("rows", rows);
            model.addAttribute("changed", rows.stream().filter(x -> !"same".equals(x.tag())).count());
        }
        return "rule-compare";
    }

    @GetMapping("/rules/new")
    public String fresh(@RequestParam(required = false) String revise, Model model) {
        model.addAttribute("cats", rules.categories());
        model.addAttribute("revise", rules.reg(revise));
        model.addAttribute("today", Kst.today().toString());
        return "rule-new";
    }

    @GetMapping("/rules/solution")
    public String solution(@RequestParam(required = false) String q, Model model) {
        model.addAttribute("v", rules.solve(q));
        model.addAttribute("ROLE", RuleService.ROLE);
        model.addAttribute("RS", rules);
        return "rule-solution";
    }

    @GetMapping("/rules/timeline")
    public String timeline(Model model) {
        model.addAttribute("events", rules.timeline());
        return "rule-timeline";
    }

    @GetMapping("/rules/index")
    public String index(@RequestParam(required = false) String q, Model model) {
        model.addAttribute("q", Txt.str(q));
        model.addAttribute("groups", rules.masterIndex(q));
        model.addAttribute("stats", rules.stats());
        return "rule-index";
    }

    @GetMapping("/rules/glossary")
    public String glossary(Model model) {
        model.addAttribute("items", rules.glossary());
        return "rule-glossary";
    }

    @PostMapping("/rules/glossary/{what}")
    public Object glossaryEdit(@PathVariable String what, @RequestParam Map<String, String> p, HttpServletRequest req) {
        return Web.reply(req, rules.glossaryEdit(what, p), null);
    }

    @PostMapping("/rules/settings/synonym/{what}")
    public Object synonymEdit(@PathVariable String what, @RequestParam Map<String, String> p, HttpServletRequest req) {
        return Web.reply(req, rules.synonymEdit(what, p), null);
    }

    @GetMapping("/rules/scan")
    public String scan(@RequestParam(required = false) String f, Model model) {
        model.addAttribute("f", Txt.str(f));
        model.addAttribute("items", rules.auditScan(f));
        model.addAttribute("today", Kst.today().toString());
        return "rule-scan";
    }

    @PostMapping("/rules/scan/checklist")
    public Object scanToChecklist(@RequestParam(required = false) java.util.List<String> key, @RequestParam(required = false) String title, HttpServletRequest req) {
        Op op = rules.checklistFromScan(title, key);
        return Web.reply(req, op, op.ok ? "/rules/checklists" : null);
    }

    @GetMapping("/rules/checklists")
    public String checklists(Model model) {
        model.addAttribute("lists", rules.checklists());
        return "rule-checklists";
    }

    @PostMapping("/rules/checklists/{what}")
    public Object checklist(@PathVariable String what, @RequestParam Map<String, String> p, HttpServletRequest req) {
        return Web.reply(req, rules.checklist(what, p), null);
    }

    @GetMapping("/rules/settings")
    public String settings(Model model) {
        model.addAttribute("cats", rules.categories());
        model.addAttribute("stats", rules.stats());
        model.addAttribute("synonyms", rules.userSynonyms());
        model.addAttribute("builtin", RuleService.BUILTIN_SYN);
        return "rule-settings";
    }

    @GetMapping("/rules/data/export")
    public ResponseEntity<byte[]> exportJson() {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, Web.disposition(false, "사규관리-백업-" + Kst.today() + ".json"))
            .contentType(MediaType.APPLICATION_JSON).body(rules.exportJson().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/rules/{id}/export")
    public ResponseEntity<byte[]> exportText(@PathVariable String id) {
        Map<String, Object> r = rules.reg(id);
        return Web.plain((r == null ? "사규" : Rules.str(r, "title")) + ".txt", rules.exportText(id), true);
    }

    @GetMapping("/rules/{id}")
    public String read(@PathVariable String id, @RequestParam(required = false) String v, @RequestParam(required = false) String q,
                       @RequestParam(required = false) String diff, Model model) {
        Map<String, Object> m = rules.read(id, v, q, "1".equals(diff));
        model.addAttribute("m", m);
        model.addAttribute("cats", rules.categories());
        return "rule-read";
    }

    // ───────────── 저장 ─────────────

    @PostMapping("/rules/new")
    public Object add(@RequestParam Map<String, String> p, @RequestParam(required = false) MultipartFile upload, HttpServletRequest req) throws IOException {
        String text = p.get("text");
        if (upload != null && !upload.isEmpty()) {
            if (upload.getSize() > 150L * 1024 * 1024) return Web.reply(req, Op.bad("파일이 너무 큽니다."), null);
            String name = Txt.str(upload.getOriginalFilename());
            Path tmp = Files.createTempFile(paths.tmp, "rule", Txt.ext(name).isEmpty() ? ".txt" : Txt.ext(name));
            try {
                upload.transferTo(tmp);
                TextReader.Result res = reader.read(tmp, name);
                if (!res.ok() || Txt.blank(res.text())) return Web.reply(req, Op.bad("파일에서 글을 읽지 못했습니다. " + Txt.str(res.note())), null);
                text = res.text();
                if (Txt.blank(p.get("title")) && Txt.blank(p.get("reviseOf"))) p.put("title", Txt.stem(name));
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
        Op op = rules.add(p, text);
        return Web.reply(req, op, op.ok ? "/rules/" + op.id : null);
    }

    @PostMapping("/rules/{id}/meta")
    public Object meta(@PathVariable String id, @RequestParam Map<String, String> p, HttpServletRequest req) {
        return Web.reply(req, rules.saveMeta(id, p), null);
    }

    @PostMapping("/rules/{id}/delete")
    public Object delete(@PathVariable String id, HttpServletRequest req) {
        Op op = rules.delete(id);
        return Web.reply(req, op, op.ok ? "/rules" : null);
    }

    @PostMapping("/rules/{id}/version/{vid}/{what}")
    public Object version(@PathVariable String id, @PathVariable String vid, @PathVariable String what, @RequestParam Map<String, String> p, HttpServletRequest req) {
        Op op = rules.version(id, vid, what, p);
        return Web.reply(req, op, op.ok ? "/rules/" + id : null);
    }

    @PostMapping("/rules/{id}/art/{aid}/{what}")
    public Object article(@PathVariable String id, @PathVariable String aid, @PathVariable String what, @RequestParam Map<String, String> p, HttpServletRequest req) {
        return Web.reply(req, rules.article(id, aid, what, p), null);
    }

    @PostMapping("/rules/settings/category/{what}")
    public Object category(@PathVariable String what, @RequestParam Map<String, String> p, HttpServletRequest req) {
        return Web.reply(req, rules.category(what, p), null);
    }

    @PostMapping("/rules/import")
    public Object importJson(@RequestParam(required = false) MultipartFile upload, @RequestParam(required = false) String mode, HttpServletRequest req) throws IOException {
        if (upload == null || upload.isEmpty()) return Web.reply(req, Op.bad("예전 프로그램에서 내보낸 JSON 파일을 고르세요."), null);
        if (upload.getSize() > 200L * 1024 * 1024) return Web.reply(req, Op.bad("파일이 너무 큽니다."), null);
        return Web.reply(req, rules.importJson(upload.getBytes(), "merge".equals(mode)), null);
    }
}
