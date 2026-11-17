package local.audit.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.fasterxml.jackson.databind.ObjectMapper;

import local.audit.Db;
import local.audit.Kst;
import local.audit.Settings;
import local.audit.Txt;

/**
 * 경영 현황판. 받은 현황판 화면(board.html)을 그대로 쓰고, 저장만 Portal 데이터 파일(Meta 'board.store')로 바꾼다.
 * 그래서 Portal 백업에 함께 들어가고, 다른 브라우저·창에서 열어도 같은 내용이 보인다.
 */
@Controller
public class BoardController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "board.store";
    private static final int MAX = 40_000_000;
    private final Db db;
    private final Map<String, Object[]> files = new ConcurrentHashMap<>();
    private volatile String page;

    public BoardController(Db db) { this.db = db; }

    @GetMapping("/board")
    public String board() { return "board"; }

    /** 현황판 화면. 저장 연결 스크립트를 맨 앞에 넣는다. */
    @GetMapping("/board/app")
    public ResponseEntity<byte[]> app() throws IOException {
        if (page == null) {
            String html, shim;
            try (InputStream in = getClass().getResourceAsStream("/board/board.html")) { html = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            try (InputStream in = getClass().getResourceAsStream("/board/shim.js")) { shim = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            int at = html.indexOf("<head>");
            page = at < 0 ? "<script>" + shim + "</script>" + html : html.substring(0, at + 6) + "\n<script>" + shim + "</script>\n" + html.substring(at + 6);
        }
        return Web.html(page);
    }

    @GetMapping("/board/state")
    @ResponseBody
    public ResponseEntity<String> state() {
        Map<String, Object> row = db.row("SELECT Value FROM Meta WHERE Key=?", KEY);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(row == null ? "{}" : Txt.str(row.get("Value")));
    }

    @PostMapping("/board/state")
    @ResponseBody
    public ResponseEntity<String> save(@RequestBody String body) {
        if (body == null || body.length() > MAX) return ResponseEntity.status(413).body("{\"ok\":false}");
        try {
            Map<?, ?> m = JSON.readValue(body, Map.class);
            for (var e : m.entrySet()) if (!String.valueOf(e.getKey()).startsWith("exec-") || !(e.getValue() instanceof String)) return ResponseEntity.badRequest().body("{\"ok\":false}");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("{\"ok\":false}");
        }
        db.tx(conn -> { Settings.put(conn, KEY, body); return null; });
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("{\"ok\":true}");
    }

    /** 현황판이 만든 파일(백업·CSV 등)을 잠시 맡아 두었다가 내려준다. */
    @PostMapping("/board/file")
    @ResponseBody
    public ResponseEntity<String> keepFile(@RequestBody Map<String, String> f) {
        String text = Txt.str(f.get("text"));
        if (text.length() > MAX) return ResponseEntity.status(413).body("{}");
        files.entrySet().removeIf(e -> System.currentTimeMillis() - (long) e.getValue()[3] > 10 * 60_000L);
        String id = Txt.newId();
        files.put(id, new Object[] { Txt.fit(f.get("name"), 120, false), text, Txt.str(f.get("mime")), System.currentTimeMillis() });
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("{\"id\":\"" + id + "\"}");
    }

    @GetMapping("/board/file/{id}")
    public ResponseEntity<byte[]> file(@PathVariable String id, @RequestParam(required = false) String dl) {
        Object[] f = files.get(id);
        if (f == null) return ResponseEntity.notFound().build();
        String name = Txt.blank((String) f[0]) ? "경영현황판.txt" : (String) f[0];
        String mime = ((String) f[2]).matches("[a-z]+/[a-z0-9.+-]+") ? (String) f[2] : "application/octet-stream";
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, Web.disposition(false, name))
            .contentType(MediaType.parseMediaType(mime + "; charset=utf-8")).body(((String) f[1]).getBytes(StandardCharsets.UTF_8));
    }

    /** Portal에 저장된 현황판 데이터 전체(현황판의 '백업·복원'에서 다시 넣을 수 있는 본 데이터 포함). */
    @GetMapping("/board/data/export")
    public ResponseEntity<byte[]> export() throws IOException {
        Map<String, Object> row = db.row("SELECT Value FROM Meta WHERE Key=?", KEY);
        String body = "{}";
        if (row != null) {
            Map<?, ?> m = JSON.readValue(Txt.str(row.get("Value")), Map.class);
            Object main = m.get("exec-board-v3");
            body = main != null ? main.toString() : JSON.writerWithDefaultPrettyPrinter().writeValueAsString(m);
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, Web.disposition(false, "경영현황판-백업-" + Kst.today() + ".json"))
            .contentType(MediaType.APPLICATION_JSON).body(body.getBytes(StandardCharsets.UTF_8));
    }
}
