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
    public record RegRow(String id, String title, String status, int articles, String label, String effectiveDate, int versions, String category,
                         String owner, int reviewMonths, String reviewDue, String reviewTone, String reviewText) { }
    public record Group(String name, String id, List<RegRow> regs) { }

    public RegRow row(Map<String, Object> r) {
        Map<String, Object> v = Rules.curVer(r);
        String eff = v == null ? "" : date(Rules.str(v, "effectiveDate"));
        int months = Rules.str(r, "reviewMonths").matches("\\d{1,3}") ? Integer.parseInt(Rules.str(r, "reviewMonths")) : 0;
        String due = "", tone = "", text = "";
        java.time.LocalDate base = Kst.date(Rules.str(r, "reviewedAt").isEmpty() ? eff : Rules.str(r, "reviewedAt"));
        if (months > 0 && base != null && !"폐지".equals(Rules.str(r, "status"))) {
            java.time.LocalDate d = base.plusMonths(months);
            long left = java.time.temporal.ChronoUnit.DAYS.between(Kst.today(), d);
            due = d.toString();
            tone = left < 0 ? "bad" : left <= 30 ? "warn" : "";
            text = left < 0 ? "검토 " + (-left) + "일 지남" : left <= 30 ? "검토 D-" + left : "다음 검토 " + due;
        }
        return new RegRow(Rules.str(r, "id"), Rules.str(r, "title"), Rules.str(r, "status"), Rules.arts(r).size(),
            v == null ? "" : Rules.str(v, "label"), eff, Rules.maps(r, "versions").size(),
            categoryName(Rules.str(r, "categoryId")), Rules.str(r, "owner"), months, due, tone, text);
    }

    /** 검토할 때가 됐거나 지난 사규(30일 안 포함). */
    public List<RegRow> reviewDue() {
        List<RegRow> out = new ArrayList<>();
        for (Map<String, Object> r : regs()) { RegRow row = row(r); if (!row.reviewTone().isEmpty()) out.add(row); }
        out.sort(java.util.Comparator.comparing(RegRow::reviewDue));
        return out;
    }

    public Op reviewed(String id) {
        Map<String, Object> r = reg(id);
        if (r == null) return Op.fail(404, "사규를 찾을 수 없습니다.");
        r.put("reviewedAt", Kst.today().toString());
        store.saveReg(r);
        RegRow row = row(r);
        return Op.ok("검토를 마친 것으로 기록했습니다." + (row.reviewDue().isEmpty() ? "" : " 다음 검토일 " + row.reviewDue() + "."));
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
        s.put("reviewDue", reviewDue().size());
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
                      boolean difficult, String tag, boolean newChapter, boolean supp, String raw, String editHtml, String noteHtml) { }

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
        Map<String, Object> notesHtml = Rules.map(state(), "notesHtml");
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
            } else if (!Rules.str(a, "html").isEmpty()) html = RuleRender.highlight(RuleRender.linkRefsHtml(Rules.str(a, "html"), current ? resolver(r, aid) : null), terms);
            else html = RuleRender.content(Rules.str(a, "content"), terms, current ? resolver(r, aid) : null);
            String key = regId + "::" + aid;
            Object note = notes.get(key);
            Object noteH = notesHtml.get(key);
            items.add(new Art(aid, Rules.str(a, "number"), Rules.str(a, "title"), Rules.artLabel(a), ch, html,
                note == null ? "" : note.toString(), marks.contains(key), Rules.bool(a, "difficult"), tag, nc, Rules.bool(a, "supp"), Rules.str(a, "content"),
                Rules.str(a, "html").isEmpty() ? RuleRender.content(Rules.str(a, "content"), List.of(), null) : Rules.str(a, "html"),
                noteH != null ? noteH.toString() : local.audit.extract.Rich.fromText(note == null ? "" : note.toString())));
        }
        List<Art> gone = new ArrayList<>();
        if (dm) {
            for (Map<String, Object> b : Rules.maps(pv, "articles")) {
                boolean still = arts.stream().anyMatch(a -> Rules.str(a, "number").equals(Rules.str(b, "number")));
                if (!still) gone.add(new Art(Rules.str(b, "id"), Rules.str(b, "number"), Rules.str(b, "title"), Rules.artLabel(b), "",
                    RuleRender.content(Rules.str(b, "content"), List.of(), null), "", false, false, "del", false, false, Rules.str(b, "content"), "", ""));
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
        List<String> base = tokenize(query);
        if (base.isEmpty()) base = terms(query);
        Map<String, Double> ex = expand(base);
        List<String> shown = new ArrayList<>(ex.keySet());
        Map<String, List<String>> rel = new LinkedHashMap<>();
        for (String t : base) {
            List<String> ws = new ArrayList<>();
            for (String w : ex.keySet()) if (w.equals(t) || related(t, w)) ws.add(w);
            rel.put(t, ws);
        }
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
                for (String t : base) {
                    boolean hit = false;
                    for (String w : rel.get(t)) {
                        String k = Rules.nk(w);
                        if (k.isEmpty()) continue;
                        double f = 0;
                        if (head.contains(k)) f += 5;
                        if (rt.contains(k)) f += 3;
                        int tc = count(body, k);
                        if (tc > 0) f += 1 + Math.log(1 + tc) * 2;
                        if (note.contains(k)) f += 2;
                        if (tags.contains(k)) f += 3;
                        if (f > 0) hit = true;
                        s += f * ex.get(w);
                    }
                    if (hit) matched++;
                }
                if (s <= 0) continue;
                if (base.size() > 1 && matched == base.size()) s *= 1.6;
                String why = base.isEmpty() ? "" : matched == base.size() ? "모든 낱말" : matched + "/" + base.size() + " 낱말";
                hits.add(new Hit(Rules.str(r, "id"), Rules.str(r, "title"), Rules.str(a, "id"), Rules.artLabel(a),
                    RuleRender.snippet(Rules.str(a, "content"), shown, 180), s, why));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.size() > limit ? hits.subList(0, limit) : hits;
    }

    /** t를 찾다가 동의어 w로 넓혀 찾았는지. */
    private boolean related(String t, String w) {
        for (List<String> g : synGroups()) if (inGroup(g, t) && g.contains(w)) return true;
        for (Gloss gl : glossary()) if (gl.term().equals(t) && gl.aliases().contains(w)) return true;
        return false;
    }

    // ───── 동의어·낱말 다듬기 ─────
    public static final List<List<String>> BUILTIN_SYN = List.of(
        List.of("음주운전", "음주", "주취", "음주측정", "면허취소", "면허정지", "품위"),
        List.of("괴롭힘", "직장내괴롭힘", "폭언", "폭행", "갑질", "모욕", "따돌림", "성희롱", "고충"),
        List.of("개인정보", "유출", "정보보호", "보안", "기밀", "누설", "정보주체"),
        List.of("겸직", "영리업무", "부업", "겸업", "이중취업"),
        List.of("지각", "근태", "결근", "무단결근", "조퇴", "출근"),
        List.of("징계", "해고", "파면", "해임", "정직", "감봉", "견책", "징계위원회", "양정"),
        List.of("퇴직", "퇴사", "사직", "면직"),
        List.of("금품", "향응", "뇌물", "청탁", "접대"),
        List.of("휴가", "연차", "병가"),
        List.of("출장", "여비"),
        List.of("횡령", "배임", "유용"),
        List.of("소명", "진술", "의견진술"),
        List.of("조사", "사실조사", "진상조사"),
        List.of("재심", "이의신청", "불복"));
    static final Set<String> STOP = Set.of(("의 가 이 은 는 을 를 에 에서 와 과 로 으로 및 또는 등 한 할 하는 한다 하여 해야 했을 때 경우 관련 대한 어떻게 처리 "
        + "해야하나 하나요 되나요 있나요 무엇 직원 사원 회사 팀원 들어왔다 됐습니다 됐어요 몰래 상습 고객 거래처").split(" "));

    public static List<String> tokenize(String q) {
        List<String> out = new ArrayList<>();
        for (String w : Txt.str(q).replaceAll("[「」『』\"'“”‘’.,?!()·]", " ").split("\\s+")) {
            String x = w.replaceFirst("(으로|에서|에게|하면|했을|하는|했다|하다|한다|이다|이란|란|은|는|이|가|을|를|에|와|과|의|로|도|만)$", "");
            if (!x.isEmpty() && !STOP.contains(x) && !out.contains(x)) out.add(x);
        }
        return out;
    }

    public List<List<String>> synGroups() {
        List<List<String>> g = new ArrayList<>(BUILTIN_SYN);
        for (Map<String, Object> s : Rules.maps(state(), "synonyms")) {
            List<String> words = Rules.list(s, "words").stream().map(String::valueOf).filter(w -> !w.isBlank()).toList();
            if (!words.isEmpty()) g.add(words);
        }
        return g;
    }

    public List<Map<String, Object>> userSynonyms() { return Rules.maps(state(), "synonyms"); }

    static boolean inGroup(List<String> g, String t) {
        for (String w : g) if (w.equals(t) || (t.length() >= 2 && w.length() >= 2 && (w.contains(t) || t.contains(w)))) return true;
        return false;
    }

    /** 찾을 말을 동의어·별칭으로 넓힌다. 원래 말은 1, 넓힌 말은 0.55~0.6. */
    public Map<String, Double> expand(List<String> terms) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String t : terms) out.put(t, 1.0);
        for (String t : terms) {
            for (List<String> g : synGroups()) if (inGroup(g, t)) for (String w : g) out.putIfAbsent(w, .55);
            for (Gloss gl : glossary()) if (gl.term().equals(t)) for (String a : gl.aliases()) out.putIfAbsent(a, .6);
        }
        return out;
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
        return addArticles(p, arts);
    }

    /** 같은 이름의 사규. 등록할 때 개정 버전으로 넣을지 묻는 데 쓴다. */
    public Map<String, Object> sameTitle(String title) {
        String n = Rules.nk(title);
        if (n.isEmpty()) return null;
        for (Map<String, Object> r : regs()) if (Rules.nk(Rules.str(r, "title")).equals(n)) return r;
        return null;
    }

    /** 미리보기에서 사람이 고친 조 목록을 받아 다듬는다. */
    public static List<Map<String, Object>> cleanArticles(List<Map<String, Object>> in) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (in == null) return out;
        for (Map<String, Object> a : in) {
            if (a == null || out.size() >= 3000) continue;
            Map<String, Object> x = new LinkedHashMap<>();
            String id = Rules.str(a, "id");
            x.put("id", id.matches("[A-Za-z0-9_]{1,40}") ? id : Rules.uid("a"));
            x.put("number", Txt.fit(Rules.str(a, "number"), 60, false));
            x.put("title", Txt.fit(Rules.str(a, "title"), 120, false));
            x.put("chapter", Txt.fit(Rules.str(a, "chapter"), 120, false));
            if (!Rules.str(a, "section").isEmpty()) x.put("section", Txt.fit(Rules.str(a, "section"), 120, false));
            x.put("content", Txt.fit(Rules.str(a, "content"), 400_000, true));
            x.put("tags", new java.util.ArrayList<>());
            x.put("difficult", false);
            x.put("crossRefs", new java.util.ArrayList<>());
            if (Rules.bool(a, "supp")) x.put("supp", true);
            if (Rules.bool(a, "attach")) x.put("attach", true);
            // 문서에서 읽은 원본 서식(조마다 잘라 붙인 HTML). 내용을 고친 조는 화면에서 빼고 보낸다.
            String html = Rules.str(a, "html");
            if (!html.isBlank() && html.length() <= 3_000_000) { String h = local.audit.HtmlClean.clean(html); if (!h.isBlank()) x.put("html", h); }
            if (Rules.str(x, "number").isEmpty() && Rules.str(x, "content").isBlank()) continue;
            out.add(x);
        }
        return out;
    }

    /**
     * 조 목록으로 등록·개정한다. mode: (비움) 같은 이름이 있으면 묻기 · revise 같은 이름 사규의 개정판 · separate 따로 등록.
     * 같은 이름이 있는데 mode가 비어 있으면 409로 알려 준다(extra에 그 사규 ID).
     */
    public Op addArticles(Map<String, String> p, List<Map<String, Object>> arts) {
        if (arts.isEmpty()) return Op.bad("조문을 찾지 못했습니다.");
        if (Txt.blank(p.get("reviseOf"))) {
            Map<String, Object> dup = sameTitle(p.get("title"));
            String mode = Txt.str(p.get("mode"));
            if (dup != null && mode.isEmpty())
                return Op.fail(409, "같은 이름의 사규 「" + Rules.str(dup, "title") + "」가 있습니다 (판 " + Rules.maps(dup, "versions").size() + "개).").extra(Rules.str(dup, "id"));
            if (dup != null && "revise".equals(mode)) {
                p = new java.util.HashMap<>(p);
                p.put("reviseOf", Rules.str(dup, "id"));
            }
        }
        Map<String, Object> ver = new LinkedHashMap<>();
        ver.put("id", Rules.uid("v"));
        String label = Txt.fit(p.get("label"), 60, false);
        String eff = Kst.date(p.get("effectiveDate")) == null ? Kst.today().toString() : Kst.date(p.get("effectiveDate")).toString();
        ver.put("effectiveDate", eff);
        ver.put("note", Txt.fit(Txt.blank(p.get("note")) ? p.get("reason") : p.get("note"), 500, true));
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

    /**
     * 지금 판의 조문을 통째로 고친다(분석 미리보기 편집기에서 고친 목록).
     * 조 번호가 같은 조는 ID·북마크·메모·어려움 표시를 이어 받고, 내용이 그대로면 서식(HTML)도 남긴다.
     */
    public Op replaceArticles(String id, List<Map<String, Object>> arts) {
        Map<String, Object> r = reg(id);
        if (r == null) return Op.fail(404, "사규를 찾을 수 없습니다.");
        if (arts.isEmpty()) return Op.bad("조문이 하나도 없습니다. 사규를 지우려면 '사규 삭제'를 쓰세요.");
        Map<String, Object> v = Rules.curVer(r);
        if (v == null) return Op.fail(404, "지금 판을 찾을 수 없습니다.");
        List<Map<String, Object>> old = Rules.arts(r);
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (Map<String, Object> a : arts) {
            Map<String, Object> match = null;
            for (Map<String, Object> o : old) {
                String oid = Rules.str(o, "id");
                if (taken.contains(oid)) continue;
                if (oid.equals(Rules.str(a, "id")) || Rules.str(o, "number").equals(Rules.str(a, "number"))) { match = o; break; }
            }
            if (match == null) continue;
            taken.add(Rules.str(match, "id"));
            a.put("id", match.get("id"));
            if (match.get("difficult") != null) a.put("difficult", match.get("difficult"));
            if (match.get("tags") != null) a.put("tags", match.get("tags"));
            if (match.get("html") != null && Rules.str(match, "content").equals(Rules.str(a, "content"))) a.put("html", match.get("html"));
        }
        v.put("articles", arts);
        store.saveReg(r);
        return Op.ok("「" + Rules.str(r, "title") + "」 조문을 고쳤습니다 · 조 " + arts.size() + "개.", id);
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
        if (p.containsKey("owner")) r.put("owner", Txt.fit(p.get("owner"), 60, false));
        if (p.containsKey("reviewMonths")) {
            Integer m = Txt.intOrNull(p.get("reviewMonths"));
            r.put("reviewMonths", m == null || m <= 0 ? "" : String.valueOf(Math.min(m, 120)));
        }
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
                Map<String, Object> notesHtml = Rules.map(state(), "notesHtml");
                if (t.isBlank()) { notes.remove(key); notesHtml.remove(key); }
                else {
                    notes.put(key, t);
                    String h = local.audit.HtmlClean.clean(p.get("html"));
                    if (h.isBlank()) notesHtml.remove(key); else notesHtml.put(key, h);
                }
                store.saveState();
                return Op.ok(t.isBlank() ? "메모를 지웠습니다." : "메모를 저장했습니다.");
            }
            case "remove" -> {
                List<Map<String, Object>> list = Rules.arts(r);
                if (list.size() <= 1) return Op.bad("마지막 조는 지울 수 없습니다. 사규를 지우려면 '사규 삭제'를 쓰세요.");
                list.remove(a);
                store.saveReg(r);
                return Op.ok("'" + Rules.str(a, "number") + "'를 지웠습니다.");
            }
            case "edit" -> {
                String title = Txt.clean(p.get("title"), 120, false);
                String content = Txt.clean(p.get("content"), 400_000, true);
                if (title == null) return Op.bad("조 제목은 120자까지 적을 수 있습니다.");
                if (content == null) return Op.bad("조문이 너무 깁니다(40만 자까지).");
                a.put("title", title);
                a.put("content", content);
                if (p.containsKey("html")) { String h = local.audit.HtmlClean.clean(p.get("html")); if (h.isBlank()) a.remove("html"); else a.put("html", h); }
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

    // ───── 용어 사전 ─────
    public record Gloss(String term, String def, String regId, String regTitle, String articleId, String label, String customId, List<String> aliases) { }

    private List<Gloss> glossCache;
    private long glossVersion = -1;

    /** 조문의 "○○"이란 … 정의를 뽑고, 직접 넣은 용어를 더한다. */
    public synchronized List<Gloss> glossary() {
        if (glossCache != null && glossVersion == store.version()) return glossCache;
        List<Gloss> g = new ArrayList<>();
        Pattern def = Pattern.compile("[\"“‘']([^\"”’']{1,20})[\"”’']\\s*(?:이)?란\\s*([^.\\n]{3,200})");
        for (Map<String, Object> r : regs())
            for (Map<String, Object> a : Rules.arts(r)) {
                Matcher m = def.matcher(Rules.str(a, "content"));
                while (m.find()) g.add(new Gloss(m.group(1).strip(), m.group(2).strip(), Rules.str(r, "id"), Rules.str(r, "title"),
                    Rules.str(a, "id"), Rules.artLabel(a), null, List.of()));
            }
        for (Map<String, Object> c : Rules.maps(state(), "glossaryCustom"))
            g.add(new Gloss(Rules.str(c, "term"), Rules.str(c, "def"), null, null, null, null, Rules.str(c, "id"),
                Rules.list(c, "aliases").stream().map(String::valueOf).toList()));
        glossCache = g;
        glossVersion = store.version();
        return g;
    }

    public Op glossaryEdit(String what, Map<String, String> p) {
        List<Map<String, Object>> list = Rules.maps(state(), "glossaryCustom");
        if ("delete".equals(what)) {
            if (!list.removeIf(x -> Rules.str(x, "id").equals(p.get("id")))) return Op.fail(404, "용어를 찾을 수 없습니다.");
            store.saveState();
            return Op.ok("용어를 지웠습니다.");
        }
        String term = Txt.clean(p.get("term"), 40, false);
        String def = Txt.clean(p.get("def"), 1000, true);
        if (Txt.blank(term) || def == null) return Op.bad("용어와 정의를 확인하세요.");
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("id", Rules.uid("g"));
        x.put("term", term);
        x.put("def", def);
        List<String> al = new ArrayList<>();
        for (String a : Txt.str(p.get("aliases")).split("[,，]")) if (!a.isBlank()) al.add(a.strip());
        x.put("aliases", al);
        list.add(x);
        store.saveState();
        return Op.ok("용어를 넣었습니다. 찾기와 상황 질의에서 별칭까지 함께 찾습니다.");
    }

    public Op synonymEdit(String what, Map<String, String> p) {
        List<Map<String, Object>> list = Rules.maps(state(), "synonyms");
        if ("delete".equals(what)) {
            if (!list.removeIf(x -> Rules.str(x, "id").equals(p.get("id")))) return Op.fail(404, "동의어 묶음을 찾을 수 없습니다.");
            store.saveState();
            return Op.ok("동의어 묶음을 지웠습니다.");
        }
        List<String> words = new ArrayList<>();
        for (String w : Txt.str(p.get("words")).split("[,，\\s]+")) if (!w.isBlank() && !words.contains(w.strip())) words.add(w.strip());
        if (words.size() < 2) return Op.bad("같은 뜻의 말을 쉼표로 둘 이상 적으세요.");
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("id", Rules.uid("sy"));
        x.put("words", words);
        list.add(x);
        store.saveState();
        return Op.ok("동의어 묶음을 넣었습니다.");
    }

    // ───── 상황 질의 ─────
    static final Pattern MISCONDUCT = Pattern.compile("(음주|괴롭힘|폭언|폭행|성희롱|유출|누설|횡령|배임|금품|향응|겸직|부업|영리|결근|지각|무단|위반|비위|부정|도박|허위|사고)");
    public static final Map<String, String> ROLE = new LinkedHashMap<>();
    static {
        ROLE.put("ground", "① 근거 (의무·금지)");
        ROLE.put("cause", "② 징계 사유");
        ROLE.put("level", "③ 처벌·징계 수위");
        ROLE.put("proc", "④ 절차 (조사·소명·위원회)");
        ROLE.put("remedy", "⑤ 재심·구제");
        ROLE.put("etc", "기타 관련 조항");
    }

    static String roleOf(String title, String text) {
        String t = title + " " + text;
        if (Pattern.compile("재심|이의").matcher(title).find()) return "remedy";
        if (Pattern.compile("징계\\s*사유|해고\\s*사유|징계\\s*대상").matcher(title).find()) return "cause";
        if (Pattern.compile("양정|가중|감경").matcher(title).find() || (title.contains("종류") && Pattern.compile("징계|해고").matcher(t).find())) return "level";
        if (Pattern.compile("징계\\s*위원회|절차|소명|조사|의결|통보|출석").matcher(title).find()) return "proc";
        if (Pattern.compile("(하여서는|아니\\s*된다|금지|하여야\\s*한다|의무)").matcher(t).find()) return "ground";
        return "etc";
    }

    public static List<String> sentences(String t) {
        List<String> out = new ArrayList<>();
        for (String s : RuleParser.mdStrip(Txt.str(t)).replace('\n', ' ').split("(?<=[다.])\\s+")) if (!s.isBlank()) out.add(s.strip());
        return out;
    }

    public record Cite(String text, String regId, String articleId, String source) { }
    public record SolHit(Hit hit, String role) { }

    public Map<String, Object> solve(String q) {
        String query = Txt.str(q).strip();
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("q", query);
        if (query.isEmpty()) return v;
        List<Hit> found = search(query, null, 200);
        List<Hit> pool = new ArrayList<>();
        for (Hit h : found) if (h.score() >= 1.5 || "조 번호 일치".equals(h.why())) { pool.add(h); if (pool.size() >= 14) break; }
        boolean mis = MISCONDUCT.matcher(query).find();
        if (!mis) for (int i = 0; i < Math.min(5, pool.size()); i++) if (articleText(pool.get(i)).contains("징계")) mis = true;
        if (mis) {
            Set<String> have = new HashSet<>();
            for (Hit h : pool) have.add(h.regId() + "::" + h.articleId());
            for (Map<String, Object> r : regs())
                for (Map<String, Object> a : Rules.arts(r))
                    if (Pattern.compile("징계|양정|재심|소명|사실\\s*조사|의결").matcher(Rules.str(a, "title")).find()
                        && have.add(Rules.str(r, "id") + "::" + Rules.str(a, "id")))
                        pool.add(new Hit(Rules.str(r, "id"), Rules.str(r, "title"), Rules.str(a, "id"), Rules.artLabel(a),
                            RuleRender.snippet(Rules.str(a, "content"), List.of(), 180), .5, "징계 절차 자동 연계"));
        }
        List<String> terms = new ArrayList<>(expand(tokenize(query)).keySet());
        Map<String, List<SolHit>> groups = new LinkedHashMap<>();
        for (String k : ROLE.keySet()) groups.put(k, new ArrayList<>());
        Map<String, Integer> levels = new LinkedHashMap<>();
        List<Cite> cautions = new ArrayList<>(), deadlines = new ArrayList<>(), notesOut = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        Map<String, Object> notes = Rules.map(state(), "notes");
        Pattern lv = Pattern.compile("파면|해임|해고|강등|정직(?:\\s*\\d+\\s*개월(?:\\s*이상)?)?|감봉(?:\\s*\\d+\\s*개월)?|견책");
        Pattern dl = Pattern.compile("\\d+\\s*(?:일|개월|시간)\\s*(?:이내|전까지|전|까지)");
        Pattern caution = Pattern.compile("다만|예외|불구하고|가중|감경|아니한다|병과|지체\\s*없이");
        Set<String> dlSeen = new HashSet<>();
        for (Hit h : pool) {
            Map<String, Object> a = article(h.regId(), h.articleId());
            if (a == null || Rules.bool(a, "supp")) continue;
            String text = RuleParser.mdStrip(Rules.str(a, "content"));
            String role = roleOf(Rules.str(a, "title"), text);
            groups.get(role).add(new SolHit(h, role));
            String src = h.regTitle() + " " + Rules.str(a, "number");
            String[] lines = text.split("\n");
            boolean anyRel = false;
            for (String l : lines) if (relevant(l, terms)) anyRel = true;
            for (String l : lines) {
                boolean rel = relevant(l, terms);
                if (!rel && !("level".equals(role) && !anyRel)) continue;
                Matcher m = lv.matcher(l);
                while (m.find()) levels.merge(m.group().replaceAll("\\s+", " "), rel ? 3 : 1, Integer::sum);
            }
            for (String sn : sentences(text)) {
                if (caution.matcher(sn).find() && (relevant(sn, terms) || !"etc".equals(role)) && cautions.size() < 7)
                    cautions.add(new Cite(sn, h.regId(), h.articleId(), src));
                Matcher d = dl.matcher(sn);
                while (d.find()) if (dlSeen.add(d.group() + h.articleId()) && deadlines.size() < 6) deadlines.add(new Cite(d.group(), h.regId(), h.articleId(), src));
            }
            Object note = notes.get(h.regId() + "::" + h.articleId());
            if (note != null && !note.toString().isBlank()) notesOut.add(new Cite(note.toString(), h.regId(), h.articleId(), src));
            if (Rules.bool(a, "difficult")) risks.add(Rules.artLabel(a) + " — 해석 어려운 조항으로 표시됨: 인사·법무 확인 권장");
        }
        if (query.contains("음주")) risks.add("형사처벌(도로교통법)과 별개로 사내 징계가 가능합니다. 혈중알코올농도·사고 여부·업무 관련성(사내 차량, 근무시간)이 양정의 핵심 사실관계입니다.");
        if (Pattern.compile("괴롭힘|성희롱").matcher(query).find()) risks.add("근로기준법 제76조의3: 신고자·피해자에 대한 불리한 처우 금지, 조사 중 피해자 보호조치 의무(위반 시 형사처벌). 조사 내용 비밀 유지.");
        if (Pattern.compile("개인정보|유출").matcher(query).find()) risks.add("개인정보 보호법상 유출 통지·신고 기한(72시간)을 놓치면 회사가 과징금·과태료 대상이 됩니다. 징계보다 통지·신고를 먼저 처리하세요.");
        if (Pattern.compile("겸직|부업|영리").matcher(query).find()) risks.add("근무시간 외 겸직을 일률 금지하면 다툼 소지가 있습니다. 업무 지장·경업·회사 명예 침해 여부를 기준으로 판단하세요.");
        if (Pattern.compile("지각|결근").matcher(query).find()) risks.add("징계 전 근태 기록(출입 기록·승인 내역)과 사전 경고 이력을 확보해야 양정의 정당성이 인정됩니다.");
        if (mis || levels.keySet().stream().anyMatch(k -> Pattern.compile("해고|파면|해임").matcher(k).find()))
            risks.add("해고 등 중징계는 사유·시기를 서면 통지해야 효력이 있고(근로기준법 제27조), 소명 기회 미부여 등 절차 하자 시 부당징계 위험이 있습니다.");
        List<Map.Entry<String, Integer>> le = new ArrayList<>(levels.entrySet());
        le.sort((x, y) -> y.getValue() - x.getValue());
        if (le.stream().anyMatch(x -> x.getValue() >= 3)) le.removeIf(x -> x.getValue() < 3);
        List<String> lvList = le.stream().map(Map.Entry::getKey).toList();
        String top = lvList.stream().filter(l -> Pattern.compile("파면|해임|해고").matcher(l).find()).findFirst()
            .orElse(lvList.stream().filter(l -> Pattern.compile("정직|강등|감봉").matcher(l).find()).findFirst().orElse(lvList.isEmpty() ? null : lvList.get(0)));
        groups.values().removeIf(List::isEmpty);
        v.put("groups", groups);
        v.put("count", groups.values().stream().mapToInt(List::size).sum());
        v.put("levels", lvList);
        v.put("top", top);
        v.put("cautions", cautions);
        v.put("deadlines", deadlines);
        v.put("risks", risks);
        v.put("notes", notesOut);
        v.put("terms", String.join("|", terms));
        return v;
    }

    public static String levelTone(String l) {
        return Pattern.compile("파면|해임|해고").matcher(l).find() ? "hi" : Pattern.compile("정직|강등|감봉").matcher(l).find() ? "md" : "lo";
    }

    private static boolean relevant(String s, List<String> terms) {
        String n = Rules.nk(s);
        for (String t : terms) if (!Rules.nk(t).isEmpty() && n.contains(Rules.nk(t))) return true;
        return false;
    }

    private String articleText(Hit h) {
        Map<String, Object> a = article(h.regId(), h.articleId());
        return a == null ? "" : Rules.str(a, "content");
    }

    Map<String, Object> article(String regId, String aid) {
        Map<String, Object> r = reg(regId);
        if (r == null) return null;
        for (Map<String, Object> a : Rules.arts(r)) if (Rules.str(a, "id").equals(aid)) return a;
        return null;
    }

    // ───── 개정 타임라인 ─────
    public record Event(String regId, String regTitle, String versionId, String prevVersionId, String label, String date, int articles, String note) { }

    public List<Event> timeline() {
        List<Event> ev = new ArrayList<>();
        for (Map<String, Object> r : regs()) {
            List<Map<String, Object>> vs = Rules.maps(r, "versions");
            for (int i = 0; i < vs.size(); i++) {
                Map<String, Object> v = vs.get(i);
                String note = Rules.str(v, "note").isEmpty() ? Rules.str(v, "reason") : Rules.str(v, "note");
                ev.add(new Event(Rules.str(r, "id"), Rules.str(r, "title"), Rules.str(v, "id"), i > 0 ? Rules.str(vs.get(i - 1), "id") : null,
                    Rules.str(v, "label"), date(Rules.str(v, "effectiveDate")), Rules.maps(v, "articles").size(), note));
            }
        }
        ev.sort(Comparator.comparing(Event::date).reversed());
        return ev;
    }

    // ───── 전체 목차 ─────
    public record IndexArt(String id, String number, String title, String chapter, boolean newChapter) { }
    public record IndexReg(RegRow reg, List<IndexArt> arts, int hits) { }
    public record IndexGroup(String name, List<IndexReg> regs) { }

    public List<IndexGroup> masterIndex(String q) {
        String k = Rules.nk(q);
        List<IndexGroup> out = new ArrayList<>();
        for (Group g : groups()) {
            List<IndexReg> rs = new ArrayList<>();
            for (RegRow row : g.regs()) {
                Map<String, Object> r = reg(row.id());
                boolean regHit = k.isEmpty() || Rules.nk(row.title()).contains(k);
                List<IndexArt> as = new ArrayList<>();
                String last = null;
                for (Map<String, Object> a : Rules.arts(r)) {
                    boolean hit = regHit || Rules.nk(Rules.str(a, "number") + " " + Rules.str(a, "title")).contains(k)
                        || Rules.nk(RuleParser.mdStrip(Rules.str(a, "content"))).contains(k);
                    if (!hit) continue;
                    String ch = Rules.str(a, "chapter");
                    as.add(new IndexArt(Rules.str(a, "id"), Rules.str(a, "number"), Rules.str(a, "title"), ch, !ch.isEmpty() && !ch.equals(last) && k.isEmpty()));
                    last = ch;
                }
                if (regHit || !as.isEmpty()) rs.add(new IndexReg(row, as, as.size()));
            }
            if (!rs.isEmpty()) out.add(new IndexGroup(g.name(), rs));
        }
        return out;
    }

    // ───── 감사점검 추출·체크리스트 ─────
    static final List<String> AUDIT_KW = List.of("하여야 한다", "아니 된다", "금지", "승인", "보고", "보관", "점검", "기록", "통지", "신고", "이내");
    public record ScanItem(String key, String regId, String regTitle, String articleId, String label, String sentence, List<String> keywords) { }

    public List<ScanItem> auditScan(String filter) {
        String f = Rules.nk(filter);
        List<ScanItem> out = new ArrayList<>();
        for (Map<String, Object> r : regs())
            for (Map<String, Object> a : Rules.arts(r)) {
                List<String> ss = sentences(Rules.str(a, "content"));
                for (int i = 0; i < ss.size(); i++) {
                    String sn = ss.get(i);
                    List<String> k = AUDIT_KW.stream().filter(sn::contains).toList();
                    if (k.isEmpty()) continue;
                    if (!f.isEmpty() && !Rules.nk(sn + " " + Rules.str(r, "title") + " " + Rules.str(a, "number")).contains(f)) continue;
                    out.add(new ScanItem(Rules.str(r, "id") + "::" + Rules.str(a, "id") + "::" + i, Rules.str(r, "id"), Rules.str(r, "title"),
                        Rules.str(a, "id"), Rules.str(r, "title") + " " + Rules.str(a, "number"), sn, k));
                }
            }
        return out;
    }

    public Op checklistFromScan(String title, List<String> keys) {
        if (keys == null || keys.isEmpty()) return Op.bad("항목을 고르세요.");
        Map<String, ScanItem> all = new LinkedHashMap<>();
        for (ScanItem x : auditScan(null)) all.put(x.key(), x);
        List<Object> items = new ArrayList<>();
        for (String k : keys) {
            ScanItem x = all.get(k);
            if (x == null) continue;
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("id", Rules.uid("ci"));
            it.put("text", x.sentence());
            it.put("done", false);
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("regId", x.regId());
            ref.put("articleId", x.articleId());
            it.put("refs", new ArrayList<>(List.of(ref)));
            items.add(it);
        }
        if (items.isEmpty()) return Op.bad("고른 항목을 찾지 못했습니다. 화면을 새로 고치세요.");
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", Rules.uid("cl"));
        String t = Txt.clean(title, 120, false);
        c.put("title", Txt.blank(t) ? "감사 점검 " + Kst.today() : t);
        c.put("items", items);
        Rules.list(state(), "checklists").add(0, c);
        store.saveState();
        return Op.ok("체크리스트를 만들었습니다 · 항목 " + items.size() + "개.", Rules.str(c, "id"));
    }

    public record CheckRef(String regId, String articleId, String label) { }
    public record CheckItem(String id, String text, boolean done, List<CheckRef> refs) { }
    public record Checklist(String id, String title, List<CheckItem> items, int done) { }

    public List<Checklist> checklists() {
        List<Checklist> out = new ArrayList<>();
        for (Map<String, Object> c : Rules.maps(state(), "checklists")) {
            List<CheckItem> items = new ArrayList<>();
            int d = 0;
            for (Map<String, Object> it : Rules.maps(c, "items")) {
                if (Rules.str(it, "id").isEmpty()) it.put("id", Rules.uid("ci"));
                List<CheckRef> refs = new ArrayList<>();
                for (Map<String, Object> rf : Rules.maps(it, "refs")) {
                    Mark m = mark(Rules.str(rf, "regId"), Rules.str(rf, "articleId"), "", "");
                    if (m != null) refs.add(new CheckRef(m.regId(), m.articleId(), m.regTitle() + " " + m.label()));
                }
                boolean done = Rules.bool(it, "done");
                if (done) d++;
                items.add(new CheckItem(Rules.str(it, "id"), Rules.str(it, "text"), done, refs));
            }
            if (Rules.str(c, "id").isEmpty()) c.put("id", Rules.uid("cl"));
            out.add(new Checklist(Rules.str(c, "id"), Rules.str(c, "title"), items, d));
        }
        return out;
    }

    public Op checklist(String what, Map<String, String> p) {
        List<Map<String, Object>> lists = Rules.maps(state(), "checklists");
        if ("new".equals(what)) {
            String t = Txt.clean(p.get("title"), 120, false);
            if (Txt.blank(t)) return Op.bad("체크리스트 이름을 적어 주세요.");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", Rules.uid("cl"));
            c.put("title", t);
            c.put("items", new ArrayList<>());
            Rules.list(state(), "checklists").add(0, c);
            store.saveState();
            return Op.ok("체크리스트를 만들었습니다.");
        }
        Map<String, Object> c = lists.stream().filter(x -> Rules.str(x, "id").equals(p.get("id"))).findFirst().orElse(null);
        if (c == null) return Op.fail(404, "체크리스트를 찾을 수 없습니다.");
        List<Map<String, Object>> items = Rules.maps(c, "items");
        Map<String, Object> it = items.stream().filter(x -> Rules.str(x, "id").equals(p.get("item"))).findFirst().orElse(null);
        switch (what) {
            case "delete" -> lists.remove(c);
            case "add" -> {
                String t = Txt.clean(p.get("text"), 4000, true);
                if (Txt.blank(t)) return Op.bad("항목을 적어 주세요.");
                for (String line : t.split("\n")) {
                    if (line.isBlank()) continue;
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("id", Rules.uid("ci"));
                    x.put("text", line.strip());
                    x.put("done", false);
                    x.put("refs", new ArrayList<>());
                    items.add(x);
                }
            }
            case "toggle" -> { if (it == null) return Op.fail(404, "항목을 찾을 수 없습니다."); it.put("done", !Rules.bool(it, "done")); }
            case "remove" -> { if (it == null) return Op.fail(404, "항목을 찾을 수 없습니다."); items.remove(it); }
            case "rename" -> {
                String t = Txt.clean(p.get("title"), 120, false);
                if (Txt.blank(t)) return Op.bad("이름을 적어 주세요.");
                c.put("title", t);
            }
            default -> { return Op.fail(404, "없는 작업입니다."); }
        }
        store.saveState();
        return Op.ok("delete".equals(what) ? "체크리스트를 지웠습니다." : "체크리스트를 고쳤습니다.");
    }
}
