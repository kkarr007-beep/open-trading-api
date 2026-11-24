package local.audit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 백업에서 되돌리기. 프로그램이 켜져 있는 동안에는 기록 파일을 바꾸지 않고(화면이 기억한 내용과 어긋나므로),
 * '되돌리기 예약' 표시만 남긴다. 다음에 켤 때 데이터베이스를 열기 전에 바꾼다.
 *  1) 지금 data·files 폴더를 backup\되돌리기전-날짜 폴더로 옮겨 둔다(다시 되돌릴 수 있게).
 *  2) 고른 백업의 data·files를 복사한다.
 *  3) 중간에 실패하면 옮겨 둔 지금 데이터를 제자리로 돌린다.
 * 고를 수 있는 폴더: 이 프로그램이 만든 백업 폴더(감사기록-백업-…), 또는 감사기록 폴더를 통째로 복사해 둔 것(포맷 전 복사본).
 */
public final class Restore {
    private Restore() { }

    public static final String PENDING = "restore-pending.txt";
    public static final String DONE = "restore-done.txt";

    /** 되돌릴 수 있는 폴더인지 확인한다. 문제가 있으면 사유, 괜찮으면 null. */
    public static String check(Path src) {
        if (src == null || !Files.isDirectory(src)) return "폴더가 없습니다. 폴더 위치를 확인하세요.";
        Path dbFile = src.resolve("data").resolve("audit.db");
        if (!Files.isRegularFile(dbFile)) return "이 폴더 안에 data\\audit.db가 없습니다. 백업 폴더(감사기록-백업-…)나 감사기록 폴더를 통째로 복사한 폴더를 고르세요.";
        try (InputStream in = Files.newInputStream(dbFile)) {
            byte[] head = in.readNBytes(16);
            if (!new String(head, StandardCharsets.ISO_8859_1).startsWith("SQLite format 3")) return "data\\audit.db가 이 프로그램의 기록 파일이 아닙니다.";
        } catch (IOException e) {
            return "기록 파일을 읽지 못했습니다: " + e.getMessage();
        }
        return null;
    }

    /** 되돌리기를 예약한다. */
    public static void request(Path home, Path src) throws IOException {
        Files.writeString(home.resolve(PENDING), src.toAbsolutePath().normalize().toString(), StandardCharsets.UTF_8);
    }

    public static String pending(Path home) {
        try { Path p = home.resolve(PENDING); return Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8).strip() : null; }
        catch (IOException e) { return null; }
    }

    public static void cancel(Path home) throws IOException { Files.deleteIfExists(home.resolve(PENDING)); }

    /** 지난번 되돌리기 결과(한 번 보여 주고 지운다). */
    public static String takeResult(Path home) {
        Path p = home.resolve(DONE);
        try {
            if (!Files.isRegularFile(p)) return null;
            String s = Files.readString(p, StandardCharsets.UTF_8);
            Files.deleteIfExists(p);
            return s;
        } catch (IOException e) {
            return null;
        }
    }

    /** 프로그램을 켤 때(데이터베이스를 열기 전) 예약된 되돌리기를 한다. */
    public static void applyPending(Path home) {
        String srcText = pending(home);
        if (srcText == null) return;
        Path src = Path.of(srcText);
        String why = check(src);
        if (why == null && src.toAbsolutePath().normalize().equals(home.toAbsolutePath().normalize())) why = "지금 쓰는 감사기록 폴더 자신은 고를 수 없습니다.";
        if (why != null) { result(home, "되돌리지 못했습니다: " + why); try { cancel(home); } catch (IOException ignored) { } return; }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path keep = home.resolve("backup").resolve("되돌리기전-" + stamp);
        Path data = home.resolve("data"), files = home.resolve("files");
        boolean movedData = false, movedFiles = false;
        try {
            Files.createDirectories(keep);
            if (Files.exists(data)) { Files.move(data, keep.resolve("data")); movedData = true; }
            if (Files.exists(files)) { Files.move(files, keep.resolve("files")); movedFiles = true; }
            copyTree(src.resolve("data"), data);
            if (Files.isDirectory(src.resolve("files"))) copyTree(src.resolve("files"), files); else Files.createDirectories(files);
            // 원본 보기 그림은 옛 자료 기준이므로 지운다(필요하면 다시 만든다)
            deleteTree(home.resolve("originals"));
            cancel(home);
            result(home, "백업에서 되돌렸습니다: " + src + "\n되돌리기 전 기록은 " + keep + " 폴더에 남겨 두었습니다.");
        } catch (IOException | RuntimeException e) {
            // 되돌리지 못했으면 지금 데이터를 제자리로
            try {
                if (movedData) { deleteTree(data); Files.move(keep.resolve("data"), data); }
                if (movedFiles) { deleteTree(files); Files.move(keep.resolve("files"), files); }
            } catch (IOException ignored) { }
            try { cancel(home); } catch (IOException ignored) { }
            result(home, "되돌리는 중 문제가 생겨 원래 기록 그대로 두었습니다: " + e.getMessage());
        }
    }

    static void result(Path home, String text) {
        try { Files.writeString(home.resolve(DONE), text, StandardCharsets.UTF_8); } catch (IOException ignored) { }
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else { Files.createDirectories(t.getParent()); Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING); }
            }
        }
    }

    static void deleteTree(Path d) {
        if (!Files.exists(d)) return;
        try (Stream<Path> w = Files.walk(d)) {
            w.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }
    }
}
