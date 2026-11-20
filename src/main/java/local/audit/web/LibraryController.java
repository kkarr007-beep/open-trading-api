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

    private final local.audit.LinkService links;
    private final local.audit.OriginalService originals;
    public LibraryController(LibraryService lib, local.audit.LinkService links, local.audit.OriginalService originals) { this.lib = lib; this.links = links; this.originals = originals; }

    @GetMapping("/library")
    public String list(@RequestParam(required = false) String f, @RequestParam(required = false) String cat,
                       @RequestParam(required = false) String view, @RequestParam(required = false) String q,
                       @RequestParam(required = false) String scope, @RequestParam(required = false) String kind,
                       @RequestParam(required = false) String tag, @RequestParam(required = false) String sort,
                       @RequestParam(required = false) Integer page, Model model) {
        String folder = Txt.blank(f) ? Txt.str(cat) : f;
        LibraryService.Folder cur = lib.folder(folder);
        if (cur == null) folder = "";
        String v = "star".equals(view) || "recent".equals(view) ? view : "";
        String sc = folder.isEmpty() && v.isEmpty() ? "all" : "all".equals(scope) ? "all" : "here";
        model.addAttribute("f", folder);
        model.addAttribute("cur", cur);
        model.addAttribute("trail", folder.isEmpty() ? List.of() : lib.trail(folder));
        model.addAttribute("view", v);
        model.addAttribute("q", Txt.str(q));
        model.addAttribute("scope", sc);
        model.addAttribute("kind", Txt.str(kind));
        model.addAttribute("tag", Txt.str(tag));
        model.addAttribute("sort", Txt.str(sort));
        model.addAttribute("KINDS", LibraryService.KINDS);
        List<LibraryService.Folder> tops = lib.folders();
        model.addAttribute("allFolders", LibraryService.flat(tops));
        // 폴더 줄: 찾는 중이 아니면 지금 폴더의 하위 폴더(맨 위면 맨 위 폴더들)를 먼저 보인다
        boolean finding = !Txt.blank(q) || !Txt.blank(kind) || !Txt.blank(tag) || !v.isEmpty();
        model.addAttribute("subFolders", finding ? List.of() : cur == null ? tops : cur.children);
        Map<String, Object> res = lib.browse(folder, v, q, sc, kind, tag, sort, page == null ? 1 : page, 30);
        model.addAttribute("v", res);
        model.addAttribute("uploadTags", lib.allTags(12));
        Map<String, Integer> counts = links.counts("doc");
        Map<String, List<Map<String, Object>>> linked = new java.util.HashMap<>();
        @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) res.get("items");
        for (Map<String, Object> d : items) if (counts.containsKey(Txt.str(d.get("Id")))) linked.put(Txt.str(d.get("Id")), links.casesFor("doc", Txt.str(d.get("Id"))));
        model.addAttribute("linked", linked);
        model.addAttribute("caseChoices", links.caseChoices());
        // 올릴 곳: 지금 폴더, 없으면 보관이 아닌 첫 폴더
        String target = folder;
        if (target.isEmpty()) for (LibraryService.Folder x : tops) if (!x.archive) { target = x.id; break; }
        model.addAttribute("uploadTo", target);
        LibraryService.Folder ut = lib.folder(target);
        model.addAttribute("uploadToName", ut == null ? "" : ut.name);
        return "library";
    }

    /** 고른 자료 한꺼번에: 옮기기·지우기·즐겨찾기·태그. */
    @PostMapping("/library/bulk")
    public Object bulk(@RequestParam(name = "id", required = false) List<String> ids, @RequestParam(name = "op", required = false) String action,
                       @RequestParam(required = false) String categoryId, @RequestParam(required = false) String tags, HttpServletRequest req) {
        if (ids == null || ids.isEmpty()) return Web.reply(req, Op.bad("자료를 고르세요."), null);
        switch (Txt.str(action)) {
            case "move": return Web.reply(req, lib.move(ids, categoryId), null);
            case "star": return Web.reply(req, lib.star(ids, true), null);
            case "unstar": return Web.reply(req, lib.star(ids, false), null);
            case "tag": return Web.reply(req, lib.addTags(ids, tags), null);
            case "delete": {
                int n = 0;
                for (String id : ids) if (Txt.isId(id) && lib.delete(id).ok) { n++; originals.delete("doc-" + id); }
                return Web.reply(req, Op.ok("자료 " + n + "건을 지웠습니다."), null);
            }
            default: return Web.reply(req, Op.bad("할 일을 고르세요."), null);
        }
    }

    /** 폴더: 만들기·이름 바꾸기·옮기기·보관·지우기. */
    @PostMapping("/library/folder/new")
    public Object folderNew(@RequestParam(required = false) String name, @RequestParam(required = false) String parentId, HttpServletRequest req) {
        return Web.reply(req, lib.createFolder(name, parentId), null);
    }

    @PostMapping("/library/folder/{id}/{what}")
    public Object folder(@PathVariable String id, @PathVariable String what, @RequestParam Map<String, String> p, HttpServletRequest req) {
        Op op = switch (what) {
            case "rename" -> lib.renameFolder(id, p.get("name"));
            case "move" -> lib.moveFolder(id, p.get("parentId"));
            case "archive" -> lib.archiveFolder(id);
            case "delete" -> lib.deleteFolder(id);
            default -> Op.fail(404, "없는 작업입니다.");
        };
        // 지금 보고 있던 폴더를 지웠으면 위 폴더로 간다
        String back = Txt.str(p.get("current"));
        String go = op.ok && "delete".equals(what) && id.equals(back) ? "/library" + (Txt.blank(op.id) ? "" : "?f=" + op.id) : null;
        return Web.reply(req, op, go);
    }

    @PostMapping("/library/saved")
    public Object saveSearch(@RequestParam(required = false) String name, @RequestParam(required = false) String params, HttpServletRequest req) {
        return Web.reply(req, lib.saveSearch(name, params), null);
    }

    @PostMapping("/library/saved/{id}/delete")
    public Object deleteSaved(@PathVariable String id, HttpServletRequest req) {
        return Web.reply(req, lib.deleteSaved(id), null);
    }

    /** 예전 '자료 등재' 주소: 자료 화면에서 바로 올린다. */
    @GetMapping("/library/new")
    public String fresh(@RequestParam(required = false) String cat) { return "redirect:/library?upload=1" + (Txt.blank(cat) ? "" : "&f=" + cat); }

    /** 예전 '분류 관리' 주소: 이제 폴더는 자료 화면 왼쪽에서 바로 관리한다. */
    @GetMapping("/library/categories")
    public String categories() { return "redirect:/library"; }

    /** 예전 '내용 보기' 주소: 목록으로(그 자료 줄로) 보낸다. */
    @GetMapping("/library/{id}")
    public String view(@PathVariable String id) {
        Map<String, Object> d = Txt.isId(id) ? lib.get(id) : null;
        return d == null ? "redirect:/library" : "redirect:/library?f=" + Txt.str(d.get("CategoryId")) + "#d-" + id;
    }

    /** 원본 파일. ?dl=1이면 내려받기. */
    @GetMapping("/library/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable String id, @RequestParam(required = false) String dl) throws IOException {
        Map<String, Object> d = Txt.isId(id) ? lib.get(id) : null;
        Path f = d == null ? null : lib.file(id);
        if (f == null || !Files.isRegularFile(f)) return ResponseEntity.notFound().build();
        lib.touch(id);
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
        String back = Txt.str(p.get("back"));
        return Web.reply(req, Op.ok(msg), back.startsWith("/library") ? back : "/library?f=" + Txt.str(p.get("categoryId")));
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
            case "delete" -> { Op d = lib.delete(id); if (d.ok) originals.delete("doc-" + id); yield d; }
            default -> Op.fail(404, "없는 작업입니다.");
        };
        return Web.reply(req, op, null);
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
            lib.touch(id);
            return Op.ok("'" + d.get("Title") + "'를 이 컴퓨터의 프로그램으로 열었습니다.");
        } catch (IOException e) {
            return Op.fail(503, "열지 못했습니다. '내려받기'로 저장해 여세요.");
        }
    }
}
