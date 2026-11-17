package local.audit.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import local.audit.Kst;
import local.audit.Op;
import local.audit.Paths2;
import local.audit.Txt;
import local.audit.rule.RuleService;
import local.audit.work.WorkService;

/**
 * 예전 프로그램의 백업 파일을 한 화면에서 옮겨 온다. 파일마다 내용을 보고 업무 일정용인지 사규용인지 가린다.
 * 바꾸기 전의 데이터는 백업 폴더에 JSON으로 남겨 둔다.
 */
@Controller
public class ImportController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final WorkService work;
    private final RuleService rules;
    private final Paths2 paths;

    public ImportController(WorkService work, RuleService rules, Paths2 paths) {
        this.work = work;
        this.rules = rules;
        this.paths = paths;
    }

    @GetMapping("/import")
    public String page(Model model) {
        model.addAttribute("workCount", work.all().size());
        model.addAttribute("ruleCount", rules.regs().size());
        model.addAttribute("backupFolder", paths.backup.toString());
        return "import";
    }

    /** 무슨 백업인지: work 업무 일정 · rules 사규 · null 모름. */
    public static String kindOf(byte[] bytes) {
        try {
            JsonNode n = JSON.readTree(new String(bytes, StandardCharsets.UTF_8).replace("﻿", ""));
            if (n == null || !n.isObject()) return null;
            if (n.has("items") && n.get("items").isArray()) return "work";
            if (n.has("regulations") && n.get("regulations").isArray()) return "rules";
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /** 바꾸기 전 모습을 백업 폴더에 남긴다. 실패해도 옮겨 오기는 막지 않는다. */
    String keepBefore(String kind) {
        try {
            Files.createDirectories(paths.backup);
            String stamp = Kst.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path f = paths.backup.resolve(("work".equals(kind) ? "업무일정" : "사규") + "-옮겨오기전-" + stamp + ".json");
            Files.writeString(f, "work".equals(kind) ? work.exportJson() : rules.exportJson(), StandardCharsets.UTF_8);
            return f.getFileName().toString();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @PostMapping("/import")
    public Object run(@RequestParam(required = false) List<MultipartFile> upload, @RequestParam(required = false) String mode, HttpServletRequest req) throws IOException {
        if (upload == null || upload.stream().allMatch(MultipartFile::isEmpty)) return Web.reply(req, Op.bad("옮겨 올 백업 파일을 고르세요."), null);
        boolean replace = "replace".equals(mode);
        Map<String, Boolean> replacedOnce = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        int ok = 0;
        for (MultipartFile f : upload) {
            if (f.isEmpty()) continue;
            String name = Txt.str(f.getOriginalFilename());
            if (f.getSize() > 200L * 1024 * 1024) { lines.add(name + ": 파일이 너무 큽니다."); continue; }
            byte[] bytes = f.getBytes();
            String kind = kindOf(bytes);
            if (kind == null) { lines.add(name + ": 업무 일정·사규 백업 파일이 아닙니다."); continue; }
            // 같은 종류의 파일이 여럿이면 '전부 바꾸기'는 첫 파일에만, 나머지는 합친다.
            boolean merge = !replace || replacedOnce.containsKey(kind);
            if (!merge) {
                String kept = keepBefore(kind);
                if (kept != null) lines.add("바꾸기 전 " + ("work".equals(kind) ? "업무 일정" : "사규") + " 데이터를 백업 폴더에 남겼습니다: " + kept);
                replacedOnce.put(kind, true);
            }
            Op op = "work".equals(kind) ? work.change("백업 옮겨 오기", () -> work.importJson(bytes, merge)) : rules.importJson(bytes, merge);
            lines.add(name + " → " + ("work".equals(kind) ? "업무 일정" : "사규") + ": " + op.message);
            if (op.ok) ok++;
        }
        String msg = String.join("\n", lines);
        return Web.reply(req, ok > 0 ? Op.ok(msg) : Op.bad(msg), null);
    }
}
