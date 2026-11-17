package local.audit.work;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 업무·일정 항목 하나. 예전 '업무·일정 관리 v2'(HTML)의 항목과 같은 이름의 칸을 쓴다.
 * 그쪽에서 내보낸 JSON을 그대로 읽고, 이 프로그램이 모르는 칸도 버리지 않고 함께 저장한다.
 *
 * kind: work 업무 · dir 지시 · event 일정 · note 노트 · plan 계획 대기 · inbox 생각 담기
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class WorkItem {
    public String id;
    public String kind = "work";
    public String typeId = "ty6";
    public String title = "";
    public String content = "";
    public String status = "todo";
    public int stage;
    public String ownerId = "ow0";
    public String projectId = "";
    public String cycle = "none";
    public List<Integer> months = new ArrayList<>();
    public int target;
    public String start = "";
    public String due = "";
    public String due0 = "";
    public List<DueChange> dueLog = new ArrayList<>();
    public List<Object> links = new ArrayList<>();
    public List<Todo> todos = new ArrayList<>();
    public String doneAt = "";
    public String pri = "mid";
    public String parentId = "";
    public Map<String, Object> checks = new LinkedHashMap<>();
    public List<Entry> log = new ArrayList<>();
    public String srcId = "";
    public List<String> tags = new ArrayList<>();
    public boolean pinned;
    public String created = "";
    public String next = "";
    public List<Map<String, Object>> comments = new ArrayList<>();
    public String repeat = "";

    // 일정(event)
    public String date;
    public String t1;
    public String t2;
    public String cat;
    public String place;
    public String memo;
    // 지시(dir)
    public String from;
    // 계획 대기(plan)
    public String when;
    public String tick;
    // 노트(note)
    public String ntype;
    public String mdate;
    public String org;
    // 정기 업무를 시작·끝내는 해
    public Integer fromY;
    public Integer toY;

    private final Map<String, Object> other = new LinkedHashMap<>();

    @JsonAnySetter public void put(String key, Object value) { other.put(key, value); }
    @JsonAnyGetter public Map<String, Object> other() { return other; }

    public static class Todo {
        public String t = "";
        public boolean d;
        public Todo() { }
        public Todo(String t, boolean d) { this.t = t; this.d = d; }
    }

    public static class Entry {
        public String d = "";
        public String t = "";
        public Entry() { }
        public Entry(String d, String t) { this.d = d; this.t = t; }
    }

    public static class DueChange {
        public String d = "";
        public String from = "";
        public String to = "";
        public DueChange() { }
        public DueChange(String d, String from, String to) { this.d = d; this.from = from; this.to = to; }
    }

    // ───── 화면에서 쓰는 이름 ─────
    public boolean isWork() { return "work".equals(kind); }
    public boolean isDone() { return "done".equals(status); }
    public boolean hasDue() { return due != null && !due.isEmpty(); }
    public String getStatusName() { return Work.statusName(status); }
    public String getPriName() { return Work.priName(pri); }
    public String getPriIcon() { return Work.priIcon(pri); }
    public String getKindName() { return Work.kindName(kind); }
    public String getCategoryName() { return Work.eventName(cat); }
    public int getTodoDone() { return (int) todos.stream().filter(x -> x.d).count(); }
    public String getTagLine() { return String.join(", ", tags); }
    public boolean hasMonth(int m) { return months.contains(m); }
}
