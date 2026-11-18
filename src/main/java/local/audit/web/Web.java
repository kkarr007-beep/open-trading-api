package local.audit.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import jakarta.servlet.http.HttpServletRequest;
import local.audit.Chain;
import local.audit.InboxService;
import local.audit.Kst;
import local.audit.Op;
import local.audit.Settings;
import local.audit.Txt;

/** 모든 화면이 함께 쓰는 값과 응답 모양. */
@ControllerAdvice
public class Web {
    private final Chain chain;
    private final Settings settings;
    private final InboxService inbox;
    private final String version;
    private final local.audit.work.WorkService work;
    private final local.audit.CaseService cases;
    private final local.audit.LibraryService library;

    public Web(Chain chain, Settings settings, InboxService inbox, local.audit.work.WorkService work, local.audit.CaseService cases,
               local.audit.LibraryService library,
               @org.springframework.beans.factory.annotation.Value("${audit.version:}") String version) {
        this.version = version;
        this.work = work;
        this.cases = cases;
        this.library = library;
        this.chain = chain;
        this.settings = settings;
        this.inbox = inbox;
    }

    @ModelAttribute
    public void common(HttpServletRequest req, Model model) {
        if (!"GET".equals(req.getMethod())) return;
        String path = req.getRequestURI();
        String nav = path.startsWith("/cases") ? "cases" : path.startsWith("/files") ? "files" : path.startsWith("/discipline") ? "discipline"
            : path.startsWith("/inbox") ? "inbox" : path.startsWith("/admin") ? "admin" : path.startsWith("/settings") ? "settings"
            : path.startsWith("/work") || path.startsWith("/rules") || path.equals("/import") || path.startsWith("/library") || path.startsWith("/board") || path.startsWith("/tools") ? "module" : path.startsWith("/search") ? "search" : "todo";
        model.addAttribute("nav", nav);
        // 업무 일정·사규 메뉴의 현재 위치: work, work/list, rules, rules/search …
        String navKey = "";
        java.util.regex.Matcher km = java.util.regex.Pattern.compile("^/(work|rules)(?:/(list|board|calendar|timeline|annual|team|directives|notes|meetings|insight|report|settings|search|marks|compare|new|solution|index|glossary|scan|checklists))?").matcher(path);
        if (km.find()) navKey = km.group(1) + (km.group(2) == null ? "" : "/" + km.group(2));
        if (path.startsWith("/work/item/")) navKey = "work/list";
        if (path.startsWith("/work/report")) navKey = "work/insight";
        if (path.equals("/import")) navKey = "import";
        if (path.startsWith("/library")) navKey = path.equals("/library/new") ? "library/new" : path.equals("/library/categories") ? "library/categories" : "library";
        if (path.startsWith("/board")) navKey = path.startsWith("/board/entry") ? "board/entry" : path.startsWith("/board/issues") ? "board/issues"
            : path.startsWith("/board/report") ? "board/report" : path.startsWith("/board/settings") ? "board/settings" : "board";
        if (path.startsWith("/tools")) navKey = path.startsWith("/tools/card/settings") ? "tools/card/settings" : path.startsWith("/tools/card/exceptions") ? "tools/card/exceptions" : "tools/card";
        model.addAttribute("navKey", navKey);
        model.addAttribute("caseCategories", cases.categories());
        model.addAttribute("libTree", library.tree());
        model.addAttribute("libCat", path.equals("/library") ? Txt.str(req.getParameter("cat")) : "");
        String navGroup = path.equals("/work/settings") || path.equals("/rules/settings") || path.startsWith("/admin") || path.startsWith("/settings") || path.equals("/import") ? "program"
            : path.startsWith("/work") ? "work" : path.startsWith("/rules") ? "rules" : path.startsWith("/library") ? "library" : path.startsWith("/board") ? "board" : path.startsWith("/tools") ? "tools"
            : path.startsWith("/todo") || path.startsWith("/inbox") || path.startsWith("/cases") || path.startsWith("/files") || path.startsWith("/discipline") ? "audit" : "";
        model.addAttribute("navGroup", navGroup);
        model.addAttribute("workUrgent", work.urgentCount());
        model.addAttribute("workDirs", work.openDirectives());
        model.addAttribute("path", path);
        model.addAttribute("integrityOk", chain.intact());
        String owner = settings.owner();
        model.addAttribute("ownerName", owner);
        model.addAttribute("ownerInitial", owner.isBlank() ? "?" : owner.strip().substring(0, 1));
        model.addAttribute("inboxPending", inbox.pendingCount());
        model.addAttribute("autoPurge", settings.autoPurge());
        model.addAttribute("nowDisplay", Kst.display(Kst.stamp()));
        model.addAttribute("flash", req.getParameter("flash"));
        model.addAttribute("searchQuery", "");
        model.addAttribute("appVersion", version);
        // 목록으로 돌아가는 단추
        String listHref = path.matches("/cases/.+") ? "/cases" : path.matches("/discipline/.+") ? "/discipline"
            : path.startsWith("/work/item/") ? "/work/list" : path.matches("/rules/(?!search|marks|compare|settings|new|import|export|solution|timeline|index|glossary|scan|checklists|data)[0-9a-zA-Z_]+") ? "/rules" : null;
        model.addAttribute("listHref", listHref);
        model.addAttribute("listLabel", "/discipline".equals(listHref) ? "징계기록 목록" : "/work/list".equals(listHref) ? "업무 목록"
            : "/rules".equals(listHref) ? "사규 목록" : "건 목록");
        model.addAttribute("showCasesLink", listHref == null && !path.equals("/cases") && "todo,cases,files,discipline,inbox".contains(nav));
    }

