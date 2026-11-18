package local.audit.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import local.audit.board.BoardCalc;
import local.audit.board.BoardData;
import local.audit.board.BoardService;

/** 경영 현황판(자바 화면). 예전 현황판 자료를 그대로 읽고 쓴다. */
@Controller
public class BoardController {
    private final BoardService board;
    /** 붙여 넣은 글을 잠시 맡아 두는 곳(입력 화면으로 넘어가는 동안만). */
    private final Map<String, Object[]> pastes = new java.util.concurrent.ConcurrentHashMap<>();

    public BoardController(BoardService board) { this.board = board; }

    void common(Model model, BoardCalc c) {
        model.addAttribute("c", c);
        model.addAttribute("d", c.orgs().isEmpty() ? null : board.load());
        model.addAttribute("PERIODS", BoardCalc.PERIODS);
        model.addAttribute("period", c.period);
        model.addAttribute("date", c.selDate);
        model.addAttribute("range", c.range());
        model.addAttribute("pace", c.pace());
        model.addAttribute("pending", c.pending());
        model.addAttribute("today", Kst.today().toString());
    }

    @GetMapping("/board")
    public String overview(@RequestParam(required = false) String p, @RequestParam(required = false) String date, Model model) {
        BoardData d = board.load();
        BoardCalc c = new BoardCalc(d, p == null ? "m" : p, BoardService.date(date));
        common(model, c);
        model.addAttribute("d", d);
        List<Map<String, Object>> orgs = new ArrayList<>();
        for (BoardData.Org o : d.orgs) {
            Map<String, Object> m = new LinkedHashMap<>();
            List<BoardCalc.Result> all = c.results(o.id, null);
            m.put("org", o);
            m.put("avg", BoardCalc.avg(all));
            m.put("prev", BoardCalc.avgPrev(all));
            m.put("land", BoardCalc.avgLanding(all));
            m.put("base", c.results(o.id, "base"));
            m.put("vu", c.results(o.id, "vu"));
            m.put("issues", c.openIssues(o.id));
            orgs.add(m);
        }
        model.addAttribute("orgs", orgs);
        model.addAttribute("attn", c.attention());
        model.addAttribute("ivr", c.ivrPair());
        model.addAttribute("days", c.monthDays());
        model.addAttribute("hasData", board.hasData());
        return "board";
    }

    @GetMapping("/board/kpi/{id}")
    public String kpi(@PathVariable String id, @RequestParam(required = false) String p, @RequestParam(required = false) String date, Model model) {
        BoardData d = board.load();
        BoardCalc c = new BoardCalc(d, p == null ? "m" : p, BoardService.date(date));
        common(model, c);
        BoardData.Kpi k = d.kpi(id);
        if (k == null) return "redirect:/board";
        model.addAttribute("k", k);
        model.addAttribute("r", c.calc(k));
        model.addAttribute("trend", c.trend(k));
        model.addAttribute("orgName", d.orgName(k.org));
        return "board-kpi";
    }

    @GetMapping("/board/entry")
    public String entry(@RequestParam(required = false) String date, @RequestParam(required = false) String paste, Model model) {
        BoardData d = board.load();
        BoardCalc c = new BoardCalc(d, "d", BoardService.date(date));
        common(model, c);
        model.addAttribute("d", d);
        model.addAttribute("week", BoardCalc.weekKey(c.sel));
        model.addAttribute("weekLabel", BoardCalc.weekLabel(BoardCalc.weekKey(c.sel)));
        model.addAttribute("prevDay", BoardCalc.prevBiz(c.sel).toString());
        Map<String, String> values = new LinkedHashMap<>();
        String wk = BoardCalc.weekKey(c.sel);
        for (BoardData.Kpi k : d.kpis) {
            Double v = k.weekly() ? d.week(wk, k.id) : d.day(c.selDate, k.id);
            if (v != null) values.put(k.id, BoardCalc.plain(v));
        }
        model.addAttribute("values", values);
        model.addAttribute("filled", new LinkedHashMap<String, String>());
        Map<String, List<BoardData.Kpi>> byOrg = new LinkedHashMap<>();
        for (BoardData.Kpi k : d.kpis) byOrg.computeIfAbsent(k.org, x -> new ArrayList<>()).add(k);
        model.addAttribute("byOrg", byOrg);
        Object[] kept = paste == null ? null : pastes.get(paste);
        if (kept != null) {
            String text = (String) kept[0];
            List<BoardService.PasteRow> rows = board.paste(text);
            Map<String, String> filled = new LinkedHashMap<>();
            for (BoardService.PasteRow x : rows) if (x.ok()) filled.put(x.kid(), BoardCalc.plain(x.value()));
            values.putAll(filled);
            model.addAttribute("pasteRows", rows);
            model.addAttribute("pasteText", text);
            model.addAttribute("filled", filled);
        }
        return "board-entry";
    }

