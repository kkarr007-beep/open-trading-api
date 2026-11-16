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

    public Web(Chain chain, Settings settings, InboxService inbox,
               @org.springframework.beans.factory.annotation.Value("${audit.version:}") String version) {
        this.version = version;
        this.chain = chain;
        this.settings = settings;
        this.inbox = inbox;
    }

    @ModelAttribute
    public void common(HttpServletRequest req, Model model) {
        if (!"GET".equals(req.getMethod())) return;
        String path = req.getRequestURI();
        String nav = path.startsWith("/cases") ? "cases" : path.startsWith("/files") ? "files" : path.startsWith("/discipline") ? "discipline"
            : path.startsWith("/inbox") ? "inbox" : path.startsWith("/admin") ? "admin" : path.startsWith("/settings") ? "settings" : "todo";
        model.addAttribute("nav", nav);
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
        String listHref = path.matches("/cases/.+") ? "/cases" : path.matches("/discipline/.+") ? "/discipline" : null;
        model.addAttribute("listHref", listHref);
        model.addAttribute("listLabel", "/discipline".equals(listHref) ? "징계기록 목록" : "건 목록");
        model.addAttribute("showCasesLink", listHref == null && !path.equals("/cases"));
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
