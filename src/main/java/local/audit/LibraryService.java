package local.audit;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            Set<String> ids = subtree(categoryId);
            sql.append(" AND d.CategoryId IN (").append(String.join(",", java.util.Collections.nCopies(ids.size(), "?"))).append(")");
            args.addAll(ids);
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
            Db.update(conn, "DELETE FROM LibStars WHERE DocId=?", id);
            Db.update(conn, "DELETE FROM LibRecent WHERE DocId=?", id);
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

    // ================================================================== 폴더 (탐색기 방식)

    /** 폴더는 세 단계까지. 깊으면 오히려 찾기 어렵다. */
    public static final int MAX_DEPTH = 3;
    public static final String ARCHIVE = "보관";

    /** 폴더 하나. Docs는 이 폴더에 바로 든 자료, Total은 하위 폴더까지 더한 수. */
    public static final class Folder {
        public String id, name, parentId;
        public int ord, docs, total, depth;
        public boolean archive;
        public List<Folder> children = new ArrayList<>();
        public String getId() { return id; }
        public String getName() { return name; }
        public String getParentId() { return parentId; }
        public int getDocs() { return docs; }
        public int getTotal() { return total; }
        public int getDepth() { return depth; }
        public boolean isArchive() { return archive; }
        public List<Folder> getChildren() { return children; }
        public int height() { int h = 1; for (Folder c : children) h = Math.max(h, 1 + c.height()); return h; }
    }

    /** 폴더 나무 전체(맨 위 폴더 목록). 보관 폴더는 맨 아래. */
    public List<Folder> folders() {
        Map<String, Folder> by = new LinkedHashMap<>();
        for (Map<String, Object> r : db.rows("""
            SELECT c.Id, c.Name, c.Ord, c.ParentId, (SELECT COUNT(*) FROM LibDocs d WHERE d.CategoryId=c.Id) AS Docs
            FROM LibCategories c ORDER BY c.Ord, c.Name""")) {
            Folder f = new Folder();
            f.id = Txt.str(r.get("Id")); f.name = Txt.str(r.get("Name")); f.parentId = Txt.str(r.get("ParentId"));
            f.ord = ((Number) r.get("Ord")).intValue(); f.docs = ((Number) r.get("Docs")).intValue();
            by.put(f.id, f);
        }
        List<Folder> top = new ArrayList<>();
        for (Folder f : by.values()) {
            Folder p = by.get(f.parentId);
            if (p == null || p == f) top.add(f); else p.children.add(f);
        }
        top.sort(java.util.Comparator.comparing((Folder f) -> ARCHIVE.equals(f.name)).thenComparingInt(f -> f.ord).thenComparing(f -> f.name));
        for (Folder f : top) fill(f, 1, ARCHIVE.equals(f.name));
        return top;
    }

    private static int fill(Folder f, int depth, boolean archive) {
        f.depth = depth;
        f.archive = archive;
        f.children.sort(java.util.Comparator.comparingInt((Folder c) -> c.ord).thenComparing(c -> c.name));
        int t = f.docs;
        for (Folder c : f.children) t += fill(c, depth + 1, archive);
        f.total = t;
        return t;
    }

    public Folder folder(String id) {
        if (Txt.blank(id)) return null;
        for (Folder f : flat(folders())) if (f.id.equals(id)) return f;
        return null;
    }

    public static List<Folder> flat(List<Folder> top) {
        List<Folder> out = new ArrayList<>();
        java.util.Deque<Folder> st = new java.util.ArrayDeque<>();
        for (int i = top.size() - 1; i >= 0; i--) st.push(top.get(i));
        while (!st.isEmpty()) {
            Folder f = st.pop();
            out.add(f);
            for (int i = f.children.size() - 1; i >= 0; i--) st.push(f.children.get(i));
        }
        return out;
    }

    /** 이 폴더와 그 아래 모든 폴더의 아이디. */
    public Set<String> subtree(String id) {
        Set<String> out = new java.util.LinkedHashSet<>();
        Folder f = folder(id);
        if (f == null) { out.add(Txt.str(id)); return out; }
        for (Folder x : flat(List.of(f))) out.add(x.id);
        return out;
    }

    /** 맨 위부터 이 폴더까지(위치 표시에 쓴다). */
    public List<Folder> trail(String id) {
        List<Folder> all = flat(folders());
        Map<String, Folder> by = new java.util.HashMap<>();
        for (Folder f : all) by.put(f.id, f);
        List<Folder> out = new ArrayList<>();
        Folder f = by.get(Txt.str(id));
        int guard = 0;
        while (f != null && guard++ < 10) { out.add(0, f); f = by.get(f.parentId); }
        return out;
    }

    public Op createFolder(String name, String parentId) {
        String n = Txt.clean(name, 40, false);
        if (Txt.blank(n)) return Op.bad("폴더 이름을 적으세요.");
        String parent = Txt.str(parentId);
        Folder p = parent.isEmpty() ? null : folder(parent);
        if (!parent.isEmpty() && p == null) return Op.fail(404, "위 폴더를 찾지 못했습니다.");
        if (p != null && p.depth >= MAX_DEPTH) return Op.bad("폴더는 " + MAX_DEPTH + "단계까지 만들 수 있습니다. 태그로 나눠 보세요.");
        return db.run(conn -> {
            if (Db.intOf(conn, "SELECT COUNT(*) FROM LibCategories WHERE Name=? AND ParentId=?", n, parent) > 0) throw Op.abort("같은 자리에 같은 이름의 폴더가 있습니다.");
            int next = Db.intOf(conn, "SELECT IFNULL(MAX(Ord),0)+1 FROM LibCategories WHERE ParentId=?", parent);
            String id = Txt.newId();
            Db.update(conn, "INSERT INTO LibCategories(Id, Name, Ord, ParentId) VALUES (?,?,?,?)", id, n, next, parent);
            return Op.ok("폴더 '" + n + "'를 만들었습니다.", id);
        });
    }

    public Op renameFolder(String id, String name) {
        String n = Txt.clean(name, 40, false);
        if (Txt.blank(n)) return Op.bad("폴더 이름을 적으세요.");
        Folder f = folder(id);
        if (f == null) return Op.fail(404, "폴더를 찾지 못했습니다.");
        return db.run(conn -> {
            if (Db.intOf(conn, "SELECT COUNT(*) FROM LibCategories WHERE Name=? AND ParentId=? AND Id<>?", n, f.parentId, id) > 0) throw Op.abort("같은 자리에 같은 이름의 폴더가 있습니다.");
            Db.update(conn, "UPDATE LibCategories SET Name=? WHERE Id=?", n, id);
            return Op.ok("이름을 '" + n + "'로 바꿨습니다.", id);
        });
    }

    /** 폴더를 다른 폴더 밑(또는 맨 위)으로 옮긴다. 자기 밑으로는 못 옮기고, 세 단계를 넘지 않게 한다. */
    public Op moveFolder(String id, String parentId) { return moveFolder(id, parentId, false); }

    private Op moveFolder(String id, String parentId, boolean archiving) {
        Folder f = folder(id);
        if (f == null) return Op.fail(404, "폴더를 찾지 못했습니다.");
        String parent = Txt.str(parentId);
        if (parent.equals(f.parentId)) return Op.ok("이미 그 자리에 있습니다.", id);
        if (!parent.isEmpty()) {
            if (subtree(id).contains(parent)) return Op.bad("폴더를 자기 안으로 옮길 수 없습니다.");
            Folder p = folder(parent);
            if (p == null) return Op.fail(404, "옮길 곳을 찾지 못했습니다.");
            if (p.depth + f.height() > MAX_DEPTH + (archiving || p.archive ? 1 : 0)) return Op.bad("그곳으로 옮기면 폴더가 " + MAX_DEPTH + "단계를 넘습니다.");
        }
        return db.run(conn -> {
            if (Db.intOf(conn, "SELECT COUNT(*) FROM LibCategories WHERE Name=? AND ParentId=? AND Id<>?", f.name, parent, id) > 0) throw Op.abort("옮길 곳에 같은 이름의 폴더가 있습니다.");
            int next = Db.intOf(conn, "SELECT IFNULL(MAX(Ord),0)+1 FROM LibCategories WHERE ParentId=?", parent);
            Db.update(conn, "UPDATE LibCategories SET ParentId=?, Ord=? WHERE Id=?", parent, next, id);
            return Op.ok("폴더 '" + f.name + "'를 옮겼습니다.", id);
        });
    }

    /** 끝난 폴더를 '보관' 밑으로. 보관 폴더가 없으면 만든다. 보관은 '전체' 찾기에서 빠진다. */
    public Op archiveFolder(String id) {
        Folder f = folder(id);
        if (f == null) return Op.fail(404, "폴더를 찾지 못했습니다.");
        if (f.archive) return Op.ok("이미 보관에 있습니다.", id);
        String arch = db.run(conn -> {
            Map<String, Object> a = Db.row(conn, "SELECT Id FROM LibCategories WHERE Name=? AND ParentId=''", ARCHIVE);
            if (a != null) return Op.ok("", (String) a.get("Id"));
            String aid = Txt.newId();
            Db.update(conn, "INSERT INTO LibCategories(Id, Name, Ord, ParentId) VALUES (?,?,999,'')", aid, ARCHIVE);
            return Op.ok("", aid);
        }).id;
        Op op = moveFolder(id, arch, true);
        return op.ok ? Op.ok("'" + f.name + "'를 보관으로 옮겼습니다. '전체'로 찾을 때는 빠집니다.", id) : op;
    }

    /** 폴더를 지운다. 안의 자료와 하위 폴더는 한 단계 위 폴더로 올린다(맨 위 폴더는 비어 있어야 지운다). */
    /** 맨 위 폴더를 지울 때 안의 자료를 모아 두는 폴더. */
    public static final String UNSORTED = "정리 필요";

    public Op deleteFolder(String id) {
        Folder f = folder(id);
        if (f == null) return Op.fail(404, "폴더를 찾지 못했습니다.");
        if (f.parentId.isEmpty()) {
            // 맨 위 폴더: 안에 바로 든 자료는 '정리 필요' 폴더로(없으면 만든다), 하위 폴더는 맨 위로 올린다. 자료는 지워지지 않는다.
            if (UNSORTED.equals(f.name) && f.total > 0) return Op.bad("'" + UNSORTED + "' 폴더는 안의 자료를 다른 폴더로 옮긴 뒤 지우세요.");
            return db.run(conn -> {
                String target = null;
                int docs = Db.intOf(conn, "SELECT COUNT(*) FROM LibDocs WHERE CategoryId=?", id);
                if (docs > 0) {
                    Map<String, Object> u = Db.row(conn, "SELECT Id FROM LibCategories WHERE Name=? AND ParentId='' AND Id<>?", UNSORTED, id);
                    if (u != null) target = (String) u.get("Id");
                    else {
                        target = Txt.newId();
                        Db.update(conn, "INSERT INTO LibCategories(Id, Name, Ord, ParentId) VALUES (?,?,?,'')", target, UNSORTED, 998);
                    }
                    Db.update(conn, "UPDATE LibDocs SET CategoryId=? WHERE CategoryId=?", target, id);
                }
                int kids = Db.update(conn, "UPDATE LibCategories SET ParentId='' WHERE ParentId=?", id);
                Db.update(conn, "DELETE FROM LibCategories WHERE Id=?", id);
                if (Db.intOf(conn, "SELECT COUNT(*) FROM LibCategories") == 0) {
                    Db.update(conn, "INSERT INTO LibCategories(Id, Name, Ord, ParentId) VALUES (?,?,?,'')", Txt.newId(), UNSORTED, 998);
                }
                String msg = "폴더 '" + f.name + "'를 지웠습니다.";
                if (docs > 0) msg += " 안에 있던 자료 " + docs + "건은 '" + UNSORTED + "' 폴더로 옮겼습니다.";
                if (kids > 0) msg += " 하위 폴더 " + kids + "개는 맨 위로 올렸습니다.";
                return Op.ok(msg, target == null ? "" : target);
            });
        }
        return db.run(conn -> {
            int docs = Db.update(conn, "UPDATE LibDocs SET CategoryId=? WHERE CategoryId=?", f.parentId, id);
            int kids = Db.update(conn, "UPDATE LibCategories SET ParentId=? WHERE ParentId=?", f.parentId, id);
            Db.update(conn, "DELETE FROM LibCategories WHERE Id=?", id);
            return Op.ok("폴더 '" + f.name + "'를 지웠습니다." + (docs + kids > 0 ? " 안에 있던 자료 " + docs + "건과 폴더 " + kids + "개는 한 단계 위로 올렸습니다." : ""), f.parentId);
        });
    }

    // ================================================================== 즐겨찾기·최근·저장한 찾기·태그

    public Op star(List<String> ids, boolean on) {
        if (ids == null || ids.isEmpty()) return Op.bad("자료를 고르세요.");
        return db.run(conn -> {
            int n = 0;
            for (String id : ids) {
                if (!Txt.isId(id)) continue;
                if (on) n += Db.update(conn, "INSERT OR IGNORE INTO LibStars(DocId, At) SELECT Id, ? FROM LibDocs WHERE Id=?", Kst.stamp(), id);
                else n += Db.update(conn, "DELETE FROM LibStars WHERE DocId=?", id);
            }
            return Op.ok(on ? "즐겨찾기에 " + n + "건을 넣었습니다." : "즐겨찾기에서 " + n + "건을 뺐습니다.");
        });
    }

    /** 열거나 내려받은 자료를 '최근 연 자료'에 남긴다(최근 50건). */
    public void touch(String id) {
        if (!Txt.isId(id)) return;
        db.tx(conn -> {
            Db.update(conn, "INSERT INTO LibRecent(DocId, At) VALUES(?,?) ON CONFLICT(DocId) DO UPDATE SET At=excluded.At", id, Kst.stamp());
            Db.update(conn, "DELETE FROM LibRecent WHERE DocId NOT IN (SELECT DocId FROM LibRecent ORDER BY At DESC LIMIT 50)");
            return null;
        });
    }

    public int starCount() { return db.read(conn -> Db.intOf(conn, "SELECT COUNT(*) FROM LibStars")); }
    public int recentCount() { return db.read(conn -> Db.intOf(conn, "SELECT COUNT(*) FROM LibRecent")); }

    /** 고른 자료에 태그를 더한다(이미 있는 태그는 그대로). */
    public Op addTags(List<String> ids, String tags) {
        List<String> add = splitTags(tags);
        if (add.isEmpty()) return Op.bad("붙일 태그를 적으세요.");
        if (ids == null || ids.isEmpty()) return Op.bad("자료를 고르세요.");
        return db.run(conn -> {
            int n = 0;
            for (String id : ids) {
                Map<String, Object> d = Db.row(conn, "SELECT Tags FROM LibDocs WHERE Id=?", id);
                if (d == null) continue;
                java.util.LinkedHashSet<String> cur = new java.util.LinkedHashSet<>(splitTags(Txt.str(d.get("Tags"))));
                cur.addAll(add);
                n += Db.update(conn, "UPDATE LibDocs SET Tags=? WHERE Id=?", Txt.fit(String.join(", ", cur), 300, false), id);
            }
            return Op.ok(n + "건에 태그 " + String.join(", ", add) + "를 붙였습니다.");
        });
    }

    public static List<String> splitTags(String tags) {
        List<String> out = new ArrayList<>();
        for (String t : Txt.str(tags).split("[,，#\\s]+")) { String x = t.strip(); if (!x.isEmpty() && !out.contains(x) && x.length() <= 30) out.add(x); }
        return out;
    }

    /** 자주 쓰는 태그(많이 쓴 순). 올리기 칩과 거르기 칩에 쓴다. */
    public List<Map.Entry<String, Integer>> topTags(java.util.Collection<Map<String, Object>> docs, int limit) {
        Map<String, Integer> c = new LinkedHashMap<>();
        for (Map<String, Object> d : docs) for (String t : splitTags(Txt.str(d.get("Tags")))) c.merge(t, 1, Integer::sum);
        List<Map.Entry<String, Integer>> l = new ArrayList<>(c.entrySet());
        l.sort((a, b) -> b.getValue() - a.getValue());
        return l.size() > limit ? l.subList(0, limit) : l;
    }

    public List<Map.Entry<String, Integer>> allTags(int limit) {
        return topTags(db.rows("SELECT Tags FROM LibDocs WHERE Tags<>''"), limit);
    }

    public List<Map<String, Object>> saved() { return db.rows("SELECT * FROM LibSaved ORDER BY CreatedAt"); }

    public Op saveSearch(String name, String params) {
        String n = Txt.clean(name, 40, false);
        if (Txt.blank(n)) return Op.bad("찾기 이름을 적으세요.");
        String pr = Txt.fit(params, 500, false);
        if (Txt.blank(pr)) return Op.bad("저장할 찾기 조건이 없습니다.");
        String id = Txt.newId();
        db.tx(conn -> Db.update(conn, "INSERT INTO LibSaved(Id, Name, Params, CreatedAt) VALUES(?,?,?,?)", id, n, pr, Kst.stamp()));
        return Op.ok("찾기 '" + n + "'를 저장했습니다. 왼쪽 '저장한 찾기'에서 다시 엽니다.", id);
    }

    public Op deleteSaved(String id) {
        int n = db.tx(conn -> Db.update(conn, "DELETE FROM LibSaved WHERE Id=?", id));
        return n == 0 ? Op.fail(404, "저장한 찾기를 찾지 못했습니다.") : Op.ok("저장한 찾기를 지웠습니다.");
    }

    // ================================================================== 탐색

    /**
     * 탐색기 화면의 목록. view: ""(폴더) · star · recent. scope: here(이 폴더와 하위) · all(전체, 보관 제외).
     * 찾는 말이 없고 폴더를 보고 있으면 이 폴더에 바로 든 자료만 보인다(하위 폴더는 폴더 줄로).
     */
    public Map<String, Object> browse(String folderId, String view, String q, String scope, String kind, String tag, String sort, int page, int size) {
        StringBuilder sql = new StringBuilder("""
            SELECT d.Id, d.CategoryId, c.Name AS Category, d.Title, d.FileName, d.SizeBytes, d.Tags, d.Note, d.RegisteredAt, d.ReadState,
                   t.Body AS Body, CASE WHEN s.DocId IS NULL THEN 0 ELSE 1 END AS Starred, r.At AS OpenedAt
            FROM LibDocs d JOIN LibCategories c ON c.Id=d.CategoryId LEFT JOIN LibText t ON t.Id=d.Id
            LEFT JOIN LibStars s ON s.DocId=d.Id LEFT JOIN LibRecent r ON r.DocId=d.Id WHERE 1=1""");
        List<Object> args = new ArrayList<>();
        List<String> terms = new ArrayList<>();
        for (String w : Txt.str(q).strip().split("\\s+")) if (!w.isBlank()) terms.add(w);
        String v = Txt.str(view);
        boolean all = "all".equals(scope);
        if ("star".equals(v)) sql.append(" AND s.DocId IS NOT NULL");
        else if ("recent".equals(v)) sql.append(" AND r.DocId IS NOT NULL");
        else if (all) {
            Set<String> arch = new java.util.HashSet<>();
            for (Folder f : flat(folders())) if (f.archive) arch.add(f.id);
            if (!arch.isEmpty()) { sql.append(" AND d.CategoryId NOT IN (").append(String.join(",", java.util.Collections.nCopies(arch.size(), "?"))).append(")"); args.addAll(arch); }
        } else if (!Txt.blank(folderId)) {
            boolean deep = !terms.isEmpty() || !Txt.blank(tag) || !Txt.blank(kind);
            Set<String> ids = deep ? subtree(folderId) : Set.of(folderId);
            sql.append(" AND d.CategoryId IN (").append(String.join(",", java.util.Collections.nCopies(ids.size(), "?"))).append(")");
            args.addAll(ids);
        }
        for (String w : terms) {
            sql.append(" AND (d.Title LIKE ? ESCAPE '\\' OR d.FileName LIKE ? ESCAPE '\\' OR d.Tags LIKE ? ESCAPE '\\' OR d.Note LIKE ? ESCAPE '\\' OR t.Body LIKE ? ESCAPE '\\')");
            for (int i = 0; i < 5; i++) args.add(Txt.like(w));
        }
        sql.append("recent".equals(v) && Txt.blank(sort) ? " ORDER BY r.At DESC" : switch (Txt.str(sort)) {
            case "old" -> " ORDER BY d.RegisteredAt";
            case "title" -> " ORDER BY d.Title COLLATE NOCASE, d.RegisteredAt DESC";
            case "size" -> " ORDER BY d.SizeBytes DESC";
            default -> " ORDER BY d.RegisteredAt DESC";
        });
        List<Map<String, Object>> rows = db.rows(sql.toString(), args.toArray());
        Map<String, Integer> kinds = new LinkedHashMap<>();
        for (String k : KINDS.keySet()) kinds.put(k, 0);
        for (Map<String, Object> d : rows) kinds.merge(kindOf(Txt.str(d.get("FileName"))), 1, Integer::sum);
        List<Map.Entry<String, Integer>> tags = topTags(rows, 14);
        if (KINDS.containsKey(Txt.str(kind))) rows.removeIf(d -> !kindOf(Txt.str(d.get("FileName"))).equals(kind));
        if (!Txt.blank(tag)) rows.removeIf(d -> !splitTags(Txt.str(d.get("Tags"))).contains(tag));
        int pages = Math.max(1, (rows.size() + size - 1) / size);
        int p = Math.max(1, Math.min(page, pages));
        List<Map<String, Object>> items = new ArrayList<>(rows.subList(Math.min(rows.size(), (p - 1) * size), Math.min(rows.size(), p * size)));
        Map<String, String> paths = new java.util.HashMap<>();
        List<Folder> tops = folders();
        for (Folder f : flat(tops)) {
            List<String> names = new ArrayList<>();
            for (Folder x : trail(f.id)) names.add(x.name);
            paths.put(f.id, String.join(" › ", names));
        }
        for (Map<String, Object> d : items) {
            decorate(d, terms);
            d.put("TagList", splitTags(Txt.str(d.get("Tags"))));
            d.put("FolderPath", paths.getOrDefault(Txt.str(d.get("CategoryId")), Txt.str(d.get("Category"))));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("kinds", kinds);
        out.put("tags", tags);
        out.put("total", rows.size());
        out.put("page", p);
        out.put("pages", pages);
        out.put("deep", !terms.isEmpty() || !Txt.blank(tag) || !Txt.blank(kind));
        return out;
    }
}
