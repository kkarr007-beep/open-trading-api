package local.audit.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import local.audit.CaseService;
import local.audit.Chain;
import local.audit.DisciplineService;
import local.audit.InboxService;
import local.audit.Kst;
import local.audit.LegacyImport;
import local.audit.Op;
import local.audit.Paths2;
import local.audit.RetentionService;
import local.audit.Settings;
import local.audit.Txt;
import local.audit.extract.OfficeBridge;

@Controller
public class OtherControllers {
    private final CaseService cases;
    private final DisciplineService discipline;
    private final InboxService inbox;
    private final RetentionService retention;
    private final Settings settings;
    private final Chain chain;
    private final Paths2 paths;
    private final OfficeBridge office;
    private final LegacyImport legacy;
    private final org.springframework.context.ConfigurableApplicationContext context;

    public OtherControllers(CaseService cases, DisciplineService discipline, InboxService inbox, RetentionService retention,
                            Settings settings, Chain chain, Paths2 paths, OfficeBridge office, LegacyImport legacy,
                            org.springframework.context.ConfigurableApplicationContext context) {
        this.context = context;
        this.cases = cases;
        this.discipline = discipline;
        this.inbox = inbox;
        this.retention = retention;
        this.settings = settings;
        this.chain = chain;
        this.paths = paths;
        this.office = office;
        this.legacy = legacy;
    }

    // ───────────── 징계기록 ─────────────

    @GetMapping("/discipline")
    public String disciplineList(@RequestParam Map<String, String> q, Model model) {
        model.addAttribute("q", q);
        model.addAttribute("rows", discipline.list(q.get("text"), q.get("sanction"), Txt.intOrNull(q.get("year"))));
        model.addAttribute("years", discipline.years());
        model.addAttribute("sanctions", DisciplineService.SANCTIONS);
        return "discipline";
    }

    @GetMapping("/discipline/export")
    public ResponseEntity<byte[]> disciplineExport() {
        return Web.csv("징계기록-" + Kst.today() + ".csv", discipline.exportCsv());
    }

    @GetMapping("/discipline/{id}")
    public String disciplineEdit(@PathVariable String id, @RequestParam(required = false) String caseRef,
                                 @RequestParam(required = false) String ruleText, Model model) {
        model.addAttribute("ruleTextInit", ruleText == null ? null : local.audit.Txt.fit(ruleText, 240, false));
        boolean fresh = "new".equals(id);
        model.addAttribute("fresh", fresh);
        model.addAttribute("item", fresh ? null : discipline.open(id));
        model.addAttribute("id", id);
        model.addAttribute("caseRefs", cases.caseRefs());
        model.addAttribute("sanctions", DisciplineService.SANCTIONS);
        model.addAttribute("year", Kst.today().getYear());
        model.addAttribute("caseRefInit", caseRef);
        return "discipline-edit";
    }

    @PostMapping("/discipline/{id}")
    public ResponseEntity<?> disciplineSave(@PathVariable String id, @RequestParam Map<String, String> p, HttpServletRequest req) {
        p.put("id", id);
        Op op = discipline.save(p);
        return Web.reply(req, op, op.ok && "new".equals(id) ? "/discipline/" + op.id : null);
    }

    @PostMapping("/discipline/{id}/hold")
    public ResponseEntity<?> disciplineHold(@PathVariable String id, @RequestParam Map<String, String> p, HttpServletRequest req) {
        return Web.reply(req, discipline.setHold(id, !"1".equals(p.get("release")), p.get("reason")), null);
    }

    @PostMapping("/discipline/{id}/retention")
    public ResponseEntity<?> disciplineRetention(@PathVariable String id, @RequestParam Map<String, String> p, HttpServletRequest req) {
        return Web.reply(req, discipline.setRetention(id, Txt.intOrNull(p.get("years"))), null);
    }

    // ───────────── 수집함 ─────────────

    @GetMapping("/inbox")
    public String inboxPage(Model model) {
        model.addAttribute("pending", inbox.list("대기"));
        model.addAttribute("done", inbox.list("처리"));
        model.addAttribute("folders", inbox.folders());
        model.addAttribute("caseRefs", cases.caseRefs());
        model.addAttribute("autoMode", settings.autoMode());
        model.addAttribute("autoScan", settings.autoScan());
        model.addAttribute("officeOn", settings.office() && office.available());
        model.addAttribute("kinds", CaseService.KINDS);
        return "inbox";
    }

