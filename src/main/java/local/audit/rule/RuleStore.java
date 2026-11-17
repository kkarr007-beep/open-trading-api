package local.audit.rule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import local.audit.Db;
import local.audit.Kst;
import local.audit.Settings;
import local.audit.Txt;

/**
 * 사규 데이터를 데이터베이스에 둔다. 사규 하나(판·조 포함)가 한 줄이고 내용은 JSON 한 칸에 담는다.
 * 카테고리·부서·메모·북마크처럼 사규 밖의 것은 Meta 표의 'rule.state' 한 칸에 둔다.
 * 예전 '사규 관리 시스템 v4'의 칸 이름을 그대로 써서, 그쪽 백업 파일을 그대로 읽고 다시 내보낼 수 있다.
 */
@Component
public class RuleStore {
    static final ObjectMapper JSON = new ObjectMapper();
    static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private static final String STATE_KEY = "rule.state";

    private final Db db;
    private List<Map<String, Object>> regs;
    private Map<String, Object> state;
    private volatile long version;

    public RuleStore(Db db) { this.db = db; }

    /** 바뀔 때마다 오른다. 찾기용 색인을 다시 만들지 판단할 때 쓴다. */
    public long version() { return version; }

    public synchronized List<Map<String, Object>> regs() {
        if (regs == null) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map<String, Object> row : db.rows("SELECT Data FROM RuleRegs ORDER BY Ord, rowid")) {
                try { list.add(Rules.fixReg(JSON.readValue(Txt.str(row.get("Data")), MAP))); } catch (Exception e) { /* 읽지 못한 줄은 건너뛴다 */ }
            }
            regs = list;
        }
        return regs;
    }

    public synchronized Map<String, Object> reg(String id) {
        if (id == null) return null;
        for (Map<String, Object> r : regs()) if (id.equals(r.get("id"))) return r;
        return null;
    }

    public synchronized Map<String, Object> state() {
        if (state == null) {
            Map<String, Object> row = db.row("SELECT Value FROM Meta WHERE Key=?", STATE_KEY);
            Map<String, Object> s = null;
            if (row != null) {
                try { s = JSON.readValue(Txt.str(row.get("Value")), MAP); } catch (Exception e) { s = null; }
            }
            state = Rules.fixState(s == null ? Rules.defaultState() : s);
        }
        return state;
    }

    public synchronized void saveReg(Map<String, Object> r) {
        r.put("updatedAt", Kst.stamp());
        int ord = -1;
        for (int i = 0; i < regs().size(); i++) if (regs().get(i) == r) ord = i;
        if (ord < 0) { regs().add(r); ord = regs().size() - 1; }
        final int at = ord;
        String json = write(r);
        db.tx(conn -> Db.update(conn, "INSERT INTO RuleRegs(RegId, Title, CategoryId, Ord, Data, UpdatedAt) VALUES (?,?,?,?,?,?) "
            + "ON CONFLICT(RegId) DO UPDATE SET Title=excluded.Title, CategoryId=excluded.CategoryId, Data=excluded.Data, UpdatedAt=excluded.UpdatedAt",
            r.get("id"), Txt.str(r.get("title")), Txt.str(r.get("categoryId")), at, json, Kst.stamp()));
        version++;
    }

    public synchronized void removeReg(String id) {
        regs().removeIf(r -> id.equals(r.get("id")));
        db.tx(conn -> Db.update(conn, "DELETE FROM RuleRegs WHERE RegId=?", id));
        version++;
    }

    public synchronized void saveState() {
        String json = write(state());
        db.tx(conn -> { Settings.put(conn, STATE_KEY, json); return null; });
        version++;
    }

    public synchronized void replaceAll(List<Map<String, Object>> newRegs, Map<String, Object> newState) {
        List<String> jsons = new ArrayList<>();
        for (Map<String, Object> r : newRegs) jsons.add(write(r));
        String st = write(newState);
        db.tx(conn -> {
            Db.update(conn, "DELETE FROM RuleRegs");
            for (int i = 0; i < newRegs.size(); i++) {
                Map<String, Object> r = newRegs.get(i);
                Db.update(conn, "INSERT INTO RuleRegs(RegId, Title, CategoryId, Ord, Data, UpdatedAt) VALUES (?,?,?,?,?,?)",
                    r.get("id"), Txt.str(r.get("title")), Txt.str(r.get("categoryId")), i, jsons.get(i), Kst.stamp());
            }
            Settings.put(conn, STATE_KEY, st);
            return null;
        });
        regs = new ArrayList<>(newRegs);
        state = newState;
        version++;
    }

    static String write(Object o) {
        try { return JSON.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
