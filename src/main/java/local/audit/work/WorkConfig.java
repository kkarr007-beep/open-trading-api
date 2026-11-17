package local.audit.work;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;

/** 업무·일정의 설정: 유형(단계), 담당자, 과제. 예전 HTML의 config와 같은 모양. */
public class WorkConfig {
    public int year;
    public int stalled = 7;
    public String lastBackup = "";
    public String theme = "light";
    public String me = "ow1";
    public String partName = "경영진단파트";
    public List<Type> types = new ArrayList<>();
    public List<Owner> owners = new ArrayList<>();
    public List<String> orgs = new ArrayList<>();
    public List<Project> projects = new ArrayList<>();
    public List<Object> savedViews = new ArrayList<>();
    public boolean alerts = true;

    private final Map<String, Object> other = new LinkedHashMap<>();
    @JsonAnySetter public void put(String key, Object value) { other.put(key, value); }
    @JsonAnyGetter public Map<String, Object> other() { return other; }

    public static class Type {
        public String id;
        public String n;
        public List<String> stages = new ArrayList<>();
        public Type() { }
        public Type(String id, String n, List<String> stages) { this.id = id; this.n = n; this.stages = new ArrayList<>(stages); }
        public String getStageLine() { return String.join(", ", stages); }
    }

    public static class Owner {
        public String id;
        public String n;
        public boolean on = true;
        public int cap;
        public Owner() { }
        public Owner(String id, String n, int cap) { this.id = id; this.n = n; this.cap = cap; }
    }

    public static class Project {
        public String id;
        public String n;
        public boolean on = true;
        public Project() { }
        public Project(String id, String n) { this.id = id; this.n = n; }
    }

    /** 처음 켤 때의 설정. 담당자는 '미배정'과 이 프로그램을 쓰는 사람 둘로 시작한다. */
    public static WorkConfig defaults(String ownerName, int year) {
        WorkConfig c = new WorkConfig();
        c.year = year;
        c.types.add(new Type("ty1", "일상업무", List.of("준비", "시행", "보고")));
        c.types.add(new Type("ty2", "정기점검", List.of("계획", "자료요청", "점검", "정리", "보고")));
        c.types.add(new Type("ty3", "업무진단", List.of("계획", "자료요청", "현장점검", "초안", "검토", "보고")));
        c.types.add(new Type("ty4", "준법감사", List.of("계획", "자료요청", "감사", "초안", "검토", "보고")));
        c.types.add(new Type("ty5", "추진과제", List.of("기획", "설계", "협의", "시행", "완료")));
        c.types.add(new Type("ty6", "수시업무", List.of()));
        c.owners.add(new Owner("ow0", "미배정", 0));
        c.owners.add(new Owner("ow1", ownerName == null || ownerName.isBlank() ? "나" : ownerName.strip(), 6));
        c.orgs.add("전사");
        return c;
    }

    public Owner owner(String id) {
        for (Owner o : owners) if (o.id.equals(id)) return o;
        return owners.isEmpty() ? new Owner("ow0", "미배정", 0) : owners.get(0);
    }

    public Type type(String id) {
        for (Type t : types) if (t.id.equals(id)) return t;
        return new Type("", "미분류", List.of());
    }

    public Project project(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Project p : projects) if (p.id.equals(id)) return p;
        return null;
    }
}
