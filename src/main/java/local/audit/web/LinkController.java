package local.audit.web;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletRequest;
import local.audit.LinkService;
import local.audit.Op;
import local.audit.Txt;

/** 감사 건과 다른 메뉴 항목을 잇고 푼다. */
@Controller
public class LinkController {
    private final LinkService links;

    public LinkController(LinkService links) { this.links = links; }

    /** 감사 건 화면에서: 종류를 고르고 이름으로 찾은 항목을 붙인다. */
    @PostMapping("/links")
    public ResponseEntity<?> add(@RequestParam(required = false) String caseId, @RequestParam(required = false) String kind,
                                 @RequestParam(required = false) String value, HttpServletRequest req) {
        if (!Txt.isId(caseId)) return Web.reply(req, Op.bad("감사 건을 찾지 못했습니다."), null);
        Op op = links.add(caseId, Txt.str(kind), value);
        return Web.reply(req, op, op.ok ? "/cases/" + caseId + "#related" : null);
    }

    /** 다른 메뉴 화면에서: 이 항목을 고른 감사 건에 붙인다. */
    @PostMapping("/links/to-case")
    public ResponseEntity<?> toCase(@RequestParam(required = false) String caseId, @RequestParam(required = false) String kind,
                                    @RequestParam(required = false) String ref, HttpServletRequest req) {
        if (!Txt.isId(caseId)) return Web.reply(req, Op.bad("연결할 감사 건을 고르세요."), null);
        Op op = links.add(caseId, Txt.str(kind), ref);
        return Web.reply(req, op, null);
    }

    @PostMapping("/links/remove")
    public ResponseEntity<?> remove(@RequestParam(required = false) String caseId, @RequestParam(required = false) String kind,
                                    @RequestParam(required = false) String ref, HttpServletRequest req) {
        if (!Txt.isId(caseId)) return Web.reply(req, Op.bad("감사 건을 찾지 못했습니다."), null);
        return Web.reply(req, links.remove(caseId, Txt.str(kind), Txt.str(ref)), "/cases/" + caseId + "#related");
    }
}
