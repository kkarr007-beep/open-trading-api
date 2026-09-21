"""HTML 보고서 산출.

내부망에서 열어야 하므로 외부 라이브러리를 부르지 않는다. 스타일과 도해를
모두 파일 안에 넣어 단일 파일로 완결시킨다.
"""

from __future__ import annotations

import html
from datetime import datetime
from pathlib import Path

import pandas as pd

from . import charts, config
from .metrics.base import MetricResult

ENTITY_TITLES = {
    "담당자": "보상담당자",
    "결재자": "차상위 결재자",
    "법인": "손사법인",
    "소속": "조직(소속)",
    "조합": "담당자-결재자-법인 조합",
}


def write(
    path: str | Path,
    rankings: dict[str, pd.DataFrame],
    results: list[MetricResult],
    data: dict[str, pd.DataFrame],
    views: dict[str, object] | None = None,
    top_n: int = 15,
) -> Path:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    views = views or {}

    sections = [
        _overview(data, rankings),
        _year_section(views),
        _org_section(views),
        _handler_section(rankings, results, views),
        _mobility_section(results),
        _regime_section(results),
        _network(rankings),
    ]
    for entity in ("담당자", "결재자", "법인", "소속", "조합"):
        frame = rankings.get(entity)
        if frame is not None and not frame.empty:
            sections.append(_ranking_section(entity, frame, top_n))
    sections.append(_metric_notes(results))

    body = "\n".join(section for section in sections if section)
    path.write_text(_document(body), encoding="utf-8")
    return path


def _find(results: list[MetricResult], name: str) -> MetricResult | None:
    return next((result for result in results if result.name == name), None)


def _year_section(views: dict[str, object]) -> str:
    matrix = views.get("업체연도행렬")
    if not isinstance(matrix, pd.DataFrame) or matrix.empty or len(matrix.columns) < 2:
        return ""

    figure = charts.heatmap(
        matrix,
        caption="칸 안 숫자는 그 해 전체 위임 건수 대비 비율(%)입니다. 연도를 가로로 읽으면 주력 업체 교체가 보입니다.",
    )
    return f"<section><h2>연도별 업체 위임율</h2>{figure}</section>"


def _org_section(views: dict[str, object]) -> str:
    frame = views.get("조직별업체")
    level = views.get("조직계층", "소속")
    if not isinstance(frame, pd.DataFrame) or frame.empty or level not in frame.columns:
        return ""

    leaders = (
        frame.groupby(level, sort=False)["위임건수"].sum()
        .nlargest(config.CHART_TOP_ORGS).index.tolist()
    )
    subset = frame[frame[level].isin(leaders)]
    order, folded = _fold(subset, level, "법인_명", "위임건수")
    if not order:
        return ""

    figure = charts.stacked_shares(
        folded, key=level, label="법인_명", value="위임건수", order=order,
        caption=f"{level} 단위 위임 구성. 건수 상위 {len(leaders)}개 조직만 표시합니다.",
    )
    return f"<section><h2>조직별 위임 업체 구성</h2>{figure}</section>"


