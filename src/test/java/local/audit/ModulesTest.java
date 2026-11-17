package local.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.Set;
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

    // ───────────── 2차: 되돌리기, 타임라인, 인사이트, 회의·쟁점 ─────────────

    @Test
    void undoRestoresPreviousState() throws Exception {
        local.audit.work.WorkStore store = new local.audit.work.WorkStore(db, settings);
        WorkService w = new WorkService(store, db);
        Op made = w.change("추가", () -> w.create("work", new HashMap<>(Map.of("title", "되돌릴 업무"))));
        assertTrue(made.ok);
        assertTrue(w.change("상태", () -> w.status(made.id, "done")).ok);
        assertEquals("done", w.get(made.id).status);
        assertTrue(w.undo().ok);
        assertEquals("todo", w.get(made.id).status);
        // 데이터베이스에도 되돌린 모습이 남는다
        assertEquals("todo", new WorkService(new local.audit.work.WorkStore(db, settings), db).get(made.id).status);
        assertTrue(w.undo().ok);
        assertNull(w.get(made.id));
        assertFalse(w.undo().ok, "더 되돌릴 것이 없음");
        // 실패한 작업은 되돌리기 목록에 남지 않는다
        assertFalse(w.change("빈 제목", () -> w.create("work", new HashMap<>())).ok);
        assertNull(w.undoLabel());
    }

    @Test
    void timelineInsightWeeklyMonthPlan() throws Exception {
        assertTrue(work.importJson(resource("work-v2-sample.json"), false).ok);
        local.audit.work.WorkReports rep = new local.audit.work.WorkReports(work, storeOf(work));
        Map<String, Object> tl = rep.timeline(null, "month", null);
        assertFalse(((List<?>) tl.get("bars")).isEmpty());
        assertFalse(((List<?>) tl.get("ticks")).isEmpty());
        Map<String, Object> in = rep.insight();
        assertNotNull(in.get("rate"));
        assertFalse(((List<?>) in.get("tips")).isEmpty());
        assertEquals(6, ((List<?>) in.get("months")).size());
        assertEquals(4, ((Map<?, ?>) rep.weekly().get("sections")).size());
        String plan = rep.monthPlan();
        assertTrue(plan.contains("2026년 12월 업무계획"));
        assertTrue(plan.contains("1. 정기 업무"));
    }

    static local.audit.work.WorkStore storeOf(WorkService w) throws Exception {
        java.lang.reflect.Field f = WorkService.class.getDeclaredField("store");
        f.setAccessible(true);
        return (local.audit.work.WorkStore) f.get(w);
    }

    @Test
    void meetingDecisionsActionsAndIssues() throws Exception {
        local.audit.work.WorkReports rep = new local.audit.work.WorkReports(work, storeOf(work));
        String text = "1. 안건\n- 판매채널 수수료 현황 공유\n## 결정\n- 수수료 상한을 3%로 하기로 함\n## 조치\n- 박준서 수수료 자료 금요일까지 제출\n- 법무 검토 요청\n";
        assertTrue(rep.addIssue(new HashMap<>(Map.of("title", "판매채널 수수료"))).ok);
        Op m = rep.addMeeting(new HashMap<>(Map.of("title", "정례회의", "mdate", "2026-11-16")), text);
        assertTrue(m.ok, m.message);
        assertEquals(List.of("수수료 상한을 3%로 하기로 함"), local.audit.work.WorkReports.decisions(text));
        List<String> acts = local.audit.work.WorkReports.actions(text);
        assertTrue(acts.contains("박준서 수수료 자료 금요일까지 제출"), acts.toString());
        assertTrue(rep.actionsToWork(m.id, acts, "ow1").ok);
        WorkItem made = rep.fromMeeting(m.id).get(0);
        assertEquals("2026-11-20", made.due, "금요일까지 → 기한");
        WorkItem issue = rep.issues().get(0);
        assertEquals(1, local.audit.work.WorkReports.mentions(issue).size());
        assertTrue(rep.issueStatus(issue.id, "watch").ok);
        assertEquals("watch", local.audit.work.WorkReports.issueState(work.get(issue.id)));
    }

    // ───────────── 2차: 사규 상황 질의, 용어, 목차, 감사점검, 체크리스트 ─────────────

    @Test
    void solutionGlossaryIndexScanChecklist() throws Exception {
        assertTrue(rules.importJson(resource("rule-v4-sample.json"), false).ok);
        Map<String, Object> sol = rules.solve("팀원이 주말에 음주운전으로 면허가 취소됐습니다");
        assertTrue((Integer) sol.get("count") > 0);
        assertFalse(((List<?>) sol.get("risks")).isEmpty());
        assertFalse(((List<?>) sol.get("levels")).isEmpty(), "징계 수위를 뽑아야 함");

        // 동의어: '주취'로 찾아도 음주 조항이 나온다
        assertFalse(rules.search("주취", null, 20).isEmpty());
        assertTrue(rules.synonymEdit("add", Map.of("words", "투잡, 겸직")).ok);
        assertFalse(rules.search("투잡", null, 20).isEmpty(), "직접 넣은 동의어로도 찾음");

        assertTrue(rules.glossaryEdit("add", Map.of("term", "비위", "def", "규정 위반 행위", "aliases", "비행")).ok);
        assertTrue(rules.glossary().stream().anyMatch(g -> "비위".equals(g.term()) && g.customId() != null));

        assertFalse(rules.masterIndex(null).isEmpty());
        assertFalse(rules.masterIndex("징계").isEmpty());
        assertFalse(rules.timeline().isEmpty());

        List<RuleService.ScanItem> scan = rules.auditScan(null);
        assertFalse(scan.isEmpty());
        Op cl = rules.checklistFromScan("점검", List.of(scan.get(0).key(), scan.get(1).key()));
        assertTrue(cl.ok, cl.message);
        RuleService.Checklist c = rules.checklists().stream().filter(x -> x.id().equals(cl.id)).findFirst().orElseThrow();
        assertEquals(2, c.items().size());
        assertFalse(c.items().get(0).refs().isEmpty());
        assertTrue(rules.checklist("toggle", Map.of("id", c.id(), "item", c.items().get(0).id())).ok);
        assertTrue(rules.checklist("add", Map.of("id", c.id(), "text", "현장 확인\n서류 확인")).ok);
        RuleService.Checklist c2 = rules.checklists().stream().filter(x -> x.id().equals(cl.id)).findFirst().orElseThrow();
        assertEquals(4, c2.items().size());
        assertEquals(1, c2.done());
    }

    @Test
    void importDetectsBackupKind() throws Exception {
        assertEquals("work", local.audit.web.ImportController.kindOf(resource("work-v2-sample.json")));
        assertEquals("rules", local.audit.web.ImportController.kindOf(resource("rule-v4-sample.json")));
        assertNull(local.audit.web.ImportController.kindOf("{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
        assertNull(local.audit.web.ImportController.kindOf("not json".getBytes(StandardCharsets.UTF_8)));
    }

    // ───────────── 1.6.0: 편집기 내용 거르기, 예전 노트 모양 ─────────────

    @Test
    void htmlCleanKeepsNestedTablesAndFormattingButDropsScripts() {
        String in = "<p class=MsoNormal style=\"mso-x:1;color:#d1304a;font-size:20px\" onclick=\"x()\">가<o:p></o:p></p>"
            + "<table border=1><tr><td colspan=2 bgcolor=#ff0>바깥<table><tr><td>안쪽</td></tr></table></td></tr></table>"
            + "<script>alert(1)</script><img src=\"http://evil/x.png\"><img src=\"data:image/png;base64,AAAA\"><a href=\"javascript:x\">링크</a>"
            + "<font color=red size=5>큰 글</font><td>표 밖 칸</td>";
        String out = local.audit.HtmlClean.clean(in);
        assertTrue(out.contains("<table><tr><td colspan=\"2\" style=\"background-color:#ff0;\">바깥<table><tr><td>안쪽</td></tr></table></td></tr></table>"), out);
        assertTrue(out.contains("color:#d1304a;font-size:20px;"), out);
        assertFalse(out.contains("mso-") || out.contains("onclick") || out.contains("script") || out.contains("evil") || out.contains("javascript") || out.contains("class="), out);
        assertTrue(out.contains("data:image/png;base64,AAAA"));
        assertTrue(out.contains("<span style=\"color:red;font-size:24px;\">큰 글</span>"), out);
        assertFalse(out.contains("<td>표 밖 칸"), "표 밖의 칸은 칸으로 두지 않음");
        assertEquals("가\n바깥\t안쪽", local.audit.HtmlClean.toText("<p>가</p><table><tr><td>바깥</td><td>안쪽</td></tr></table>"));
        // 닫히지 않은 태그도 닫아 준다
        assertEquals("<b>굵게</b>", local.audit.HtmlClean.clean("<b>굵게"));
    }

    @Test
    void oldMarkdownNotesShowAsFormattedText() {
        String md = "# 제목\n\n- 하나\n- **둘**\n\n| 구분 | 값 |\n|---|---|\n| 가 | 1 |\n\n[[연결 업무]] #태그";
        assertTrue(local.audit.Md.looksLike(md));
        String h = local.audit.Md.toHtml(md);
        assertTrue(h.contains("<h2>제목</h2>"), h);
        assertTrue(h.contains("<li><b>둘</b></li>"), h);
        assertTrue(h.contains("<table><tr><th>구분</th><th>값</th></tr><tr><td>가</td><td>1</td></tr></table>"), h);
        assertFalse(h.contains("[["), h);
        assertFalse(local.audit.Md.looksLike("그냥 한 줄 메모"));
    }

    @Test
    void notesAreGroupedByTypeNewestFirstAndPaged() throws Exception {
        assertTrue(work.importJson(resource("work-v2-sample.json"), false).ok);
        for (int i = 0; i < 35; i++) {
            Op op = work.create("note", new HashMap<>(Map.of("title", "회의 " + i, "ntype", "meet")));
            work.get(op.id).mdate = "2026-" + String.format("%02d", 1 + i % 11) + "-10";
        }
        List<WorkService.NoteGroup> g = work.noteGroups(null, 5);
        WorkService.NoteGroup meet = g.stream().filter(x -> x.key().equals("meet")).findFirst().orElseThrow();
        assertEquals(35, meet.count());
        assertEquals(5, meet.rows().size());
        assertTrue(meet.rows().get(0).date().compareTo(meet.rows().get(4).date()) >= 0, "최신순");
        Map<String, Object> p1 = work.notePage("meet", null, 1, 30), p2 = work.notePage("meet", null, 2, 30);
        assertEquals(2, p1.get("pages"));
        assertEquals(5, ((Map<?, ?>) p2.get("months")).values().stream().mapToInt(v -> ((List<?>) v).size()).sum());
        // 편집기 내용 저장
        Op n = work.create("note", new HashMap<>(Map.of("title", "표 노트")));
        assertTrue(work.update(n.id, Map.of("content", "가", "contentHtml", "<table><tr><td>가<table><tr><td>나</td></tr></table></td></tr></table><script>x</script>")).ok);
        assertTrue(work.contentHtml(work.get(n.id)).contains("<td>가<table>"));
        assertFalse(work.contentHtml(work.get(n.id)).contains("script"));
    }

    @Test
    void registerAsksBeforeSameNameAndAddsRevision() {
        List<Map<String, Object>> arts = RuleService.cleanArticles(rules.preview("제1조(목적) 이 규칙은 보안을 정한다.\n제2조(정의) 용어는 다음과 같다."));
        assertEquals(2, arts.size());
        Op first = rules.addArticles(Map.of("title", "보안규칙", "label", "제정"), arts);
        assertTrue(first.ok, first.message);
        List<Map<String, Object>> again = RuleService.cleanArticles(rules.preview("제1조(목적) 이 규칙은 정보보안을 정한다."));
        Op ask = rules.addArticles(Map.of("title", " 보안규칙 "), again);
        assertFalse(ask.ok);
        assertEquals(409, ask.status);
        assertEquals(first.id, ask.extra);
        Op rev = rules.addArticles(Map.of("title", "보안규칙", "mode", "revise", "label", "개정 1", "note", "용어 정비"), again);
        assertTrue(rev.ok, rev.message);
        assertEquals(first.id, rev.id);
        assertEquals(2, ((List<?>) rules.reg(first.id).get("versions")).size());
        Op sep = rules.addArticles(Map.of("title", "보안규칙", "mode", "separate"), RuleService.cleanArticles(rules.preview("제1조(목적) 따로.")));
        assertTrue(sep.ok);
        assertNotEquals(first.id, sep.id);
    }

    static final String CARD_TSV = String.join("\n",
        "카드번호\t승인번호\t부서\t이름\t승인일자\t승인시간\t승인금액\t전표처리금액\t취소금액\t청구금액\t처리가능금액\t비용구분\t가맹점 사업자번호\t가맹점명\t업종\t업태\t가맹점 주소\t집주소\t구분\t사용내역\t품의자\t전송받은사번\t삼성전표번호\tSAP전표번호\t전표코스트센터\t전기일자\t전표상태\t취소여부\t결재내역 등록일",
        "1234-****-****-5678\t30012345\t경영진단파트\t홍길동\t2026-11-10\t10:30:00\t10000\t\t\t\t\t\t\t예시 식당\t음식점\t\t\t\t\t\t\t\t\t\t\t\t정상\t\t",
        // 쪼개기: 같은 카드·같은 날·같은 가맹점 20분 안, 각 20만·15만(한도 30만 아래), 합 35만
        "1111-****-****-0001\tA1\t영업1팀\t김철수\t2026-11-11\t19:05:00\t200,000\t\t\t\t\t업무추진비\t123-45-67890\t한우마을\t일반음식점\t한식\t서울특별시 중구 세종대로 10\t서울 강남구 역삼1동 1-1 래미안 101동\t\t거래처 간담회\t\t\t\t\t\t\t\t\t2026-11-12",
        "1111-****-****-0001\tA2\t영업1팀\t김철수\t2026-11-11\t19:25:00\t150,000\t\t\t\t\t업무추진비\t123-45-67890\t한우마을\t일반음식점\t한식\t서울특별시 중구 세종대로 10\t서울 강남구 역삼1동 1-1 래미안 101동\t\t거래처 간담회\t\t\t\t\t\t\t\t\t2026-11-12",
        // 여러 카드: 다른 카드 둘이 같은 가맹점에서 10분 안, 합 45만
        "2222-****-****-0002\tB1\t영업2팀\t이영희\t2026-11-12\t20:00:00\t250000\t\t\t\t\t업무추진비\t222-22-22222\t바다횟집\t일반음식점\t일식\t부산광역시 해운대구 우동 1\t\t\t팀 회식\t\t\t\t\t\t\t\t\t2026-11-13",
        "3333-****-****-0003\tC1\t영업2팀\t박민수\t2026-11-12\t20:08:00\t200000\t\t\t\t\t업무추진비\t222-22-22222\t바다횟집\t일반음식점\t일식\t부산광역시 해운대구 우동 1\t\t\t팀 회식\t\t\t\t\t\t\t\t\t2026-11-13",
        // 일요일 + 자택 인근(같은 동) + 제한업종(가맹점명) + 사용 사유 없음 + 늦은 등록
        "1111-****-****-0001\tA3\t영업1팀\t김철수\t2026-11-15\t23:40:00\t88000\t\t\t\t\t업무추진비\t333-33-33333\t별빛노래연습장\t일반음식점\t\t서울특별시 강남구 테헤란로 123 (역삼동)\t서울 강남구 역삼1동 1-1 래미안 101동\t\t\t\t\t\t\t\t\t\t\t2026-12-30",
        // 딱 떨어지는 고액 + 한도 바로 아래
        "4444-****-****-0004\tD1\t경영지원팀\t최정우\t2026-11-13\t12:10:00\t280000\t\t\t\t\t복리후생비\t444-44-44444\t정우식당\t일반음식점\t한식\t서울 종로구 종로 1\t\t\t부서 점심\t\t\t\t\t\t\t\t\t2026-11-14",
        // 취소된 건은 점검에서 뺀다
        "4444-****-****-0004\tD2\t경영지원팀\t최정우\t2026-11-14\t23:30:00\t500000\t\t500000\t\t\t복리후생비\t555-55-55555\t룸살롱 하늘\t유흥주점\t\t\t\t\t\t\t\t\t\t\t\t\tY\t");

    @Test
    void cardCheckFindsRiskyUsesAndKeepsReviews() throws Exception {
        local.audit.tools.CardCheck card = new local.audit.tools.CardCheck(db, settings);
        Op up = card.upload("11월 점검", "card.tsv", List.of(new local.audit.tools.Sheet.Table("승인내역", local.audit.tools.Sheet.text(CARD_TSV))));
        assertTrue(up.ok, up.message);
        Map<String, Object> pend = card.pending(up.id);
        @SuppressWarnings("unchecked") Map<String, Integer> map = (Map<String, Integer>) pend.get("map");
        assertEquals(3, map.get("name"));
        assertEquals(17, map.get("homeAddr"));
        assertEquals(13, map.get("merchant"));
        assertEquals(12, map.get("bizNo"));
        Map<String, String> form = new java.util.HashMap<>();
        map.forEach((k, v) -> form.put("col_" + k, String.valueOf(v)));
        Op done = card.confirm(up.id, form);
        assertTrue(done.ok, done.message);
        assertTrue(done.message.contains("7건"), done.message); // 예시 줄은 빠짐
        // 올린 원문과 집주소 원문은 남지 않는다
        assertNull(db.row("SELECT Pending FROM CardRuns WHERE Id=?", up.id).get("Pending"));
        assertEquals(0, db.rows("SELECT * FROM CardTx WHERE HomeArea LIKE '%래미안%' OR Extra LIKE '%래미안%'").size());

        Map<String, Set<String>> rulesByAppr = new java.util.HashMap<>();
        for (Map<String, Object> r : db.rows("SELECT t.ApprovalNo, h.Rule FROM CardHits h JOIN CardTx t ON t.Id=h.TxId WHERE h.RunId=?", up.id))
            rulesByAppr.computeIfAbsent((String) r.get("ApprovalNo"), k -> new java.util.HashSet<>()).add((String) r.get("Rule"));
        assertTrue(rulesByAppr.get("A1").contains("split") && rulesByAppr.get("A2").contains("split"), rulesByAppr.toString());
        assertTrue(rulesByAppr.get("B1").contains("multicard") && rulesByAppr.get("C1").contains("multicard"), rulesByAppr.toString());
        Set<String> a3 = rulesByAppr.get("A3");
        for (String r : List.of("holiday", "night", "restricted", "home", "combo", "purpose", "late")) assertTrue(a3.contains(r), r + " " + a3);
        assertTrue(rulesByAppr.get("D1").containsAll(List.of("near", "round")), rulesByAppr.toString());
        assertFalse(rulesByAppr.containsKey("D2"), "취소 건은 점검하지 않음");
        assertFalse(rulesByAppr.get("A1").contains("home"), "중구 가맹점은 강남구 집과 다름");

        Map<String, Object> list = card.list(up.id, Map.of(), 1, 100);
        @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) list.get("items");
        assertEquals("A3", items.get(0).get("ApprovalNo"), "가장 위험한 건이 맨 위");
        assertEquals("높음", items.get(0).get("Level"));

        // 소명 기록은 승인번호로 다음 회차에도 이어진다
        String key = (String) items.get(0).get("TxKey");
        assertTrue(card.review(key, "소명 받음", "가족 행사 아님 확인 필요").ok);
        Op up2 = card.upload("재점검", "card.tsv", List.of(new local.audit.tools.Sheet.Table("승인내역", local.audit.tools.Sheet.text(CARD_TSV))));
        assertTrue(card.confirm(up2.id, form).ok);
        @SuppressWarnings("unchecked") List<Map<String, Object>> items2 = (List<Map<String, Object>>) card.list(up2.id, Map.of(), 1, 100).get("items");
        assertEquals("소명 받음", items2.get(0).get("Status"));
        // 지난 회차 반복 표시
        @SuppressWarnings("unchecked") List<Map<String, Object>> people = (List<Map<String, Object>>) card.summary(up2.id).get("people");
        assertEquals(1, people.stream().filter(p -> "김철수".equals(p.get("Name"))).findFirst().orElseThrow().get("Repeat"));

        // 예외: 김철수의 그날 사용은 모든 규칙에서 뺀다
        long a3id = ((Number) items2.get(0).get("Id")).longValue();
        assertTrue(card.addException(a3id, "personDate", "", "휴일근무명령 확인").ok);
        @SuppressWarnings("unchecked") List<Map<String, Object>> items3 = (List<Map<String, Object>>) card.list(up2.id, Map.of(), 1, 100).get("items");
        assertTrue(items3.stream().noneMatch(m -> "A3".equals(m.get("ApprovalNo"))));
        assertFalse(card.addException(a3id, "person", "", "").ok, "사람 전체 예외는 규칙을 골라야 함");

        // 내려받기에 집주소 없음
        String csv = card.csv(up2.id, true);
        assertFalse(csv.contains("래미안") || csv.contains("역삼1동"));
        assertTrue(csv.contains("한우마을"));

        // 감사기록 초안 글
        Map<String, Object> draft = card.caseDraft(up2.id, List.of(((Number) items3.get(0).get("Id")).longValue()));
        assertTrue(((String) draft.get("body")).contains("점검:"));
    }

    @Test
    void cardCheckReadsDatesTimesAddressesAndXlsx() throws Exception {
        assertEquals(java.time.LocalDate.of(2026, 11, 10), local.audit.tools.CardCheck.date("2026.11.10"));
        assertEquals(java.time.LocalDate.of(2026, 11, 10), local.audit.tools.CardCheck.date("20261110"));
        assertEquals(java.time.LocalDate.of(2025, 11, 10), local.audit.tools.CardCheck.date("45971"));
        assertEquals("10:30:00", local.audit.tools.CardCheck.time("0.4375"));
        assertEquals("22:05:00", local.audit.tools.CardCheck.time("오후 10:05"));
        assertEquals("09:07:03", local.audit.tools.CardCheck.time("090703"));
        assertEquals(-12000L, local.audit.tools.CardCheck.amount("(12,000)"));
        assertEquals("4518123412341234", local.audit.tools.Sheet.tidyNumber("4518123412341234"));
        assertEquals("0.4375", local.audit.tools.Sheet.tidyNumber("0.4375"));
        assertArrayEquals(new String[] { "서울", "강남구", "역삼동", "테헤란로" }, local.audit.tools.CardCheck.area("서울특별시 강남구 테헤란로 123 (역삼동)"));
        assertArrayEquals(new String[] { "경기", "성남시 분당구", "", "정자일로" }, local.audit.tools.CardCheck.area("경기도 성남시 분당구 정자일로 95"));
        assertEquals("역삼동", local.audit.tools.CardCheck.area("서울 강남구 역삼1동 1-1 래미안 101동")[2]);
        assertEquals(2, local.audit.tools.CardCheck.near(local.audit.tools.CardCheck.area("서울 강남구 역삼1동 1-1"), local.audit.tools.CardCheck.area("서울특별시 강남구 테헤란로 1 (역삼동)")));
        assertEquals(1, local.audit.tools.CardCheck.near(local.audit.tools.CardCheck.area("서울 강남구 대치동 1"), local.audit.tools.CardCheck.area("서울특별시 강남구 테헤란로 1 (역삼동)")));
        assertEquals(0, local.audit.tools.CardCheck.near(local.audit.tools.CardCheck.area("서울 중구 을지로 1"), local.audit.tools.CardCheck.area("부산 중구 중앙대로 1")));

        // 손으로 만든 최소 xlsx: 공유 글자, 날짜 일련번호, 시간 소수
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(bo)) {
            String[][] parts = {
                { "xl/workbook.xml", "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"승인내역\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>" },
                { "xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"x\" Target=\"worksheets/sheet1.xml\"/></Relationships>" },
                { "xl/sharedStrings.xml", "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><si><t>이름</t></si><si><t>승인일자</t></si><si><t>승인시간</t></si><si><t>승인금액</t></si><si><t>가맹점명</t></si><si><t>홍길동</t></si><si><r><t>한우</t></r><r><t>마을</t></r></si></sst>" },
                { "xl/worksheets/sheet1.xml", "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
                    + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c><c r=\"C1\" t=\"s\"><v>2</v></c><c r=\"D1\" t=\"s\"><v>3</v></c><c r=\"E1\" t=\"s\"><v>4</v></c></row>"
                    + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>5</v></c><c r=\"B2\"><v>46336</v></c><c r=\"C2\"><v>0.4375</v></c><c r=\"D2\"><v>150000</v></c><c r=\"E2\" t=\"s\"><v>6</v></c></row>"
                    + "</sheetData></worksheet>" } };
            for (String[] pt : parts) { z.putNextEntry(new java.util.zip.ZipEntry(pt[0])); z.write(pt[1].getBytes(java.nio.charset.StandardCharsets.UTF_8)); z.closeEntry(); }
        }
        List<local.audit.tools.Sheet.Table> t = local.audit.tools.Sheet.read("a.xlsx", bo.toByteArray());
        assertEquals(List.of("홍길동", "46336", "0.4375", "150000", "한우마을"), t.get(0).rows().get(1));
        local.audit.tools.CardCheck card = new local.audit.tools.CardCheck(db, settings);
        Op up = card.upload("", "a.xlsx", t);
        assertTrue(up.ok, up.message);
        Map<String, String> form = Map.of("col_name", "0", "col_date", "1", "col_time", "2", "col_amount", "3", "col_merchant", "4");
        assertTrue(card.confirm(up.id, form).ok);
        Map<String, Object> row = db.row("SELECT TxDate, TxTime, Amount FROM CardTx WHERE RunId=?", up.id);
        assertEquals("2026-11-10", row.get("TxDate"));
        assertEquals("10:30:00", row.get("TxTime"));
        assertEquals(150000L, ((Number) row.get("Amount")).longValue());
    }

    @Test
    void ruleReviewCycleShowsDueAndResets() {
        Op made = rules.addArticles(Map.of("title", "취업규칙", "effectiveDate", "2025-10-01"), RuleService.cleanArticles(rules.preview("제1조(목적) 이 규칙은 근로조건을 정한다.")));
        assertTrue(made.ok, made.message);
        assertTrue(rules.saveMeta(made.id, Map.of("title", "취업규칙", "status", "active", "owner", "인사팀", "reviewMonths", "12")).ok);
        RuleService.RegRow row = rules.row(rules.reg(made.id));
        assertEquals("인사팀", row.owner());
        assertEquals("2026-10-01", row.reviewDue());
        assertEquals("bad", row.reviewTone()); // 오늘(2026-11-17) 기준 지남
        assertEquals(1, rules.reviewDue().size());
        assertTrue(rules.reviewed(made.id).ok);
        RuleService.RegRow after = rules.row(rules.reg(made.id));
        assertEquals("2027-11-17", after.reviewDue());
        assertEquals("", after.reviewTone());
    }

    @Test
    void benfordComparesFirstDigits() throws Exception {
        local.audit.tools.CardCheck card = new local.audit.tools.CardCheck(db, settings);
        StringBuilder t = new StringBuilder("이름\t승인일자\t승인금액\t가맹점명\n");
        for (int i = 0; i < 400; i++) t.append("가\t2026-11-10\t").append(90000 + i).append("\t식당\n"); // 모두 9로 시작
        Op up = card.upload("", "b.tsv", List.of(new local.audit.tools.Sheet.Table("t", local.audit.tools.Sheet.text(t.toString()))));
        assertTrue(card.confirm(up.id, Map.of("col_name", "0", "col_date", "1", "col_amount", "2", "col_merchant", "3")).ok);
        Map<String, Object> bf = card.benford(up.id);
        assertEquals(400L, bf.get("total"));
        assertEquals("bad", bf.get("tone"));
        assertEquals(9, bf.get("worstDigit"));
    }
}
