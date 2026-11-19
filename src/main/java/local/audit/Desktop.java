package local.audit;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.print.PrinterJob;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * 프로그램 자체 창. 화면 엔진(JavaFX WebView)을 프로그램 안에 넣어 Edge나 Chrome 없이 뜬다.
 * 창을 닫으면 프로그램이 끝난다. 저장 창, 인쇄, 복사, 파일 끌어다 놓기는 여기서 맡는다.
 */
public final class Desktop {
    private static volatile Stage stage;
    private static volatile ConfigurableApplicationContext context;
    private static WebView view;
    private static WebEngine engine;
    private static final java.util.List<Bridge> POPUP_BRIDGES = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static Bridge bridge;
    private static Path stateFile;
    private static String base;
    private static String pendingToast;
    private static VBox splash;
    private static Label splashNote;

    private Desktop() { }

    /** 창을 띄우고 뒤에서 프로그램 본체를 켠다. 화면 엔진을 쓸 수 없으면 false. */
    static boolean launch(String[] args, String url, Path home) {
        try {
            base = url;
            stateFile = home.resolve("window").resolve("window.properties");
            Files.createDirectories(stateFile.getParent());
            Platform.setImplicitExit(false);
            Platform.startup(() -> {
                try {
                    build(args, home);
                } catch (Throwable t) {
                    t.printStackTrace();
                    fallback(args);
                }
            });
            App.desktopActive = true;
            App.showWindow = Desktop::show;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 창을 만들다 실패하면 창 없이 켜고 브라우저로 연다. */
    private static void fallback(String[] args) {
        App.desktopActive = false;
        new Thread(() -> SpringApplication.run(App.class, args), "main-fallback").start();
    }

    /** 새 창. 큰 표처럼 따로 펼쳐 보는 화면을 띄운다. 창을 닫아도 본 창은 그대로다. */
    private static WebEngine popup(Stage owner) {
        Stage p = new Stage();
        p.getIcons().addAll(owner.getIcons());
        WebView v = new WebView();
        v.setContextMenuEnabled(false);
        WebEngine e = v.getEngine();
        // 화면 스크립트가 쥔 자바 객체는 약하게 잡히므로, 창이 열려 있는 동안 여기서 붙잡아 둔다.
        Bridge pb = new Bridge(e);
        POPUP_BRIDGES.add(pb);
        p.setOnHidden(ev -> POPUP_BRIDGES.remove(pb));
        p.titleProperty().bind(e.titleProperty());
        e.setConfirmHandler(message -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
            alert.initOwner(p);
            alert.setHeaderText(null);
            return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
        });
        e.setOnAlert(ev -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, ev.getData(), ButtonType.OK);
            alert.initOwner(p);
            alert.setHeaderText(null);
            alert.showAndWait();
        });
        e.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == Worker.State.SUCCEEDED) {
                try {
                    Object window = e.executeScript("window");
                    Class.forName("netscape.javascript.JSObject").getMethod("setMember", String.class, Object.class).invoke(window, "auditApp", pb);
                    e.executeScript("document.documentElement.classList.add('in-app','in-popup'); window.dispatchEvent(new Event('audit-app-ready'));");
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    ex.printStackTrace();
                }
            }
        });
        e.locationProperty().addListener((obs, old, now) -> {
            if (now != null && !now.isEmpty() && !now.startsWith(base) && !now.startsWith("about:")) Platform.runLater(() -> e.load(old));
        });
        e.setCreatePopupHandler(features -> popup(owner));
        v.setZoom(view.getZoom());
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        p.setScene(new Scene(v, Math.min(1320, screen.getWidth() - 40), Math.min(880, screen.getHeight() - 40)));
        p.show();
        return e;
    }

    private static void build(String[] args, Path home) {
        Stage s = new Stage();
        s.setTitle("경영진단 Portal");
        for (String icon : new String[] { "/static/icons/app.png", "/static/icons/app-256.png" }) {
            try (InputStream in = Desktop.class.getResourceAsStream(icon)) {
                if (in != null) s.getIcons().add(new Image(in));
            } catch (Exception ignored) { }
        }
        view = new WebView();
        view.setContextMenuEnabled(false);
        engine = view.getEngine();
        engine.setUserDataDirectory(home.resolve("window").resolve("web").toFile());
        engine.setConfirmHandler(message -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
            alert.initOwner(s);
            alert.setHeaderText(null);
            alert.setTitle("경영진단 Portal");
            return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
        });
        engine.setOnAlert(e -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, e.getData(), ButtonType.OK);
            alert.initOwner(s);
            alert.setHeaderText(null);
            alert.setTitle("경영진단 Portal");
            alert.showAndWait();
        });
        bridge = new Bridge();
        engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == Worker.State.SUCCEEDED) {
                try {
                    Object window = engine.executeScript("window");
                    Class.forName("netscape.javascript.JSObject").getMethod("setMember", String.class, Object.class).invoke(window, "auditApp", bridge);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    e.printStackTrace();
                }
                engine.executeScript("document.documentElement.classList.add('in-app'); window.dispatchEvent(new Event('audit-app-ready'));");
                hideSplash();
                if (pendingToast != null) {
                    toast(pendingToast);
                    pendingToast = null;
                }
            }
        });
        // 새 창으로 여는 링크(target=_blank): 같은 프로그램 화면을 따로 띄운 창에서 연다.
        engine.setCreatePopupHandler(features -> popup(s));
        // 이 프로그램 화면이 아닌 주소로는 가지 않는다.
        engine.locationProperty().addListener((obs, old, now) -> {
            if (now != null && !now.isEmpty() && !now.startsWith(base) && !now.startsWith("about:")) {
                Platform.runLater(() -> engine.load(old == null || old.isEmpty() ? base + "/todo" : old));
            }
        });

        ImageView logo = new ImageView();
        try (InputStream in = Desktop.class.getResourceAsStream("/static/icons/app-256.png")) {
            if (in != null) logo.setImage(new Image(in, 96, 96, true, true));
        } catch (Exception ignored) { }
        Label name = new Label("경영진단 Portal");
        name.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: #1d1d1f;");
        splashNote = new Label("여는 중…");
        splashNote.setStyle("-fx-font-size: 13px; -fx-text-fill: #6e6e73;");
        splashNote.setWrapText(true);
        splashNote.setMaxWidth(520);
        ProgressBar bar = new ProgressBar();
        bar.setPrefWidth(180);
        bar.setPrefHeight(6);
        splash = new VBox(14, logo, name, bar, splashNote);
        splash.setAlignment(Pos.CENTER);
        splash.setStyle("-fx-background-color: #f5f5f7;");
        StackPane root = new StackPane(view, splash);

        Properties saved = loadState();
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double w = Math.min(num(saved, "w", 1440), screen.getWidth()), h = Math.min(num(saved, "h", 920), screen.getHeight());
        Scene scene = new Scene(root, w, h);
        s.setScene(scene);
        s.setMinWidth(900);
        s.setMinHeight(600);
        double x = num(saved, "x", Double.NaN), y = num(saved, "y", Double.NaN);
        if (!Double.isNaN(x) && !Double.isNaN(y) && !Screen.getScreensForRectangle(x, y, w, h).isEmpty()) { s.setX(x); s.setY(y); }
        if ("1".equals(saved.getProperty("max"))) s.setMaximized(true);
        view.setZoom(Math.max(0.6, Math.min(2.0, num(saved, "zoom", 1.0))));

        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            boolean ctrl = e.isShortcutDown();
            if (e.getCode() == KeyCode.F5 || (ctrl && e.getCode() == KeyCode.R)) { engine.reload(); e.consume(); }
            else if (e.isAltDown() && e.getCode() == KeyCode.LEFT) { history(-1); e.consume(); }
            else if (e.isAltDown() && e.getCode() == KeyCode.RIGHT) { history(1); e.consume(); }
            else if (ctrl && (e.getCode() == KeyCode.EQUALS || e.getCode() == KeyCode.PLUS || e.getCode() == KeyCode.ADD)) { view.setZoom(Math.min(2.0, view.getZoom() + 0.1)); e.consume(); }
            else if (ctrl && (e.getCode() == KeyCode.MINUS || e.getCode() == KeyCode.SUBTRACT)) { view.setZoom(Math.max(0.6, view.getZoom() - 0.1)); e.consume(); }
            else if (ctrl && (e.getCode() == KeyCode.DIGIT0 || e.getCode() == KeyCode.NUMPAD0)) { view.setZoom(1.0); e.consume(); }
            else if (ctrl && e.getCode() == KeyCode.K) { engine.executeScript("var b=document.querySelector('[data-global-search]'); if(b){b.focus();b.select();}"); e.consume(); }
        });
        // 창 어디에든 파일을 끌어다 놓으면 받는다.
        root.addEventFilter(DragEvent.DRAG_OVER, e -> {
            if (e.getDragboard().hasFiles()) { e.acceptTransferModes(TransferMode.COPY); e.consume(); }
        });
        root.addEventFilter(DragEvent.DRAG_DROPPED, e -> {
            if (!e.getDragboard().hasFiles()) return;
            List<File> dropped = new ArrayList<>(e.getDragboard().getFiles());
            e.setDropCompleted(true);
            e.consume();
            receive(dropped);
        });
        s.setOnCloseRequest(e -> {
            saveState(s);
            s.hide();
            ConfigurableApplicationContext c = context;
            if (c != null) App.quit(c); else System.exit(0);
        });
        stage = s;
        s.show();

        Thread boot = new Thread(() -> {
            try {
                context = SpringApplication.run(App.class, args);
                String first = System.getProperty("audit.snapshot-url", "/todo");
                Platform.runLater(() -> engine.load(base + first));
            } catch (Throwable t) {
                Throwable root0 = t;
                while (root0.getCause() != null) root0 = root0.getCause();
                String why = String.valueOf(root0.getMessage());
                Platform.runLater(() -> {
                    bar.setVisible(false);
                    splashNote.setText(why.contains("in use") || why.contains("BindException") || why.toLowerCase().contains("address already")
                        ? "프로그램이 쓰는 통로(포트)를 다른 프로그램이 쓰고 있어 열지 못했습니다. 컴퓨터를 다시 켠 뒤 실행해 보세요."
                        : "프로그램을 열지 못했습니다: " + why);
                });
            }
        }, "main-boot");
        boot.start();
    }

    private static void hideSplash() {
        if (splash == null || !splash.isVisible()) return;
        FadeTransition fade = new FadeTransition(Duration.millis(220), splash);
        fade.setToValue(0);
        fade.setOnFinished(e -> splash.setVisible(false));
        fade.play();
        String shot = System.getProperty("audit.snapshot", "");
        if (!shot.isEmpty()) {
            String script = System.getProperty("audit.snapshot-js", "");
            if (!script.isEmpty()) {
                javafx.animation.PauseTransition first = new javafx.animation.PauseTransition(Duration.millis(1200));
                first.setOnFinished(e -> { try { System.out.println("JS=" + engine.executeScript(script)); } catch (RuntimeException ex) { System.out.println("JS-ERROR=" + ex); } });
                first.play();
            }
            javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(Duration.millis(3500));
            wait.setOnFinished(e -> snapshot(Path.of(shot)));
            wait.play();
        }
    }

    private static void history(int step) {
        try { engine.getHistory().go(step); } catch (RuntimeException ignored) { }
    }

    /** 이미 켜져 있을 때 아이콘을 또 누르면 창을 앞으로 가져온다. */
    static boolean show() {
        Stage s = stage;
        if (!App.desktopActive || s == null) return false;
        Platform.runLater(() -> {
            s.setIconified(false);
            s.show();
            s.toFront();
            s.requestFocus();
        });
        return true;
    }

    private static void toast(String message) {
        try {
            engine.executeScript("window.auditToast && window.auditToast(" + js(message) + ")");
        } catch (RuntimeException ignored) { }
    }

    private static String js(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c < 0x20 || c == '<' || c == '>' || c == ' ' || c == ' ') sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    /** 끌어다 놓은 파일. 건 상세 화면이면 그 건에 붙이고, 다른 화면이면 수집함에 넣는다. */
    private static void receive(List<File> dropped) {
        ConfigurableApplicationContext c = context;
        if (c == null) return;
        String location = String.valueOf(engine.getLocation());
        Matcher m = Pattern.compile("/cases/([0-9a-f]{32})(?:[#?].*)?$").matcher(location);
        String caseId = m.find() ? m.group(1) : null;
        String kind = "증빙";
        if (caseId != null) {
            try {
                Object v = engine.executeScript("(function(){var s=document.querySelector('#files select[name=kind]');return s?s.value:'';})()");
                if (v != null && CaseService.KINDS.contains(v.toString())) kind = v.toString();
            } catch (RuntimeException ignored) { }
        }
        String useKind = kind;
        // 자료 화면이면 지금 보고 있는 폴더로 올린다(예전에는 수집함으로 갔다)
        if (location.startsWith(base + "/library")) {
            String folder = "", tags = "";
            try {
                Object f = engine.executeScript("(function(){var r=document.getElementById('lib-root');return r?r.getAttribute('data-folder'):'';})()");
                Object t = engine.executeScript("(function(){var i=document.querySelector('[data-tag-input]');return i?i.value:'';})()");
                folder = f == null ? "" : f.toString();
                tags = t == null ? "" : t.toString();
            } catch (RuntimeException ignored) { }
            if (!folder.isEmpty()) { receiveLibrary(dropped, folder, tags); return; }
        }
        toast(dropped.size() + "개 파일을 받는 중…");
        new Thread(() -> {
            int ok = 0;
            String last = "";
            for (File f : dropped) {
                if (!f.isFile()) continue;
                try (InputStream in = Files.newInputStream(f.toPath())) {
                    Op op = caseId != null
                        ? c.getBean(CaseService.class).addFile(caseId, null, useKind, "", f.getName(), in, null)
                        : c.getBean(InboxService.class).upload(f.getName(), in);
                    if (op.ok) ok++;
                    last = op.message;
                } catch (Exception e) {
                    last = "파일을 읽지 못했습니다: " + f.getName();
                }
            }
            String message = caseId != null ? (ok > 0 ? "이 건에 " + useKind + " " + ok + "개를 붙였습니다." : last)
                : (ok > 0 ? "수집함에 " + ok + "개를 넣었습니다. " + last : last);
            Platform.runLater(() -> {
                pendingToast = message;
                if (caseId != null) engine.reload(); else engine.load(base + "/inbox");
            });
        }, "drop").start();
    }

    /** 자료 화면에 끌어 놓은 파일을 그 폴더에 올린다. 폴더째 놓으면 안의 파일을 모두 올린다(하위 폴더 포함, 500개까지). */
    private static void receiveLibrary(List<File> dropped, String folder, String tags) {
        ConfigurableApplicationContext c = context;
        toast(dropped.size() + "개를 자료로 올리는 중…");
        new Thread(() -> {
            List<File> all = new ArrayList<>();
            for (File f : dropped) {
                if (f.isFile()) all.add(f);
                else if (f.isDirectory()) {
                    try (java.util.stream.Stream<java.nio.file.Path> w = Files.walk(f.toPath(), 4)) {
                        w.filter(Files::isRegularFile).filter(x -> !x.getFileName().toString().startsWith("~$") && !x.getFileName().toString().startsWith("."))
                            .limit(500).forEach(x -> all.add(x.toFile()));
                    } catch (Exception ignored) { }
                }
            }
            int ok = 0, dup = 0;
            String last = "";
            LibraryService lib = c.getBean(LibraryService.class);
            for (File f : all) {
                try (InputStream in = Files.newInputStream(f.toPath())) {
                    Op op = lib.add(folder, null, tags, null, f.getName(), in);
                    if (op.ok) ok++; else if (op.status == 409) dup++; else last = op.message;
                } catch (Exception e) {
                    last = "파일을 읽지 못했습니다: " + f.getName();
                }
            }
            String message = ok > 0 ? "자료 " + ok + "건을 올렸습니다." + (dup > 0 ? " 이미 있는 파일 " + dup + "개는 건너뛰었습니다." : "")
                : dup > 0 ? "고른 파일이 모두 이미 있습니다." : last.isEmpty() ? "올릴 파일이 없습니다." : last;
            Platform.runLater(() -> { pendingToast = message; engine.reload(); });
        }, "drop-library").start();
    }

    private static Properties loadState() {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(stateFile)) { p.load(in); } catch (Exception ignored) { }
        return p;
    }

    private static double num(Properties p, String key, double fallback) {
        try { return Double.parseDouble(p.getProperty(key)); } catch (RuntimeException e) { return fallback; }
    }

    private static void saveState(Stage s) {
        try {
            Properties p = loadState();
            p.setProperty("max", s.isMaximized() ? "1" : "0");
            if (!s.isMaximized() && !s.isIconified()) {
                p.setProperty("x", Double.toString(s.getX()));
                p.setProperty("y", Double.toString(s.getY()));
                p.setProperty("w", Double.toString(s.getScene().getWidth()));
                p.setProperty("h", Double.toString(s.getScene().getHeight()));
            }
            p.setProperty("zoom", Double.toString(view.getZoom()));
            try (var out = Files.newOutputStream(stateFile)) { p.store(out, "window"); }
        } catch (Exception ignored) { }
    }

    /** 개발 확인용: 화면을 그림 파일로 남기고 끝낸다. */
    private static void snapshot(Path file) {
        try {
            WritableImage img = view.snapshot(null, null);
            PixelReader px = img.getPixelReader();
            BufferedImage out = new BufferedImage((int) img.getWidth(), (int) img.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < out.getHeight(); y++) for (int x = 0; x < out.getWidth(); x++) out.setRGB(x, y, px.getArgb(x, y));
            javax.imageio.ImageIO.write(out, "png", file.toFile());
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (context != null) App.quit(context); else System.exit(0);
    }

    /** 화면 스크립트가 부르는 창 기능. */
    public static final class Bridge {
        private final WebEngine target;
        public Bridge() { this(null); }
        Bridge(WebEngine target) { this.target = target; }
        /** 내려받기: 저장할 곳을 묻고 저장한다. */
        public void download(String url) {
            String full = url.startsWith("http") ? url : base + url;
            if (!full.startsWith(base)) return;
            new Thread(() -> {
                try {
                    HttpClient client = HttpClient.newBuilder().proxy(ProxySelector.of(null)).build();
                    HttpResponse<byte[]> res = client.send(HttpRequest.newBuilder(URI.create(full)).build(), HttpResponse.BodyHandlers.ofByteArray());
                    if (res.statusCode() != 200) { Platform.runLater(() -> toast("내려받지 못했습니다.")); return; }
                    String name = "내려받기";
                    Matcher m = Pattern.compile("filename\\*=UTF-8''([^;]+)").matcher(res.headers().firstValue("Content-Disposition").orElse(""));
                    if (m.find()) name = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
                    String suggested = name;
                    Platform.runLater(() -> {
                        FileChooser chooser = new FileChooser();
                        chooser.setTitle("저장");
                        chooser.setInitialFileName(suggested);
                        File downloads = new File(System.getProperty("user.home"), "Downloads");
                        if (downloads.isDirectory()) chooser.setInitialDirectory(downloads);
                        File target = chooser.showSaveDialog(stage);
                        if (target == null) return;
                        try {
                            Files.write(target.toPath(), res.body());
                            toast("저장했습니다: " + target.getAbsolutePath());
                        } catch (Exception e) {
                            toast("저장하지 못했습니다: " + e.getMessage());
                        }
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> toast("내려받지 못했습니다."));
                }
            }, "download").start();
        }

        public void copy(String text) {
            ClipboardContent content = new ClipboardContent();
            content.putString(text == null ? "" : text);
            Clipboard.getSystemClipboard().setContent(content);
            toast("복사했습니다.");
        }

        public void print() {
            PrinterJob job = PrinterJob.createPrinterJob();
            if (job == null) { toast("연결된 프린터가 없습니다."); return; }
            if (job.showPrintDialog(stage)) {
                (target == null ? engine : target).print(job);
                job.endJob();
            }
        }
    }
}
