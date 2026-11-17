package local.audit;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 편집기로 쓴 서식 있는 내용(HTML)을 둔다. 글자만 담는 원래 칸은 그대로 두고, 같은 내용의 꾸민 모양을 여기 둔다.
 * 열쇠 예: action:{조치ID}, entry:{이행기록ID}.
 */
@Component
public class RichTexts {
    private final Db db;

    public RichTexts(Db db) { this.db = db; }

    public String get(String owner) {
        Map<String, Object> row = db.row("SELECT Html FROM RichText WHERE Owner=?", owner);
        return row == null ? null : Txt.str(row.get("Html"));
    }

    public void put(String owner, String html) {
        String clean = HtmlClean.clean(html);
        db.tx(conn -> {
            if (clean.isBlank()) Db.update(conn, "DELETE FROM RichText WHERE Owner=?", owner);
            else Db.update(conn, "INSERT INTO RichText(Owner, Html) VALUES (?,?) ON CONFLICT(Owner) DO UPDATE SET Html=excluded.Html", owner, clean);
            return null;
        });
    }

    public void remove(String owner) { db.tx(conn -> Db.update(conn, "DELETE FROM RichText WHERE Owner=?", owner)); }

    /** 저장된 꾸민 모양이 있으면 그것을, 없으면 글자에서 만든 모양을. */
    public String view(String owner, String text) {
        String h = get(owner);
        return h != null ? h : local.audit.extract.Rich.fromText(text);
    }
}