    /** 화면 스크립트가 보낸 저장 요청에는 JSON으로, 그 밖에는 화면 이동으로 답한다. */
    public static ResponseEntity<?> reply(HttpServletRequest req, Op op, String go) {
        if ("1".equals(req.getHeader("X-Audit-Fetch"))) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", op.ok);
            body.put("status", op.status);
            body.put("message", op.message);
            if (op.ok && go != null) body.put("go", go);
            return ResponseEntity.status(op.ok ? 200 : op.status).contentType(MediaType.APPLICATION_JSON).body(body);
        }
        String back = op.ok && go != null ? go : req.getHeader("Referer");
        if (back == null || !back.startsWith("/") && !back.startsWith("http://127.0.0.1") && !back.startsWith("http://localhost")) back = "/todo";
        back = back.replaceFirst("[?&]flash=[^&#]*", "");
        String target = back + (back.contains("?") ? "&" : "?") + "flash=" + URLEncoder.encode(op.message, StandardCharsets.UTF_8);
        return ResponseEntity.status(303).header(HttpHeaders.LOCATION, target).build();
    }

    public static String disposition(boolean inline, String name) {
        String ascii = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (ascii.replace("_", "").replace(".", "").isEmpty()) ascii = "file" + Txt.ext(name);
        return (inline ? "inline" : "attachment") + "; filename=\"" + ascii + "\"; filename*=UTF-8''"
            + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static ResponseEntity<byte[]> csv(String name, String body) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, disposition(false, name))
            .contentType(MediaType.parseMediaType("text/csv; charset=utf-8")).body(body.getBytes(StandardCharsets.UTF_8));
    }

    public static ResponseEntity<byte[]> html(String page) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html; charset=utf-8")).body(page.getBytes(StandardCharsets.UTF_8));
    }

    public static ResponseEntity<byte[]> plain(String name, String text, boolean download) {
        if (text == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, disposition(!download, name))
            .contentType(MediaType.parseMediaType("text/plain; charset=utf-8")).body(text.getBytes(StandardCharsets.UTF_8));
    }
}
