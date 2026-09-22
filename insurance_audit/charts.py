"""보고서 도해.

내부망에서 열리는 단일 파일이어야 해서 차트 라이브러리를 쓰지 않고 SVG를 직접
그린다. 색은 색약 조건에서 인접 색이 구분되는지 검증한 순서를 고정으로 쓰고,
슬롯 수를 넘기면 꼬리를 '기타'로 접는다. 색을 더 만들어 쓰면 구분이 무너진다.
"""

from __future__ import annotations

import html

import pandas as pd

# 검증을 통과한 고정 순서. 순서 자체가 색약 안전 장치이므로 섞지 않는다.
SERIES_LIGHT = ("#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300")
SERIES_DARK = ("#3987e5", "#d95926", "#199e70", "#c98500", "#d55181", "#008300")
OTHER_COLOR = ("#898781", "#898781")

# 크기가 곧 값인 자리에는 한 가지 색의 명도 단계만 쓴다.
SEQUENTIAL_LIGHT = ("#eef4fd", "#cde2fb", "#9ec5f4", "#6da7ec", "#3987e5", "#256abf", "#184f95")
SEQUENTIAL_DARK = ("#152131", "#183050", "#1c4a80", "#1f5ca3", "#2a78d6", "#5598e7", "#86b6ef")

ROW_HEIGHT = 26
LABEL_WIDTH = 132
BAR_WIDTH = 520
GAP = 2  # 인접 조각 사이 여백. 경계가 보여야 조각이 구분된다.


def _escape(value: object) -> str:
    return html.escape(str(value))


def series_style() -> str:
    """색 슬롯을 CSS 변수로 선언한다. 밝은/어두운 모드가 한곳에서 바뀐다."""
    light = "\n".join(f"  --series-{i}: {c};" for i, c in enumerate(SERIES_LIGHT, 1))
    dark = "\n".join(f"  --series-{i}: {c};" for i, c in enumerate(SERIES_DARK, 1))
    seq_light = "\n".join(f"  --seq-{i}: {c};" for i, c in enumerate(SEQUENTIAL_LIGHT, 1))
    seq_dark = "\n".join(f"  --seq-{i}: {c};" for i, c in enumerate(SEQUENTIAL_DARK, 1))
    return f""":root {{
{light}
{seq_light}
  --series-other: {OTHER_COLOR[0]};
}}
@media (prefers-color-scheme: dark) {{
  :root:not([data-theme="light"]) {{
{dark}
{seq_dark}
    --series-other: {OTHER_COLOR[1]};
  }}
}}
:root[data-theme="dark"] {{
{dark}
{seq_dark}
  --series-other: {OTHER_COLOR[1]};
}}"""


def _color_var(index: int, label: str) -> str:
    if label == "기타":
        return "var(--series-other)"
    return f"var(--series-{min(index + 1, len(SERIES_LIGHT))})"


def legend(order: list[str]) -> str:
    """계열이 둘 이상이면 범례는 항상 둔다. 색만으로 구분을 맡기지 않는다."""
    if len(order) < 2:
        return ""
    items = "".join(
        f'<span class="legend-item">'
        f'<span class="legend-swatch" style="background:{_color_var(i, label)}"></span>'
        f"{_escape(label)}</span>"
        for i, label in enumerate(order)
    )
    return f'<div class="legend">{items}</div>'


