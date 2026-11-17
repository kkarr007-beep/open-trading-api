package local.audit.rule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import local.audit.Kst;
import local.audit.Op;
import local.audit.Txt;

/** 사규 읽기·찾기·등록·개정 비교. 예전 '사규 관리 시스템 v4'의 동작을 옮겼다. */
@Service
public class RuleService {
    private final RuleStore store;

    public RuleService(RuleStore store) { this.store = store; }

    public List<Map<String, Object>> regs() { return store.regs(); }
    public Map<String, Object> reg(String id) { return store.reg(id); }
    public Map<String, Object> state() { return store.state(); }

    public List<Map<String, Object>> categories() {
        List<Map<String, Object>> cats = new ArrayList<>(Rules.maps(state(), "categories"));
        cats.sort(Comparator.comparingDouble(c -> c.get("order") instanceof Number n ? n.doubleValue() : 999));
        return cats;
    }

    public String categoryName(String id) {
        for (Map<String, Object> c : Rules.maps(state(), "categories")) if (Rules.str(c, "id").equals(id)) return Rules.str(c, "name");
        return "미분류";
    }

    // ───── 목록 ─────
    public record RegRow(String id, String title, String status, int articles, String label, String effectiveDate, int versions, String category) { }
    public record Group(String name, String id, List<RegRow> regs) { }

    public RegRow row(Map<String, Object> r) {
        Map<String, Object> v = Rules.curVer(r);
        return new RegRow(Rules.str(r, "id"), Rules.str(r, "title"), Rules.str(r, "status"), Rules.arts(r).size(),
            v == null ? "" : Rules.str(v, "label"), v == null ? "" : date(Rules.str(v, "effectiveDate")), Rules.maps(r, "versions").size(),
            categoryName(Rules.str(r, "categoryId")));
    }

    static String date(String iso) { return iso.length() >= 10 ? iso.substring(0, 10) : iso; }

    public List<Group> groups() {
        List<Group> out = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (Map<String, Object> c : categories()) {
            String cid = Rules.str(c, "id");
            List<RegRow> rs = new ArrayList<>();
            for (Map<String, Object> r : regs()) if (cid.equals(Rules.str(r, "categoryId"))) { rs.add(row(r)); placed.add(Rules.str(r, "id")); }
            if (!rs.isEmpty()) out.add(new Group(Rules.str(c, "name"), cid, rs));
        }
        List<RegRow> rest = new ArrayList<>();
        for (Map<String, Object> r : regs()) if (!placed.contains(Rules.str(r, "id"))) rest.add(row(r));
        if (!rest.isEmpty()) out.add(new Group("미분류", "", rest));
        return out;
    }

    public Map<String, Object> stats() {
        Map<String, Object> s = new LinkedHashMap<>();
        int arts = 0;
        for (Map<String, Object> r : regs()) arts += Rules.arts(r).size();
        s.put("regs", regs().size());
        s.put("articles", arts);
        s.put("notes", (int) Rules.map(state(), "notes").values().stream().filter(v -> v != null && !v.toString().isBlank()).count());
        int bm = 0;
        for (Map<String, Object> f : Rules.maps(state(), "bookmarkFolders")) bm += Rules.list(f, "bookmarks").size();
        s.put("bookmarks", bm);
        int diff = 0;
        for (Map<String, Object> r : regs()) for (Map<String, Object> a : Rules.arts(r)) if (Rules.bool(a, "difficult")) diff++;
        s.put("difficult", diff);
        return s;
    }

