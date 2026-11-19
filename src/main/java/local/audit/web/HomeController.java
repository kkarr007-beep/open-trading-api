package local.audit.web;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import local.audit.CaseService;
import local.audit.Db;
import local.audit.Kst;
import local.audit.LibraryService;
import local.audit.board.BoardCalc;
import local.audit.board.BoardService;
import local.audit.rule.RuleService;
import local.audit.tools.CardCheck;
import local.audit.work.WorkService;

/** 홈: 프로그램을 열면 처음 보는 화면. 메뉴마다 지금 챙길 것을 한 화면에 모은다. */
@Controller
public class HomeController {
    private final WorkService work;
    private final CaseService cases;
    private final BoardService board;
    private final CardCheck card;
    private final RuleService rules;
    private final LibraryService library;
    private final Db db;

    public HomeController(WorkService work, CaseService cases, BoardService board, CardCheck card, RuleService rules, LibraryService library, Db db) {
        this.work = work; this.cases = cases; this.board = board; this.card = card; this.rules = rules; this.library = library; this.db = db;
    }

    @GetMapping("/")
    public String root() { return "redirect:/home"; }

    @GetMapping("/home")
    public String home(Model model) {
        Map<String, Object> v = work.todayView(7);
        model.addAttribute("v", v);
        model.addAttribute("W", work);
        model.addAttribute("today", Kst.today().toString());
        model.addAttribute("todayText", Kst.today().format(java.time.format.DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", java.util.Locale.KOREAN)));
        model.addAttribute("todos", cases.todos());
        List<Map<String, Object>> open = db.rows("""
            SELECT c.CaseId, c.Title, c.Target, c.Phase, c.Year, c.Category,
              (SELECT COUNT(*) FROM Actions a WHERE a.CaseId=c.CaseId) AS Acts,
              (SELECT COUNT(*) FROM Actions a WHERE a.CaseId=c.CaseId AND a.ActionState='이행완료') AS Done,
              (SELECT MIN(a.ReplyDueOn) FROM Actions a WHERE a.CaseId=c.CaseId AND a.ActionState<>'이행완료') AS NextDue
            FROM Cases c WHERE c.PurgedAt IS NULL AND c.Phase IN ('초안','확정','조치중')
            ORDER BY CASE c.Phase WHEN '조치중' THEN 0 WHEN '초안' THEN 1 ELSE 2 END, c.CreatedAt DESC""");
        model.addAttribute("openCases", open.size() > 6 ? open.subList(0, 6) : open);
        model.addAttribute("openCaseCount", open.size());
        boolean hasBoard = board.hasData();
        List<BoardCalc.Attn> attn = hasBoard ? board.calc("m", Kst.today()).attention() : List.of();
        model.addAttribute("hasBoard", hasBoard);
        model.addAttribute("attn", attn.size() > 6 ? attn.subList(0, 6) : attn);
        model.addAttribute("attnBad", attn.stream().filter(a -> "bad".equals(a.kind())).count());
        List<Map<String, Object>> runs = card.runs();
        model.addAttribute("runs", runs.size() > 3 ? runs.subList(0, 3) : runs);
        model.addAttribute("lastRun", runs.isEmpty() ? null : runs.get(0));
        List<RuleService.RegRow> due = rules.reviewDue();
        model.addAttribute("ruleDue", due.size() > 5 ? due.subList(0, 5) : due);
        model.addAttribute("docs", library.list(null, null, 1, 5).get("items"));
        return "home";
    }
}
