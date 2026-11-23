package local.audit.work;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import local.audit.Db;
import local.audit.Kst;
import local.audit.LibraryService;
import local.audit.Op;
import local.audit.Paths2;
import local.audit.Txt;
import local.audit.extract.TextReader;

/**
 * 월간 업무보고: 보고서(지난달 현황 표·다음달 일정 표)를 읽어 미리보기 → 등록.
 * 등록하면 다음달 일정은 그 달 업무가 되어 달력·일·주·월 계획에 같이 나오고, 지난달 현황은 같은 업무의 진행 기록과 월별 기록으로 남는다.
 * 올린 보고서는 자료 › 월간 업무보고 › 연도 폴더에 넣어 원본 보기로 바로 연다.
 */
@Service
public class MonthlyService {
    static final ObjectMapper JSON = new ObjectMapper();
    public static final String FOLDER = "월간 업무보고";

    private final Db db;
    private final Paths2 paths;
    private final TextReader reader;
    private final LibraryService lib;
    private final WorkService work;

    public MonthlyService(Db db, Paths2 paths, TextReader reader, LibraryService lib, WorkService work) {
        this.db = db;
        this.paths = paths;
        this.reader = reader;
        this.lib = lib;
        this.work = work;
        db.tx(conn -> {
            try (var st = conn.createStatement()) {
                st.execute("""
                    CREATE TABLE IF NOT EXISTS MonthlyReports (Month TEXT PRIMARY KEY, DocId TEXT NOT NULL DEFAULT '', DocTitle TEXT NOT NULL DEFAULT '',
                      PlanJson TEXT NOT NULL DEFAULT '[]', StatusJson TEXT NOT NULL DEFAULT '[]', UpdatedAt TEXT NOT NULL)""");
            }
            return null;
        });
    }

    public record Preview(String token, String fileName, int year, int month, List<MonthlyReport.Row> plan, List<MonthlyReport.Row> status, String note) {
        public String ym() { return String.format("%04d-%02d", year, month); }
        public String prevYm() { return YearMonth.of(year, month).minusMonths(1).toString(); }
    }

    private Path dir() throws IOException { return Files.createDirectories(paths.tmp.resolve("monthly")); }

    /** 올린 보고서를 읽어 미리보기를 만든다. 파일은 등록할 때까지 잠시 맡겨 둔다(하루 지난 것은 지운다). */
    public Op read(String fileName, InputStream in) throws IOException {
        String name = Txt.str(fileName);
        String ext = Txt.ext(name);
        if (!List.of(".hwp", ".hwpx", ".docx", ".doc", ".pdf").contains(ext)) return Op.bad("한글·워드·PDF 보고서를 올리세요.");
        Path d = dir();
        try (var old = Files.list(d)) {
            long cut = System.currentTimeMillis() - 86_400_000L;
            old.filter(p -> { try { return Files.getLastModifiedTime(p).toMillis() < cut; } catch (IOException e) { return false; } })
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
        }
        String token = Txt.newId();
        Path file = d.resolve(token + ext);
        Files.copy(in, file);
        TextReader.Result res = reader.read(file, name);
        if (!res.ok() || Txt.blank(res.text())) { Files.deleteIfExists(file); return Op.bad("보고서에서 글을 읽지 못했습니다. " + Txt.str(res.note())); }
        MonthlyReport.Parsed p = MonthlyReport.parse(res.html(), res.text(), Kst.today());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fileName", name); m.put("year", p.year()); m.put("month", p.month());
        m.put("plan", p.plan()); m.put("status", p.status()); m.put("note", p.note());
        Files.writeString(d.resolve(token + ".json"), JSON.writeValueAsString(m), StandardCharsets.UTF_8);
        return Op.ok(p.plan().size() + p.status().size() == 0 ? "표를 찾지 못했습니다." : "보고서를 읽었습니다. 확인하고 등록하세요.", token);
    }