    // ───── 읽기 ─────
    /** 다른 조문으로 가는 주소. 사규 이름이 없으면 같은 사규 안에서 찾는다. */
    Function<String[], String> resolver(Map<String, Object> self, String selfArtId) {
        return ref -> {
            Map<String, Object> target = self;
            if (ref[0] != null) {
                String n = Rules.nk(ref[0]);
                target = null;
                for (Map<String, Object> r : regs()) if (Rules.nk(Rules.str(r, "title")).equals(n)) { target = r; break; }
                if (target == null) for (Map<String, Object> r : regs()) {
                    String t = Rules.nk(Rules.str(r, "title"));
                    if (!t.isEmpty() && (t.contains(n) || n.contains(t))) { target = r; break; }
                }
                if (target == null) return null;
            }
            Double no = RuleParser.parseArtNo(ref[1]);
            if (no == null) return null;
            for (Map<String, Object> a : Rules.arts(target)) {
                if (Rules.bool(a, "supp")) continue;
                if (no.equals(RuleParser.parseArtNo(Rules.str(a, "number")))) {
                    if (target == self && Rules.str(a, "id").equals(selfArtId)) return null;
                    return "/rules/" + Rules.str(target, "id") + "#a-" + Rules.str(a, "id");
                }
            }
            return null;
        };
    }

    public record Art(String id, String number, String title, String label, String chapter, String html, String note, boolean bookmarked,
                      boolean difficult, String tag, boolean newChapter, boolean supp, String raw) { }

    public Map<String, Object> read(String regId, String vid, String q, boolean diffMode) {
        Map<String, Object> r = reg(regId);
        if (r == null) return null;
        Map<String, Object> v = Rules.version(r, vid);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reg", r);
        m.put("row", row(r));
        m.put("version", v);
        List<Map<String, Object>> vs = Rules.maps(r, "versions");
        m.put("versions", vs);
        int vi = v == null ? -1 : vs.indexOf(v);
        Map<String, Object> pv = vi > 0 ? vs.get(vi - 1) : null;
        m.put("prevVersion", pv);
        boolean dm = diffMode && pv != null;
        m.put("diff", dm);
        List<String> terms = new ArrayList<>();
        for (String t : Txt.str(q).split("[|\\s]+")) if (t.strip().length() >= 1) terms.add(t.strip());
        m.put("q", Txt.str(q));
        Map<String, Object> notes = Rules.map(state(), "notes");
        Set<String> marks = bookmarkKeys();
        List<Art> items = new ArrayList<>();
        String lastCh = null;
        List<Map<String, Object>> arts = v == null ? List.of() : Rules.maps(v, "articles");
        boolean current = v == Rules.curVer(r);
        for (Map<String, Object> a : arts) {
            String aid = Rules.str(a, "id");
            String ch = Rules.str(a, "chapter");
            boolean nc = !ch.equals(lastCh) && !ch.isEmpty();
            lastCh = ch;
            String html, tag = "";
            if (dm) {
                Map<String, Object> before = null;
                for (Map<String, Object> b : Rules.maps(pv, "articles")) if (Rules.str(b, "number").equals(Rules.str(a, "number"))) { before = b; break; }
                if (before == null) { tag = "new"; html = RuleRender.content(Rules.str(a, "content"), terms, null); }
                else if (!Rules.str(before, "content").equals(Rules.str(a, "content")) || !Rules.str(before, "title").equals(Rules.str(a, "title"))) {
                    tag = "chg";
                    html = "<div class=\"para\">" + RuleRender.diff(Rules.str(before, "content"), Rules.str(a, "content")) + "</div>";
                } else html = RuleRender.content(Rules.str(a, "content"), terms, current ? resolver(r, aid) : null);
            } else html = RuleRender.content(Rules.str(a, "content"), terms, current ? resolver(r, aid) : null);
            String key = regId + "::" + aid;
            Object note = notes.get(key);
            items.add(new Art(aid, Rules.str(a, "number"), Rules.str(a, "title"), Rules.artLabel(a), ch, html,
                note == null ? "" : note.toString(), marks.contains(key), Rules.bool(a, "difficult"), tag, nc, Rules.bool(a, "supp"), Rules.str(a, "content")));
        }
        List<Art> gone = new ArrayList<>();
        if (dm) {
            for (Map<String, Object> b : Rules.maps(pv, "articles")) {
                boolean still = arts.stream().anyMatch(a -> Rules.str(a, "number").equals(Rules.str(b, "number")));
                if (!still) gone.add(new Art(Rules.str(b, "id"), Rules.str(b, "number"), Rules.str(b, "title"), Rules.artLabel(b), "",
                    RuleRender.content(Rules.str(b, "content"), List.of(), null), "", false, false, "del", false, false, Rules.str(b, "content")));
            }
        }
        m.put("items", items);
        m.put("gone", gone);
        m.put("current", current);
        m.put("related", Rules.list(r, "relatedRegIds").stream().map(x -> reg(String.valueOf(x))).filter(x -> x != null).map(this::row).toList());
        return m;
    }

