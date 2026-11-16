package local.audit;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/** 설정 값. 데이터베이스의 Meta 표에 둔다. */
@Component
public class Settings {
    private final Db db;

    public Settings(Db db) { this.db = db; }

    public String get(String key, String fallback) {
        Map<String, Object> row = db.row("SELECT Value FROM Meta WHERE Key=?", "set." + key);
        return row == null ? fallback : Txt.str(row.get("Value"));
    }

    public void set(String key, String value) {
        db.tx(conn -> { put(conn, "set." + key, value); return null; });
    }

    public static void put(Connection conn, String key, String value) throws SQLException {
        Db.update(conn, "INSERT INTO Meta(Key, Value) VALUES (?,?) ON CONFLICT(Key) DO UPDATE SET Value=excluded.Value", key, value);
    }

    public static String meta(Connection conn, String key, String fallback) throws SQLException {
        Map<String, Object> row = Db.row(conn, "SELECT Value FROM Meta WHERE Key=?", key);
        return row == null ? fallback : Txt.str(row.get("Value"));
    }

    /** 이력과 담당 칸에 적히는 이름. */
    public String owner() { return get("ownerName", "나"); }

    /** 살펴볼 폴더들. 한 줄에 하나. */
    public List<String> watchFolders() {
        List<String> list = new ArrayList<>();
        for (String line : get("watchFolders", "").split("\n")) {
            if (!line.isBlank()) list.add(line.strip());
        }
        return list;
    }

    /** off: 수집만, attach: 맞는 건에 붙이기까지, full: 새 건 초안·조치요구·징계기록까지. */
    public String autoMode() { return get("autoMode", "full"); }
    public boolean keepText() { return "1".equals(get("keepText", "1")); }
    public boolean autoBackup() { return "1".equals(get("autoBackup", "1")); }
    public boolean autoPurge() { return "1".equals(get("autoPurge", "0")); }
    public boolean office() { return "1".equals(get("office", "1")); }
    public boolean moveProcessed() { return "1".equals(get("moveProcessed", "1")); }
    public Integer defaultRetention() { return Txt.intOrNull(get("defaultRetention", "")); }

    public int replyDays() {
        Integer n = Txt.intOrNull(get("replyDays", "30"));
        return n == null || n < 1 || n > 365 ? 30 : n;
    }
}
