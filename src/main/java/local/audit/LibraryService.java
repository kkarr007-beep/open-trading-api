package local.audit;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import local.audit.extract.Rich;
import local.audit.extract.TextReader;

/**
 * 자료실: 분류별로 문서를 차곡차곡 쌓아 두고 언제든 찾는다.
 * 등재하면 파일을 그대로 보관하고, 뒤에서 내용을 읽어 원본 모양(표·합친 칸·그림·서식)으로 보여 줄 HTML과 찾기용 글자를 만든다.
 * 내용 편집은 하지 않는다. 제목·분류·태그·메모만 고친다.
 */
@Service
public class LibraryService {
    /** 자료실 파일을 두는 files 폴더 안의 칸. 건 ID와 겹치지 않는 값. */
    static final String FOLDER = "00000000000000000000000000000000";
    private final Db db;
    private final FileStore files;
    private final TextReader reader;
    private final ExecutorService queue = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "library-read");
        t.setDaemon(true);
        return t;
    });

    public LibraryService(Db db, FileStore files, TextReader reader) {
        this.db = db;
        this.files = files;
        this.reader = reader;
        db.tx(conn -> {
            boolean fresh = Db.intOf(conn, "SELECT COUNT(*) FROM LibCategories") == 0;
            boolean upgrade = Db.intOf(conn, "SELECT COUNT(*) FROM pragma_table_info('LibCategories') WHERE name='ParentId'") == 0;
            if (upgrade) Db.update(conn, "ALTER TABLE LibCategories ADD COLUMN ParentId TEXT NOT NULL DEFAULT ''");
            if (fresh) {
                String[] names = { "사내 규정·매뉴얼", "보고서", "회의 자료", "서식·양식", "법령·감독규정", "참고 자료" };
                for (int i = 0; i < names.length; i++) section(conn, names[i], i + 1);
            }
            return null;
        });
    }

    private static String section(java.sql.Connection conn, String name, int ord) throws java.sql.SQLException {
        String id = Txt.newId();
        Db.update(conn, "INSERT INTO LibCategories(Id, Name, Ord, ParentId) VALUES (?,?,?,'')", id, name, ord);
        return id;
    }

    /** 분류와 그 밑 하위 분류. 분류의 Docs는 하위 분류까지 더한 수. 왼쪽 메뉴와 분류 관리에 쓴다. */
    public List<Map<String, Object>> tree() {
        List<Map<String, Object>> all = categories();
        List<Map<String, Object>> top = new ArrayList<>();
        for (Map<String, Object> c : all) if (Txt.blank(Txt.str(c.get("ParentId")))) { c.put("children", new ArrayList<Map<String, Object>>()); top.add(c); }
        for (Map<String, Object> c : all) {
            String pid = Txt.str(c.get("ParentId"));
            if (pid.isEmpty()) continue;
            for (Map<String, Object> t : top) if (pid.equals(t.get("Id"))) {
                @SuppressWarnings("unchecked") List<Map<String, Object>> kids = (List<Map<String, Object>>) t.get("children");
                kids.add(c);
                t.put("Docs", ((Number) t.get("Docs")).intValue() + ((Number) c.get("Docs")).intValue());
            }
        }
        return top;
    }

    public boolean isSection(String id) {
        return db.row("SELECT Id FROM LibCategories WHERE Id=? AND ParentId=''", id) != null;
    }

    /** 고른 분류의 이름. 하위 분류면 '분류 › 하위 분류'. */
    public String path(String id) {
        if (Txt.blank(id)) return "";
        Map<String, Object> r = db.row("""
            SELECT CASE WHEN p.Name IS NULL THEN c.Name ELSE p.Name || ' › ' || c.Name END AS Path
            FROM LibCategories c LEFT JOIN LibCategories p ON p.Id=c.ParentId WHERE c.Id=?""", id);
        return r == null ? "" : Txt.str(r.get("Path"));
    }

    /** 이 분류가 속한 위 분류(위 분류면 자기). */
    public String sectionOf(String id) {
        if (Txt.blank(id)) return "";
        Map<String, Object> r = db.row("SELECT ParentId FROM LibCategories WHERE Id=?", id);
        if (r == null) return "";
        String pid = Txt.str(r.get("ParentId"));
        return pid.isEmpty() ? id : pid;
    }

    public List<Map<String, Object>> categories() {
        return db.rows("""
            SELECT c.Id, c.Name, c.Ord, c.ParentId, (SELECT COUNT(*) FROM LibDocs d WHERE d.CategoryId=c.Id) AS Docs,
                   CASE WHEN p.Name IS NULL THEN c.Name ELSE p.Name || ' › ' || c.Name END AS Path
            FROM LibCategories c LEFT JOIN LibCategories p ON p.Id=c.ParentId
            ORDER BY COALESCE(p.Ord, c.Ord), COALESCE(p.Name, c.Name), CASE WHEN c.ParentId='' THEN 0 ELSE 1 END, c.Ord, c.Name""");
    }

    public int total() { return db.read(conn -> Db.intOf(conn, "SELECT COUNT(*) FROM LibDocs")); }

    public Op category(String what, Map<String, String> p) {
        String id = Txt.str(p.get("id"));
        return db.run(conn -> {
            switch (what) {
                case "save" -> {
                    String name = Txt.clean(p.get("name"), 40, false);
                    if (Txt.blank(name)) throw Op.abort("분류 이름을 적으세요.");
                    Integer ord = Txt.intOrNull(p.get("ord"));
                    String parent = Txt.str(p.get("parentId"));
                    if (!parent.isEmpty()) {
                        if (parent.equals(id)) throw Op.abort("자기 자신 밑으로 옮길 수 없습니다.");
                        if (Db.intOf(conn, "SELECT COUNT(*) FROM LibCategories WHERE Id=? AND ParentId=''", parent) == 0) throw Op.abort("위 분류를 고르세요.");
                        if (!id.isEmpty() && Db.intOf(conn, "SELECT COUNT(*) FROM LibCategories WHERE ParentId=?", id) > 0)
                            throw Op.abort("하위 분류가 있는 분류는 다른 분류 밑으로 옮길 수 없습니다.");
                    }
                    if (Db.intOf(conn, "SELECT COUNT(*) FROM LibCategories WHERE Name=? AND ParentId=? AND Id<>?", name, parent, id) > 0)
                        throw Op.abort("같은 자리에 같은 이름의 분류가 있습니다.");
                    String kind = parent.isEmpty() ? "분류" : "하위 분류";
                    if (id.isEmpty()) {
                        int next = Db.intOf(conn, "SELECT IFNULL(MAX(Ord),0)+1 FROM LibCategories WHERE ParentId=?", parent);
                        Db.update(conn, "INSERT INTO LibCategories(Id, Name, Ord, ParentId) VALUES (?,?,?,?)", Txt.newId(), name, ord == null ? next : ord, parent);
                        return Op.ok(kind + " '" + name + "'를 넣었습니다.");
                    }
                    String keepParent = p.containsKey("parentId") ? parent : null;
                    if (Db.update(conn, "UPDATE LibCategories SET Name=?, Ord=COALESCE(?, Ord), ParentId=COALESCE(?, ParentId) WHERE Id=?", name, ord, keepParent, id) == 0)
                        throw Op.abort(404, "분류를 찾을 수 없습니다.");
                    return Op.ok(kind + "를 고쳤습니다.");
                }
                case "delete" -> {
                    if (Db.intOf(conn, "SELECT COUNT(*) FROM LibCategories WHERE ParentId=?", id) > 0) throw Op.abort("하위 분류가 있는 분류는 지울 수 없습니다. 하위 분류를 먼저 지우거나 옮기세요.");
                    if (Db.intOf(conn, "SELECT COUNT(*) FROM LibDocs WHERE CategoryId=?", id) > 0) throw Op.abort("자료가 있는 분류는 지울 수 없습니다. 자료를 다른 분류로 옮긴 뒤 지우세요.");
                    if (Db.update(conn, "DELETE FROM LibCategories WHERE Id=?", id) == 0) throw Op.abort(404, "분류를 찾을 수 없습니다.");
                    return Op.ok("분류를 지웠습니다.");
                }
                default -> throw Op.abort(404, "없는 작업입니다.");
            }
        });
    }

    /** 파일 하나를 등재한다. 내용 읽기는 뒤에서 한다. */
    public Op add(String categoryId, String title, String tags, String note, String fileName, InputStream in) throws java.io.IOException {
        if (db.row("SELECT Id FROM LibCategories WHERE Id=?", categoryId) == null) return Op.bad("분류를 고르세요.");
        String name = FileStore.safeName(fileName);
        if (Txt.blank(name)) return Op.bad("파일 이름을 확인하세요.");
        String id = Txt.newId();
        FileStore.Stored st = files.save(FOLDER, id, Txt.ext(name), in);
        // 같은 파일(내용 지문이 같음)이 이미 있으면 다시 쌓지 않는다.
        Map<String, Object> dup = db.row("SELECT d.Id, d.Title, c.Name AS Category FROM LibDocs d JOIN LibCategories c ON c.Id=d.CategoryId WHERE d.Sha256=?", st.sha256());
        if (dup != null) {
            files.delete(st.key());
            return Op.fail(409, "같은 파일이 이미 있습니다: '" + dup.get("Title") + "' (" + dup.get("Category") + ")").extra((String) dup.get("Id"));
        }
        String t = Txt.fit(Txt.blank(title) ? Txt.stem(name) : title, 200, false);
        db.tx(conn -> Db.update(conn, """
            INSERT INTO LibDocs(Id, CategoryId, Title, FileName, StoragePath, SizeBytes, Sha256, Tags, Note, RegisteredAt, ReadState, ReadNote)
            VALUES (?,?,?,?,?,?,?,?,?,?, '읽는 중', '')""",
            id, categoryId, t, name, st.key(), st.size(), st.sha256(), Txt.fit(tags, 300, false), Txt.fit(note, 2000, true), Kst.stamp()));
        queue.submit(() -> read(id));
        return Op.ok("'" + t + "'를 등재했습니다. 내용은 뒤에서 읽습니다.", id);
    }

    void read(String id) {
        Map<String, Object> d = db.row("SELECT FileName, StoragePath FROM LibDocs WHERE Id=?", id);
        if (d == null) return;
        Path file = files.resolve((String) d.get("StoragePath"));
        TextReader.Result r;
        try {
            r = reader.read(file, (String) d.get("FileName"));
        } catch (RuntimeException e) {
            r = new TextReader.Result("실패", "", "읽지 못했습니다.", "", false);
        }
        String html = r.ok() ? (r.html() != null ? r.html() : Rich.fromText(r.text())) : null;
        String body = r.ok() ? Txt.str(r.text()) : null;
        String state = r.ok() ? "읽음" : r.encrypted() ? "암호문" : "못 읽음";
        String note = Txt.fit(r.note(), 300, false);
        db.tx(conn -> {
            Db.update(conn, "DELETE FROM LibText WHERE Id=?", id);
            if (body != null) Db.update(conn, "INSERT INTO LibText(Id, Body, Html) VALUES (?,?,?)", id, body, html);
            Db.update(conn, "UPDATE LibDocs SET ReadState=?, ReadNote=? WHERE Id=?", state, note, id);
            return null;
        });
    }

    public void awaitReads() {
        try { queue.submit(() -> { }).get(120, TimeUnit.SECONDS); } catch (Exception ignored) { }
    }

    public Op reread(String id) {
        if (db.row("SELECT Id FROM LibDocs WHERE Id=?", id) == null) return Op.fail(404, "자료를 찾을 수 없습니다.");
        db.tx(conn -> Db.update(conn, "UPDATE LibDocs SET ReadState='읽는 중' WHERE Id=?", id));
        queue.submit(() -> read(id));
        return Op.ok("내용을 다시 읽습니다.");
    }

    /** 파일 형식 묶음: 거르기 칩에 쓴다. */
    public static final Map<String, String> KINDS = new LinkedHashMap<>();
    static {
        KINDS.put("hwp", "한글"); KINDS.put("word", "워드"); KINDS.put("excel", "엑셀"); KINDS.put("ppt", "파워포인트");
        KINDS.put("pdf", "PDF"); KINDS.put("image", "그림"); KINDS.put("etc", "기타");
    }

    public static String kindOf(String fileName) {
        String e = Txt.ext(Txt.str(fileName)).replace(".", "").toLowerCase();
        return switch (e) {
            case "hwp", "hwpx" -> "hwp";
            case "doc", "docx", "rtf" -> "word";
            case "xls", "xlsx", "csv", "xlsm" -> "excel";
            case "ppt", "pptx" -> "ppt";
            case "pdf" -> "pdf";
            case "png", "jpg", "jpeg", "gif", "bmp", "webp", "tif", "tiff" -> "image";
            default -> "etc";
        };
    }

    public Map<String, Object> list(String categoryId, String q, int page, int size) { return list(categoryId, q, null, null, page, size); }

    /** 찾기·거르기·정렬·쪽 나누기. kind: 파일 형식 묶음, sort: new(최근)·old·title·size. */
    public Map<String, Object> list(String categoryId, String q, String kind, String sort, int page, int size) {
        StringBuilder sql = new StringBuilder("""
            SELECT d.Id, d.CategoryId, CASE WHEN p.Name IS NULL THEN c.Name ELSE p.Name || ' › ' || c.Name END AS Category,
                   d.Title, d.FileName, d.SizeBytes, d.Tags, d.Note, d.RegisteredAt, d.ReadState, t.Body AS Body
            FROM LibDocs d JOIN LibCategories c ON c.Id=d.CategoryId LEFT JOIN LibCategories p ON p.Id=c.ParentId
            LEFT JOIN LibText t ON t.Id=d.Id WHERE 1=1""");
        List<Object> args = new ArrayList<>();
        if (!Txt.blank(categoryId)) {
            sql.append(" AND (d.CategoryId=? OR c.ParentId=?)");
            args.add(categoryId);
            args.add(categoryId);
        }
        List<String> terms = new ArrayList<>();
        for (String w : Txt.str(q).strip().split("\\s+")) if (!w.isBlank()) terms.add(w);
        for (String w : terms) {
            sql.append(" AND (d.Title LIKE ? ESCAPE '\\' OR d.FileName LIKE ? ESCAPE '\\' OR d.Tags LIKE ? ESCAPE '\\' OR d.Note LIKE ? ESCAPE '\\' OR t.Body LIKE ? ESCAPE '\\')");
            for (int i = 0; i < 5; i++) args.add(Txt.like(w));
        }
        sql.append(switch (Txt.str(sort)) {
            case "old" -> " ORDER BY d.RegisteredAt";
            case "title" -> " ORDER BY d.Title COLLATE NOCASE, d.RegisteredAt DESC";
            case "size" -> " ORDER BY d.SizeBytes DESC";
            default -> " ORDER BY d.RegisteredAt DESC";
        });
        List<Map<String, Object>> all = db.rows(sql.toString(), args.toArray());
        Map<String, Integer> kinds = new LinkedHashMap<>();
        for (String k : KINDS.keySet()) kinds.put(k, 0);
        for (Map<String, Object> d : all) kinds.merge(kindOf(Txt.str(d.get("FileName"))), 1, Integer::sum);
        if (KINDS.containsKey(Txt.str(kind))) all.removeIf(d -> !kindOf(Txt.str(d.get("FileName"))).equals(kind));
        int pages = Math.max(1, (all.size() + size - 1) / size);
        int p = Math.max(1, Math.min(page, pages));
        List<Map<String, Object>> items = new ArrayList<>(all.subList(Math.min(all.size(), (p - 1) * size), Math.min(all.size(), p * size)));
        for (Map<String, Object> d : items) decorate(d, terms);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("items", items);
        v.put("kinds", kinds);
        v.put("total", all.size());
        v.put("page", p);
        v.put("pages", pages);
        return v;
    }

    void decorate(Map<String, Object> d, List<String> terms) {
        String body = Txt.str(d.remove("Body")).replaceAll("\\s+", " ");
        String snip = "";
        if (!terms.isEmpty()) {
            String low = body.toLowerCase();
            for (String t : terms) {
                int at = low.indexOf(t.toLowerCase());
                if (at >= 0) {
                    int from = Math.max(0, at - 50), to = Math.min(body.length(), at + 110);
                    snip = (from > 0 ? "…" : "") + body.substring(from, to) + (to < body.length() ? "…" : "");
                    break;
                }
            }
        }
        d.put("Snippet", snip);
        d.put("SizeText", size(((Number) d.getOrDefault("SizeBytes", 0)).longValue()));
        d.put("RegisteredDisplay", Kst.display(Txt.str(d.get("RegisteredAt"))));
        d.put("Ext", Txt.ext(Txt.str(d.get("FileName"))).replace(".", "").toUpperCase());
        d.put("Kind", kindOf(Txt.str(d.get("FileName"))));
    }

    static String size(long b) {
        return b < 1024 ? b + " B" : b < 1024 * 1024 ? String.format("%.1f KB", b / 1024.0) : String.format("%.1f MB", b / 1048576.0);
    }

    public Map<String, Object> get(String id) {
        Map<String, Object> d = db.row("""
            SELECT d.*, CASE WHEN p.Name IS NULL THEN c.Name ELSE p.Name || ' › ' || c.Name END AS Category, t.Html AS Html, LENGTH(t.Body) AS TextLength
            FROM LibDocs d JOIN LibCategories c ON c.Id=d.CategoryId LEFT JOIN LibCategories p ON p.Id=c.ParentId
            LEFT JOIN LibText t ON t.Id=d.Id WHERE d.Id=?""", id);
        if (d == null) return null;
        d.put("SizeText", size(((Number) d.getOrDefault("SizeBytes", 0)).longValue()));
        d.put("RegisteredDisplay", Kst.display(Txt.str(d.get("RegisteredAt"))));
        d.put("Ext", Txt.ext(Txt.str(d.get("FileName"))).replace(".", "").toUpperCase());
        d.put("Kind", kindOf(Txt.str(d.get("FileName"))));
        return d;
    }

    public Path file(String id) {
        Map<String, Object> d = db.row("SELECT StoragePath FROM LibDocs WHERE Id=?", id);
        return d == null ? null : files.resolve((String) d.get("StoragePath"));
    }

    public Op meta(String id, Map<String, String> p) {
        String title = Txt.clean(p.get("title"), 200, false);
        if (Txt.blank(title)) return Op.bad("제목을 적으세요.");
        if (db.row("SELECT Id FROM LibCategories WHERE Id=?", p.get("categoryId")) == null) return Op.bad("분류를 고르세요.");
        int n = db.tx(conn -> Db.update(conn, "UPDATE LibDocs SET Title=?, CategoryId=?, Tags=?, Note=? WHERE Id=?",
            title, p.get("categoryId"), Txt.fit(p.get("tags"), 300, false), Txt.fit(p.get("note"), 2000, true), id));
        return n == 0 ? Op.fail(404, "자료를 찾을 수 없습니다.") : Op.ok("저장했습니다.");
    }

    /** 고른 자료를 다른 분류로 옮긴다. */
    public Op move(List<String> ids, String categoryId) {
        if (ids == null || ids.isEmpty()) return Op.bad("옮길 자료를 고르세요.");
        return db.run(conn -> {
            if (Db.intOf(conn, "SELECT COUNT(*) FROM LibCategories WHERE Id=?", Txt.str(categoryId)) == 0) throw Op.abort("옮길 분류를 고르세요.");
            int n = 0;
            for (String id : ids) if (Txt.isId(id)) n += Db.update(conn, "UPDATE LibDocs SET CategoryId=? WHERE Id=?", categoryId, id);
            return Op.ok("자료 " + n + "건을 옮겼습니다.");
        });
    }

    public Op delete(String id) {
        Map<String, Object> d = db.row("SELECT Title, StoragePath FROM LibDocs WHERE Id=?", id);
        if (d == null) return Op.fail(404, "자료를 찾을 수 없습니다.");
        db.tx(conn -> {
            Db.update(conn, "DELETE FROM LibText WHERE Id=?", id);
            Db.update(conn, "DELETE FROM LibDocs WHERE Id=?", id);
            return null;
        });
        boolean gone = files.delete((String) d.get("StoragePath"));
        return Op.ok("'" + d.get("Title") + "'를 지웠습니다." + (gone ? "" : " 파일이 열려 있어 files 폴더에서 직접 지워야 합니다."));
    }

    public List<Map<String, Object>> search(String q, int limit) {
        if (Txt.blank(q)) return List.of();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) list(null, q, 1, limit).get("items");
        return items;
    }
}