    /** 붙여 넣은 표를 맡아 두고, 입력 화면이 그 값을 칸에 채워 보여 준다(저장은 '실적 저장'에서). */
    @PostMapping("/board/entry/paste")
    public Object paste(@RequestParam Map<String, String> p, HttpServletRequest req) {
        String text = Txt.str(p.get("text"));
        if (text.isBlank()) return Web.reply(req, Op.bad("붙여 넣은 내용이 없습니다."), null);
        if (text.length() > 200_000) return Web.reply(req, Op.bad("붙여 넣은 내용이 너무 깁니다."), null);
        pastes.entrySet().removeIf(e -> System.currentTimeMillis() - (long) e.getValue()[1] > 30 * 60_000L);
        String id = Txt.newId();
        pastes.put(id, new Object[] { text, System.currentTimeMillis() });
        LocalDate d = BoardService.date(p.get("date"));
        long ok = board.paste(text).stream().filter(BoardService.PasteRow::ok).count();
        return Web.reply(req, Op.ok(ok + "개 지표를 칸에 채웠습니다. 확인 후 '실적 저장'을 누르세요."), "/board/entry?date=" + d + "&paste=" + id);
    }

    @PostMapping("/board/entry")
    public Object saveEntry(@RequestParam Map<String, String> p, HttpServletRequest req) {
        LocalDate d = BoardService.date(p.get("date"));
        return Web.reply(req, board.saveEntries(d, p), "/board/entry?date=" + d);
    }

    @PostMapping("/board/entry/{what}")
    public Object entryAction(@PathVariable String what, @RequestParam Map<String, String> p, HttpServletRequest req) {
        LocalDate d = BoardService.date(p.get("date"));
        Op op = switch (what) {
            case "prev" -> board.fillPrev(d);
            case "clear" -> board.clearDay(d);
            default -> Op.fail(404, "없는 작업입니다.");
        };
        return Web.reply(req, op, "/board/entry?date=" + d);
    }

    @GetMapping("/board/issues")
    public String issues(@RequestParam(required = false) String org, @RequestParam(required = false) String st, @RequestParam(required = false) String edit, Model model) {
        BoardData d = board.load();
        BoardCalc c = new BoardCalc(d, "m", Kst.today());
        common(model, c);
        model.addAttribute("d", d);
        List<Map.Entry<String, BoardData.Issue>> list = new ArrayList<>();
        String fs = st == null ? "open" : st;
        for (Map.Entry<String, BoardData.Issue> e : d.issues.entrySet()) {
            BoardData.Issue x = e.getValue();
            if (x == null) continue;
            if (!Txt.blank(org) && !org.equals(x.org)) continue;
            if ("open".equals(fs) && !x.open() || "해결".equals(fs) && x.open()) continue;
            list.add(e);
        }
        list.sort((a, b) -> Txt.str(b.getValue().at).compareTo(Txt.str(a.getValue().at)));
        model.addAttribute("list", list);
        model.addAttribute("forg", Txt.str(org));
        model.addAttribute("fst", fs);
        model.addAttribute("editId", edit != null && d.issues.containsKey(edit) ? edit : null);
        model.addAttribute("ed", edit != null ? d.issues.get(edit) : null);
        model.addAttribute("CATS", BoardService.CATS);
        model.addAttribute("LEVELS", BoardService.LEVELS);
        model.addAttribute("STATES", BoardService.STATES);
        return "board-issues";
    }

