package local.audit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import local.audit.board.BoardData;
import local.audit.board.BoardService;
import local.audit.work.WorkConfig;
import local.audit.work.WorkService;

/**
 * 기준정보: 조직과 사람. 메뉴마다 따로 적던 부서·담당자 이름을 한 목록으로 모아
 * 감사 건 대상, 조치 대상 부서, 징계 부서, 사규 주관부서 입력칸의 추천 목록으로 함께 쓴다.
 * 사람은 업무 설정의 담당자 목록을 그대로 쓴다(한 곳에서만 관리).
 */
@Service
public class OrgService {
    private static final String KEY = "org.list", REMOVED = "org.removed";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Db db;
    private final Settings settings;
    private final BoardService board;
    private final WorkService work;

    public OrgService(Db db, Settings settings, BoardService board, WorkService work) {
        this.db = db; this.settings = settings; this.board = board; this.work = work;
    }

    private List<String> cacheFound = List.of();
    private long cacheAt;

    /** 저장한 목록 + 자료에 새로 나온 이름(사용자가 지운 이름은 빼고). 새 이름은 자동으로 목록 끝에 붙는다. */
    public synchronized List<String> orgs() {
        List<String> out = read(KEY);
        long now = System.currentTimeMillis();
        if (now - cacheAt > 5_000) { cacheFound = found(); cacheAt = now; }
        Set<String> removed = new java.util.HashSet<>(read(REMOVED));
        boolean changed = false;
        for (String n : cacheFound) if (!out.contains(n) && !removed.contains(n)) { out.add(n); changed = true; }
        if (changed) store(out);
        return out;
    }

    private List<String> read(String key) {
        List<String> out = new ArrayList<>();
        try {
            String raw = settings.get(key, "");
            if (!raw.isEmpty()) for (Object o : JSON.readValue(raw, List.class)) if (o != null && !o.toString().isBlank()) out.add(o.toString());
        } catch (Exception ignored) { }
        return out;
    }

    private void store(List<String> names) { store(KEY, names); }

    private void store(String key, List<String> names) {
        try { settings.set(key, JSON.writeValueAsString(names)); } catch (Exception ignored) { }
    }

    /** 이미 들어 있는 자료에서 부서·조직 이름을 모은다. */
    public List<String> found() {
        Set<String> s = new LinkedHashSet<>();
        try {
            if (board.hasData()) {
                BoardData d = board.load();
                for (BoardData.Org o : d.orgs) { add(s, o.name); for (String u : o.units) add(s, u); }
            }
        } catch (RuntimeException ignored) { }
        for (String sql : List.of("SELECT DISTINCT Target AS N FROM Cases WHERE PurgedAt IS NULL",
                "SELECT DISTINCT TargetDept AS N FROM Actions", "SELECT DISTINCT Department AS N FROM Discipline",
                "SELECT DISTINCT Dept AS N FROM CardTx")) {
            try { for (Map<String, Object> r : db.rows(sql)) add(s, Txt.str(r.get("N"))); } catch (RuntimeException ignored) { }
        }
        return new ArrayList<>(s);
    }

    private static void add(Set<String> s, String n) {
        String t = Txt.str(n).strip().replaceAll("\\s+", " ");
        if (!t.isEmpty() && t.length() <= 80) s.add(t);
    }

    /** 한 줄에 하나씩 적은 목록으로 바꾼다. 같은 이름은 하나만 남긴다. */
    public synchronized Op save(String text) {
        Set<String> s = new LinkedHashSet<>();
        for (String line : Txt.str(text).split("\\R")) add(s, line);
        if (s.size() > 1000) return Op.bad("조직은 1,000개까지 둘 수 있습니다.");
        // 목록에서 지운 이름은 기억해 두고, 자료에 있어도 다시 붙이지 않는다.
        Set<String> removed = new LinkedHashSet<>(read(REMOVED));
        for (String n : read(KEY)) if (!s.contains(n)) removed.add(n);
        removed.removeAll(s);
        store(REMOVED, new ArrayList<>(removed));
        store(new ArrayList<>(s));
        return Op.ok("조직 " + s.size() + "개를 저장했습니다.");
    }

    /** 자료에서 찾은 이름 중 목록에 없는 것만 더한다. */
    public synchronized Op gather() {
        Set<String> s = new LinkedHashSet<>(read(KEY));
        int before = s.size();
        for (String n : found()) s.add(n);
        store(REMOVED, new ArrayList<>());
        store(new ArrayList<>(s));
        cacheAt = 0;
        int added = s.size() - before;
        return Op.ok(added == 0 ? "새로 더할 이름이 없습니다." : "자료에서 찾은 조직 " + added + "개를 더했습니다.");
    }

    /** 비슷한 이름(띄어쓰기·'팀/부/실' 차이만 있는 것) 묶음. 합칠지 사람이 보고 정한다. */
    public List<List<String>> similar() {
        Map<String, List<String>> g = new java.util.LinkedHashMap<>();
        for (String n : orgs()) g.computeIfAbsent(n.replaceAll("[\\s·\\-_()]", "").toLowerCase(), k -> new ArrayList<>()).add(n);
        List<List<String>> out = new ArrayList<>();
        for (List<String> v : g.values()) if (v.size() > 1) out.add(v);
        return out;
    }

    /** 사람: 업무 담당자(미배정 제외)와 이 컴퓨터 사용자. */
    public List<String> people() {
        Set<String> s = new LinkedHashSet<>();
        add(s, settings.owner());
        WorkConfig c = work.config();
        for (WorkConfig.Owner o : c.owners) if (!"ow0".equals(o.id)) add(s, o.n);
        return new ArrayList<>(s);
    }
}