def _handler_section(
    rankings: dict[str, pd.DataFrame], results: list[MetricResult], views: dict[str, object]
) -> str:
    frame = views.get("담당자별업체")
    if not isinstance(frame, pd.DataFrame) or frame.empty:
        return ""

    order, folded = _fold(frame, "담당자_id", "법인_명", "위임건수")
    names = frame.drop_duplicates("담당자_id").set_index("담당자_id")["담당자_명"].to_dict()
    blocks = []

    assignment = _find(results, "A_배당편중")
    if assignment is not None and not assignment.detail.empty:
        ranked = rankings.get("담당자")
        if ranked is not None and not ranked.empty:
            wanted = [names.get(key, key) for key in ranked["id"].head(config.CHART_TOP_HANDLERS)]
            candidates = assignment.detail[assignment.detail["담당자_명"].isin(wanted)]
            # 편차가 유의하지 않은 담당자는 빼야 한다. 기대와 실제가 붙어 있는
            # 막대가 섞이면 도해가 '상위 담당자는 다 이렇다'로 잘못 읽힌다.
            candidates = candidates[candidates["편중강도"] >= 2.0]
            strongest = (
                candidates.sort_values("편중강도", ascending=False)
                .drop_duplicates("담당자_명")
            )
            # 소속을 옮긴 사람은 비율이 어느 소속 기준인지 밝혀야 한다.
            multi = set(
                candidates.groupby("담당자_명")["소속_명"].nunique().pipe(
                    lambda counts: counts[counts > 1].index
                )
            )
            rows = [
                {
                    "이름": (
                        f"{row['담당자_명']} · {row['소속_명']}"
                        if row["담당자_명"] in multi
                        else row["담당자_명"]
                    ),
                    "업체": row["법인_명"],
                    "실제": row["실제배당률"],
                    "기대": row["기대배당률"],
                }
                for _, row in strongest.iterrows()
            ]
            blocks.append(
                charts.meters(
                    rows,
                    caption="혐의도 상위 담당자가 가장 많이 보낸 업체의 비율과, 같은 점포 동료들의 평균입니다.",
                )
            )

    if order:
        blocks.append(
            charts.stacked_shares(
                folded, key="담당자_id", label="법인_명", value="위임건수",
                order=order, row_labels=names,
                caption="같은 담당자의 전체 위임 구성. 한 색이 막대를 덮으면 거래처가 한 곳에 묶여 있다는 뜻입니다.",
            )
        )

    if not blocks:
        return ""
    return f"<section><h2>담당자별 위임 업체</h2>{''.join(blocks)}</section>"


def _mobility_section(results: list[MetricResult]) -> str:
    result = _find(results, "I_인물이동")
    if result is None or result.detail.empty:
        return ""

    detail = result.detail.sort_values("지속강도", ascending=False)
    people = detail.drop_duplicates(["인물", "법인_명"]).head(8)

    rows = []
    for _, person in people.iterrows():
        postings = detail[
            (detail["인물"] == person["인물"]) & (detail["법인_명"] == person["법인_명"])
        ]
        cells = "".join(
            f"<tr><td class='name'>{html.escape(str(row['소속']))}</td>"
            f"<td class='num'>{html.escape(str(row['기간']))}</td>"
            f"<td class='num'>{int(row['배당건수']):,}건</td>"
            f"<td class='num'>{row['본인배당률']:.0f}%</td>"
            f"<td class='num'>{row['소속기대율']:.0f}%</td></tr>"
            for _, row in postings.iterrows()
        )
        rows.append(
            f"<div class='mobility-card'>"
            f"<p class='mobility-head'><strong>{html.escape(str(person['인물']))}</strong>"
            f" · {html.escape(str(person['역할']))} · "
            f"유지 업체 <strong>{html.escape(str(person['법인_명']))}</strong></p>"
            f"<div class='table-wrap'><table><thead><tr>"
            f"<th>소속</th><th>기간</th><th>위임건수</th><th>본인 비율</th><th>소속 기준</th>"
            f"</tr></thead><tbody>{cells}</tbody></table></div></div>"
        )

    return f"""<section>
<h2>소속을 옮겨도 유지되는 업체</h2>
<p class="note">소속이 바뀐 뒤에도 같은 업체 편중이 남아 있는 인물입니다.
각 소속의 기준은 그 소속 동료들의 평균이므로, 지점 관행으로는 설명되지 않습니다.</p>
{"".join(rows)}
</section>"""


