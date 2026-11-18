package local.audit.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
import local.audit.FileStore;
import local.audit.LibraryService;
import local.audit.Op;
import local.audit.Txt;

/** 자료 화면. */
@Controller
public class LibraryController {
    private final LibraryService lib;

    public LibraryController(LibraryService lib) { this.lib = lib; }

    @GetMapping("/library")
    public String list(@RequestParam(required = false) String cat, @RequestParam(required = false) String q,
                       @RequestParam(required = false) Integer page, Model model) {
        List<Map<String, Object>> tree = lib.tree();
        String sec = lib.sectionOf(cat);
        model.addAttribute("tree", tree);
        model.addAttribute("sec", sec);
        model.addAttribute("secNode", tree.stream().filter(t -> sec.equals(t.get("Id"))).findFirst().orElse(null));
        model.addAttribute("catPath", lib.path(cat));
        model.addAttribute("total", lib.total());
        model.addAttribute("cat", Txt.str(cat));
        model.addAttribute("q", Txt.str(q));
        model.addAttribute("v", lib.list(cat, q, page == null ? 1 : page, 40));
        return "library";
    }

    @GetMapping("/library/new")
    public String fresh(@RequestParam(required = false) String cat, Model model) {
        model.addAttribute("tree", lib.tree());
        model.addAttribute("cat", Txt.str(cat));
        return "library-new";
    }

    @GetMapping("/library/categories")
    public String categories(Model model) {
        model.addAttribute("tree", lib.tree());
        return "library-categories";
    }

    /** 예전 '내용 보기' 주소: 목록으로(그 자료 줄로) 보낸다. */
    @GetMapping("/library/{id}")
    public String view(@PathVariable String id) {
        Map<String, Object> d = Txt.isId(id) ? lib.get(id) : null;
        return d == null ? "redirect:/library" : "redirect:/library?q=" + java.net.URLEncoder.encode(Txt.str(d.get("Title")), java.nio.charset.StandardCharsets.UTF_8) + "#d-" + id;
    }

    /** 원본 파일. ?dl=1이면 내려받기. */
    @GetMapping("/library/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable String id, @RequestParam(required = false) String dl) throws IOException {
        Map<String, Object> d = Txt.isId(id) ? lib.get(id) : null;
        Path f = d == null ? null : lib.file(id);
        if (f == null || !Files.isRegularFile(f)) return ResponseEntity.notFound().build();
        String name = Txt.str(d.get("FileName"));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, Web.disposition(dl == null, name))
            .contentType(MediaType.parseMediaType(FileStore.contentType(name))).body(Files.readAllBytes(f));
    }

    @PostMapping("/library/upload")
    public Object upload(@RequestParam(required = false) List<MultipartFile> upload, @RequestParam Map<String, String> p, HttpServletRequest req) throws IOException {
        if (upload == null || upload.stream().allMatch(MultipartFile::isEmpty)) return Web.reply(req, Op.bad("등재할 파일을 고르세요."), null);
        int ok = 0, dupCount = 0;
        String last = null, lastId = null, dupMsg = null, dupId = null;
        long chosen = upload.stream().filter(x -> !x.isEmpty()).count();
        for (MultipartFile f : upload) {
            if (f.isEmpty()) continue;
            try (InputStream in = f.getInputStream()) {
                Op op = lib.add(p.get("categoryId"), chosen == 1 ? p.get("title") : null, p.get("tags"), p.get("note"), f.getOriginalFilename(), in);
                if (op.status == 409) { dupCount++; dupMsg = op.message; dupId = op.extra; continue; }
                if (!op.ok) return Web.reply(req, op, null);
                ok++;
                last = op.message;
                lastId = op.id;
            }
        }
        if (ok == 0 && dupCount > 0) return Web.reply(req, Op.fail(409, dupCount == 1 ? dupMsg : "고른 파일 " + dupCount + "개가 모두 이미 등재되어 있습니다."), dupCount == 1 ? "/library/" + dupId : null);
        String msg = ok == 1 ? last : "자료 " + ok + "건을 등재했습니다. 내용은 뒤에서 읽습니다.";
        if (dupCount > 0) msg += " 이미 있는 파일 " + dupCount + "개는 건너뛰었습니다.";
        return Web.reply(req, Op.ok(msg), ok == 1 ? "/library/" + lastId : "/library?cat=" + Txt.str(p.get("categoryId")));
    }

    @PostMapping("/library/categories/{what}")
    public Object category(@PathVariable String what, @RequestParam Map<String, String> p, HttpServletRequest req) {
        return Web.reply(req, lib.category(what, p), null);
    }

    @PostMapping("/library/{id}/{what}")
    public Object action(@PathVariable String id, @PathVariable String what, @RequestParam Map<String, String> p, HttpServletRequest req) {
        if (!Txt.isId(id)) return Web.reply(req, Op.fail(404, "자료를 찾을 수 없습니다."), null);
        Op op = switch (what) {
            case "open" -> openLocally(id);
            case "meta" -> lib.meta(id, p);
            case "reread" -> lib.reread(id);
            case "delete" -> lib.delete(id);
            default -> Op.fail(404, "없는 작업입니다.");
        };
        return Web.reply(req, op, op.ok && "delete".equals(what) ? "/library" : null);
    }

    /** 원본을 이 컴퓨터의 프로그램(한글·워드·엑셀·PDF 보기 등)으로 연다. 보관본을 고치지 않게 임시 복사본을 읽기 전용으로 연다. */
    Op openLocally(String id) {
        Map<String, Object> d = lib.get(id);
        Path f = d == null ? null : lib.file(id);
        if (f == null || !Files.isRegularFile(f)) return Op.fail(404, "파일이 제자리에 없습니다.");
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "portal-open", id);
            Files.createDirectories(dir);
            Path copy = dir.resolve(FileStore.safeName(Txt.str(d.get("FileName"))));
            if (Files.exists(copy)) { copy.toFile().setWritable(true); Files.delete(copy); }
            Files.copy(f, copy);
            copy.toFile().setReadOnly();
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", copy.toString()).start();
            else if (os.contains("mac")) new ProcessBuilder("open", copy.toString()).start();
            else new ProcessBuilder("xdg-open", copy.toString()).start();
            return Op.ok("'" + d.get("Title") + "'를 이 컴퓨터의 프로그램으로 열었습니다.");
        } catch (IOException e) {
            return Op.fail(503, "열지 못했습니다. '내려받기'로 저장해 여세요.");
        }
    }
}
