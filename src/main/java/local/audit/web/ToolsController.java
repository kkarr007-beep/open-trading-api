package local.audit.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import local.audit.CaseService;
import local.audit.Kst;
import local.audit.Op;
import local.audit.Txt;
import local.audit.tools.CardCheck;
import local.audit.tools.Sheet;

/** 점검툴 화면. 지금은 법인카드 점검 하나, 같은 틀로 더한다. */
@Controller
public class ToolsController {
    private final CardCheck card;
    private final CaseService cases;
    private final local.audit.LinkService links;

    public ToolsController(CardCheck card, CaseService cases, local.audit.LinkService links) {
        this.links = links;
        this.card = card;
        this.cases = cases;
    }

    @GetMapping("/tools")
    public String tools() { return "redirect:/tools/card"; }

    @GetMapping("/tools/card")
    public String runs(Model model) {
        model.addAttribute("runs", card.runs());
        model.addAttribute("RULES", CardCheck.RULES);
        model.addAttribute("HELP", CardCheck.HELP);
        model.addAttribute("defaultTitle", Kst.today().toString().substring(0, 7) + " 법인카드 점검");
        return "tools-card";
    }

    @PostMapping("/tools/card/upload")
    public Object upload(@RequestParam(required = false) MultipartFile upload, @RequestParam Map<String, String> p, HttpServletRequest req) {
        List<Sheet.Table> tables;
        String name;
        try {
            if (upload != null && !upload.isEmpty()) {
                name = upload.getOriginalFilename();
                tables = Sheet.read(name, upload.getBytes());
            } else if (!Txt.blank(p.get("paste"))) {
                name = "붙여 넣은 표";
                tables = List.of(new Sheet.Table("붙여 넣기", Sheet.text(p.get("paste"))));
            } else {
                return Web.reply(req, Op.bad("사용내역 파일을 고르거나 표를 붙여 넣으세요."), null);
            }
        } catch (IOException | RuntimeException e) {
            return Web.reply(req, Op.bad("표를 읽지 못했습니다. 엑셀(xlsx·xls)이나 CSV로 저장해 다시 올려 주세요."), null);
        }
        Op op = card.upload(p.get("title"), name, tables);
        return Web.reply(req, op, op.ok ? "/tools/card/" + op.id + "/map" : null);
    }

    @GetMapping("/tools/card/{id}/map")
    public String map(@PathVariable String id, Model model) {
        Map<String, Object> v = card.pending(id);
        if (v == null) return "redirect:/tools/card/" + id;
        model.addAllAttributes(v);
        model.addAttribute("fields", CardCheck.FIELDS);
        return "tools-card-map";
    }

    @PostMapping("/tools/card/{id}/map")
    public Object confirm(@PathVariable String id, @RequestParam Map<String, String> p, HttpServletRequest req) {
        Op op = card.confirm(id, p);
        return Web.reply(req, op, op.ok ? "/tools/card/" + id : null);
    }

    @GetMapping("/tools/card/{id}")
    public String result(@PathVariable String id, @RequestParam Map<String, String> q, Model model) {
        Map<String, Object> run = card.run(id);
        if (run == null) return "redirect:/tools/card";
        if (run.get("Pending") != null) return "redirect:/tools/card/" + id + "/map";
        Integer page = Txt.intOrNull(q.get("page"));
        // 기본은 '확인 대상'(20점 이상)만. 낮은 점수까지 보려면 점수: 전체.
        q = new java.util.HashMap<>(q);
        if (!q.containsKey("min")) q.put("min", String.valueOf(CardCheck.FOCUS));
        model.addAttribute("run", run);
        model.addAttribute("linkedCases", links.casesFor("card", id));
        model.addAttribute("caseChoices", links.caseChoices());
        model.addAttribute("s", card.summary(id));
        model.addAttribute("v", card.list(id, q, page == null ? 1 : page, 100));
        model.addAttribute("q", q);
        model.addAttribute("names", card.names(id));
        model.addAttribute("depts", card.depts(id));
        model.addAttribute("RULES", CardCheck.RULES);
        model.addAttribute("STATUSES", CardCheck.STATUSES);
        return "tools-card-run";
    }

