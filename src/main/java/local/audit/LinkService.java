package local.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import local.audit.board.BoardData;
import local.audit.board.BoardService;
import local.audit.rule.RuleService;
import local.audit.rule.Rules;
import local.audit.tools.CardCheck;
import local.audit.work.WorkItem;
import local.audit.work.WorkService;

/**
 * 메뉴끼리 잇는 끈. 감사 건 하나에 사규 조문·자료·업무·점검 회차·경영 지표를 붙이고,
 * 반대쪽 화면에서도 이 연결을 거꾸로 찾아 '연결된 감사 건'으로 보여 준다.
 */
@Service
public class LinkService {
    public static final Map<String, String> KINDS = new LinkedHashMap<>();
    static {
        KINDS.put("rule", "사규 조문");
        KINDS.put("doc", "자료");
        KINDS.put("work", "업무·일정");
        KINDS.put("card", "법인카드 점검");
        KINDS.put("kpi", "경영 지표");
    }

    public record Pick(String ref, String label) { }
    public record Link(String caseId, String kind, String kindName, String ref, String label, String href, String at) { }

    private final Db db;
    private final Chain chain;
    private final Settings settings;
    private final RuleService rules;
    private final LibraryService library;
    private final WorkService work;
    private final CardCheck card;
    private final BoardService board;

    public LinkService(Db db, Chain chain, Settings settings, RuleService rules, LibraryService library, WorkService work,
                       CardCheck card, BoardService board) {
        this.db = db; this.chain = chain; this.settings = settings; this.rules = rules; this.library = library;
        this.work = work; this.card = card; this.board = board;
    }

    public static String href(String kind, String ref) {
        return switch (kind) {
            case "rule" -> { int i = ref.indexOf('#'); yield i < 0 ? "/rules/" + ref : "/rules/" + ref.substring(0, i) + "#a-" + ref.substring(i + 1); }
            case "doc" -> "/library/" + ref;
            case "work" -> "/work/item/" + ref;
            case "card" -> "/tools/card/" + ref + "#hits";
            case "kpi" -> "/board/kpi/" + ref;
            default -> "/cases";
        };
    }

    /** 고를 수 있는 항목. 화면의 추천 목록에 그대로 들어간다. */
    @SuppressWarnings("unchecked")
    public List<Pick> picks(String kind) {
        List<Pick> out = new ArrayList<>();
        switch (kind) {
            case "rule" -> {
                for (Map<String, Object> r : rules.regs()) {
                    String id = Rules.str(r, "id"), title = Rules.str(r, "title");
                    out.add(new Pick(id, title + " (전체)"));
                    for (Map<String, Object> a : Rules.arts(r)) out.add(new Pick(id + "#" + Rules.str(a, "id"), title + " " + Rules.artLabel(a)));
                }
            }
            case "doc" -> {
                for (Map<String, Object> d : (List<Map<String, Object>>) library.list(null, null, 1, 1000).get("items"))
                    out.add(new Pick(Txt.str(d.get("Id")), Txt.str(d.get("Title")) + " · " + Txt.str(d.get("Category"))));
            }
            case "work" -> {
                for (WorkItem x : work.all())
                    if (!"inbox".equals(x.kind) && !Txt.blank(x.title)) out.add(new Pick(x.id, x.title + (x.hasDue() ? " · 기한 " + x.due : "")));
            }
            case "card" -> {
                for (Map<String, Object> r : card.runs()) out.add(new Pick(Txt.str(r.get("Id")), Txt.str(r.get("Title")) + " · " + Txt.str(r.get("FileName"))));
            }
            case "kpi" -> {
                BoardData d = board.load();
                for (BoardData.Kpi k : d.kpis) out.add(new Pick(k.id, k.name + " · " + d.orgName(k.org)));
            }
            default -> { }
        }
        return out;
    }

    private Pick resolve(String kind, String value) {
        String v = Txt.str(value).strip();
        if (v.isEmpty()) return null;
        for (Pick p : picks(kind)) if (p.ref().equals(v) || p.label().equals(v)) return p;
        for (Pick p : picks(kind)) if (p.label().startsWith(v)) return p;
        return null;
    }

    public Op add(String caseId, String kind, String value) {
        if (!KINDS.containsKey(kind)) return Op.bad("연결할 종류를 고르세요.");
        if (!chain.intact()) return Op.fail(503, "기록 이력이 훼손되어 변경을 멈췄습니다.");
        Pick p = resolve(kind, value);
        if (p == null) return Op.bad("목록에서 연결할 " + KINDS.get(kind) + "을(를) 고르세요.");
        return db.run(conn -> {
            Map<String, Object> c = Db.row(conn, "SELECT Title, PurgedAt FROM Cases WHERE CaseId=?", caseId);
            if (c == null) throw Op.abort(404, "감사 건을 찾지 못했습니다.");
            if (Db.intOf(conn, "SELECT COUNT(*) FROM Links WHERE CaseId=? AND Kind=? AND Ref=?", caseId, kind, p.ref()) > 0)
                return Op.ok("이미 연결된 항목입니다.");
            Db.update(conn, "INSERT INTO Links(CaseId, Kind, Ref, Label, CreatedAt) VALUES(?,?,?,?,?)", caseId, kind, p.ref(), Txt.fit(p.label(), 200, false), Kst.stamp());
            Chain.append(conn, caseId, settings.owner(), "연결", "관련 항목", p.ref(), KINDS.get(kind) + " 연결: " + Txt.fit(p.label(), 160, false));
            return Op.ok(KINDS.get(kind) + "을(를) 감사 건에 연결했습니다.", caseId);
        });
    }

