package local.audit;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 이력. 추가만 한다. 각 줄은 자기 내용과 앞줄 지문(SHA-256)으로 지문을 만든다.
 * 데이터 파일을 직접 고치면 화면 위에 훼손으로 표시한다.
 * 꼬리와 기준값을 함께 바꾸거나 전체를 다시 계산한 경우는 알아내지 못한다.
 */
@Component
public class Chain {
    private final Db db;
    private volatile long checkedAt;
    private volatile boolean intact = true;

    public Chain(Db db) { this.db = db; }

    /** 이전 버전 데이터를 옮길 때만 원래 시각을 넣는다. */
    static final ThreadLocal<String> stampOverride = new ThreadLocal<>();

    public static long append(Connection conn, String caseId, String actor, String action,
                              String targetKind, String targetId, String summary) throws SQLException {
        long seq = Long.parseLong(Settings.meta(conn, "chain.seq", "0")) + 1;
        byte[] prev = HexFormat.of().parseHex(Settings.meta(conn, "chain.hash", "00".repeat(32)));
        long count = Long.parseLong(Settings.meta(conn, "chain.count", "0"));
        String at = stampOverride.get() != null ? stampOverride.get() : Kst.stamp();
        byte[] hash = compute(seq, caseId, at, actor, action, targetKind, targetId, summary, prev);
        Db.update(conn, """
            INSERT INTO History(Seq, CaseId, At, Actor, Action, TargetKind, TargetId, Summary, PrevHash, RowHash)
            VALUES (?,?,?,?,?,?,?,?,?,?)""", seq, caseId, at, actor, action, targetKind, targetId, summary, prev, hash);
        Settings.put(conn, "chain.seq", Long.toString(seq));
        Settings.put(conn, "chain.hash", Txt.hex(hash));
        Settings.put(conn, "chain.count", Long.toString(count + 1));
        return seq;
    }

    static byte[] compute(long seq, String caseId, String at, String actor, String action,
                          String targetKind, String targetId, String summary, byte[] prev) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String part : new String[] { "v1", Long.toString(seq), caseId, at, actor, action, targetKind, targetId, summary }) {
            byte[] bytes = (part == null ? "" : part).getBytes(StandardCharsets.UTF_8);
            out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.length).array());
            out.writeBytes(bytes);
        }
        out.writeBytes(prev);
        return Txt.sha256(out.toByteArray());
    }

    /** 처음부터 끝까지 다시 계산해 맞는지 본다. */
    public boolean verify() {
        boolean ok = db.read(conn -> {
            if ("1".equals(Settings.meta(conn, "chain.broken", "0"))) return false;
            List<Map<String, Object>> rows = Db.rows(conn, "SELECT * FROM History ORDER BY Seq");
            byte[] prev = new byte[32];
            long expect = 1;
            for (Map<String, Object> r : rows) {
                long seq = ((Number) r.get("Seq")).longValue();
                byte[] storedPrev = (byte[]) r.get("PrevHash");
                byte[] stored = (byte[]) r.get("RowHash");
                if (seq != expect || !MessageDigest.isEqual(prev, storedPrev)) return false;
                byte[] hash = compute(seq, (String) r.get("CaseId"), (String) r.get("At"), (String) r.get("Actor"),
                    (String) r.get("Action"), (String) r.get("TargetKind"), (String) r.get("TargetId"), (String) r.get("Summary"), prev);
                if (!MessageDigest.isEqual(hash, stored)) return false;
                prev = hash;
                expect++;
            }
            return Long.parseLong(Settings.meta(conn, "chain.count", "0")) == rows.size()
                && Long.parseLong(Settings.meta(conn, "chain.seq", "0")) == rows.size()
                && Settings.meta(conn, "chain.hash", "00".repeat(32)).equals(Txt.hex(prev));
        });
        intact = ok;
        checkedAt = System.currentTimeMillis();
        if (!ok) db.tx(conn -> { Settings.put(conn, "chain.broken", "1"); return null; });
        return ok;
    }

    /** 화면마다 부른다. 1분에 한 번만 실제로 계산한다. */
    public boolean intact() {
        if (System.currentTimeMillis() - checkedAt > 60_000) verify();
        return intact;
    }

    /** 조사한 뒤 사유를 남기고 지문을 다시 세운다. */
    public Op reseal(String actor, String reason) {
        String why = Txt.clean(reason, 240, false);
        if (Txt.blank(why)) return Op.bad("사유를 입력하세요.");
        Op op = db.run(conn -> {
            List<Map<String, Object>> rows = Db.rows(conn, "SELECT * FROM History ORDER BY Seq");
            byte[] prev = new byte[32];
            long n = 0;
            for (Map<String, Object> r : rows) {
                n++;
                long old = ((Number) r.get("Seq")).longValue();
                byte[] hash = compute(n, (String) r.get("CaseId"), (String) r.get("At"), (String) r.get("Actor"),
                    (String) r.get("Action"), (String) r.get("TargetKind"), (String) r.get("TargetId"), (String) r.get("Summary"), prev);
                Db.update(conn, "UPDATE History SET Seq=?, PrevHash=?, RowHash=? WHERE Seq=?", -n, prev, hash, old);
                prev = hash;
            }
            Db.update(conn, "UPDATE History SET Seq=-Seq WHERE Seq<0");
            Settings.put(conn, "chain.seq", Long.toString(n));
            Settings.put(conn, "chain.hash", Txt.hex(prev));
            Settings.put(conn, "chain.count", Long.toString(n));
            Settings.put(conn, "chain.broken", "0");
            append(conn, null, actor, "수정", "이력", null, "이력 훼손 조사 후 지문 재설정. 사유: " + why);
            return Op.ok("이력 지문을 다시 세웠습니다. 고쳐진 줄의 원래 내용은 되돌리지 못합니다.");
        });
        verify();
        return op;
    }
}