def _regime_section(results: list[MetricResult]) -> str:
    result = _find(results, "J_조직전환")
    if result is None or result.detail.empty:
        return ""

    top = result.detail.head(6)
    blocks = []
    for _, row in top.iterrows():
        pairs = [
            {"이름": f"{row['이탈업체']} (이탈)", "이전": row["이탈업체_이전"], "이후": row["이탈업체_이후"]},
            {"이름": f"{row['신규주력업체']} (신규)", "이전": row["신규업체_이전"], "이후": row["신규업체_이후"]},
        ]
        staff = (
            f" · 같은 시점 결재자 교체: {html.escape(str(row['신규결재자']))}"
            if row["결재자교체"] == "예" and row["신규결재자"]
            else ""
        )
        blocks.append(
            f"<div class='regime-card'>"
            f"<p class='mobility-head'><strong>{html.escape(str(row['조직']))}</strong>"
            f" · {html.escape(str(row['전환']))}{staff}</p>"
            + charts.dumbbells(pairs)
            + "</div>"
        )

    return f"""<section>
<h2>조직 위임처 전환</h2>
<p class="note">지점의 주력 위임 업체가 해를 넘기며 바뀐 구간입니다.
전환 자체는 정상적인 업체 교체일 수 있으므로, 같은 시점의 결재자 교체 여부를 함께 봅니다.</p>
{"".join(blocks)}
</section>"""


def _fold(frame: pd.DataFrame, key: str, label: str, value: str):
    from . import profile

    return profile.composition_series(frame, key, label, value, config.CHART_SERIES_CAP)


def _document(body: str) -> str:
    return f"""<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>외부조사 유착 혐의 분석</title>
<style>{charts.series_style()}
{_CSS}
{charts.CSS}</style>
</head>
<body>
<main>
<header class="page-head">
  <p class="eyebrow">보험금 심사 · 외부조사 배당 점검</p>
  <h1>외부조사 법인 유착 혐의 1차 분석</h1>
  <p class="meta">산출 {datetime.now():%Y-%m-%d %H:%M}</p>
</header>
{body}
<footer>
  <p>혐의도는 동료 집단 대비 통계적 이상 신호를 점수화한 값입니다.
  그 자체가 비위 사실을 뜻하지 않으며, 소명 절차를 거쳐 확인해야 합니다.</p>
</footer>
</main>
</body>
</html>"""


def _overview(data: dict[str, pd.DataFrame], rankings: dict[str, pd.DataFrame]) -> str:
    cases = data["cases"]
    cards = [
        ("조사 배당 건수", f"{len(cases):,}", "청구번호 기준"),
        ("지급 행 수", f"{len(data['payments']):,}", "원본 행"),
        ("담당자", f"{cases['담당자_id'].nunique():,}", "명"),
        ("손사법인", f"{cases['법인_id'].nunique():,}", "곳"),
    ]

    high = 0
    for frame in rankings.values():
        if not frame.empty:
            high += int((frame["등급"] == config.RISK_RULES[0][2]).sum())
    cards.append(("혐의도 높음", f"{high:,}", "주체 수"))

    tiles = "".join(
        f'<div class="tile"><p class="tile-label">{html.escape(label)}</p>'
        f'<p class="tile-value">{html.escape(value)}</p>'
        f'<p class="tile-unit">{html.escape(unit)}</p></div>'
        for label, value, unit in cards
    )
    return f'<section><h2>개요</h2><div class="tiles">{tiles}</div></section>'


def _ranking_section(entity: str, frame: pd.DataFrame, top_n: int) -> str:
    top = frame.head(top_n)
    rows = []
    for order, (_, row) in enumerate(top.iterrows(), start=1):
        band = str(row.get("등급", ""))
        volume = f"{int(row['담당건수']):,}건" if "담당건수" in row else "-"
        rows.append(
            f"<tr>"
            f'<td class="rank">{order}</td>'
            f'<td class="name">{html.escape(str(row["명"]))}</td>'
            f'<td class="num">{html.escape(volume)}</td>'
            f'<td class="score">{_bar(float(row["혐의도"]))}</td>'
            f'<td><span class="badge band-{_band_key(band)}">{html.escape(band)}</span></td>'
            f'<td class="reason">{html.escape(str(row.get("주요사유", "")))}</td>'
            f"</tr>"
        )

    return f"""<section>
<h2>{html.escape(ENTITY_TITLES.get(entity, entity))} 혐의 순위</h2>
<p class="note">상위 {len(top)}건 · 전체 {len(frame):,}건 중</p>
<div class="table-wrap">
<table>
<thead><tr>
<th>순위</th><th>대상</th><th>담당건수</th><th>혐의도</th><th>등급</th><th>주요 사유</th>
</tr></thead>
<tbody>{"".join(rows)}</tbody>
</table>
</div>
</section>"""


