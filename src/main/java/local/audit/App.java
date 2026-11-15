package local.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 감사기록 - 한 사람이 자기 컴퓨터에서 쓰는 프로그램. */
@SpringBootApplication
@EnableScheduling
public class App {
    private final Environment env;

    public App(Environment env) {
        this.env = env;
    }

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        String url = "http://127.0.0.1:" + env.getProperty("local.server.port", env.getProperty("server.port", "47321"));
        System.out.println("감사기록이 켜졌습니다. 브라우저 주소: " + url);
        if (!"true".equalsIgnoreCase(env.getProperty("audit.open-browser", "true"))) return;
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            else if (os.contains("mac")) new ProcessBuilder("open", url).start();
        } catch (Exception ignored) {
            // 브라우저를 열지 못해도 프로그램은 계속 돕니다.
        }
    }
}