    public Op remove(String caseId, String kind, String ref) {
        if (!chain.intact()) return Op.fail(503, "기록 이력이 훼손되어 변경을 멈췄습니다.");
        return db.run(conn -> {
            Map<String, Object> l = Db.row(conn, "SELECT Label FROM Links WHERE CaseId=? AND Kind=? AND Ref=?", caseId, kind, ref);
            if (l == null) throw Op.abort(404, "연결을 찾지 못했습니다.");
            Db.update(conn, "DELETE FROM Links WHERE CaseId=? AND Kind=? AND Ref=?", caseId, kind, ref);
            Chain.append(conn, caseId, settings.owner(), "연결해제", "관련 항목", ref, KINDS.getOrDefault(kind, kind) + " 연결 해제: " + Txt.str(l.get("Label")));
            return Op.ok("연결을 풀었습니다.", caseId);
        });
    }

    /** 감사 건에 붙은 항목들. */
    public List<Link> ofCase(String caseId) {
        List<Link> out = new ArrayList<>();
        for (Map<String, Object> r : db.rows("SELECT * FROM Links WHERE CaseId=? ORDER BY CreatedAt", caseId)) {
            String k = Txt.str(r.get("Kind")), ref = Txt.str(r.get("Ref"));
            out.add(new Link(caseId, k, KINDS.getOrDefault(k, k), ref, Txt.str(r.get("Label")), href(k, ref), Txt.str(r.get("CreatedAt"))));
        }
        return out;
    }

    /** 반대쪽: 이 항목과 연결된 감사 건. 사규는 사규 전체와 그 조문을 함께 찾는다. */
    public List<Map<String, Object>> casesFor(String kind, String ref) {
        if (Txt.blank(ref)) return List.of();
        String sql = """
            SELECT DISTINCT c.CaseId, c.Title, c.Phase, c.Year, c.Target, l.Label, l.Ref FROM Links l JOIN Cases c ON c.CaseId=l.CaseId
            WHERE l.Kind=? AND (l.Ref=? OR l.Ref LIKE ?) AND c.PurgedAt IS NULL ORDER BY c.Year DESC, c.CreatedAt DESC""";
        return db.rows(sql, kind, ref, "rule".equals(kind) ? ref + "#%" : ref);
    }

    /** 자료 목록처럼 여러 줄에 한 번에 붙일 때 쓰는 개수. */
    public Map<String, Integer> counts(String kind) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Map<String, Object> r : db.rows("SELECT l.Ref, COUNT(*) AS N FROM Links l JOIN Cases c ON c.CaseId=l.CaseId WHERE l.Kind=? AND c.PurgedAt IS NULL GROUP BY l.Ref", kind))
            m.put(Txt.str(r.get("Ref")), ((Number) r.get("N")).intValue());
        return m;
    }

    /** 다른 화면에서 '감사 건에 연결'할 때 고르는 건 목록(삭제되지 않은 건). */
    public List<Map<String, Object>> caseChoices() {
        return db.rows("SELECT CaseId, Title, Year, Phase FROM Cases WHERE PurgedAt IS NULL ORDER BY Year DESC, CreatedAt DESC LIMIT 300");
    }

    /** 전체 찾기에 더하는 경영현황(지표·이슈)과 법인카드 점검 회차. */
    public List<Map<String, String>> boardHits(String term) {
        List<Map<String, String>> out = new ArrayList<>();
        String t = Txt.str(term).strip().toLowerCase();
        if (t.isEmpty() || !board.hasData()) return out;
        BoardData d = board.load();
        for (BoardData.Kpi k : d.kpis)
            if (k.name.toLowerCase().contains(t) || d.orgName(k.org).toLowerCase().contains(t))
                out.add(Map.of("title", k.name, "sub", "지표 · " + d.orgName(k.org), "href", "/board/kpi/" + k.id));
        for (Map.Entry<String, BoardData.Issue> e : d.issues.entrySet()) {
            BoardData.Issue x = e.getValue();
            if ((x.title + " " + x.note).toLowerCase().contains(t))
                out.add(Map.of("title", x.title, "sub", "이슈 · " + d.orgName(x.org) + " · " + x.cat + " · " + x.st, "href", "/board/issues"));
        }
        return out.size() > 30 ? out.subList(0, 30) : out;
    }

    public List<Map<String, String>> cardHits(String term) {
        List<Map<String, String>> out = new ArrayList<>();
        String t = Txt.str(term).strip().toLowerCase();
        if (t.isEmpty()) return out;
        for (Map<String, Object> r : card.runs()) {
            String title = Txt.str(r.get("Title")), file = Txt.str(r.get("FileName"));
            if ((title + " " + file).toLowerCase().contains(t))
                out.add(Map.of("title", title, "sub", "점검 회차 · " + file + " · 확인 대상 " + Txt.str(r.get("HitTx")) + "건", "href", "/tools/card/" + r.get("Id") + "#hits"));
        }
        return out;
    }
}
