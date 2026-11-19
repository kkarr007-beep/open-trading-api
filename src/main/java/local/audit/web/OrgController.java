package local.audit.web;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletRequest;
import local.audit.OrgService;

/** 설정 › 조직·사람. */
@Controller
public class OrgController {
    private final OrgService orgs;

    public OrgController(OrgService orgs) { this.orgs = orgs; }

    @GetMapping("/settings/orgs")
    public String page(Model model) {
        model.addAttribute("orgList", String.join("\n", orgs.orgs()));
        model.addAttribute("similar", orgs.similar());
        model.addAttribute("people", orgs.people());
        return "settings-orgs";
    }

    @PostMapping("/settings/orgs")
    public ResponseEntity<?> save(@RequestParam(required = false) String list, HttpServletRequest req) {
        return Web.reply(req, orgs.save(list), "/settings/orgs");
    }

    @PostMapping("/settings/orgs/gather")
    public ResponseEntity<?> gather(HttpServletRequest req) {
        return Web.reply(req, orgs.gather(), "/settings/orgs");
    }
}
