package local.audit.work;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import local.audit.Db;
import local.audit.Kst;
import local.audit.Settings;
import local.audit.Txt;

/**
 * 업무·일정 항목을 데이터베이스에 둔다. 항목 하나가 한 줄이고, 내용은 JSON 한 칸에 담는다.
 * 찾고 거르기 쉽게 종류·제목·기한·날짜는 따로 칸을 둔다.
 * 한 사람이 쓰는 프로그램이라 항목 전체를 메모리에 올려 두고 쓴다.
 */
@Component
public class WorkStore {
    private static final String CONFIG_KEY = "work.config";

    private final Db db;
    private final Settings settings;
    private List<WorkItem> cache;
    private WorkConfig config;

    public WorkStore(Db db, Settings settings) {
        this.db = db;
        this.settings = settings;
    }

    public synchronized List<WorkItem> all() {
        if (cache == null) {
            List<WorkItem> list = new ArrayList<>();
            for (Map<String, Object> row : db.rows("SELECT Data FROM WorkItems ORDER BY Ord, rowid")) {
                try {
                    list.add(fix(Work.JSON.readValue(Txt.str(row.get("Data")), WorkItem.class)));
                } catch (Exception e) {
                    // 읽지 못한 줄은 건너뛴다. 원본은 데이터베이스에 그대로 남는다.
                }
            }
            cache = list;
        }
        return cache;
    }

    public synchronized WorkItem get(String id) {
        if (id == null) return null;
        for (WorkItem x : all()) if (id.equals(x.id)) return x;
        return null;
    }

    public synchronized WorkConfig config() {
        if (config == null) {
            Map<String, Object> row = db.row("SELECT Value FROM Meta WHERE Key=?", CONFIG_KEY);
            WorkConfig c = null;
            if (row != null) {
                try { c = Work.JSON.readValue(Txt.str(row.get("Value")), WorkConfig.class); } catch (Exception e) { c = null; }
            }
            config = c == null ? WorkConfig.defaults(settings.owner(), Kst.today().getYear()) : c;
            if (config.owners.isEmpty()) config.owners.add(new WorkConfig.Owner("ow0", "미배정", 0));
        }
        return config;
    }

    /** 바뀐 항목만 저장한다. */
    public synchronized void save(Collection<WorkItem> changed) {
        if (changed.isEmpty()) return;
        db.tx(conn -> {
            for (WorkItem x : changed) write(conn, x);
            return null;
        });
    }

    public void save(WorkItem x) { save(List.of(x)); }

    /** 새 항목을 맨 뒤에 붙인다. */
    public synchronized void add(WorkItem x) {
        all().add(x);
        save(x);
    }

    public synchronized void remove(WorkItem x) {
        all().removeIf(z -> z.id.equals(x.id));
        db.tx(conn -> Db.update(conn, "DELETE FROM WorkItems WHERE Id=?", x.id));
    }

    public synchronized void saveConfig(WorkConfig c) {
        config = c;
        try {
            String json = Work.JSON.writeValueAsString(c);
            db.tx(conn -> { Settings.put(conn, CONFIG_KEY, json); return null; });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 가져오기: 전부 바꾸기. */
    public synchronized void replaceAll(List<WorkItem> items, WorkConfig c) {
        String json;
        try { json = Work.JSON.writeValueAsString(c); } catch (Exception e) { throw new IllegalStateException(e); }
        cache = new ArrayList<>(items);
        db.tx(conn -> {
            Db.update(conn, "DELETE FROM WorkItems");
            for (WorkItem x : items) write(conn, x);
            Settings.put(conn, CONFIG_KEY, json);
            return null;
        });
        config = c;
    }

    private void write(Connection conn, WorkItem x) throws SQLException {
        String json;
        try { json = Work.JSON.writeValueAsString(x); } catch (Exception e) { throw new IllegalStateException(e); }
        int ord = cache == null ? 0 : Math.max(0, cache.indexOf(x));
        Db.update(conn, "INSERT INTO WorkItems(Id, Kind, Title, Due, OnDate, Ord, Data, UpdatedAt) VALUES (?,?,?,?,?,?,?,?) "
            + "ON CONFLICT(Id) DO UPDATE SET Kind=excluded.Kind, Title=excluded.Title, Due=excluded.Due, OnDate=excluded.OnDate, "
            + "Data=excluded.Data, UpdatedAt=excluded.UpdatedAt",
            x.id, x.kind, x.title, Txt.str(x.due), Txt.str(x.date), ord, json, Kst.stamp());
    }

    /** 빈칸을 채워 화면에서 null을 만나지 않게 한다. */
    static WorkItem fix(WorkItem x) {
        if (x.id == null || x.id.isBlank()) x.id = Txt.newId();
        if (x.kind == null) x.kind = "work";
        if (x.title == null) x.title = "";
        if (x.content == null) x.content = "";
        if (x.status == null || !Work.STATUS.containsKey(x.status)) x.status = "todo";
        if (x.ownerId == null) x.ownerId = "ow0";
        if (x.typeId == null) x.typeId = "ty6";
        if (x.projectId == null) x.projectId = "";
        if (x.cycle == null || !Work.CYCLE.containsKey(x.cycle)) x.cycle = "none";
        if (x.months == null) x.months = new ArrayList<>();
        x.months.removeIf(m -> m == null || m < 1 || m > 12);
        if (x.start == null) x.start = "";
        if (x.due == null) x.due = "";
        if (x.due0 == null) x.due0 = "";
        if (x.dueLog == null) x.dueLog = new ArrayList<>();
        if (x.links == null) x.links = new ArrayList<>();
        if (x.todos == null) x.todos = new ArrayList<>();
        x.todos.removeIf(t -> t == null);
        if (x.doneAt == null) x.doneAt = "";
        if (x.pri == null || !Work.PRI.containsKey(x.pri)) x.pri = "mid";
        if (x.parentId == null) x.parentId = "";
        if (x.checks == null) x.checks = new java.util.LinkedHashMap<>();
        if (x.log == null) x.log = new ArrayList<>();
        x.log.removeIf(t -> t == null);
        if (x.srcId == null) x.srcId = "";
        if (x.tags == null) x.tags = new ArrayList<>();
        if (x.created == null) x.created = "";
        if (x.next == null) x.next = "";
        if (x.comments == null) x.comments = new ArrayList<>();
        if (x.repeat == null) x.repeat = "";
        return x;
    }
}