    @GetMapping("/tools/card/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id, @RequestParam(required = false) String all) {
        Map<String, Object> run = card.run(id);
        if (run == null) return ResponseEntity.notFound().build();
        String name = Txt.str(run.get("Title")) + ("1".equals(all) ? " 전체" : " 점검 결과") + ".csv";
        return Web.csv(name, card.csv(id, "1".equals(all)));
    }

    @PostMapping("/tools/card/{id}/recheck")
    public Object recheck(@PathVariable String id, HttpServletRequest req) {
        if (card.run(id) == null) return Web.reply(req, Op.fail(404, "점검 회차를 찾을 수 없습니다."), null);
        card.recheck(id);
        return Web.reply(req, Op.ok("지금 기준으로 다시 점검했습니다."), "/tools/card/" + id);
    }

    @PostMapping("/tools/card/{id}/delete")
    public Object delete(@PathVariable String id, HttpServletRequest req) {
        Op op = card.deleteRun(id);
        return Web.reply(req, op, op.ok ? "/tools/card" : null);
    }

    @PostMapping("/tools/card/review")
    public Object review(@RequestParam Map<String, String> p, HttpServletRequest req) {
        return Web.reply(req, card.review(p.get("key"), p.get("status"), p.get("note")), null);
    }

    @PostMapping("/tools/card/exception")
    public Object exception(@RequestParam Map<String, String> p, HttpServletRequest req) {
        Long tx;
        try { tx = Long.parseLong(Txt.str(p.get("tx"))); } catch (NumberFormatException e) { return Web.reply(req, Op.bad("건을 찾을 수 없습니다."), null); }
        Op op = card.addException(tx, p.get("scope"), p.get("rule"), p.get("note"));
        String back = Txt.str(p.get("back"));
        return Web.reply(req, op, op.ok ? (back.startsWith("/tools/card/") ? back : "/tools/card") : null);
    }

    @GetMapping("/tools/card/exceptions")
    public String exceptions(Model model) {
        model.addAttribute("items", card.exceptions());
        return "tools-card-exceptions";
    }

    @PostMapping("/tools/card/exceptions/{id}/delete")
    public Object deleteException(@PathVariable String id, HttpServletRequest req) {
        Op op = card.deleteException(id);
        return Web.reply(req, op, op.ok ? "/tools/card/exceptions" : null);
    }

    @GetMapping("/tools/card/settings")
    public String settings(Model model) {
        model.addAttribute("c", card.config());
        model.addAttribute("RULES", CardCheck.RULES);
        model.addAttribute("HELP", CardCheck.HELP);
        return "tools-card-settings";
    }

    @PostMapping("/tools/card/settings")
    public Object saveSettings(@RequestParam Map<String, String> p, HttpServletRequest req) {
        return Web.reply(req, card.saveConfig(p), "/tools/card/settings");
    }

    @PostMapping("/tools/card/settings/reset")
    public Object resetSettings(HttpServletRequest req) {
        return Web.reply(req, card.resetConfig(), "/tools/card/settings");
    }

    /** 고른 건으로 감사기록 건(초안)을 만든다. */
    @PostMapping("/tools/card/{id}/case")
    public Object toCase(@PathVariable String id, @RequestParam(name = "tx", required = false) List<String> tx, HttpServletRequest req) {
        List<Long> ids = new ArrayList<>();
        if (tx != null) for (String s : tx) { try { ids.add(Long.parseLong(s)); } catch (NumberFormatException ignored) { } }
        Map<String, Object> d = card.caseDraft(id, ids);
        if (d == null) return Web.reply(req, Op.bad("감사기록으로 넘길 건을 고르세요."), null);
        List<String> cats = cases.categories();
        Op op = cases.register(Kst.today().getYear(), cats.isEmpty() ? "업무진단" : cats.get(0), (String) d.get("title"), (String) d.get("target"),
            "", "", (String) d.get("summary"), (String) d.get("body"), null);
        if (op.ok) links.add(op.id, "card", id);
        return Web.reply(req, op.ok ? Op.ok("감사기록에 건 초안을 만들었습니다. 구분·대상을 확인하세요.", op.id) : op, op.ok ? "/cases/" + op.id : null);
    }
}
