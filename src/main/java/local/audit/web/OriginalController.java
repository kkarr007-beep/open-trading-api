package local.audit.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.fasterxml.jackson.databind.ObjectMapper;

import local.audit.FileStore;
import local.audit.LibraryService;
import local.audit.Op;
import local.audit.OriginalService;
import local.audit.Txt;
import local.audit.rule.RuleService;
import local.audit.rule.Rules;

/** 원본 보기: 등록 당시 원본 모양(쪽 그림)과 글자 층. 새 창으로 연다. */
@Controller
public class OriginalController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final OriginalService originals;
    private final LibraryService lib;
    private final RuleService rules;

    public OriginalController(OriginalService originals, LibraryService lib, RuleService rules) {
        this.originals = originals;
        this.lib = lib;
        this.rules = rules;
    }

    /** 원본이 어디서 왔는지: 자료는 보관 파일을 쓰고, 사규는 등록 때 붙인 파일을 쓴다. */
    private String title(String key) {
        if (key.startsWith("doc-")) {
            Map<String, Object> d = lib.get(key.substring(4));
            return d == null ? null : Txt.str(d.get("Title"));
        }
        Map<String, Object> m = rules.read(key.substring(5), null, null, false);
        if (m == null) return null;
        @SuppressWarnings("unchecked") Map<String, Object> reg = (Map<String, Object>) m.get("reg");
        return Rules.str(reg, "title");
    }

    /** 자료는 처음 열 때 만든다. */
    private void ensure(String key) {
        if (originals.info(key) != null || !key.startsWith("doc-")) return;
        String id = key.substring(4);
        Map<String, Object> d = lib.get(id);
        Path f = d == null ? null : lib.file(id);
        String name = d == null ? "" : Txt.str(d.get("FileName"));
        if (f != null && Files.isRegularFile(f) && OriginalService.supports(name)) originals.request(key, f, name);
    }

    @GetMapping("/original/{key}")
    public String view(@PathVariable String key, Model model) {
        String title = OriginalService.validKey(key) ? title(key) : null;
        model.addAttribute("key", key);
        model.addAttribute("title", title == null ? "원본 보기" : title);
        model.addAttribute("missing", title == null);
        if (title != null) ensure(key);
        model.addAttribute("info", title == null ? null : originals.info(key));
        model.addAttribute("isRule", key.startsWith("rule-"));
        return "original";
    }

    @GetMapping("/original/{key}/state")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> state(@PathVariable String key) throws IOException {
        Map<String, Object> r = originals.info(key);
        Map<String, Object> out = new LinkedHashMap<>();
        if (r == null) { out.put("state", "없음"); return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out); }
        out.put("state", r.get("State"));
        out.put("pages", r.get("Pages"));
        out.put("done", r.get("Done"));
        out.put("note", r.get("Note"));
        out.put("via", r.get("Via"));
        out.put("name", r.get("Name"));
        out.put("ext", r.get("Ext"));
        out.put("made", r.get("CreatedAt"));
        out.put("meta", JSON.readValue(Txt.str(r.get("Meta")).isEmpty() ? "[]" : Txt.str(r.get("Meta")), Object.class));
        out.put("pdf", originals.pdf(key) != null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
    }

    @GetMapping("/original/{key}/text.json")
    public ResponseEntity<String> text(@PathVariable String key) throws IOException {
        if (!OriginalService.validKey(key)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(originals.textJson(key));
    }

    @GetMapping("/original/{key}/p/{n}.png")
    public ResponseEntity<byte[]> page(@PathVariable String key, @PathVariable int n) throws IOException {
        Path p = originals.page(key, n);
        if (p == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noCache()).body(Files.readAllBytes(p));
    }

    @GetMapping("/original/{key}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String key) throws IOException {
        Path p = originals.pdf(key);
        Map<String, Object> r = originals.info(key);
        if (p == null || r == null) return ResponseEntity.notFound().build();
        String name = Txt.stem(Txt.str(r.get("Name"))) + ".pdf";
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, Web.disposition(false, name))
            .contentType(MediaType.APPLICATION_PDF).body(Files.readAllBytes(p));
    }

    @GetMapping("/original/{key}/source")
    public ResponseEntity<byte[]> source(@PathVariable String key) throws IOException {
        Path p = originals.source(key);
        Map<String, Object> r = originals.info(key);
        if (p == null || r == null) return ResponseEntity.notFound().build();
        String name = Txt.str(r.get("Name"));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, Web.disposition(false, name))
            .contentType(MediaType.parseMediaType(FileStore.contentType(name))).body(Files.readAllBytes(p));
    }

    /** 다시 만들기(실패했거나 사무용 프로그램을 새로 설치한 뒤). */
    @PostMapping("/original/{key}/make")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> make(@PathVariable String key, @RequestParam(required = false) String x) {
        Op op;
        if (!OriginalService.validKey(key) || title(key) == null) op = Op.fail(404, "원본을 찾을 수 없습니다.");
        else if (key.startsWith("doc-")) { originals.delete(key); ensure(key); op = originals.info(key) == null ? Op.bad("이 형식은 원본 보기를 만들 수 없습니다.") : Op.ok("다시 만들고 있습니다."); }
        else op = originals.remake(key);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", op.ok);
        out.put("message", op.message);
        return ResponseEntity.ok(out);
    }
}
