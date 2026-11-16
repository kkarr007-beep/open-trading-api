package local.audit;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 감사기록 - 한 사람이 자기 컴퓨터에서 쓰는 프로그램.
 * 아이콘을 누르면 전용 창이 뜨고, 창을 닫으면 프로그램도 끝난다.
 */
@SpringBootApplication
@EnableScheduling
public class App {
    private final Environment env;
    private final Paths2 paths;
    private final ConfigurableApplicationContext context;

    public App(Environment env, Paths2 paths, ConfigurableApplicationContext context) {
        this.env = env;
        this.paths = paths;
        this.context = context;
    }

    public static void main(String[] args) {
        String port = "47321";
        for (String a : args) if (a.startsWith("--server.port=")) port = a.substring("--server.port=".length());
        String url = "http://127.0.0.1:" + port;
        if (alreadyRunning(url)) {
            // 이미 켜져 있으면 창만 하나 더 띄우고 끝낸다.
            Path home = null;
            for (String a : args) if (a.startsWith("--audit.home=") && a.length() > 13) home = Path.of(a.substring(13));
            Process window = openWindow(url, (home == null ? Path.of("var") : home).toAbsolutePath().resolve("window"), null);
            if (window == null) openBrowser(url);
            return;
        }
        SpringApplication.run(App.class, args);
    }

    static boolean alreadyRunning(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(url + "/ping").toURL().openConnection(java.net.Proxy.NO_PROXY);
            c.setConnectTimeout(700);
            c.setReadTimeout(1500);
            try (var in = c.getInputStream()) {
                return new String(in.readAllBytes()).startsWith("audit-record");
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        String url = "http://127.0.0.1:" + env.getProperty("local.server.port", env.getProperty("server.port", "47321"));
        System.out.println("감사기록이 켜졌습니다: " + url);
        String mode = env.getProperty("audit.window", "app");
        if ("false".equalsIgnoreCase(env.getProperty("audit.open-browser", "true")) || "none".equals(mode)) return;
        Process window = "app".equals(mode) ? openWindow(url, paths.home.resolve("window"), env.getProperty("audit.browser-path", "")) : null;
        if (window == null) {
            // 전용 창을 띄울 프로그램이 없으면 기본 브라우저로 연다. 끝낼 때는 관리 화면의 '프로그램 끝내기'를 쓴다.
            openBrowser(url);
            return;
        }
        Thread watcher = new Thread(() -> {
            try {
                long started = System.currentTimeMillis();
                window.waitFor();
                // 창을 띄운 프로그램이 곧바로 끝났다면 다른 프로세스에 넘긴 것이다. 그때는 끝내지 않는다.
                if (System.currentTimeMillis() - started > 5000) quit(context);
            } catch (InterruptedException ignored) { }
        }, "window-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    public static void quit(ConfigurableApplicationContext context) {
        new Thread(() -> {
            try { Thread.sleep(400); } catch (InterruptedException ignored) { }
            System.exit(SpringApplication.exit(context));
        }, "quit").start();
    }

    /** Edge나 Chrome을 주소 줄과 탭이 없는 전용 창으로 띄운다. 이 프로그램만의 사용자 폴더를 써서 평소 쓰는 브라우저와 섞이지 않는다. */
    static Process openWindow(String url, Path profile, String configured) {
        Path exe = findBrowser(configured);
        if (exe == null) return null;
        try {
            Files.createDirectories(profile);
            return new ProcessBuilder(exe.toString(), "--app=" + url, "--user-data-dir=" + profile, "--no-first-run",
                "--no-default-browser-check", "--disable-background-mode", "--disable-sync", "--disable-extensions", "--window-size=1440,920")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static Path findBrowser(String configured) {
        List<String> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) candidates.add(configured.strip());
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            for (String root : new String[] { System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"), System.getenv("LOCALAPPDATA") }) {
                if (root == null) continue;
                candidates.add(root + "\\Microsoft\\Edge\\Application\\msedge.exe");
                candidates.add(root + "\\Google\\Chrome\\Application\\chrome.exe");
                candidates.add(root + "\\Naver\\Naver Whale\\Application\\whale.exe");
            }
        } else if (os.contains("mac")) {
            candidates.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            candidates.add("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
        } else {
            for (String name : new String[] { "google-chrome", "chromium", "chromium-browser", "microsoft-edge" }) candidates.add("/usr/bin/" + name);
        }
        for (String c : candidates) {
            try {
                Path p = Path.of(c);
                if (Files.isRegularFile(p)) return p;
            } catch (RuntimeException ignored) { }
        }
        return null;
    }

    public static void openBrowser(String url) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            else if (os.contains("mac")) new ProcessBuilder("open", url).start();
        } catch (IOException ignored) {
            // 열지 못해도 프로그램은 계속 돈다.
        }
    }
}