    public Preview preview(String token) {
        if (!Txt.isId(token)) return null;
        try {
            Path j = dir().resolve(token + ".json");
            if (!Files.isRegularFile(j)) return null;
            Map<String, Object> m = JSON.readValue(Files.readString(j, StandardCharsets.UTF_8), new TypeReference<Map<String, Object>>() { });
            return new Preview(token, Txt.str(m.get("fileName")), ((Number) m.get("year")).intValue(), ((Number) m.get("month")).intValue(),
                JSON.convertValue(m.get("plan"), new TypeReference<List<MonthlyReport.Row>>() { }),
                JSON.convertValue(m.get("status"), new TypeReference<List<MonthlyReport.Row>>() { }), Txt.str(m.get("note")));
        } catch (Exception e) {
            return null;
        }
    }

    /** 미리보기에서 고친 줄로 등록한다. */
    public Op save(String token, Map<String, String> p) {
        Preview pv = preview(token);
        if (pv == null) return Op.fail(404, "읽은 보고서를 찾지 못했습니다(하루가 지났을 수 있습니다). 다시 올리세요.");
        YearMonth ym;
        try { ym = YearMonth.parse(Txt.str(p.get("month"))); } catch (Exception e) { return Op.bad("일정 달을 확인하세요(예: 2026-12)."); }
        String month = ym.toString(), prev = ym.minusMonths(1).toString();
        List<MonthlyReport.Row> plan = rows(p, "p", ym), status = rows(p, "s", ym.minusMonths(1));
        if (plan.isEmpty() && status.isEmpty()) return Op.bad("등록할 줄이 없습니다. 줄 앞의 칸을 골라 주세요.");
        // 1) 원본을 자료에 넣는다
        String docId = "", docTitle = ym.getYear() + "년 " + ym.getMonthValue() + "월 업무보고(" + ym.minusMonths(1).getMonthValue() + "월 현황·" + ym.getMonthValue() + "월 일정)";
        try {
            String folder = folderFor(ym.getYear());
            Path file;
            try (var fs = Files.list(dir())) { file = fs.filter(x -> x.getFileName().toString().startsWith(token + ".") && !x.toString().endsWith(".json")).findFirst().orElse(null); }
            if (file != null) {
                try (InputStream in = Files.newInputStream(file)) {
                    Op a = lib.add(folder, docTitle, "월간보고, " + ym.getYear(), "월간 업무보고에서 자동 등재", pv.fileName(), in);
                    docId = a.ok ? a.id : a.status == 409 ? Txt.str(a.extra) : "";
                }
            }
        } catch (IOException e) {
            docId = "";
        }
        // 2) 일정 → 업무, 현황 → 진행 기록
        int made = 0, upd = 0, noted = 0;
        for (MonthlyReport.Row r : plan) {
            String res = work.upsertMonthly(month, r.cat(), r.item(), r.detail(), r.start(), r.due());
            if (res == null) continue;
            if (res.startsWith("new:")) made++; else upd++;
        }
        for (MonthlyReport.Row r : status) if (work.noteMonthlyStatus(prev, r.cat(), r.item(), r.detail())) noted++;
        // 3) 월별 기록: 일정은 그 달, 현황은 지난달
        String now = Kst.stamp();
        String fDoc = docId, fTitle = docTitle;
        try {
            String planJson = JSON.writeValueAsString(plan), statusJson = JSON.writeValueAsString(status);
            db.tx(conn -> {
                upsert(conn, month, fDoc, fTitle, planJson, null, now);
                if (!status.isEmpty()) upsert(conn, prev, fDoc, fTitle, null, statusJson, now);
                return null;
            });
        } catch (IOException e) {
            return Op.fail(500, e.getMessage());
        }
        try (var fs = Files.list(dir())) { fs.filter(x -> x.getFileName().toString().startsWith(token + ".")).forEach(x -> { try { Files.deleteIfExists(x); } catch (IOException ignored) { } }); }
        catch (IOException ignored) { }
        String msg = ym.getMonthValue() + "월 일정 " + plan.size() + "줄 등록(새 업무 " + made + "건, 이어 받은 업무 " + upd + "건)"
            + (status.isEmpty() ? "" : ", " + ym.minusMonths(1).getMonthValue() + "월 현황 " + status.size() + "줄 기록(업무 진행 기록 " + noted + "건)")
            + (docId.isEmpty() ? "." : ". 보고서는 자료 › " + FOLDER + "에 넣었습니다.");
        return Op.ok(msg, month);
    }