    @PostMapping("/inbox/scan")
    public ResponseEntity<?> inboxScan(HttpServletRequest req) {
        return Web.reply(req, inbox.scan(true), null);
    }

    @PostMapping("/inbox/upload")
    public ResponseEntity<?> inboxUpload(@RequestParam(required = false) List<MultipartFile> upload, HttpServletRequest req) throws IOException {
        if (upload == null || upload.stream().allMatch(MultipartFile::isEmpty)) return Web.reply(req, Op.bad("넣을 파일을 고르세요."), null);
        Op last = Op.ok("");
        for (MultipartFile f : upload) {
            if (f.isEmpty()) continue;
            try (InputStream in = f.getInputStream()) {
                last = inbox.upload(f.getOriginalFilename(), in);
            }
        }
        return Web.reply(req, last, null);
    }

    @PostMapping("/inbox/{id}/{what}")
    public ResponseEntity<?> inboxWrite(@PathVariable String id, @PathVariable String what, @RequestParam Map<String, String> p, HttpServletRequest req) {
        Op op = switch (what) {
            case "apply" -> inbox.apply(id, p, false);
            case "exclude" -> inbox.exclude(id);
            case "reread" -> inbox.reread(id);
            default -> Op.fail(404, "없는 작업입니다.");
        };
        return Web.reply(req, op, null);
    }

    @GetMapping("/inbox/{id}/view")
    public ResponseEntity<byte[]> inboxView(@PathVariable String id, @RequestParam(required = false) String dl) {
        if (dl != null) return inboxText(id, dl);
        return Web.html(local.audit.extract.Rich.page("읽은 내용", inbox.html(id)));
    }

    @GetMapping("/discipline/template")
    public ResponseEntity<byte[]> disciplineTemplate() {
        return Web.csv("징계기록-양식.csv", DisciplineService.templateCsv());
    }

    /** CSV나 엑셀(xlsx) 파일로 징계기록을 한꺼번에 등재한다. */
    @PostMapping("/discipline/import")
    public ResponseEntity<?> disciplineImport(@RequestParam(required = false) MultipartFile upload, HttpServletRequest req) throws IOException {
        if (upload == null || upload.isEmpty()) return Web.reply(req, Op.bad("CSV 또는 엑셀(xlsx) 파일을 고르세요."), null);
        String name = String.valueOf(upload.getOriginalFilename()).toLowerCase();
        List<List<String>> rows;
        try {
            if (name.endsWith(".xlsx")) {
                java.nio.file.Path tmp = java.nio.file.Files.createTempFile(paths.tmp, "import", ".xlsx");
                try {
                    upload.transferTo(tmp);
                    rows = new java.util.ArrayList<>();
                    for (String line : local.audit.extract.TextReader.xlsx(tmp).split("\n")) rows.add(java.util.Arrays.asList(line.split("\t", -1)));
                } finally {
                    java.nio.file.Files.deleteIfExists(tmp);
                }
            } else if (name.endsWith(".csv") || name.endsWith(".txt") || name.endsWith(".tsv")) {
                rows = DisciplineService.parseCsv(local.audit.extract.TextReader.decode(upload.getBytes()));
            } else {
                return Web.reply(req, Op.bad("CSV 또는 엑셀(xlsx) 파일만 받습니다. 암호화된 엑셀은 수집함에 넣으세요."), null);
            }
        } catch (Exception e) {
            return Web.reply(req, Op.bad("파일을 읽지 못했습니다. 암호화된 파일이면 수집함에 넣으세요."), null);
        }
        return Web.reply(req, discipline.importRows(rows), null);
    }

    @GetMapping("/inbox/{id}/text")
    public ResponseEntity<byte[]> inboxText(@PathVariable String id, @RequestParam(required = false) String dl) {
        return Web.plain("읽은내용.txt", inbox.text(id), dl != null);
    }

    // ───────────── 관리·설정 ─────────────