def stacked_shares(
    frame: pd.DataFrame,
    key: str,
    label: str,
    value: str,
    order: list[str],
    row_labels: dict[str, str] | None = None,
    caption: str = "",
) -> str:
    """구성비 가로 누적 막대.

    항목 이름이 길고 개수가 많아 가로로 눕힌다. 10% 이상인 조각에만 값을 얹어
    잔조각의 숫자가 겹치지 않게 한다.
    """
    if frame.empty:
        return ""

    # 한 곳에 몰린 행이 위로 오게 둔다. 이름순으로 두면 눈에 띄어야 할 행이
    # 가운데 묻힌다.
    totals = frame.groupby(key, sort=False)[value].transform("sum")
    peak = (frame[value] / totals).groupby(frame[key]).max()
    rows = peak.sort_values(ascending=False).index.tolist()

    height = len(rows) * ROW_HEIGHT + 16
    width = LABEL_WIDTH + BAR_WIDTH + 58
    color_of = {name: _color_var(i, name) for i, name in enumerate(order)}

    marks = []
    for row_index, row_key in enumerate(rows):
        subset = frame[frame[key] == row_key]
        total = float(subset[value].sum())
        if total <= 0:
            continue
        y = row_index * ROW_HEIGHT + 8
        x = LABEL_WIDTH

        name = (row_labels or {}).get(row_key, row_key)
        marks.append(
            f'<text x="{LABEL_WIDTH - 10}" y="{y + 13}" class="row-label" '
            f'text-anchor="end">{_escape(name)}</text>'
        )

        ordered = sorted(
            subset.itertuples(index=False),
            key=lambda item: order.index(getattr(item, label))
            if getattr(item, label) in order
            else len(order),
        )
        for item in ordered:
            share = float(getattr(item, value)) / total * 100
            if share <= 0:
                continue
            segment = max(BAR_WIDTH * share / 100 - GAP, 1.0)
            name_of = getattr(item, label)
            marks.append(
                f'<rect x="{x:.1f}" y="{y}" width="{segment:.1f}" height="18" rx="3" '
                f'fill="{color_of.get(name_of, "var(--series-other)")}">'
                f"<title>{_escape(name_of)} {share:.1f}%</title></rect>"
            )
            # 조각이 충분히 넓을 때만 값을 안에 넣는다. 밝은 모드 대비 보완도 겸한다.
            if segment > 46:
                marks.append(
                    f'<text x="{x + segment / 2:.1f}" y="{y + 13}" class="seg-label" '
                    f'text-anchor="middle">{share:.0f}%</text>'
                )
            x += segment + GAP

        marks.append(
            f'<text x="{LABEL_WIDTH + BAR_WIDTH + 10}" y="{y + 13}" class="row-total">'
            f"{int(total):,}건</text>"
        )

    body = "\n".join(marks)
    note = f'<p class="note">{_escape(caption)}</p>' if caption else ""
    return f"""{note}{legend(order)}
<div class="figure">
<svg viewBox="0 0 {width} {height}" width="{width}" height="{height}" role="img" aria-label="구성비 누적 막대">
{body}
</svg>
</div>"""


def meters(rows: list[dict], caption: str = "") -> str:
    """실제 비율과 기대 비율을 한 줄에 겹쳐 보여준다.

    누적 막대는 구성 전체를 보여주지만 '기대보다 얼마나 많은가'는 드러내지 못한다.
    한 업체에 대한 비율 하나를 기준선과 함께 보는 편이 판단에 바로 쓰인다.
    """
    if not rows:
        return ""

    height = len(rows) * 30 + 22
    width = LABEL_WIDTH + BAR_WIDTH + 130
    marks = []

    for index, row in enumerate(rows):
        y = index * 30 + 16
        actual = max(0.0, min(100.0, float(row["실제"])))
        expected = max(0.0, min(100.0, float(row["기대"])))

        marks.append(
            f'<text x="{LABEL_WIDTH - 10}" y="{y + 13}" class="row-label" '
            f'text-anchor="end">{_escape(row["이름"])}</text>'
        )
        marks.append(
            f'<rect x="{LABEL_WIDTH}" y="{y + 3}" width="{BAR_WIDTH}" height="14" rx="4" '
            f'class="track"/>'
        )
        marks.append(
            f'<rect x="{LABEL_WIDTH}" y="{y + 3}" '
            f'width="{max(BAR_WIDTH * actual / 100, 2):.1f}" height="14" rx="4" '
            f'fill="var(--series-1)"><title>실제 {actual:.1f}%</title></rect>'
        )
        tick = LABEL_WIDTH + BAR_WIDTH * expected / 100
        marks.append(
            f'<line x1="{tick:.1f}" y1="{y}" x2="{tick:.1f}" y2="{y + 20}" class="tick">'
            f"<title>동료 기준 {expected:.1f}%</title></line>"
        )
        marks.append(
            f'<text x="{LABEL_WIDTH + BAR_WIDTH + 10}" y="{y + 14}" class="row-total">'
            f'{actual:.0f}% · 기대 {expected:.0f}%</text>'
        )
        marks.append(
            f'<text x="{LABEL_WIDTH + 6}" y="{y + 14}" class="seg-label" '
            f'text-anchor="start">{_escape(row["업체"])}</text>'
        )

    body = "\n".join(marks)
    note = f'<p class="note">{_escape(caption)}</p>' if caption else ""
    return f"""{note}
<div class="figure">
<svg viewBox="0 0 {width} {height}" width="{width}" height="{height}" role="img" aria-label="담당자별 편중 업체 비율과 동료 기준선">
<text x="{LABEL_WIDTH}" y="10" class="axis-label">막대: 실제 위임 비율 · 세로선: 같은 점포 동료 기준</text>
{body}
</svg>
</div>"""