def _bar(score: float) -> str:
    width = max(0.0, min(100.0, score))
    return (
        f'<span class="bar"><span class="bar-fill" style="width:{width:.0f}%"></span></span>'
        f'<span class="bar-value">{score:.0f}</span>'
    )


def _band_key(band: str) -> str:
    return {"높음": "high", "중간": "mid"}.get(band, "low")


def _network(rankings: dict[str, pd.DataFrame]) -> str:
    """상위 3자 조합을 도해로 보여준다. 어떤 라인이 반복되는지 한눈에 보기 위함."""
    frame = rankings.get("조합")
    if frame is None or frame.empty:
        return ""

    triples = frame[frame["명"].astype(str).str.count("→") == 2].head(8)
    if triples.empty:
        return ""

    rows = []
    for order, (_, row) in enumerate(triples.iterrows()):
        parts = [part.strip() for part in str(row["명"]).split("→")]
        y = 40 + order * 46
        score = float(row["혐의도"])
        opacity = 0.35 + min(score, 100) / 100 * 0.65
        rows.append(
            f'<g opacity="{opacity:.2f}">'
            f'<line x1="150" y1="{y}" x2="330" y2="{y}" class="edge"/>'
            f'<line x1="470" y1="{y}" x2="650" y2="{y}" class="edge"/>'
            f'<text x="145" y="{y + 4}" class="node-text" text-anchor="end">{html.escape(parts[0])}</text>'
            f'<text x="400" y="{y + 4}" class="node-text" text-anchor="middle">{html.escape(parts[1])}</text>'
            f'<text x="655" y="{y + 4}" class="node-text" text-anchor="start">{html.escape(parts[2])}</text>'
            f'<circle cx="150" cy="{y}" r="4" class="node"/>'
            f'<circle cx="400" cy="{y}" r="4" class="node"/>'
            f'<circle cx="650" cy="{y}" r="4" class="node"/>'
            f"</g>"
        )

    height = 40 + len(triples) * 46 + 20
    return f"""<section>
<h2>반복되는 위임 라인</h2>
<p class="note">혐의도 상위 조합. 선이 진할수록 신호가 강합니다.</p>
<div class="figure">
<svg viewBox="0 0 860 {height}" role="img" aria-label="담당자-결재자-법인 3자 조합 도해">
<text x="150" y="20" class="axis-label" text-anchor="end">담당자</text>
<text x="400" y="20" class="axis-label" text-anchor="middle">결재자</text>
<text x="650" y="20" class="axis-label" text-anchor="start">손사법인</text>
{"".join(rows)}
</svg>
</div>
</section>"""


def _metric_notes(results: list[MetricResult]) -> str:
    rows = []
    for result in results:
        status = "산출" if result.scores else "미산출"
        rows.append(
            f"<tr><td>{html.escape(result.name)}</td>"
            f"<td>{html.escape(result.title)}</td>"
            f'<td><span class="badge band-{"low" if result.scores else "none"}">'
            f"{status}</span></td>"
            f"<td class='reason'>{html.escape(result.note)}</td></tr>"
        )
    return f"""<section>
<h2>지표별 산출 결과</h2>
<div class="table-wrap">
<table>
<thead><tr><th>지표</th><th>내용</th><th>상태</th><th>비고</th></tr></thead>
<tbody>{"".join(rows)}</tbody>
</table>
</div>
</section>"""