    @GetMapping("/admin")
    public String admin(Model model) {
        model.addAttribute("backups", retention.recentBackups());
        model.addAttribute("backupFolder", settings.get("backupFolder", paths.backup.toString()));
        model.addAttribute("codes", cases.categoryCodes());
        model.addAttribute("autoBackup", settings.autoBackup());
        model.addAttribute("home", paths.home.toString());
        model.addAttribute("holds", cases.caseRefs());
        return "admin";
    }

    /** 이미 켜져 있는지 알아볼 때 쓴다. */
    @GetMapping("/ping")
    @org.springframework.web.bind.annotation.ResponseBody
    public String ping(@RequestParam(required = false) String show) {
        return show != null && local.audit.App.bringToFront() ? "audit-record shown" : "audit-record";
    }

    @PostMapping("/admin/quit")
    public ResponseEntity<?> quit(HttpServletRequest req) {
        local.audit.App.quit(context);
        return Web.reply(req, Op.ok("프로그램을 끝냅니다. 이 창은 닫아도 됩니다."), null);
    }

    @PostMapping("/admin/{what}")
    public ResponseEntity<?> adminWrite(@PathVariable String what, @RequestParam Map<String, String> p, HttpServletRequest req) {
        Op op = switch (what) {
            case "backup" -> retention.backup(p.get("folder"));
            case "purge" -> retention.purgeDue();
            case "verify" -> chain.verify() ? Op.ok("이력 지문이 모두 맞습니다.") : Op.fail(409, "이력이 맞지 않습니다. 데이터 파일이 직접 고쳐졌을 수 있습니다.");
            case "reseal" -> chain.reseal(settings.owner(), p.get("reason"));
            case "code" -> cases.setCategoryCode(p.get("category"), p.get("code"));
            case "import" -> legacy.run(p.get("folder"));
            default -> Op.fail(404, "없는 작업입니다.");
        };
        return Web.reply(req, op, null);
    }

    @GetMapping("/settings")
    public String settingsPage(Model model) {
        model.addAttribute("s", Map.of(
            "ownerName", settings.owner(), "watchFolders", String.join("\n", settings.watchFolders()), "autoMode", settings.autoMode(),
            "keepText", settings.keepText(), "autoPurge", settings.autoPurge(), "office", settings.office(), "moveProcessed", settings.moveProcessed(),
            "defaultRetention", settings.get("defaultRetention", ""), "replyDays", settings.replyDays()));
        model.addAttribute("autoBackup", settings.autoBackup());
        model.addAttribute("autoScan", settings.autoScan());
        model.addAttribute("home", paths.home.toString());
        model.addAttribute("inboxFolder", paths.inbox.toString());
        model.addAttribute("officeAvailable", office.available());
        return "settings";
    }

    @PostMapping("/settings")
    public ResponseEntity<?> settingsSave(@RequestParam Map<String, String> p, HttpServletRequest req) {
        String owner = Txt.clean(p.get("ownerName"), 40, false);
        String folders = Txt.clean(p.get("watchFolders"), 4000, true);
        Integer ret = Txt.intOrNull(p.get("defaultRetention")), days = Txt.intOrNull(p.get("replyDays"));
        if (Txt.blank(owner) || folders == null || (ret != null && (ret < 1 || ret > 100)) || days == null || days < 1 || days > 365
            || !java.util.Arrays.asList("off", "attach", "full").contains(p.get("autoMode")))
            return Web.reply(req, Op.bad(CaseService.BAD), null);
        for (String line : folders.split("\n")) {
            if (line.isBlank()) continue;
            try {
                if (!java.nio.file.Files.isDirectory(java.nio.file.Path.of(line.strip())))
                    return Web.reply(req, Op.bad("폴더를 찾을 수 없습니다: " + line.strip()), null);
            } catch (RuntimeException e) {
                return Web.reply(req, Op.bad("폴더 경로를 확인하세요: " + line.strip()), null);
            }
        }
        settings.set("ownerName", owner);
        settings.set("watchFolders", folders);
        settings.set("autoMode", p.get("autoMode"));
        settings.set("defaultRetention", ret == null ? "" : ret.toString());
        settings.set("replyDays", days.toString());
        for (String key : List.of("keepText", "autoPurge", "office", "moveProcessed", "autoBackup", "autoScan")) settings.set(key, "1".equals(p.get(key)) ? "1" : "0");
        return Web.reply(req, Op.ok("설정을 저장했습니다."), null);
    }
}