    Set<String> bookmarkKeys() {
        Set<String> s = new HashSet<>();
        for (Map<String, Object> f : Rules.maps(state(), "bookmarkFolders"))
            for (Map<String, Object> b : Rules.maps(f, "bookmarks")) s.add(Rules.str(b, "regId") + "::" + Rules.str(b, "articleId"));
        return s;
    }

    // ───── 찾기 ─────
    public record Hit(String regId, String regTitle, String articleId, String label, String snippet, double score, String why) { }

    public List<Hit> search(String q, String categoryId, int limit) {
        String query = Txt.str(q).strip();
        if (query.isEmpty()) return List.of();
        List<Hit> hits = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher ma = Pattern.compile("^(?:(.+?)\\s*)?제?\\s*(\\d+)\\s*조(?:\\s*의\\s*(\\d+))?(?:\\s*제?\\s*(\\d+)\\s*항)?$").matcher(query);
        if (ma.matches()) {
            double no = Integer.parseInt(ma.group(2)) + (ma.group(3) != null ? Integer.parseInt(ma.group(3)) / 100.0 : 0);
            String rn = ma.group(1) == null ? null : Rules.nk(ma.group(1));
            for (Map<String, Object> r : regs()) {
                if (!Txt.blank(categoryId) && !categoryId.equals(Rules.str(r, "categoryId"))) continue;
                if (rn != null && !Rules.nk(Rules.str(r, "title")).contains(rn)) continue;
                for (Map<String, Object> a : Rules.arts(r)) {
                    if (Rules.bool(a, "supp")) continue;
                    Double n = RuleParser.parseArtNo(Rules.str(a, "number"));
                    if (n != null && n == no) {
                        String key = Rules.str(r, "id") + "::" + Rules.str(a, "id");
                        seen.add(key);
                        hits.add(new Hit(Rules.str(r, "id"), Rules.str(r, "title"), Rules.str(a, "id"), Rules.artLabel(a),
                            RuleRender.snippet(Rules.str(a, "content"), List.of(), 160), 100, "조 번호 일치"));
                    }
                }
            }
        }
        List<String> terms = terms(query);
        String phrase = Rules.nk(query);
        Map<String, Object> notes = Rules.map(state(), "notes");
        for (Map<String, Object> r : regs()) {
            if (!Txt.blank(categoryId) && !categoryId.equals(Rules.str(r, "categoryId"))) continue;
            String rt = Rules.nk(Rules.str(r, "title"));
            for (Map<String, Object> a : Rules.arts(r)) {
                String key = Rules.str(r, "id") + "::" + Rules.str(a, "id");
                if (seen.contains(key)) continue;
                String head = Rules.nk(Rules.artLabel(a) + " " + Rules.str(a, "chapter"));
                String body = Rules.nk(RuleParser.mdStrip(Rules.str(a, "content")));
                String note = Rules.nk(notes.get(key) == null ? "" : notes.get(key).toString());
                String tags = Rules.nk(String.join(" ", Rules.list(a, "tags").stream().map(String::valueOf).toList()));
                double s = 0;
                int matched = 0;
                if (phrase.length() >= 3 && body.contains(phrase)) s += 12;
                for (String t : terms) {
                    String k = Rules.nk(t);
                    if (k.isEmpty()) continue;
                    double f = 0;
                    if (head.contains(k)) f += 5;
                    if (rt.contains(k)) f += 3;
                    int tc = count(body, k);
                    if (tc > 0) f += 1 + Math.log(1 + tc) * 2;
                    if (note.contains(k)) f += 2;
                    if (tags.contains(k)) f += 3;
                    if (f > 0) matched++;
                    s += f;
                }
                if (s <= 0) continue;
                if (terms.size() > 1 && matched == terms.size()) s *= 1.6;
                String why = matched == terms.size() ? "모든 낱말" : matched + "/" + terms.size() + " 낱말";
                hits.add(new Hit(Rules.str(r, "id"), Rules.str(r, "title"), Rules.str(a, "id"), Rules.artLabel(a),
                    RuleRender.snippet(Rules.str(a, "content"), terms, 180), s, why));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.size() > limit ? hits.subList(0, limit) : hits;
    }

    static int count(String hay, String k) {
        int n = 0, i = 0;
        while ((i = hay.indexOf(k, i)) >= 0) { n++; i += k.length(); }
        return n;
    }

    public static List<String> terms(String q) {
        List<String> out = new ArrayList<>();
        for (String t : Txt.str(q).split("[\\s,·]+")) {
            String x = t.strip().replaceAll("^[\"'「『]+|[\"'」』]+$", "");
            if (!x.isEmpty() && !out.contains(x)) out.add(x);
        }
        return out;
    }

    // ───── 등록·개정 ─────
    public Op add(Map<String, String> p, String text) {
        String body = Txt.str(text).replace("\r\n", "\n");
        if (body.isBlank()) return Op.bad("사규 글을 붙여 넣거나 파일을 고르세요.");
        if (body.length() > 3_000_000) return Op.bad("글이 너무 깁니다.");
        List<Map<String, Object>> arts = RuleParser.parse(body);
        if (arts.isEmpty()) return Op.bad("조문을 찾지 못했습니다.");
        Map<String, Object> ver = new LinkedHashMap<>();
        ver.put("id", Rules.uid("v"));
        String label = Txt.fit(p.get("label"), 60, false);
        String eff = Kst.date(p.get("effectiveDate")) == null ? Kst.today().toString() : Kst.date(p.get("effectiveDate")).toString();
        ver.put("effectiveDate", eff);
        ver.put("note", Txt.fit(p.get("note"), 500, true));
        ver.put("createdAt", Kst.stamp());
        ver.put("articles", arts);
        String target = Txt.str(p.get("reviseOf"));
        if (!target.isEmpty()) {
            Map<String, Object> r = reg(target);
            if (r == null) return Op.fail(404, "개정할 사규를 찾을 수 없습니다.");
            List<Map<String, Object>> old = Rules.arts(r);
            for (Map<String, Object> a : arts) {
                for (Map<String, Object> o : old) {
                    if (Rules.str(o, "number").equals(Rules.str(a, "number"))) {
                        a.put("id", o.get("id"));
                        a.put("difficult", o.get("difficult"));
                        a.put("tags", o.get("tags"));
                        break;
                    }
                }
            }
            ver.put("label", label.isEmpty() ? "개정 " + eff : label);
            Rules.maps(r, "versions").add(ver);
            r.put("currentVersionId", ver.get("id"));
            store.saveReg(r);
            return Op.ok("「" + Rules.str(r, "title") + "」 개정판을 넣었습니다 · 조 " + arts.size() + "개.", Rules.str(r, "id"));
        }
        String title = Txt.clean(p.get("title"), 120, false);
        if (Txt.blank(title)) return Op.bad("사규 이름을 적어 주세요.");
        ver.put("label", label.isEmpty() ? "제정" : label);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", Rules.uid("r"));
        r.put("title", title);
        r.put("categoryId", Txt.str(p.get("categoryId")));
        r.put("status", "active");
        r.put("departmentIds", new ArrayList<>());
        r.put("relatedRegIds", new ArrayList<>());
        r.put("versions", new ArrayList<>(List.of(ver)));
        r.put("currentVersionId", ver.get("id"));
        r.put("createdAt", Kst.stamp());
        store.saveReg(r);
        return Op.ok("「" + title + "」를 등록했습니다 · 조 " + arts.size() + "개.", Rules.str(r, "id"));
    }

    /** 붙여 넣은 글이 어떻게 갈라지는지 미리 본다. */
    public List<Map<String, Object>> preview(String text) { return RuleParser.parse(Txt.str(text)); }

    public Op saveMeta(String id, Map<String, String> p) {
        Map<String, Object> r = reg(id);
        if (r == null) return Op.fail(404, "사규를 찾을 수 없습니다.");
        String title = Txt.clean(p.get("title"), 120, false);
        if (Txt.blank(title)) return Op.bad("사규 이름을 적어 주세요.");
        r.put("title", title);
        r.put("categoryId", Txt.str(p.get("categoryId")));
        String st = Txt.str(p.get("status"));
        r.put("status", List.of("active", "폐지", "개정 예정", "검토 중").contains(st) ? st : "active");
        store.saveReg(r);
        return Op.ok("저장했습니다.");
    }

    public Op version(String id, String vid, String what, Map<String, String> p) {
        Map<String, Object> r = reg(id);
        if (r == null) return Op.fail(404, "사규를 찾을 수 없습니다.");
        Map<String, Object> v = null;
        for (Map<String, Object> x : Rules.maps(r, "versions")) if (Rules.str(x, "id").equals(vid)) v = x;
        if (v == null) return Op.fail(404, "판을 찾을 수 없습니다.");
        switch (what) {
            case "current" -> r.put("currentVersionId", vid);
            case "label" -> {
                String label = Txt.clean(p.get("label"), 60, false);
                if (Txt.blank(label)) return Op.bad("판 이름을 적어 주세요.");
                v.put("label", label);
                if (Kst.date(p.get("effectiveDate")) != null) v.put("effectiveDate", Kst.date(p.get("effectiveDate")).toString());
            }
            case "delete" -> {
                if (Rules.maps(r, "versions").size() <= 1) return Op.bad("판이 하나뿐이면 지울 수 없습니다. 사규를 지우세요.");
                Rules.maps(r, "versions").remove(v);
                if (vid.equals(Rules.str(r, "currentVersionId"))) {
                    List<Map<String, Object>> vs = Rules.maps(r, "versions");
                    r.put("currentVersionId", Rules.str(vs.get(vs.size() - 1), "id"));
                }
            }
            default -> { return Op.fail(404, "없는 작업입니다."); }
        }
        store.saveReg(r);
        return Op.ok("판을 고쳤습니다.");
    }

    public Op delete(String id) {
        Map<String, Object> r = reg(id);
        if (r == null) return Op.fail(404, "사규를 찾을 수 없습니다.");
        store.removeReg(id);
        Map<String, Object> notes = Rules.map(state(), "notes");
        notes.keySet().removeIf(k -> k.startsWith(id + "::"));
        for (Map<String, Object> f : Rules.maps(state(), "bookmarkFolders")) Rules.maps(f, "bookmarks").removeIf(b -> id.equals(Rules.str(b, "regId")));
        store.saveState();
        return Op.ok("「" + Rules.str(r, "title") + "」를 지웠습니다.");
    }

    /** 조문 하나에 대한 작업: 북마크, 해석 어려움 표시, 메모, 본문 고치기. */
    public Op article(String regId, String aid, String what, Map<String, String> p) {
        Map<String, Object> r = reg(regId);
        if (r == null) return Op.fail(404, "사규를 찾을 수 없습니다.");
        Map<String, Object> a = null;
        for (Map<String, Object> x : Rules.arts(r)) if (Rules.str(x, "id").equals(aid)) a = x;
        if (a == null) return Op.fail(404, "조문을 찾을 수 없습니다. 지금 판의 조문만 고칠 수 있습니다.");
        String key = regId + "::" + aid;
        switch (what) {
            case "bookmark" -> {
                boolean removed = false;
                for (Map<String, Object> f : Rules.maps(state(), "bookmarkFolders"))
                    removed |= Rules.maps(f, "bookmarks").removeIf(b -> regId.equals(Rules.str(b, "regId")) && aid.equals(Rules.str(b, "articleId")));
                if (!removed) {
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("regId", regId);
                    b.put("articleId", aid);
                    b.put("at", java.time.Instant.now().toString());
                    Rules.list(Rules.maps(state(), "bookmarkFolders").get(0), "bookmarks").add(0, b);
                }
                store.saveState();
                return Op.ok(removed ? "북마크를 풀었습니다." : "북마크에 넣었습니다.");
            }
            case "difficult" -> {
                a.put("difficult", !Rules.bool(a, "difficult"));
                store.saveReg(r);
                return Op.ok(Rules.bool(a, "difficult") ? "해석 어려운 조항으로 표시했습니다." : "표시를 풀었습니다.");
            }
            case "note" -> {
                String t = Txt.clean(p.get("text"), 20_000, true);
                if (t == null) return Op.bad("메모가 너무 깁니다.");
                Map<String, Object> notes = Rules.map(state(), "notes");
                if (t.isBlank()) notes.remove(key); else notes.put(key, t);
                store.saveState();
                return Op.ok(t.isBlank() ? "메모를 지웠습니다." : "메모를 저장했습니다.");
            }
            case "edit" -> {
                String title = Txt.clean(p.get("title"), 120, false);
                String content = Txt.clean(p.get("content"), 400_000, true);
                if (title == null || content == null) return Op.bad("입력을 확인하세요.");
                a.put("title", title);
                a.put("content", content);
                store.saveReg(r);
                return Op.ok("조문을 고쳤습니다.");
            }
            default -> { return Op.fail(404, "없는 작업입니다."); }
        }
    }

    // ───── 북마크·메모·어려운 조항 ─────
    public record Mark(String regId, String regTitle, String articleId, String label, String text, String at) { }

    public Map<String, List<Mark>> marks() {
        Map<String, List<Mark>> m = new LinkedHashMap<>();
        List<Mark> bm = new ArrayList<>(), nt = new ArrayList<>(), df = new ArrayList<>();
        for (Map<String, Object> f : Rules.maps(state(), "bookmarkFolders")) {
            for (Map<String, Object> b : Rules.maps(f, "bookmarks")) {
                Mark k = mark(Rules.str(b, "regId"), Rules.str(b, "articleId"), Rules.str(f, "name"), date(Rules.str(b, "at")));
                if (k != null) bm.add(k);
            }
        }
        for (var e : Rules.map(state(), "notes").entrySet()) {
            String[] k = e.getKey().split("::", 2);
            if (k.length < 2 || e.getValue() == null || e.getValue().toString().isBlank()) continue;
            Mark x = mark(k[0], k[1], e.getValue().toString(), "");
            if (x != null) nt.add(x);
        }
        for (Map<String, Object> r : regs())
            for (Map<String, Object> a : Rules.arts(r)) if (Rules.bool(a, "difficult"))
                df.add(new Mark(Rules.str(r, "id"), Rules.str(r, "title"), Rules.str(a, "id"), Rules.artLabel(a), "", ""));
        m.put("bookmarks", bm);
        m.put("notes", nt);
        m.put("difficult", df);
        return m;
    }

    Mark mark(String regId, String aid, String text, String at) {
        Map<String, Object> r = reg(regId);
        if (r == null) return null;
        for (Map<String, Object> a : Rules.arts(r))
            if (Rules.str(a, "id").equals(aid)) return new Mark(regId, Rules.str(r, "title"), aid, Rules.artLabel(a), text, at);
        return null;
    }

    // ───── 개정 비교 ─────
    public record Cmp(String number, String title, String tag, String html) { }

    public List<Cmp> compare(String regId, String v1, String v2) {
        Map<String, Object> r = reg(regId);
        if (r == null) return List.of();
        Map<String, Object> a = Rules.version(r, v1), b = Rules.version(r, v2);
        if (a == null || b == null) return List.of();
        List<Cmp> out = new ArrayList<>();
        List<Map<String, Object>> after = Rules.maps(b, "articles"), before = Rules.maps(a, "articles");
        for (Map<String, Object> x : after) {
            Map<String, Object> o = null;
            for (Map<String, Object> y : before) if (Rules.str(y, "number").equals(Rules.str(x, "number"))) { o = y; break; }
            if (o == null) out.add(new Cmp(Rules.str(x, "number"), Rules.str(x, "title"), "new", RuleRender.esc(Rules.str(x, "content"))));
            else if (!Rules.str(o, "content").equals(Rules.str(x, "content")) || !Rules.str(o, "title").equals(Rules.str(x, "title")))
                out.add(new Cmp(Rules.str(x, "number"), Rules.str(x, "title"), "chg", RuleRender.diff(Rules.str(o, "content"), Rules.str(x, "content"))));
            else out.add(new Cmp(Rules.str(x, "number"), Rules.str(x, "title"), "same", RuleRender.esc(Rules.str(x, "content"))));
        }
        for (Map<String, Object> y : before) {
            boolean still = after.stream().anyMatch(x -> Rules.str(x, "number").equals(Rules.str(y, "number")));
            if (!still) out.add(new Cmp(Rules.str(y, "number"), Rules.str(y, "title"), "del", RuleRender.esc(Rules.str(y, "content"))));
        }
        return out;
    }

    // ───── 카테고리 ─────
    public Op category(String what, Map<String, String> p) {
        List<Map<String, Object>> cats = Rules.maps(state(), "categories");
        String id = Txt.str(p.get("id"));
        switch (what) {
            case "save" -> {
                String name = Txt.clean(p.get("name"), 40, false);
                if (Txt.blank(name)) return Op.bad("이름을 적어 주세요.");
                Integer order = Txt.intOrNull(p.get("order"));
                Map<String, Object> c = cats.stream().filter(x -> Rules.str(x, "id").equals(id)).findFirst().orElse(null);
                if (c == null) { c = new LinkedHashMap<>(); c.put("id", Rules.uid("c")); cats.add(c); }
                c.put("name", name);
                c.put("order", order == null ? cats.size() : order);
            }
            case "delete" -> {
                if (!cats.removeIf(x -> Rules.str(x, "id").equals(id))) return Op.fail(404, "카테고리를 찾을 수 없습니다.");
                for (Map<String, Object> r : regs()) if (id.equals(Rules.str(r, "categoryId"))) { r.put("categoryId", ""); store.saveReg(r); }
            }
            default -> { return Op.fail(404, "없는 작업입니다."); }
        }
        store.saveState();
        return Op.ok("카테고리를 고쳤습니다.");
    }

    // ───── 옮겨 오기·내보내기 ─────
    public Op importJson(byte[] bytes, boolean merge) {
        JsonNode root;
        try {
            root = RuleStore.JSON.readTree(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).replace("﻿", ""));
        } catch (Exception e) {
            return Op.bad("JSON 파일을 읽지 못했습니다.");
        }
        if (root == null || !root.has("regulations") || !root.get("regulations").isArray()) {
            if (root != null && root.has("items")) return Op.bad("업무·일정 파일입니다. 업무 설정의 '옮겨 오기'에 넣으세요.");
            return Op.bad("사규 관리 데이터 형식이 아닙니다.");
        }
        Map<String, Object> all;
        try { all = RuleStore.JSON.convertValue(root, RuleStore.MAP); } catch (Exception e) { return Op.bad("파일 내용을 읽지 못했습니다."); }
        List<Map<String, Object>> incoming = new ArrayList<>();
        for (Map<String, Object> r : Rules.maps(all, "regulations")) incoming.add(Rules.fixReg(r));
        all.remove("regulations");
        Map<String, Object> st = Rules.fixState(all);
        if (!merge) {
            store.replaceAll(incoming, st);
            int arts = incoming.stream().mapToInt(r -> Rules.arts(r).size()).sum();
            return Op.ok("옮겨 왔습니다 · 사규 " + incoming.size() + "건, 조문 " + arts + "개.");
        }
        int added = 0, updated = 0;
        for (Map<String, Object> r : incoming) {
            Map<String, Object> have = reg(Rules.str(r, "id"));
            if (have == null) { store.saveReg(r); added++; }
            else if (Rules.str(r, "updatedAt").compareTo(Rules.str(have, "updatedAt")) > 0) { have.clear(); have.putAll(r); store.saveReg(have); updated++; }
        }
        Map<String, Object> mine = state();
        Map<String, Object> notes = Rules.map(mine, "notes");
        for (var e : Rules.map(st, "notes").entrySet()) {
            Object cur = notes.get(e.getKey());
            String in = String.valueOf(e.getValue());
            if (cur == null) notes.put(e.getKey(), in);
            else if (!cur.toString().contains(in)) notes.put(e.getKey(), cur + "\n---\n" + in);
        }
        for (String k : new String[] { "categories", "departments", "bookmarkFolders", "checklists", "violationMemos", "collections",
            "riskAssessments", "correctiveActions", "auditHistory", "glossaryCustom", "synonyms" }) {
            List<Object> to = Rules.list(mine, k);
            Set<String> ids = new HashSet<>();
            for (Object o : to) if (o instanceof Map<?, ?> m && m.get("id") != null) ids.add(m.get("id").toString());
            for (Object o : Rules.list(st, k)) if (!(o instanceof Map<?, ?> m) || m.get("id") == null || !ids.contains(m.get("id").toString())) to.add(o);
        }
        store.saveState();
        return Op.ok("합쳤습니다 · 새 사규 " + added + "건, 더 최근 것으로 바꾼 사규 " + updated + "건.");
    }

    public String exportJson() {
        Map<String, Object> out = new LinkedHashMap<>(state());
        out.put("regulations", regs());
        out.put("savedAt", java.time.Instant.now().toString());
        out.put("schemaVersion", 4);
        try {
            return RuleStore.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 사규 하나를 글로 내려받기. 다시 붙여 넣으면 같은 모양으로 갈라진다. */
    public String exportText(String regId) {
        Map<String, Object> r = reg(regId);
        if (r == null) return null;
        StringBuilder sb = new StringBuilder(Rules.str(r, "title")).append("\n\n");
        String lastCh = "";
        for (Map<String, Object> a : Rules.arts(r)) {
            String ch = Rules.str(a, "chapter");
            boolean supp = Rules.bool(a, "supp");
            if (!ch.isEmpty() && !ch.equals(lastCh) && !Rules.bool(a, "attach")) sb.append(ch).append("\n\n");
            lastCh = ch;
            String label = Rules.artLabel(a);
            if (supp) label = label.replaceFirst("^부칙\\S*\\s+", "");
            if (!(supp && label.startsWith("부칙"))) sb.append(label).append('\n');
            sb.append(Rules.str(a, "content")).append("\n\n");
        }
        return sb.toString();
    }
}