    private static void upsert(java.sql.Connection conn, String month, String docId, String title, String plan, String status, String now) throws java.sql.SQLException {
        Map<String, Object> have = Db.row(conn, "SELECT Month FROM MonthlyReports WHERE Month=?", month);
        if (have == null) Db.update(conn, "INSERT INTO MonthlyReports(Month, DocId, DocTitle, PlanJson, StatusJson, UpdatedAt) VALUES(?,?,?,?,?,?)",
            month, plan != null ? docId : "", plan != null ? title : "", plan == null ? "[]" : plan, status == null ? "[]" : status, now);
        else {
            if (plan != null) Db.update(conn, "UPDATE MonthlyReports SET DocId=?, DocTitle=?, PlanJson=?, UpdatedAt=? WHERE Month=?", docId, title, plan, now, month);
            if (status != null) Db.update(conn, "UPDATE MonthlyReports SET StatusJson=?, UpdatedAt=? WHERE Month=?", status, now, month);
        }
    }

    /** 미리보기 표의 입력 칸 → 줄. 고른(use) 줄만. */
    static List<MonthlyReport.Row> rows(Map<String, String> p, String pre, YearMonth ym) {
        List<MonthlyReport.Row> out = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            if (!p.containsKey(pre + "item" + i)) break;
            if (!"1".equals(p.get(pre + "use" + i))) continue;
            String item = Txt.fit(Txt.clean(p.get(pre + "item" + i), 200, false), 200, false);
            if (Txt.blank(item)) continue;
            String s = date(p.get(pre + "start" + i)), d = date(p.get(pre + "due" + i));
            if (!s.isEmpty() && !d.isEmpty() && d.compareTo(s) < 0) d = s;
            out.add(new MonthlyReport.Row(Txt.fit(Txt.str(p.get(pre + "cat" + i)).strip(), 30, false), item,
                Txt.fit(Txt.str(p.get(pre + "detail" + i)), 4000, true), s, d));
        }
        return out;
    }

    static String date(String v) {
        try { return LocalDate.parse(Txt.str(v).strip()).toString(); } catch (Exception e) { return ""; }
    }

    /** 자료 › 월간 업무보고 › 2026 폴더(없으면 만든다). */
    String folderFor(int year) {
        String top = null;
        for (LibraryService.Folder f : lib.folders()) if (f.parentId.isEmpty() && FOLDER.equals(f.name)) top = f.id;
        if (top == null) { Op o = lib.createFolder(FOLDER, ""); top = o.id; }
        String y = String.valueOf(year);
        LibraryService.Folder t = lib.folder(top);
        if (t != null) for (LibraryService.Folder c : t.children) if (y.equals(c.name)) return c.id;
        Op o = lib.createFolder(y, top);
        return o.ok ? o.id : top;
    }

    /** 한 달 화면에 쓸 기록: 보고서 문서, 일정 줄, 현황 줄. */
    public Map<String, Object> record(YearMonth ym) {
        Map<String, Object> r = db.row("SELECT * FROM MonthlyReports WHERE Month=?", ym.toString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("docId", r == null ? "" : Txt.str(r.get("DocId")));
        out.put("docTitle", r == null ? "" : Txt.str(r.get("DocTitle")));
        out.put("updated", r == null ? "" : Txt.str(r.get("UpdatedAt")));
        try {
            out.put("plan", r == null ? List.of() : JSON.readValue(Txt.str(r.get("PlanJson")), new TypeReference<List<MonthlyReport.Row>>() { }));
            out.put("status", r == null ? List.of() : JSON.readValue(Txt.str(r.get("StatusJson")), new TypeReference<List<MonthlyReport.Row>>() { }));
        } catch (IOException e) {
            out.put("plan", List.of()); out.put("status", List.of());
        }
        if (!Txt.blank((String) out.get("docId")) && lib.get((String) out.get("docId")) == null) out.put("docId", "");   // 자료에서 지운 경우
        return out;
    }

    // ───── 한 달 화면: 주차별·구분별 ─────

    public record Week(int no, LocalDate from, LocalDate to, boolean now) {
        public String label() { return no + "주 (" + from.getMonthValue() + "/" + from.getDayOfMonth() + "~" + to.getMonthValue() + "/" + to.getDayOfMonth() + ")"; }
    }

    /** 그 달의 주(월요일 시작). 첫 주·끝 주는 그 달 안 날짜만. */
    public static List<Week> weeks(YearMonth ym, LocalDate today) {
        List<Week> out = new ArrayList<>();
        LocalDate d = ym.atDay(1), end = ym.atEndOfMonth();
        int n = 1;
        while (!d.isAfter(end)) {
            LocalDate to = d.plusDays(7 - d.getDayOfWeek().getValue());
            if (to.isAfter(end)) to = end;
            out.add(new Week(n++, d, to, !today.isBefore(d) && !today.isAfter(to)));
            d = to.plusDays(1);
        }
        return out;
    }

    public record Cell(String cat, List<List<WorkItem>> byWeek, int total, int done) { }

    /** 구분(업무진단·내부통제 …)별 줄, 주차별 칸. 구분 태그가 없는 업무·일정은 '기타'. */
    public List<Cell> grid(YearMonth ym, List<Week> weeks, List<String> catOrder) {
        Map<String, List<List<WorkItem>>> by = new LinkedHashMap<>();
        Map<String, int[]> cnt = new LinkedHashMap<>();
        for (String c : catOrder) { by.put(c, empty(weeks.size())); cnt.put(c, new int[2]); }
        for (WorkItem x : work.inMonth(ym)) {
            String cat = "기타";
            if (x.tags != null) for (String t : x.tags) if (!"월간계획".equals(t) && catOrder.contains(t)) { cat = t; break; }
            if ("event".equals(x.kind)) cat = catOrder.contains("일정") ? "일정" : cat;
            by.computeIfAbsent(cat, k -> empty(weeks.size()));
            int[] c = cnt.computeIfAbsent(cat, k -> new int[2]);
            if (!"event".equals(x.kind)) { c[0]++; if ("done".equals(x.status)) c[1]++; }
            String s0 = "event".equals(x.kind) ? x.date : Txt.blank(x.start) ? x.due : x.start;
            String e0 = "event".equals(x.kind) ? x.date : Txt.blank(x.due) ? x.start : x.due;
            for (int i = 0; i < weeks.size(); i++) {
                Week w = weeks.get(i);
                if (s0.compareTo(w.to().toString()) <= 0 && e0.compareTo(w.from().toString()) >= 0) by.get(cat).get(i).add(x);
            }
        }
        List<Cell> out = new ArrayList<>();
        for (var e : by.entrySet()) {
            int[] c = cnt.getOrDefault(e.getKey(), new int[2]);
            boolean any = e.getValue().stream().anyMatch(l -> !l.isEmpty());
            if (any || !("기타".equals(e.getKey()) || "일정".equals(e.getKey()))) out.add(new Cell(e.getKey(), e.getValue(), c[0], c[1]));
        }
        return out;
    }

    static List<List<WorkItem>> empty(int n) { List<List<WorkItem>> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(new ArrayList<>()); return l; }
}