def heatmap(matrix: pd.DataFrame, caption: str = "") -> str:
    """업체 × 연도 위임율. 값의 크기를 한 가지 색의 명도로만 나타낸다."""
    if matrix.empty:
        return ""

    columns = list(matrix.columns)
    cell_w = max(min(int(BAR_WIDTH / max(len(columns), 1)), 96), 52)
    width = LABEL_WIDTH + cell_w * len(columns) + 16
    height = len(matrix) * 24 + 34
    top = float(matrix.to_numpy().max()) or 1.0

    marks = []
    for column_index, column in enumerate(columns):
        x = LABEL_WIDTH + column_index * cell_w + cell_w / 2
        marks.append(
            f'<text x="{x:.0f}" y="16" class="axis-label" text-anchor="middle">'
            f"{_escape(column)}</text>"
        )

    for row_index, (name, series) in enumerate(matrix.iterrows()):
        y = row_index * 24 + 24
        marks.append(
            f'<text x="{LABEL_WIDTH - 10}" y="{y + 15}" class="row-label" '
            f'text-anchor="end">{_escape(name)}</text>'
        )
        for column_index, column in enumerate(columns):
            value = float(series[column])
            step = min(int(value / top * len(SEQUENTIAL_LIGHT)), len(SEQUENTIAL_LIGHT) - 1)
            x = LABEL_WIDTH + column_index * cell_w
            marks.append(
                f'<rect x="{x}" y="{y}" width="{cell_w - GAP}" height="{24 - GAP}" rx="3" '
                f'fill="var(--seq-{step + 1})">'
                f"<title>{_escape(name)} {_escape(column)} {value:.1f}%</title></rect>"
            )
            # 숫자를 직접 얹어 색만으로 값을 읽게 하지 않는다. 소수 한 자리까지
            # 적는다. 반올림해 버리면 색은 다른데 숫자는 같아 보인다.
            ink = "cell-ink-strong" if step >= 4 else "cell-ink-weak"
            marks.append(
                f'<text x="{x + (cell_w - GAP) / 2:.0f}" y="{y + 15}" '
                f'class="{ink}" text-anchor="middle">{value:.1f}</text>'
            )

    body = "\n".join(marks)
    note = f'<p class="note">{_escape(caption)}</p>' if caption else ""
    return f"""{note}
<div class="figure">
<svg viewBox="0 0 {width} {height}" width="{width}" height="{height}" role="img" aria-label="업체별 연도별 위임율">
{body}
</svg>
</div>"""


