"""HTML 보고서 산출.

내부망에서 열어야 하므로 외부 라이브러리를 부르지 않는다. 스타일과 도해를
모두 파일 안에 넣어 단일 파일로 완결시킨다.
"""

from __future__ import annotations

import html
from datetime import datetime
from pathlib import Path

import pandas as pd

from . import config
from .metrics.base import MetricResult

ENTITY_TITLES = {
    "담당자": "보상담당자",
    "결재자": "차상위 결재자",
    "법인": "손사법인",
    "조합": "담당자-결재자-법인 조합",
}


def write(
    path: str | Path,
    rankings: dict[str, pd.DataFrame],
    results: list[MetricResult],
    data: dict[str, pd.DataFrame],
    top_n: int = 15,
) -> Path:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)

    sections = [
        _overview(data, rankings),
        _network(rankings),
    ]
    for entity in ("담당자", "결재자", "법인", "조합"):
        frame = rankings.get(entity)
        if frame is not None and not frame.empty:
            sections.append(_ranking_section(entity, frame, top_n))
    sections.append(_metric_notes(results))

    path.write_text(_document("\n".join(sections)), encoding="utf-8")
    return path


def _document(body: str) -> str:
    return f"""<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>외부조사 유착 혐의 분석</title>
<style>{_CSS}</style>
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
footer { border-top: 1px solid var(--border); padding-top: 16px; color: var(--muted); font-size: 13px; }
footer p { margin: 0; }
@media (max-width: 640px) { main { padding: 24px 16px 48px; } h1 { font-size: 21px; } }
"""
