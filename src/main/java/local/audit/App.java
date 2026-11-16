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
    /** 자체 창이 떠 있는지. 화면 엔진이 없는 환경에서는 Desktop 클래스를 건드리지 않도록 여기에 둔다. */
    static volatile boolean desktopActive;
    static volatile java.util.function.BooleanSupplier showWindow = () -> false;

    /** 이미 켜져 있을 때 창을 앞으로 가져온다. 창이 없으면 false. */
    public static boolean bringToFront() { return desktopActive && showWindow.getAsBoolean(); }

    private final Environment env;
    private final Paths2 paths;
    private final ConfigurableApplicationContext context;

    public App(Environment env, Paths2 paths, ConfigurableApplicationContext context) {
        this.env = env;
        this.paths = paths;
        this.context = context;
    }

    public static void main(String[] args) {
        String port = "47321", mode = "app";
        Path home = Path.of("var");
        for (String a : args) {
            if (a.startsWith("--server.port=")) port = a.substring(14);
            else if (a.startsWith("--audit.window=")) mode = a.substring(15);
            else if (a.startsWith("--audit.open-browser=false")) mode = "none";
            else if (a.startsWith("--audit.home=") && a.length() > 13) home = Path.of(a.substring(13));
            else if (a.startsWith("--audit.snapshot=")) System.setProperty("audit.snapshot", a.substring(17));
            else if (a.startsWith("--audit.snapshot-js=")) System.setProperty("audit.snapshot-js", a.substring(20));
            else if (a.startsWith("--audit.snapshot-url=")) System.setProperty("audit.snapshot-url", a.substring(21));
        }
        home = home.toAbsolutePath().normalize();
        String url = "http://127.0.0.1:" + port;
        String running = ping(url + "/ping?show=1");
        if (running.startsWith("audit-record")) {
            // 이미 켜져 있다. 자체 창이 있으면 앞으로 나왔고, 없으면 화면만 다시 띄운다.
            if (!running.contains("shown") && !"none".equals(mode) && openWindow(url, home.resolve("window"), null) == null) openBrowser(url);
            return;
        }
        if ("app".equals(mode)) {
            try {
                if (Desktop.launch(args, url, home)) return;
            } catch (Throwable noEngine) {
                // 화면 엔진이 함께 있지 않으면 Edge·Chrome 창이나 기본 브라우저로 연다.
            }
        }
        SpringApplication.run(App.class, args);
    }

    static String ping(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection(java.net.Proxy.NO_PROXY);
            c.setConnectTimeout(700);
            c.setReadTimeout(2000);
            try (var in = c.getInputStream()) {
                return new String(in.readAllBytes());
            }
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        String url = "http://127.0.0.1:" + env.getProperty("local.server.port", env.getProperty("server.port", "47321"));
        System.out.println("감사기록이 켜졌습니다: " + url);
        if (desktopActive) return; // 자체 창이 화면을 맡는다
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
