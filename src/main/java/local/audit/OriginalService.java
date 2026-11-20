package local.audit;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 원본 보기: 문서를 원본과 같은 모양으로 쪽마다 그림으로 만들어 둔다.
 * 워드·엑셀·파워포인트·한글은 이 컴퓨터에 설치된 프로그램(한컴오피스·MS 오피스, 없으면 LibreOffice)으로 PDF를 만든 뒤,
 * 프로그램에 들어 있는 PDF 부품(PDFBox)으로 쪽 그림과 '글자 층'(쪽 위 글자 위치)을 만든다. 글자 층 덕분에 원본 보기 안에서도 찾기가 된다.
 * 만든 그림은 등록 당시 원본의 모양이다. 조문을 고쳐도 원본 보기는 바뀌지 않는다.
 */
@Service
public class OriginalService {
    private static final Logger log = LoggerFactory.getLogger(OriginalService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final int MAX_PAGES = 300;
    static final float DPI = 160f;

    private final Paths2 paths;
    private final Db db;
    private final ExecutorService queue = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "original-view"); t.setDaemon(true); return t; });
    private final boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private Path script;

    public OriginalService(Paths2 paths, Db db) {
        this.paths = paths;
        this.db = db;
        db.tx(conn -> {
            try (var st = conn.createStatement()) {
                st.execute("""
                    CREATE TABLE IF NOT EXISTS Originals (Key TEXT PRIMARY KEY, Name TEXT NOT NULL, Ext TEXT NOT NULL, State TEXT NOT NULL,
                      Pages INTEGER NOT NULL DEFAULT 0, Done INTEGER NOT NULL DEFAULT 0, Note TEXT NOT NULL DEFAULT '', Meta TEXT NOT NULL DEFAULT '[]',
                      Via TEXT NOT NULL DEFAULT '', CreatedAt TEXT NOT NULL)""");
            }
            // 만드는 중에 프로그램이 꺼졌으면 다시 만들 수 있게 '실패'로 둔다
            Db.update(conn, "UPDATE Originals SET State='실패', Note='만드는 도중에 프로그램이 꺼졌습니다. 다시 만드세요.' WHERE State='만드는 중'");
            return null;
        });
    }

    public static boolean validKey(String key) { return key != null && key.matches("(doc|rule|att|work)-[A-Za-z0-9_-]{3,80}"); }

    public static boolean supports(String fileName) {
        return Set_EXT.contains(ext(fileName));
    }

    private static final java.util.Set<String> OFFICE = java.util.Set.of(".doc", ".docx", ".rtf", ".odt", ".xls", ".xlsx", ".xlsm", ".ods", ".ppt", ".pptx", ".odp", ".hwp", ".hwpx");
    private static final java.util.Set<String> IMAGES = java.util.Set.of(".png", ".jpg", ".jpeg", ".gif", ".bmp");
    private static final java.util.Set<String> Set_EXT = union();
    private static java.util.Set<String> union() { java.util.Set<String> s = new java.util.HashSet<>(OFFICE); s.addAll(IMAGES); s.add(".pdf"); return s; }

    static String ext(String name) { return Txt.ext(Txt.str(name)).toLowerCase(Locale.ROOT); }

    private Path dir(String key) { return paths.home.resolve("originals").resolve(key); }

    public Map<String, Object> info(String key) {
        if (!validKey(key)) return null;
        return db.row("SELECT * FROM Originals WHERE Key=?", key);
    }

    public Path page(String key, int n) {
        Path p = dir(key).resolve("p" + n + ".png");
        return validKey(key) && Files.isRegularFile(p) ? p : null;
    }

    public Path pdf(String key) {
        Path p = dir(key).resolve("view.pdf");
        return validKey(key) && Files.isRegularFile(p) ? p : null;
    }

    public Path source(String key) {
        if (!validKey(key)) return null;
        Map<String, Object> r = info(key);
        if (r == null) return null;
        Path p = dir(key).resolve("source" + Txt.str(r.get("Ext")));
        return Files.isRegularFile(p) ? p : null;
    }

    /** 쪽마다 글자 줄과 위치(쪽 크기에 대한 비율). 원본 보기의 찾기에 쓴다. */
    public String textJson(String key) throws IOException {
        Path p = dir(key).resolve("text.json");
        return validKey(key) && Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8) : "[]";
    }

    // ------------------------------------------------------------------ 맡기기

    /** 파일을 복사해 두고 원본 보기를 만들기 시작한다(뒤에서). */
    public Op request(String key, Path file, String fileName) {
        if (!validKey(key)) return Op.bad("잘못된 원본 키입니다.");
        if (file == null || !Files.isRegularFile(file)) return Op.fail(404, "원본 파일이 없습니다.");
        String ext = ext(fileName);
        if (!Set_EXT.contains(ext)) return Op.bad("이 형식(" + ext + ")은 원본 보기를 만들 수 없습니다. 한글·워드·엑셀·파워포인트·PDF·그림 파일만 됩니다.");
        try {
            Path d = dir(key);
            deleteTree(d);
            Files.createDirectories(d);
            Files.copy(file, d.resolve("source" + ext), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            return Op.fail(500, "원본 파일을 복사하지 못했습니다: " + e.getMessage());
        }
        db.tx(conn -> {
            Db.update(conn, "DELETE FROM Originals WHERE Key=?", key);
            Db.update(conn, "INSERT INTO Originals(Key, Name, Ext, State, CreatedAt) VALUES(?,?,?,'만드는 중',?)", key, FileStore.safeName(fileName), ext, Kst.stamp());
            return null;
        });
        queue.submit(() -> build(key));
        return Op.ok("원본 보기를 만들고 있습니다. 잠시 뒤 쪽이 나타납니다.");
    }

    /** 이미 복사해 둔 원본으로 다시 만든다. */
    public Op remake(String key) {
        Path src = source(key);
        Map<String, Object> r = info(key);
        if (src == null || r == null) return Op.fail(404, "다시 만들 원본 파일이 없습니다.");
        try {
            Path tmp = Files.createTempFile(paths.tmp, "orig", Txt.str(r.get("Ext")));
            Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING);
            try { return request(key, tmp, Txt.str(r.get("Name"))); } finally { Files.deleteIfExists(tmp); }
        } catch (IOException e) {
            return Op.fail(500, e.getMessage());
        }
    }

    public void delete(String key) {
        if (!validKey(key)) return;
        db.tx(conn -> Db.update(conn, "DELETE FROM Originals WHERE Key=?", key));
        deleteTree(dir(key));
    }

    /** 사규 등록 화면: 읽은 파일을 잠시 맡겨 두고(표), 저장할 때 그 사규에 붙인다. 하루 지난 것은 지운다. */
    public String stash(Path file, String fileName) {
        try {
            Path d = paths.tmp.resolve("orig-stash");
            Files.createDirectories(d);
            try (var old = Files.list(d)) {
                long cut = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1);
                old.filter(x -> { try { return Files.getLastModifiedTime(x).toMillis() < cut; } catch (IOException e) { return false; } })
                    .forEach(x -> { try { Files.deleteIfExists(x); } catch (IOException ignored) { } });
            }
            String token = Txt.newId();
            Files.copy(file, d.resolve(token + "__" + Txt.newId().substring(0, 4) + ext(fileName)));
            Files.writeString(d.resolve(token + ".name"), FileStore.safeName(fileName), StandardCharsets.UTF_8);
            return token;
        } catch (IOException e) {
            log.warn("원본 맡기기 실패: {}", e.getMessage());
            return "";
        }
    }

    public Op adopt(String token, String key) {
        if (!Txt.isId(token)) return Op.bad("맡긴 원본이 없습니다.");
        Path d = paths.tmp.resolve("orig-stash");
        try (var files = Files.list(d)) {
            Path f = files.filter(x -> x.getFileName().toString().startsWith(token + "__")).findFirst().orElse(null);
            Path nameFile = d.resolve(token + ".name");
            if (f == null || !Files.isRegularFile(nameFile)) return Op.fail(404, "맡긴 원본이 없습니다(하루가 지났을 수 있습니다).");
            String name = Files.readString(nameFile, StandardCharsets.UTF_8);
            Op op = request(key, f, name);
            Files.deleteIfExists(f);
            Files.deleteIfExists(nameFile);
            return op;
        } catch (IOException e) {
            return Op.fail(500, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 만들기

    void build(String key) {
        Map<String, Object> r = info(key);
        if (r == null) return;
        String ext = Txt.str(r.get("Ext"));
        Path d = dir(key), src = d.resolve("source" + ext);
        try {
            if (IMAGES.contains(ext)) { image(key, src); return; }
            String[] via = new String[1];
            Path pdf = ext.equals(".pdf") ? src : toPdf(src, ext, d, via);
            if (!ext.equals(".pdf")) Files.move(pdf, d.resolve("view.pdf"), StandardCopyOption.REPLACE_EXISTING);
            else Files.copy(src, d.resolve("view.pdf"), StandardCopyOption.REPLACE_EXISTING);
            render(key, d.resolve("view.pdf"), via[0] == null ? "PDF" : via[0]);
        } catch (Exception e) {
            log.info("원본 보기 실패 {}: {}", key, e.getMessage());
            db.tx(conn -> Db.update(conn, "UPDATE Originals SET State='실패', Note=? WHERE Key=?", Txt.fit(e.getMessage(), 300, false), key));
        }
    }

    private void image(String key, Path src) throws IOException {
        BufferedImage img = ImageIO.read(src.toFile());
        if (img == null) throw new IOException("그림을 읽지 못했습니다.");
        ImageIO.write(img, "png", dir(key).resolve("p1.png").toFile());
        Files.writeString(dir(key).resolve("text.json"), "[[]]", StandardCharsets.UTF_8);
        String meta = JSON.writeValueAsString(List.of(List.of(img.getWidth(), img.getHeight())));
        db.tx(conn -> Db.update(conn, "UPDATE Originals SET State='완료', Pages=1, Done=1, Meta=?, Via='그림', Note='' WHERE Key=?", meta, key));
    }

    private void render(String key, Path pdf, String via) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            int pages = Math.min(doc.getNumberOfPages(), MAX_PAGES);
            String note = doc.getNumberOfPages() > MAX_PAGES ? "쪽이 많아 앞 " + MAX_PAGES + "쪽만 만들었습니다." : "";
            db.tx(conn -> Db.update(conn, "UPDATE Originals SET Pages=?, Via=? WHERE Key=?", pages, via, key));
            PDFRenderer renderer = new PDFRenderer(doc);
            List<List<Object>> meta = new ArrayList<>();
            List<List<Map<String, Object>>> text = new ArrayList<>();
            for (int i = 0; i < pages; i++) {
                PDPage pg = doc.getPage(i);
                PDRectangle box = pg.getCropBox();
                boolean turned = pg.getRotation() % 180 != 0;
                float w = turned ? box.getHeight() : box.getWidth(), h = turned ? box.getWidth() : box.getHeight();
                BufferedImage img = renderer.renderImageWithDPI(i, DPI, ImageType.RGB);
                ImageIO.write(img, "png", dir(key).resolve("p" + (i + 1) + ".png").toFile());
                meta.add(List.of(Math.round(w), Math.round(h)));
                text.add(lines(doc, i, w, h));
                int done = i + 1;
                db.tx(conn -> Db.update(conn, "UPDATE Originals SET Done=? WHERE Key=?", done, key));
            }
            Files.writeString(dir(key).resolve("text.json"), JSON.writeValueAsString(text), StandardCharsets.UTF_8);
            String m = JSON.writeValueAsString(meta);
            db.tx(conn -> Db.update(conn, "UPDATE Originals SET State='완료', Meta=?, Note=? WHERE Key=?", m, note, key));
        }
    }

    /** 한 쪽의 글자 줄과 위치(쪽 너비·높이에 대한 % 값). */
    static List<Map<String, Object>> lines(PDDocument doc, int index, float pw, float ph) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        PDFTextStripper st = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> ps) {
                if (ps == null || ps.isEmpty() || text == null || text.isBlank()) return;
                float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = 0, y1 = 0;
                for (TextPosition t : ps) {
                    float x = t.getXDirAdj(), y = t.getYDirAdj(), hh = t.getHeightDir(), ww = t.getWidthDirAdj();
                    x0 = Math.min(x0, x); x1 = Math.max(x1, x + ww); y0 = Math.min(y0, y - hh); y1 = Math.max(y1, y);
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("x", pct(x0, pw)); m.put("y", pct(y0, ph)); m.put("w", pct(x1 - x0, pw)); m.put("h", pct(Math.max(1f, y1 - y0) * 1.15f, ph));
                m.put("t", text);
                out.add(m);
            }
        };
        st.setSortByPosition(true);
        st.setStartPage(index + 1);
        st.setEndPage(index + 1);
        st.getText(doc);
        return out;
    }

    private static double pct(float v, float total) { return total <= 0 ? 0 : Math.round(v / total * 100000.0) / 1000.0; }

    /** PC 프로그램으로 PDF를 만든다. 한글 문서는 한컴오피스, 오피스 문서는 MS 오피스를 먼저 쓰고, 안 되면 LibreOffice. */
    private Path toPdf(Path src, String ext, Path dir, String[] via) throws IOException, InterruptedException {
        if (!OFFICE.contains(ext)) throw new IOException("이 형식은 원본 보기를 만들 수 없습니다.");
        List<String> tried = new ArrayList<>();
        if (windows) {
            Path out = dir.resolve("office.pdf");
            String why = officePdf(src, ext, out);
            if (why == null && Files.isRegularFile(out)) { via[0] = ext.startsWith(".hw") ? "한글" : "MS 오피스"; return out; }
            tried.add(why);
        }
        Path soffice = sofficePath();
        if (soffice != null) {
            Path outDir = Files.createDirectories(dir.resolve("lo"));
            Process p = new ProcessBuilder(List.of(soffice.toString(), "--headless", "--norestore", "--convert-to", "pdf", "--outdir", outDir.toString(), src.toString()))
                .redirectErrorStream(true).start();
            p.getOutputStream().close();
            try (InputStream in = p.getInputStream()) { in.readAllBytes(); }
            if (!p.waitFor(180, TimeUnit.SECONDS)) { p.destroyForcibly(); tried.add("LibreOffice가 제시간에 끝나지 않았습니다."); }
            Path made = outDir.resolve("source.pdf");
            if (Files.isRegularFile(made)) { via[0] = "LibreOffice"; return made; }
            tried.add("LibreOffice가 PDF를 만들지 못했습니다.");
        }
        if (tried.isEmpty()) throw new IOException("이 컴퓨터에서 쓸 수 있는 한글·MS 오피스·LibreOffice가 없어 원본 모양을 만들 수 없습니다. '내려받기'로 원본을 여세요.");
        throw new IOException(String.join(" / ", tried.stream().filter(java.util.Objects::nonNull).toList()));
    }

    private String officePdf(Path src, String ext, Path out) {
        try {
            Path ps1 = script();
            Process process = new ProcessBuilder(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", ps1.toString(),
                "-Path", src.toAbsolutePath().toString(), "-Out", out.toAbsolutePath().toString(), "-Ext", ext)).redirectErrorStream(true).start();
            process.getOutputStream().close();
            byte[] console;
            try (InputStream in = process.getInputStream()) { console = in.readAllBytes(); }
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return "사무용 프로그램이 제시간에 응답하지 않았습니다.";
            }
            if (process.exitValue() == 0) return null;
            String msg = new String(console, StandardCharsets.UTF_8).strip();
            String[] ls = msg.split("\\R");
            return ls.length == 0 || ls[ls.length - 1].isBlank() ? "사무용 프로그램으로 PDF를 만들지 못했습니다." : ls[ls.length - 1].strip();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return "사무용 프로그램을 실행하지 못했습니다: " + e.getMessage();
        }
    }

    private synchronized Path script() throws IOException {
        if (script != null && Files.isRegularFile(script)) return script;
        Path target = paths.tmp.resolve("export-pdf.ps1");
        try (InputStream in = OriginalService.class.getResourceAsStream("/scripts/export-pdf.ps1")) {
            if (in == null) throw new IOException("export-pdf.ps1 없음");
            byte[] body = in.readAllBytes();
            byte[] withBom = new byte[body.length + 3];
            withBom[0] = (byte) 0xEF; withBom[1] = (byte) 0xBB; withBom[2] = (byte) 0xBF;
            System.arraycopy(body, 0, withBom, 3, body.length);
            Files.write(target, withBom);
        }
        script = target;
        return target;
    }

    /** LibreOffice 실행 파일. 기본 설치 위치와 PATH를 본다. */
    static Path sofficePath() {
        List<String> cands = new ArrayList<>(List.of(
            "C:\\Program Files\\LibreOffice\\program\\soffice.exe", "C:\\Program Files (x86)\\LibreOffice\\program\\soffice.exe",
            "/usr/bin/soffice", "/usr/bin/libreoffice", "/usr/local/bin/soffice", "/Applications/LibreOffice.app/Contents/MacOS/soffice"));
        for (String c : cands) { Path p = Path.of(c); if (Files.isExecutable(p)) return p; }
        String path = System.getenv("PATH");
        if (path != null) for (String d : path.split(File.pathSeparator)) {
            for (String n : List.of("soffice", "soffice.exe", "libreoffice")) { Path p = Path.of(d, n); if (Files.isExecutable(p)) return p; }
        }
        return null;
    }

    private static void deleteTree(Path d) {
        if (!Files.exists(d)) return;
        try (var w = Files.walk(d)) {
            w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }
    }
}
