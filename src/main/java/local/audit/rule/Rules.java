package local.audit.rule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 사규 데이터(JSON 모양)를 다루는 작은 도구. */
public final class Rules {
    private Rules() { }

    static int seq;
    public static String uid(String p) {
        return p + "_" + Long.toString(System.currentTimeMillis(), 36) + Integer.toString(++seq & 0xffff, 36) + Integer.toString((int) (Math.random() * 46656), 36);
    }

    public static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? "" : v.toString();
    }

    public static boolean bool(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Boolean b ? b : v != null && "true".equals(v.toString());
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> maps(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof List<?> l) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : l) if (o instanceof Map<?, ?>) out.add((Map<String, Object>) o);
            if (out.size() != l.size()) { m.put(k, out); }
            return out.size() == l.size() ? (List<Map<String, Object>>) v : out;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        if (m != null) m.put(k, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof List<?> l) return (List<Object>) l;
        List<Object> out = new ArrayList<>();
        m.put(k, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Map<?, ?> x) return (Map<String, Object>) x;
        Map<String, Object> out = new LinkedHashMap<>();
        m.put(k, out);
        return out;
    }

    public static Map<String, Object> curVer(Map<String, Object> r) {
        if (r == null) return null;
        List<Map<String, Object>> vs = maps(r, "versions");
        String cur = str(r, "currentVersionId");
        for (Map<String, Object> v : vs) if (cur.equals(str(v, "id"))) return v;
        return vs.isEmpty() ? null : vs.get(vs.size() - 1);
    }

    public static Map<String, Object> version(Map<String, Object> r, String vid) {
        if (vid != null) for (Map<String, Object> v : maps(r, "versions")) if (vid.equals(str(v, "id"))) return v;
        return curVer(r);
    }

    public static List<Map<String, Object>> arts(Map<String, Object> r) {
        Map<String, Object> v = curVer(r);
        return v == null ? List.of() : maps(v, "articles");
    }

    public static String artLabel(Map<String, Object> a) {
        String t = str(a, "title");
        return str(a, "number") + (t.isEmpty() ? "" : "(" + t + ")");
    }

    /** 비교용 이름: 소문자, 띄어쓰기 없음. */
    public static String nk(String s) { return s == null ? "" : s.toLowerCase().replaceAll("\\s+", ""); }

    public static Map<String, Object> defaultState() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("schemaVersion", 4);
        List<Object> cats = new ArrayList<>();
        String[][] c = { { "인사", "1" }, { "회계", "2" }, { "감사", "3" }, { "영업", "4" }, { "총무", "5" }, { "기타", "99" } };
        for (String[] x : c) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", uid("c"));
            m.put("name", x[0]);
            m.put("order", Integer.parseInt(x[1]));
            cats.add(m);
        }
        s.put("categories", cats);
        List<Object> deps = new ArrayList<>();
        for (String d : new String[] { "경영진단팀", "인사팀", "회계팀" }) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", uid("d"));
            m.put("name", d);
            deps.add(m);
        }
        s.put("departments", deps);
        Map<String, Object> folder = new LinkedHashMap<>();
        folder.put("id", uid("bf"));
        folder.put("name", "기본 폴더");
        folder.put("bookmarks", new ArrayList<>());
        s.put("bookmarkFolders", new ArrayList<>(List.of(folder)));
        s.put("notes", new LinkedHashMap<>());
        for (String k : new String[] { "recentViews", "checklists", "violationMemos", "collections", "riskAssessments", "correctiveActions",
            "auditHistory", "glossaryCustom", "synonyms", "recentSearches", "savedSearches" }) s.put(k, new ArrayList<>());
        s.put("viewCounts", new LinkedHashMap<>());
        s.put("lawLinks", new LinkedHashMap<>());
        s.put("ui", new LinkedHashMap<>());
        return s;
    }

    /** 빠진 칸을 채운다. 모르는 칸은 그대로 둔다. */
    public static Map<String, Object> fixState(Map<String, Object> s) {
        Map<String, Object> d = defaultState();
        for (var e : d.entrySet()) {
            Object v = s.get(e.getKey());
            if (v == null || (e.getValue() instanceof List && !(v instanceof List)) || (e.getValue() instanceof Map && !(v instanceof Map))) s.put(e.getKey(), e.getValue());
        }
        s.remove("regulations");
        if (maps(s, "bookmarkFolders").isEmpty()) s.put("bookmarkFolders", d.get("bookmarkFolders"));
        for (Map<String, Object> f : maps(s, "bookmarkFolders")) list(f, "bookmarks");
        List<Object> cats = list(s, "categories");
        for (int i = 0; i < cats.size(); i++) {
            if (!(cats.get(i) instanceof Map)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", uid("c"));
                m.put("name", String.valueOf(cats.get(i)).strip());
                m.put("order", i + 1);
                cats.set(i, m);
            }
        }
        List<Object> deps = list(s, "departments");
        for (int i = 0; i < deps.size(); i++) {
            if (!(deps.get(i) instanceof Map)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", uid("d"));
                m.put("name", String.valueOf(deps.get(i)).strip());
                deps.set(i, m);
            }
        }
        return s;
    }

    public static Map<String, Object> fixReg(Map<String, Object> r) {
        if (str(r, "id").isEmpty()) r.put("id", uid("r"));
        if (!r.containsKey("status")) r.put("status", "active");
        list(r, "relatedRegIds");
        list(r, "departmentIds");
        for (Map<String, Object> v : maps(r, "versions")) {
            if (str(v, "id").isEmpty()) v.put("id", uid("v"));
            for (Map<String, Object> a : maps(v, "articles")) {
                if (str(a, "id").isEmpty()) a.put("id", uid("a"));
                if (a.get("content") == null) a.put("content", "");
                a.put("number", str(a, "number"));
                if (a.get("title") == null) a.put("title", "");
                list(a, "tags");
                list(a, "crossRefs");
                if (!a.containsKey("difficult")) a.put("difficult", false);
            }
        }
        if (str(r, "currentVersionId").isEmpty()) {
            List<Map<String, Object>> vs = maps(r, "versions");
            if (!vs.isEmpty()) r.put("currentVersionId", str(vs.get(vs.size() - 1), "id"));
        }
        return r;
    }
}