_CSS = """
:root {
  --bg: #f7f8fa; --surface: #ffffff; --border: #e3e6ec;
  --text: #1a1d23; --muted: #5f6672; --accent: #2f5fd0;
  --high: #c2410c; --mid: #a16207; --low: #4b5563;
  --high-bg: #fdece3; --mid-bg: #fdf4dc; --low-bg: #eef0f3;
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    --bg: #14161a; --surface: #1c1f25; --border: #2b2f37;
    --text: #e8eaee; --muted: #9aa2ae; --accent: #7aa2f7;
    --high: #fb923c; --mid: #fbbf24; --low: #9aa2ae;
    --high-bg: #3a2416; --mid-bg: #3a3116; --low-bg: #24272d;
  }
}
:root[data-theme="dark"] {
  --bg: #14161a; --surface: #1c1f25; --border: #2b2f37;
  --text: #e8eaee; --muted: #9aa2ae; --accent: #7aa2f7;
  --high: #fb923c; --mid: #fbbf24; --low: #9aa2ae;
  --high-bg: #3a2416; --mid-bg: #3a3116; --low-bg: #24272d;
}
* { box-sizing: border-box; }
body {
  margin: 0; background: var(--bg); color: var(--text);
  font-family: -apple-system, "Segoe UI", "Malgun Gothic", "Apple SD Gothic Neo", sans-serif;
  font-size: 15px; line-height: 1.6;
}
main { max-width: 1120px; margin: 0 auto; padding: 40px 16px 64px; }
.page-head { border-bottom: 2px solid var(--text); padding-bottom: 20px; margin-bottom: 36px; }
.eyebrow { margin: 0 0 6px; font-size: 13px; color: var(--muted); letter-spacing: .04em; }
h1 { margin: 0 0 8px; font-size: 26px; letter-spacing: -.01em; }
h2 { font-size: 18px; margin: 0 0 14px; }
.meta, .note { color: var(--muted); font-size: 13px; margin: 0 0 14px; }
section { margin-bottom: 40px; }
.tiles { display: grid; gap: 12px; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); }
.tile { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 16px; }
.tile-label { margin: 0; font-size: 12px; color: var(--muted); }
.tile-value { margin: 6px 0 2px; font-size: 24px; font-weight: 650; font-variant-numeric: tabular-nums; }
.tile-unit { margin: 0; font-size: 12px; color: var(--muted); }
.table-wrap { overflow-x: auto; border: 1px solid var(--border); border-radius: 10px; background: var(--surface); }
table { width: 100%; border-collapse: collapse; font-size: 14px; }
th, td { padding: 10px 12px; text-align: left; border-bottom: 1px solid var(--border); vertical-align: top; }
th { font-size: 12px; color: var(--muted); font-weight: 600; white-space: nowrap; background: var(--bg); }
tbody tr:last-child td { border-bottom: none; }
.rank { color: var(--muted); font-variant-numeric: tabular-nums; width: 44px; }
.name { font-weight: 600; white-space: nowrap; }
.num { font-variant-numeric: tabular-nums; white-space: nowrap; color: var(--muted); }
.score { white-space: nowrap; min-width: 130px; }
.reason { color: var(--muted); font-size: 13px; min-width: 240px; }
.bar { display: inline-block; width: 74px; height: 7px; border-radius: 4px; background: var(--low-bg); vertical-align: middle; overflow: hidden; }
.bar-fill { display: block; height: 100%; background: var(--accent); }
.bar-value { margin-left: 8px; font-variant-numeric: tabular-nums; font-weight: 600; }
.badge { display: inline-block; padding: 2px 9px; border-radius: 999px; font-size: 12px; font-weight: 600; white-space: nowrap; }
.band-high { background: var(--high-bg); color: var(--high); }
.band-mid { background: var(--mid-bg); color: var(--mid); }
.band-low, .band-none { background: var(--low-bg); color: var(--low); }
.figure { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 12px; overflow-x: auto; }
svg { width: 100%; height: auto; min-width: 620px; display: block; }
.edge { stroke: var(--accent); stroke-width: 1.5; }
.node { fill: var(--accent); }
.node-text { fill: var(--text); font-size: 12px; font-family: inherit; }
.axis-label { fill: var(--muted); font-size: 11px; font-weight: 600; font-family: inherit; letter-spacing: .04em; }
.mobility-card, .regime-card { margin-bottom: 20px; }
.mobility-head { margin: 0 0 8px; font-size: 14px; color: var(--text); }
.mobility-card table { font-size: 13px; }
footer { border-top: 1px solid var(--border); padding-top: 16px; color: var(--muted); font-size: 13px; }
footer p { margin: 0; }
@media (max-width: 640px) { main { padding: 24px 16px 48px; } h1 { font-size: 21px; } }
"""
