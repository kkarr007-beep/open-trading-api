package local.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import local.audit.rule.RuleParser;
import local.audit.rule.RuleRender;
import local.audit.rule.RuleService;
import local.audit.rule.RuleStore;
import local.audit.rule.Rules;
import local.audit.work.WorkItem;
import local.audit.work.WorkService;
import local.audit.work.WorkStore;

/** 업무·일정과 사규: 예전 HTML 판에서 옮겨 오기, 규칙, 화면 묶음. */
class ModulesTest {
    @TempDir Path dir;
    Db db;
    Settings settings;
    WorkService work;
    RuleService rules;

    @BeforeEach
    void setUp() throws Exception {
        Kst.clock = Clock.fixed(Instant.parse("2026-11-17T03:00:00Z"), ZoneOffset.UTC);
        Paths2 paths = new Paths2(dir.toString());
        db = new Db(paths);
        settings = new Settings(db);
        work = new WorkService(new WorkStore(db, settings), db);
        rules = new RuleService(new RuleStore(db));
    }

    @AfterEach
    void tearDown() { Kst.clock = Clock.systemUTC(); }

    static byte[] resource(String name) throws Exception {
        try (InputStream in = ModulesTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, name);
            return in.readAllBytes();
        }
    }

    // ───────────── 사규 글 가르기: 예전 프로그램(JS)과 같은 결과 ─────────────

    @Test
    void parserMatchesOriginalProgram() throws Exception {
        JsonNode cases = new ObjectMapper().readTree(resource("rule-parse-cases.json"));
        int n = 0;
        for (JsonNode c : cases) {
            List<Map<String, Object>> got = RuleParser.parse(c.get("text").asText());
            JsonNode want = c.get("arts");
            assertEquals(want.size(), got.size(), "조 개수 · 사례 " + n);
            for (int i = 0; i < want.size(); i++) {
                JsonNode w = want.get(i);
                Map<String, Object> g = got.get(i);
                String where = "사례 " + n + " · " + w.get("number").asText();
                assertEquals(w.get("number").asText(), g.get("number"), where);
                assertEquals(w.get("title").asText(), g.get("title"), where);
                assertEquals(w.get("chapter").asText(), Rules.str(g, "chapter"), where);
                assertEquals(w.get("content").asText(), g.get("content"), where);
                assertEquals(w.get("supp").asBoolean(), Rules.bool(g, "supp"), where);
                assertEquals(w.get("attach").asBoolean(), Rules.bool(g, "attach"), where);
            }
            n++;
        }
        assertTrue(n >= 4);
    }

    @Test
    void renderEscapesAndLinksAndMergesTableCells() {
        String html = RuleRender.content("① <script>는 제3조에 따른다.\n| 구분 | 기준 |\n| 가 | < |\n1. **굵게**", List.of("기준"),
            ref -> "제3조".equals(ref[1].replace(" ", "")) ? "/rules/r#a-3" : null);
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("<a class=\"xref\" href=\"/rules/r#a-3\">"));
        assertTrue(html.contains("colspan=\"2\""));
        assertTrue(html.contains("<b>굵게</b>"));
        assertTrue(html.contains("<mark>기준</mark>"));
        assertTrue(html.contains("class=\"para p\"") && html.contains("class=\"para h\""));
        String d = RuleRender.diff("정직 1개월에 처한다.", "정직 3개월에 처한다.");
        assertTrue(d.contains("<del>1개월에</del>") && d.contains("<ins>3개월에</ins>"), d);
    }

    // ───────────── 사규 옮겨 오기·찾기·개정 ─────────────

    @Test
    void ruleImportSearchReviseAndExportRoundTrip() throws Exception {
        Op op = rules.importJson(resource("rule-v4-sample.json"), false);
        assertTrue(op.ok, op.message);
        assertEquals(3, rules.regs().size());
        Map<String, Object> disc = rules.regs().stream().filter(r -> "징계규정".equals(r.get("title"))).findFirst().orElseThrow();
        assertEquals(2, Rules.maps(disc, "versions").size());

        // 다시 읽어도 같은 내용 (데이터베이스에서)
        RuleService again = new RuleService(new RuleStore(db));
        assertEquals(3, again.regs().size());
        assertEquals(Rules.arts(disc).size(), Rules.arts(again.reg(Rules.str(disc, "id"))).size());

        List<RuleService.Hit> hits = rules.search("음주운전", null, 50);
        assertFalse(hits.isEmpty());
        assertTrue(hits.get(0).snippet().contains("<mark>"));
        List<RuleService.Hit> byNo = rules.search("징계규정 제1조", null, 50);
        assertEquals("조 번호 일치", byNo.get(0).why());

        Map<String, Object> view = rules.read(Rules.str(disc, "id"), null, "징계", true);
        assertTrue((Boolean) view.get("diff"));
        @SuppressWarnings("unchecked")
        List<RuleService.Art> items = (List<RuleService.Art>) view.get("items");
        assertTrue(items.stream().anyMatch(a -> !a.tag().isEmpty()), "앞 판과 다른 조가 표시되어야 함");

        // 메모·북마크·어려움 표시
        String aid = items.get(0).id();
        assertTrue(rules.article(Rules.str(disc, "id"), aid, "bookmark", Map.of()).ok);
        assertTrue(rules.article(Rules.str(disc, "id"), aid, "note", Map.of("text", "면담 때 확인")).ok);
        assertTrue(rules.article(Rules.str(disc, "id"), aid, "difficult", Map.of()).ok);
        Map<String, List<RuleService.Mark>> marks = rules.marks();
        assertTrue(marks.get("bookmarks").stream().anyMatch(k -> k.articleId().equals(aid)));
        assertTrue(marks.get("notes").stream().anyMatch(k -> k.articleId().equals(aid) && "면담 때 확인".equals(k.text())));
        assertTrue(marks.get("difficult").stream().anyMatch(k -> k.articleId().equals(aid)));

        // 개정판 넣기: 같은 조 번호는 표시를 이어받는다
        Map<String, String> p = new HashMap<>();
        p.put("reviseOf", Rules.str(disc, "id"));
        p.put("label", "개정 2026.11");
        p.put("effectiveDate", "2026-11-17");
        String text = rules.exportText(Rules.str(disc, "id")).replace("견책", "경고");
        assertTrue(rules.add(p, text).ok);
        assertEquals(3, Rules.maps(disc, "versions").size());
        assertTrue(Rules.arts(disc).stream().anyMatch(a -> Rules.str(a, "id").equals(aid) && Rules.bool(a, "difficult")));

        // 내보내기 → 다시 옮겨 오기
        String out = rules.exportJson();
        RuleService other = new RuleService(new RuleStore(new Db(new Paths2(dir.resolve("other").toString()))));
        assertTrue(other.importJson(out.getBytes(StandardCharsets.UTF_8), false).ok);
        assertEquals(3, other.regs().size());
        assertTrue(other.marks().get("notes").stream().anyMatch(k -> "면담 때 확인".equals(k.text())));

        // 다른 파일을 잘못 넣으면 알려 준다
        assertFalse(rules.importJson(resource("work-v2-sample.json"), false).ok);
    }

    @Test
    void ruleAddFromPastedText() {
        Map<String, String> p = new HashMap<>();
        p.put("title", "보안규정");
        Op op = rules.add(p, "제1조(목적) 보안을 정한다.\n제2조(적용) ① 전 직원에게 적용한다.\n② 제1조의 목적에 따른다.");
        assertTrue(op.ok, op.message);
        Map<String, Object> view = rules.read(op.id, null, null, false);
        @SuppressWarnings("unchecked")
        List<RuleService.Art> items = (List<RuleService.Art>) view.get("items");
        assertEquals(2, items.size());
        assertTrue(items.get(1).html().contains("href=\"/rules/" + op.id + "#a-"), "같은 사규 안의 조 참조는 연결");
        assertFalse(rules.add(new HashMap<>(), "제1조(목적) 내용").ok, "이름 없이는 등록하지 않음");
    }

    // ───────────── 업무·일정 ─────────────

    @Test
    void workImportKeepsEverythingAndViewsWork() throws Exception {
        Op op = work.importJson(resource("work-v2-sample.json"), false);
        assertTrue(op.ok, op.message);
        assertEquals(40, work.all().size());
        assertTrue(work.config().owners.size() >= 5);

        // 다시 읽어도 같다
        WorkService again = new WorkService(new WorkStore(db, settings), db);
        assertEquals(40, again.all().size());

        Map<String, Object> today = work.todayView(7);
        assertNotNull(today.get("late"));
        assertEquals(6, work.board(null).size());
        assertFalse(work.calendar(java.time.YearMonth.of(2026, 11), null).isEmpty());
        @SuppressWarnings("unchecked")
        Map<String, List<WorkService.AnnualRow>> groups = (Map<String, List<WorkService.AnnualRow>>) work.annual(2026, null).get("groups");
        assertTrue(groups.containsKey("정기 업무"));
        assertFalse(work.team().isEmpty());

        // 내보낸 JSON에 예전 칸이 그대로 있다
        JsonNode out = new ObjectMapper().readTree(work.exportJson());
        assertEquals(40, out.get("items").size());
        assertEquals("team-task-v2", out.get("app").asText());
        assertFalse(out.get("items").get(0).has("statusName"), "화면용 이름은 저장하지 않음");

        assertFalse(work.importJson(resource("rule-v4-sample.json"), false).ok);
    }

    @Test
    void quickAddParsesOwnerTagsPriorityAndDate() {
        work.saveEntry("owner", new HashMap<>(Map.of("n", "김하린", "cap", "6")));
        WorkService.Quick q = work.parseQuick("보고서 초안 @김하린 #점검 !높음 금요일");
        assertEquals("보고서 초안", q.title());
        assertEquals(List.of("점검"), q.tags());
        assertEquals("high", q.pri());
        assertEquals("2026-11-20", q.due(), "2026-11-17은 화요일 → 이번 주 금요일");
        assertEquals("2026-11-18", work.parseQuick("회의 준비 내일").due());
        assertEquals("2026-11-24", work.parseQuick("점검 다음주 화").due());
        assertEquals("2026-11-30", work.parseQuick("정리 월말").due());
        assertEquals("2027-01-05", work.parseQuick("신년 1/5").due());
        assertTrue(work.quickAdd("보고서 초안 @김하린 내일", "ow0").ok);
        WorkItem x = work.all().get(work.all().size() - 1);
        assertEquals("2026-11-18", x.due);
        assertFalse("ow0".equals(x.ownerId));
    }

    @Test
    void statusRepeatDueLogAndMonthlyChecks() {
        Map<String, String> p = new HashMap<>(Map.of("title", "주간 브리핑", "due", "2026-11-20"));
        Op op = work.create("work", p);
        String id = op.id;
        assertTrue(work.update(id, Map.of("repeat", "weekly", "due", "2026-11-21")).ok);
        WorkItem x = work.get(id);
        assertEquals(1, x.dueLog.size());
        assertEquals("2026-11-20", x.due0);
        Op done = work.status(id, "done");
        assertTrue(done.ok);
        assertEquals("2026-11-17", work.get(id).doneAt);
        WorkItem next = work.all().stream().filter(z -> !z.id.equals(id) && "주간 브리핑".equals(z.title)).findFirst().orElseThrow();
        assertEquals("2026-11-28", next.due);
        assertEquals("todo", next.status);

        Op m = work.create("work", new HashMap<>(Map.of("title", "개인정보 파기확인서")));
        Map<String, String> u = new HashMap<>();
        u.put("cycle", "months");
        u.put("monthsSent", "1");
        u.put("m10", "1");
        u.put("m11", "1");
        assertTrue(work.update(m.id, u).ok);
        assertEquals("miss", WorkService.cellState(work.get(m.id), 2026, 10));
        assertEquals("doing", WorkService.cellState(work.get(m.id), 2026, 11));
        assertTrue(work.check(m.id, 2026, 10).ok);
        assertEquals("done", WorkService.cellState(work.get(m.id), 2026, 10));
        assertTrue(work.check(m.id, 2026, 10).ok);
        assertEquals("miss", WorkService.cellState(work.get(m.id), 2026, 10));

        assertTrue(work.todo(id, "add", null, "하나\n- 둘").ok);
        assertEquals(2, work.get(id).todos.size());
        assertEquals("둘", work.get(id).todos.get(1).t);
        assertTrue(work.todo(id, "toggle", 0, null).ok);
        assertTrue(work.todo(id, "clean", null, null).ok);
        assertEquals(1, work.get(id).todos.size());

        Op dir = work.create("dir", new HashMap<>(Map.of("title", "소명 요청 회신", "from", "감사위원회", "due", "2026-11-10")));
        assertEquals(7, WorkService.lateDays(work.get(dir.id)));
        Op moved = work.toWork(dir.id);
        assertTrue(moved.ok);
        assertEquals(dir.id, work.get(moved.id).srcId);
        assertTrue(work.delete(moved.id).ok);
        assertNull(work.get(moved.id));
    }
}
