package local.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import local.audit.work.MonthlyReport;

/** 월간 업무보고서(지난달 현황 표·다음달 일정 표) 읽기. */
class MonthlyReportTest {
    static String td(String s) { return "<td><div class=\"para\">" + s + "</div></td>"; }

    static final String HTML = "<div class=\"para\">2026년 11월 경영진단파트 업무보고</div>"
        + "<div class=\"para\">1. 11월 업무 추진 현황</div>"
        + "<div class=\"tw\"><table><tr>" + td("구분") + td("추진항목") + td("주요내용") + "</tr>"
        + "<tr><td rowspan=\"2\"><div class=\"para\">업무진단</div></td>" + td("영업점 준법감사") + td("11.3~11.14 실시, 지적 5건") + "</tr>"
        + "<tr>" + td("법인카드 점검") + td("10월분 점검 완료") + "</tr>"
        + "<tr>" + td("내부통제") + td("자체점검 결과 취합") + td("부서별 제출 완료") + "</tr></table></div>"
        + "<div class=\"para\">2. 12월 업무 일정</div>"
        + "<div class=\"tw\"><table><tr>" + td("구분") + td("추진항목") + td("주요내용") + "</tr>"
        + "<tr><td rowspan=\"3\"><div class=\"para\">업무진단</div></td>" + td("보상센터 업무진단") + td("12.1~12.12 현장 진단") + "</tr>"
        + "<tr>" + td("법인카드 점검") + td("11월분 점검(12월 2주)") + "</tr>"
        + "<tr>" + td("결과 보고") + td("하순 대표이사 보고") + "</tr>"
        + "<tr>" + td("부정예방") + td("청렴 교육") + td("12월 중 전 직원 대상") + "</tr></table></div>";

    @Test
    void readsStatusAndPlanTables() {
        MonthlyReport.Parsed p = MonthlyReport.parse(HTML, "2026년 11월 경영진단파트 업무보고", LocalDate.of(2026, 11, 28));
        assertEquals(2026, p.year());
        assertEquals(12, p.month(), "다음달 일정 = 12월");
        assertEquals(3, p.status().size());
        assertEquals(4, p.plan().size());
        // 합친 구분 칸은 아래 줄에도
        assertEquals("업무진단", p.status().get(1).cat());
        assertEquals("법인카드 점검", p.status().get(1).item());
        assertEquals("내부통제", p.status().get(2).cat());
        assertEquals("업무진단", p.plan().get(2).cat());
        assertEquals("부정예방", p.plan().get(3).cat());
        // 날짜
        assertEquals("2026-12-01", p.plan().get(0).start()); assertEquals("2026-12-12", p.plan().get(0).due());
        assertEquals("2026-12-07", p.plan().get(1).start()); assertEquals("2026-12-13", p.plan().get(1).due(), "2주 = 달력 둘째 주(월~일)");
        assertEquals("2026-12-21", p.plan().get(2).start()); assertEquals("2026-12-31", p.plan().get(2).due());
        assertEquals("2026-12-01", p.plan().get(3).start()); assertEquals("2026-12-31", p.plan().get(3).due(), "날짜 없으면 그 달 전체");
        assertEquals("2026-11-03", p.status().get(0).start(), "현황은 지난달(11월) 기준");
        assertTrue(p.note().isEmpty(), p.note());
    }

    @Test
    void januaryPlanInDecemberReportIsNextYear() {
        String html = "<div class=\"para\">1월 업무 일정</div><table><tr>" + td("구분") + td("추진항목") + td("주요내용") + "</tr><tr>"
            + td("업무진단") + td("연간 계획 수립") + td("1.5~1.16") + "</tr></table>";
        MonthlyReport.Parsed p = MonthlyReport.parse(html, "", LocalDate.of(2026, 12, 20));
        assertEquals(2027, p.year()); assertEquals(1, p.month());
        assertEquals("2027-01-05", p.plan().get(0).start());
    }

    @Test
    void noTableGivesNote() {
        MonthlyReport.Parsed p = MonthlyReport.parse("<div class=\"para\">표 없음</div>", "표 없음", LocalDate.of(2026, 11, 1));
        assertTrue(p.plan().isEmpty() && !p.note().isEmpty());
    }
}