def dumbbells(rows: list[dict], caption: str = "") -> str:
    """전환 전후 비교. 두 시점을 선으로 이어 이동 방향과 폭을 함께 보여준다."""
    if not rows:
        return ""

    height = len(rows) * 34 + 26
    width = LABEL_WIDTH + BAR_WIDTH + 96
    marks = [
        f'<text x="{LABEL_WIDTH}" y="12" class="axis-label">'
        f"엷은 점: 전환 전 점유율 · 진한 점: 전환 후</text>"
    ]

    for index, row in enumerate(rows):
        y = index * 34 + 32
        before = max(0.0, min(100.0, float(row["이전"])))
        after = max(0.0, min(100.0, float(row["이후"])))
        x1 = LABEL_WIDTH + BAR_WIDTH * before / 100
        x2 = LABEL_WIDTH + BAR_WIDTH * after / 100

        marks.append(
            f'<text x="{LABEL_WIDTH - 10}" y="{y + 4}" class="row-label" '
            f'text-anchor="end">{_escape(row["이름"])}</text>'
        )
        marks.append(f'<line x1="{LABEL_WIDTH}" y1="{y}" x2="{LABEL_WIDTH + BAR_WIDTH}" y2="{y}" class="track-line"/>')
        marks.append(f'<line x1="{x1:.1f}" y1="{y}" x2="{x2:.1f}" y2="{y}" class="dumbbell"/>')
        marks.append(
            f'<circle cx="{x1:.1f}" cy="{y}" r="5" class="dot-before">'
            f"<title>전환 전 {before:.1f}%</title></circle>"
        )
        marks.append(
            f'<circle cx="{x2:.1f}" cy="{y}" r="5" class="dot-after">'
            f"<title>전환 후 {after:.1f}%</title></circle>"
        )
        marks.append(
            f'<text x="{LABEL_WIDTH + BAR_WIDTH + 10}" y="{y + 4}" class="row-total">'
            f"{before:.0f}% → {after:.0f}%</text>"
        )

    body = "\n".join(marks)
    note = f'<p class="note">{_escape(caption)}</p>' if caption else ""
    return f"""{note}
<div class="figure">
<svg viewBox="0 0 {width} {height}" width="{width}" height="{height}" role="img" aria-label="전환 전후 점유율 비교">
{body}
</svg>
</div>"""


CSS = """
.figure { background: var(--surface); border: 1px solid var(--border); border-radius: 8px;
  padding: 12px 10px; overflow-x: auto; margin-bottom: 8px; max-width: 100%; }
/* SVG 에 고유 크기(width/height 속성)를 줘서 컨테이너에 맞춰 늘어나지 않게 한다.
   늘어나면 그 안의 글자도 함께 커져 본문 글자 크기와 어긋난다. 화면보다 넓은
   도해만 감싼 상자 안에서 가로로 굴린다. */
.figure svg { max-width: 100%; height: auto; display: block; }
.row-label { fill: var(--text); font-size: 12px; font-family: inherit; }
.row-total { fill: var(--muted); font-size: 11px; font-family: inherit;
  font-variant-numeric: tabular-nums; }
.seg-label { fill: #ffffff; font-size: 11px; font-weight: 600; font-family: inherit;
  font-variant-numeric: tabular-nums; }
/* 값이 큰 칸은 밝은 모드에서 짙어지고 어두운 모드에서는 밝아진다.
   글자색도 모드에 따라 뒤집어야 칸 위에서 읽힌다. */
.cell-ink-strong { fill: #ffffff; font-size: 11px; font-family: inherit;
  font-variant-numeric: tabular-nums; }
.cell-ink-weak { fill: #1a1a19; font-size: 11px; font-family: inherit;
  font-variant-numeric: tabular-nums; }
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) .cell-ink-strong { fill: #14161a; }
  :root:not([data-theme="light"]) .cell-ink-weak { fill: #e8eaee; }
}
:root[data-theme="dark"] .cell-ink-strong { fill: #14161a; }
:root[data-theme="dark"] .cell-ink-weak { fill: #e8eaee; }
.track { fill: var(--low-bg); }
.track-line { stroke: var(--border); stroke-width: 1; }
.tick { stroke: var(--text); stroke-width: 2; }
.dumbbell { stroke: var(--series-1); stroke-width: 2; }
.dot-before { fill: var(--surface); stroke: var(--series-1); stroke-width: 2; }
.dot-after { fill: var(--series-1); stroke: var(--surface); stroke-width: 2; }
.legend { display: flex; flex-wrap: wrap; gap: 8px 14px; margin-bottom: 10px;
  font-size: 10pt; color: var(--muted); }
.legend-item { display: inline-flex; align-items: center; gap: 6px; }
.legend-swatch { width: 11px; height: 11px; border-radius: 3px; display: inline-block; }
@media print {
  /* 인쇄면에 맞춰 줄어들어야 한다. 최소 너비를 두면 오른쪽이 잘린다. */
  .figure { overflow: visible; break-inside: avoid; }
  .figure svg { min-width: 0; }
}
"""