    @PostMapping("/board/issues/save")
    public Object saveIssue(@RequestParam Map<String, String> p, HttpServletRequest req) {
        Op op = board.saveIssue(p.get("id"), p);
        return Web.reply(req, op, "/board/issues");
    }

    @PostMapping("/board/issues/{id}/delete")
    public Object deleteIssue(@PathVariable String id, HttpServletRequest req) {
        return Web.reply(req, board.deleteIssue(id), "/board/issues");
    }

    @PostMapping("/board/issues/auto")
    public Object autoIssues(@RequestParam Map<String, String> p, HttpServletRequest req) {
        return Web.reply(req, board.autoIssues(Txt.blank(p.get("p")) ? "m" : p.get("p"), BoardService.date(p.get("date"))), "/board/issues");
    }

    @GetMapping("/board/report")
    public String report(@RequestParam(required = false) String p, @RequestParam(required = false) String date, Model model) {
        BoardCalc c = board.calc(p == null ? "m" : p, BoardService.date(date));
        common(model, c);
        model.addAttribute("text", c.report());
        return "board-report";
    }

    @GetMapping("/board/report/export")
    public ResponseEntity<byte[]> reportFile(@RequestParam(required = false) String p, @RequestParam(required = false) String date) {
        BoardCalc c = board.calc(p == null ? "m" : p, BoardService.date(date));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, Web.disposition(false, "경영현황-보고-" + c.selDate + ".txt"))
            .contentType(MediaType.parseMediaType("text/plain; charset=utf-8")).body(c.report().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/board/settings")
    public String settings(@RequestParam(required = false) String kpi, Model model) {
        BoardData d = board.load();
        BoardCalc c = new BoardCalc(d, "m", Kst.today());
        common(model, c);
        model.addAttribute("d", d);
        Map<String, Long> kpiCount = new LinkedHashMap<>();
        for (BoardData.Kpi k : d.kpis) kpiCount.merge(k.org, 1L, Long::sum);
        model.addAttribute("kpiCount", kpiCount);
        model.addAttribute("focusText", String.join("\n", d.focus.stream().map(f -> f.w.isBlank() ? f.t : f.t + " | " + f.w).toList()));
        model.addAttribute("hasData", board.hasData());
        return "board-settings";
    }

    @PostMapping("/board/settings/{what}")
    public Object settingsAction(@PathVariable String what, @RequestParam Map<String, String> p, HttpServletRequest req) {
        Op op = switch (what) {
            case "org" -> board.saveOrg(p);
            case "org-delete" -> board.deleteOrg(p.get("id"));
            case "kpi" -> board.saveKpi(p);
            case "kpi-delete" -> board.deleteKpi(p.get("id"));
            case "focus" -> board.saveFocus(p.get("text"));
            case "card" -> board.saveCard(Txt.intOrNull(p.get("index")), p);
            case "card-delete" -> { Integer i = Txt.intOrNull(p.get("index")); yield i == null ? Op.bad("항목을 고르세요.") : board.deleteCard(i); }
            default -> Op.fail(404, "없는 작업입니다.");
        };
        String back = "card".equals(what) || "card-delete".equals(what) || "focus".equals(what) ? "/board/settings#cards" : "/board/settings#" + what.replace("-delete", "");
        return Web.reply(req, op, back);
    }

    @PostMapping("/board/import")
    public Object importFile(@RequestParam(required = false) MultipartFile upload, HttpServletRequest req) throws IOException {
        if (upload == null || upload.isEmpty()) return Web.reply(req, Op.bad("현황판 백업 파일(JSON)을 고르세요."), null);
        return Web.reply(req, board.importJson(new String(upload.getBytes(), StandardCharsets.UTF_8).replace("﻿", "")), "/board");
    }

    @GetMapping("/board/data/export")
    public ResponseEntity<byte[]> export() {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, Web.disposition(false, "경영현황판-백업-" + Kst.today() + ".json"))
            .contentType(MediaType.APPLICATION_JSON).body(board.exportJson().getBytes(StandardCharsets.UTF_8));
    }
}
