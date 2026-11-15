package local.audit.extract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import local.audit.Paths2;
import local.audit.Txt;

/**
 * 이 컴퓨터에 설치된 워드·엑셀·파워포인트·한글에게 문서를 읽기 전용으로 열게 하고 글자만 받아 온다.
 * 문서보안(DRM) 암호는 그 프로그램과 보안 에이전트가 푼다. 이 프로그램은 암호를 다루지 않는다.
 * Windows에서만 동작한다. 한 번에 한 문서만 처리한다.
 */
@Component
public class OfficeBridge {
    private static final Logger log = LoggerFactory.getLogger(OfficeBridge.class);
    private static final int TIMEOUT_SECONDS = 120;

    public record Outcome(boolean ok, String app, String text, String error) { }

    private final Paths2 paths;
    private final ReentrantLock lock = new ReentrantLock();
    private final boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
    private Path script;

    public OfficeBridge(Paths2 paths) { this.paths = paths; }

    public boolean available() { return windows; }

    public boolean supports(String ext) { return windows && !appFor(ext).isEmpty(); }

    static String appFor(String ext) {
        return switch (ext) {
            case ".doc", ".docx", ".rtf" -> "워드";
            case ".xls", ".xlsx" -> "엑셀";
            case ".ppt", ".pptx" -> "파워포인트";
            case ".hwp", ".hwpx" -> "한글";
            default -> "";
        };
    }

    public String unavailableReason(String ext) {
        if (!windows) return "Windows가 아니어서 사무용 프로그램으로 열어 읽는 기능을 쓸 수 없습니다.";
        if (appFor(ext).isEmpty()) return "이 형식(" + ext + ")은 자동으로 열어 읽지 못합니다. 원문 위치에서 직접 여세요.";
        return "설정에서 '사무용 프로그램으로 열어 읽기'가 꺼져 있습니다.";
    }

    public Outcome read(Path file, String ext) {
        String app = appFor(ext);
        if (!windows || app.isEmpty()) return new Outcome(false, app, "", unavailableReason(ext));
        lock.lock();
        Path out = paths.tmp.resolve("read-" + Txt.newId() + ".txt");
        try {
            Path ps1 = script();
            Process process = new ProcessBuilder(List.of(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", ps1.toString(),
                "-Path", file.toAbsolutePath().toString(), "-Out", out.toString(), "-Ext", ext))
                .redirectErrorStream(true).start();
            process.getOutputStream().close();
            byte[] console;
            Thread killer = new Thread(() -> {
                try {
                    if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        process.descendants().forEach(ProcessHandle::destroyForcibly);
                        process.destroyForcibly();
                    }
                } catch (InterruptedException ignored) { }
            });
            killer.setDaemon(true);
            killer.start();
            try (InputStream in = process.getInputStream()) {
                console = in.readAllBytes();
            }
            boolean done = process.waitFor(5, TimeUnit.SECONDS);
            int code = done ? process.exitValue() : -1;
            String message = new String(console, StandardCharsets.UTF_8).strip();
            if (code == 0 && Files.isRegularFile(out)) {
                return new Outcome(true, app, TextReader.decode(Files.readAllBytes(out)), "");
            }
            log.info("{} 자동 열기 실패({}): {}", app, code, message);
            String why = code == -1 ? app + "이(가) 제시간에 응답하지 않았습니다. 포털 로그인 창이나 안내 창이 떠 있는지 확인하세요."
                : message.isEmpty() ? app + "(으)로 열지 못했습니다." : lastLine(message);
            return new Outcome(false, app, "", why);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Outcome(false, app, "", app + " 실행에 실패했습니다: " + e.getMessage());
        } finally {
            try { Files.deleteIfExists(out); } catch (IOException ignored) { }
            lock.unlock();
        }
    }

    private static String lastLine(String message) {
        String[] lines = message.split("\\R");
        String last = lines[lines.length - 1].strip();
        return last.length() > 200 ? last.substring(0, 200) : last;
    }

    private synchronized Path script() throws IOException {
        if (script != null && Files.isRegularFile(script)) return script;
        Path target = paths.tmp.resolve("read-document.ps1");
        try (InputStream in = OfficeBridge.class.getResourceAsStream("/scripts/read-document.ps1")) {
            if (in == null) throw new IOException("read-document.ps1 없음");
            // PowerShell 5는 BOM이 있어야 UTF-8로 읽는다.
            byte[] body = in.readAllBytes();
            byte[] withBom = new byte[body.length + 3];
            withBom[0] = (byte) 0xEF; withBom[1] = (byte) 0xBB; withBom[2] = (byte) 0xBF;
            System.arraycopy(body, 0, withBom, 3, body.length);
            Path temp = Files.createTempFile(paths.tmp, "ps", ".tmp");
            Files.write(temp, withBom);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        script = target;
        return target;
    }
}
