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
        model.addAttribute("reviewDue", rules.reviewDue());
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
    public String fresh(@RequestParam(required = false) String revise, @RequestParam(required = false) String edit, Model model) throws IOException {
        model.addAttribute("cats", rules.categories());
        model.addAttribute("revise", rules.reg(revise));
        Map<String, Object> er = edit == null ? null : rules.reg(edit);
        model.addAttribute("edit", er);
        if (er != null) {
            java.util.List<Map<String, Object>> list = new java.util.ArrayList<>();
            for (Map<String, Object> a : local.audit.rule.Rules.arts(er)) {
                Map<String, Object> x = new java.util.LinkedHashMap<>();
                for (String k : new String[] { "id", "number", "title", "content", "chapter", "section", "supp", "attach" }) if (a.get(k) != null) x.put(k, a.get(k));
                list.add(x);
            }
            model.addAttribute("editArts", new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(list));
        }
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

    // ───── 등록: 파일 읽기 → 분석 미리보기 → 고쳐서 확정 ─────

    /** 올린 파일을 읽어 글과 모양(HTML)을 돌려준다. 화면이 모양을 사규 글 꼴(표 안의 표 포함)로 바꾼 뒤 /rules/parse로 보낸다. */
    @PostMapping("/rules/analyze")
    @org.springframework.web.bind.annotation.ResponseBody
    public ResponseEntity<Map<String, Object>> analyze(@RequestParam(required = false) MultipartFile upload) throws IOException {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (upload == null || upload.isEmpty()) { out.put("ok", false); out.put("message", "파일을 고르세요."); return ResponseEntity.ok(out); }
        if (upload.getSize() > 150L * 1024 * 1024) { out.put("ok", false); out.put("message", "파일이 너무 큽니다."); return ResponseEntity.ok(out); }
        String name = Txt.str(upload.getOriginalFilename());
        Path tmp = Files.createTempFile(paths.tmp, "rule", Txt.ext(name).isEmpty() ? ".txt" : Txt.ext(name));
        try {
            upload.transferTo(tmp);
            TextReader.Result res = reader.read(tmp, name);
            out.put("ok", res.ok() && !Txt.blank(res.text()));
            out.put("message", res.ok() ? "읽었습니다." : "파일에서 글을 읽지 못했습니다. " + Txt.str(res.note()));
            out.put("name", Txt.stem(name));
            out.put("text", Txt.str(res.text()));
            out.put("html", res.html());
            return ResponseEntity.ok(out);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** 사규 글을 조·부칙·별첨으로 갈라 미리보기용으로 돌려준다. */
    @PostMapping("/rules/parse")
    @org.springframework.web.bind.annotation.ResponseBody
    public ResponseEntity<Map<String, Object>> parse(@org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
        String text = Txt.str(body.get("text"));
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (text.isBlank() || text.length() > 3_000_000) { out.put("ok", false); out.put("message", "분석할 글이 없습니다."); return ResponseEntity.ok(out); }
        java.util.List<Map<String, Object>> arts = local.audit.rule.RuleParser.parse(text);
        out.put("ok", !arts.isEmpty());
        out.put("articles", arts);
        out.put("message", arts.isEmpty() ? "조문을 찾지 못했습니다." : "조 " + arts.stream().filter(a -> !Rules.bool(a, "supp")).count() + "개, 부칙·별첨 " + arts.stream().filter(a -> Rules.bool(a, "supp") || Rules.bool(a, "attach")).count() + "개로 나눴습니다.");
        Map<String, Object> dup = Txt.blank(Txt.str(body.get("reviseOf"))) ? rules.sameTitle(Txt.str(body.get("title"))) : null;
        if (dup != null) out.put("dup", Map.of("id", Rules.str(dup, "id"), "title", Rules.str(dup, "title"), "versions", Rules.maps(dup, "versions").size()));
        return ResponseEntity.ok(out);
    }

    /** 미리보기에서 확인·수정한 조로 등록하거나 개정판을 넣는다. */
    @PostMapping("/rules/save")
    @org.springframework.web.bind.annotation.ResponseBody
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> save(@org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
        Map<String, String> p = new java.util.HashMap<>();
        for (String k : new String[] { "title", "categoryId", "label", "effectiveDate", "note", "reviseOf", "mode" }) p.put(k, Txt.str(body.get(k)));
        Object raw = body.get("articles");
        java.util.List<Map<String, Object>> arts = RuleService.cleanArticles(raw instanceof java.util.List<?> l ? (java.util.List<Map<String, Object>>) l : null);
        String editOf = Txt.str(body.get("editOf"));
        Op op = editOf.isEmpty() ? rules.addArticles(p, arts) : rules.replaceArticles(editOf, arts);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", op.ok);
        out.put("message", op.message);
        if (op.ok) out.put("go", "/rules/" + op.id);
        if (op.status == 409) out.put("dupId", op.extra);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/rules/{id}/reviewed")
    public Object reviewed(@PathVariable String id, HttpServletRequest req) {
        return Web.reply(req, rules.reviewed(id), "/rules/" + id);
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
