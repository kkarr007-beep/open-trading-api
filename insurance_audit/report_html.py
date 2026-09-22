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

ENTITY_TITLES = config.ROLE_TITLES


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
        _guide_section(),
        _person_section(rankings, results, views, data["cases"]),
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


def _guide_section() -> str:
    """결과를 어떻게 읽는지 먼저 알려 준다.

    점수와 등급만 나열하면 무엇을 본 수치인지 알 수 없어 해석이 멈춘다.
    """
    bands = "".join(
        f'<tr><td><span class="badge band-{_band_key(band)}">{html.escape(band)}</span></td>'
        f"<td>{html.escape(text)}</td></tr>"
        for band, text in config.BAND_HELP.items()
    )
    metrics = "".join(
        f"<tr><td class='mcode'>{html.escape(key.split('_')[0])}</td>"
        f"<td class='name'>{html.escape(config.METRIC_LABELS.get(key, key))}</td>"
        f"<td class='num'>{weight:.0%}</td>"
        f"<td>{html.escape(config.METRIC_HELP.get(key, ''))}</td></tr>"
        for key, weight in sorted(
            config.METRIC_WEIGHTS.items(), key=lambda item: item[1], reverse=True
        )
    )
    return f"""<section id="guide">
<h2>읽는 법</h2>
<p class="note">이 보고서는 <strong>누가 비위를 저질렀는지 가려내지 않습니다.</strong>
같은 조건의 동료와 견줘 설명되지 않는 쏠림이 있는 대상을 추려, 소명을 받을 순서를 정하는 자료입니다.</p>

<h3>혐의도</h3>
<p>0~100 점입니다. 아래 10개 지표를 각각 채점한 뒤,
<strong>여러 지표에 걸쳐 고르게 이상한 정도</strong>와
<strong>한 지표에서 유난히 튄 정도</strong>를 6:4로 섞은 값입니다.
한 지표만 크게 튀어도 점수가 오르도록 설계했습니다.</p>
<p class="note">점수는 건수가 아니라 <strong>동료 대비 편차</strong>입니다.
배당을 많이 받았다는 이유만으로는 점수가 오르지 않습니다.
비교 대상은 같은 팀에서 같은 담보·사고원인을 처리한 동료이며, 기대치를 낼 때 본인 실적은 빼고 계산합니다.</p>

<h3>등급</h3>
<div class="table-wrap"><table>
<thead><tr><th>등급</th><th>뜻과 다음 할 일</th></tr></thead>
<tbody>{bands}</tbody>
</table></div>

<h3>지표 10종</h3>
<p class="note">가중치는 혐의도를 낼 때의 비중입니다. 결재 라인과 조사자 지정을 가장 무겁게 봅니다.</p>
<div class="table-wrap"><table>
<thead><tr><th>지표</th><th>이름</th><th>비중</th><th>무엇을 보는가</th></tr></thead>
<tbody>{metrics}</tbody>
</table></div>

<h3>보는 순서</h3>
<ol class="steps">
<li><strong>개요</strong>에서 전체 규모와 높음 등급이 몇 명인지 봅니다.</li>
<li><strong>혐의 순위</strong>에서 대상을 고릅니다.</li>
<li><strong>인물별 상세</strong>에서 그 사람의 지표별 점수와 위임 업체 내역을 확인합니다.</li>
<li>엑셀 보고서의 <strong>90_소명대상건</strong> 시트에서 건별 목록을 뽑아 소명을 요청합니다.</li>
</ol>
</section>"""


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

    unit = config.ORG_TITLES.get(str(level), str(level))
    figure = charts.stacked_shares(
        folded, key=level, label="법인_명", value="위임건수", order=order,
        caption=f"{unit} 단위 위임 구성. 건수 상위 {len(leaders)}개만 표시합니다."
                " 전체 내역은 엑셀 보고서에 있습니다.",
    )
    return f"<section><h2>{html.escape(unit)}별 위임 업체 구성</h2>{figure}</section>"


def _handler_section(
    rankings: dict[str, pd.DataFrame], results: list[MetricResult], views: dict[str, object]
) -> str:
    frame = views.get("담당자별업체_상위")
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
                caption="혐의도 상위 담당자의 위임 구성. 한 색이 막대를 덮으면 거래처가 한 곳에 묶여 있다는 뜻입니다."
                        " 전 직원 내역은 아래 '인물별 상세'와 엑셀 보고서에서 봅니다.",
            )
        )

    if not blocks:
        return ""
    return f"<section><h2>담당자별 위임 업체</h2>{''.join(blocks)}</section>"


def _metric_reasons(results: list[MetricResult]) -> dict[tuple[str, str, str], str]:
    """(주체, 지표, id) -> 그 지표가 잡은 근거 문장."""
    lookup: dict[tuple[str, str, str], str] = {}
    for result in results:
        for entity, frame in result.scores.items():
            if frame.empty or "사유" not in frame.columns:
                continue
            for _, row in frame.iterrows():
                text = row.get("사유")
                if isinstance(text, str) and text and text != "특이사항 없음":
                    lookup[(entity, result.name, str(row["id"]))] = text
    return lookup


def _person_section(
    rankings: dict[str, pd.DataFrame],
    results: list[MetricResult],
    views: dict[str, object],
    cases: pd.DataFrame,
) -> str:
    """담당자·차상위자를 골라 그 사람의 모든 혐의점을 보는 절.

    순위표는 한 줄에 요약만 담겨 개인을 판단하기 어렵다. 소명을 요청하려면
    그 사람이 어느 지표에서 왜 걸렸는지, 어느 업체에 얼마나 보냈는지를
    한자리에서 봐야 한다.
    """
    reasons = _metric_reasons(results)
    mixes = {
        "담당자": views.get("담당자별업체"),
        "결재자": views.get("차상위자별업체"),
    }
    org = _person_org(cases)

    options: list[str] = []
    cards: list[str] = []
    for entity in ("담당자", "결재자"):
        frame = rankings.get(entity)
        if frame is None or frame.empty:
            continue

        group: list[str] = []
        mix = mixes.get(entity)
        for _, row in frame.iterrows():
            pid = f"{entity}:{row['id']}"
            band = str(row.get("등급", ""))
            group.append(
                f'<option value="{html.escape(pid)}">'
                f'{html.escape(str(row["명"]))} · {float(row["혐의도"]):.0f}점 · {html.escape(band)}'
                f"</option>"
            )
            cards.append(_person_card(entity, row, reasons, mix, org, pid))

        if group:
            label = config.ROLE_TITLES.get(entity, entity)
            options.append(
                f'<optgroup label="{html.escape(label)} ({len(group):,}명)">'
                + "".join(group)
                + "</optgroup>"
            )

    if not cards:
        return ""

    return f"""<section id="people">
<h2>인물별 상세</h2>
<p class="note">이름을 고르면 그 사람의 지표별 점수와 위임 업체 내역이 모두 나옵니다.
혐의도가 높은 순으로 정렬돼 있습니다.</p>
<div class="picker no-print">
  <label for="person-pick">대상 선택</label>
  <select id="person-pick">{"".join(options)}</select>
</div>
{"".join(cards)}
</section>"""


def _person_org(cases: pd.DataFrame) -> dict[str, str]:
    """인물별 소속 표기. 부서·팀을 함께 적어야 누구인지 특정된다."""
    labels: dict[str, str] = {}
    for role in ("담당자", "결재자"):
        key = f"{role}_id"
        if key not in cases.columns:
            continue
        columns = [c for c in ("소속2", "소속3") if c in cases.columns]
        if not columns:
            continue
        grouped = cases.dropna(subset=[key]).groupby(key, sort=False)
        for pid, block in grouped:
            parts = []
            for column in columns:
                values = block[column].dropna().unique()
                if len(values):
                    unit = config.ORG_TITLES.get(column, column)
                    shown = str(values[0]) + ("…" if len(values) > 1 else "")
                    parts.append(f"{unit} {shown}")
            if parts:
                labels[f"{role}:{pid}"] = " · ".join(parts)
    return labels


def _person_card(
    entity: str,
    row: pd.Series,
    reasons: dict[tuple[str, str, str], str],
    mix: object,
    org: dict[str, str],
    pid: str,
) -> str:
    band = str(row.get("등급", ""))
    name = str(row["명"])
    volume = f"{int(row['담당건수']):,}건" if "담당건수" in row else "-"
    where = org.get(pid, "")

    rows = []
    for metric in config.ENTITY_METRICS.get(entity, ()):
        if metric not in row.index:
            continue
        score = float(row[metric]) if pd.notna(row[metric]) else 0.0
        note = reasons.get((entity, metric, str(row["id"])), "")
        if score <= 0 and not note:
            note = "해당 없음"
        rows.append(
            f"<tr><td class='mcode'>{html.escape(metric.split('_')[0])}</td>"
            f"<td class='name'>{html.escape(config.METRIC_LABELS.get(metric, metric))}</td>"
            f"<td class='score'>{_bar(score)}</td>"
            f"<td class='reason'>{html.escape(note)}</td></tr>"
        )
    metric_table = (
        f"<div class='table-wrap'><table>"
        f"<thead><tr><th>지표</th><th>보는 것</th><th>점수</th><th>근거</th></tr></thead>"
        f"<tbody>{''.join(rows)}</tbody></table></div>"
        if rows
        else "<p class='note'>채점된 지표가 없습니다.</p>"
    )

    return f"""<article class="person-card" data-pid="{html.escape(pid)}" hidden>
<div class="person-head">
  <div>
    <p class="person-name">{html.escape(name)}
      <span class="badge band-{_band_key(band)}">{html.escape(band)}</span></p>
    <p class="note">{html.escape(config.ROLE_TITLES.get(entity, entity))}
      {(" · " + html.escape(where)) if where else ""} · 담당 {html.escape(volume)}</p>
  </div>
  <div class="person-score">
    <span class="person-score-value">{float(row["혐의도"]):.0f}</span>
    <span class="note">혐의도</span>
  </div>
</div>
<p class="person-reason">{html.escape(str(row.get("주요사유", "")) or "특이 신호 없음")}</p>
<h3>지표별 점수</h3>
{metric_table}
{_vendor_table(mix, entity, str(row["id"]))}
</article>"""


def _vendor_table(mix: object, entity: str, pid: str) -> str:
    """그 사람이 어느 업체에 얼마나 보냈는지. 업체를 접지 않고 전부 낸다."""
    if not isinstance(mix, pd.DataFrame) or mix.empty:
        return ""
    key = f"{entity}_id"
    if key not in mix.columns:
        return ""

    block = mix[mix[key].astype(str) == pid]
    if block.empty:
        return ""

    rows = "".join(
        f"<tr><td class='num'>{int(row['업체순위'])}</td>"
        f"<td class='name'>{html.escape(str(row['법인_명']))}</td>"
        f"<td class='num'>{int(row['위임건수']):,}건</td>"
        f"<td class='score'>{_share_bar(float(row['구성비']))}</td></tr>"
        for _, row in block.iterrows()
    )
    total = int(block["총건수"].iloc[0])
    return f"""<h3>위임 업체 전체 내역</h3>
<p class="note">전체 {total:,}건 · 업체 {len(block):,}곳. 한 곳에 절반을 넘기면 사유를 확인해야 합니다.</p>
<div class="table-wrap"><table>
<thead><tr><th>순위</th><th>손사법인</th><th>위임건수</th><th>구성비</th></tr></thead>
<tbody>{rows}</tbody>
</table></div>"""


def _share_bar(share: float) -> str:
    width = max(0.0, min(100.0, share))
    return (
        f'<span class="bar"><span class="bar-fill" style="width:{width:.0f}%"></span></span>'
        f'<span class="bar-value">{share:.1f}%</span>'
    )


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
  <button type="button" class="print-btn no-print" onclick="window.print()">인쇄 / PDF 저장</button>
</header>
{body}
<footer>
  <p>혐의도는 동료 집단 대비 통계적 이상 신호를 점수화한 값입니다.
  그 자체가 비위 사실을 뜻하지 않으며, 소명 절차를 거쳐 확인해야 합니다.</p>
</footer>
</main>
<script>{_SCRIPT}</script>
</body>
</html>"""


# 인물 카드 전환. 외부 자원을 부르지 않도록 파일 안에 둔다.
_SCRIPT = """
(function () {
  var pick = document.getElementById('person-pick');
  if (!pick) return;
  var cards = document.querySelectorAll('.person-card');
  function show(id) {
    for (var i = 0; i < cards.length; i++) {
      cards[i].hidden = cards[i].dataset.pid !== id;
    }
  }
  pick.addEventListener('change', function () { show(pick.value); });
  if (pick.value) show(pick.value);
})();
"""


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
/* 본문·표·주석은 모두 10pt 한 가지로 맞춘다. 제목만 위계를 위해 키운다. */
* { box-sizing: border-box; }
body {
  margin: 0; background: var(--bg); color: var(--text);
  font-family: -apple-system, "Segoe UI", "Malgun Gothic", "Apple SD Gothic Neo", sans-serif;
  font-size: 10pt; line-height: 1.6;
  word-break: keep-all; overflow-wrap: anywhere;
}
main { max-width: 1120px; margin: 0 auto; padding: 32px 16px 56px; }
.page-head { border-bottom: 2px solid var(--text); padding-bottom: 16px; margin-bottom: 28px; }
.eyebrow { margin: 0 0 6px; color: var(--muted); letter-spacing: .04em; }
h1 { margin: 0 0 8px; font-size: 16pt; letter-spacing: -.01em; }
h2 { font-size: 12pt; margin: 0 0 12px; }
h3 { font-size: 10pt; margin: 20px 0 8px; color: var(--muted); letter-spacing: .02em; }
p { margin: 0 0 10px; }
.meta, .note { color: var(--muted); margin: 0 0 12px; }
section { margin-bottom: 32px; }
.steps { margin: 0; padding-left: 20px; color: var(--muted); }
.steps li { margin-bottom: 4px; }
.print-btn {
  margin-top: 10px; padding: 6px 14px; font: inherit; cursor: pointer;
  background: var(--surface); color: var(--text);
  border: 1px solid var(--border); border-radius: 6px;
}
.tiles { display: grid; gap: 10px; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); }
.tile { background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 12px; }
.tile-label, .tile-unit { margin: 0; color: var(--muted); }
.tile-value { margin: 4px 0 2px; font-size: 14pt; font-weight: 650; font-variant-numeric: tabular-nums; }
.table-wrap { overflow-x: auto; border: 1px solid var(--border); border-radius: 8px; background: var(--surface); }
table { width: 100%; border-collapse: collapse; }
th, td { padding: 7px 10px; text-align: left; border-bottom: 1px solid var(--border); vertical-align: top; }
th { color: var(--muted); font-weight: 600; white-space: nowrap; background: var(--bg); }
tbody tr:last-child td { border-bottom: none; }
.rank, .mcode { color: var(--muted); font-variant-numeric: tabular-nums; width: 40px; }
.mcode { font-weight: 700; color: var(--accent); }
.name { font-weight: 600; }
.num { font-variant-numeric: tabular-nums; white-space: nowrap; color: var(--muted); }
.score { white-space: nowrap; width: 132px; }
.reason { color: var(--muted); }
.bar { display: inline-block; width: 70px; height: 6px; border-radius: 4px; background: var(--low-bg); vertical-align: middle; overflow: hidden; }
.bar-fill { display: block; height: 100%; background: var(--accent); }
.bar-value { margin-left: 8px; font-variant-numeric: tabular-nums; font-weight: 600; }
.badge { display: inline-block; padding: 1px 8px; border-radius: 999px; font-weight: 600; white-space: nowrap; }
.band-high { background: var(--high-bg); color: var(--high); }
.band-mid { background: var(--mid-bg); color: var(--mid); }
.band-low, .band-none { background: var(--low-bg); color: var(--low); }
.figure { background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 10px; overflow-x: auto; }
/* min-width 를 두면 좁은 화면에서 본문이 통째로 밀려 나간다. 감싼 상자만 굴린다. */
svg { width: 100%; height: auto; display: block; }
.edge { stroke: var(--accent); stroke-width: 1.5; }
.node { fill: var(--accent); }
.node-text { fill: var(--text); font-size: 11px; font-family: inherit; }
.axis-label { fill: var(--muted); font-size: 10px; font-weight: 600; font-family: inherit; letter-spacing: .04em; }
.mobility-card, .regime-card { margin-bottom: 18px; }
.mobility-head { margin: 0 0 8px; color: var(--text); }
.picker { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 16px; }
.picker label { color: var(--muted); }
.picker select {
  font: inherit; padding: 6px 10px; max-width: 100%; min-width: 260px;
  background: var(--surface); color: var(--text);
  border: 1px solid var(--border); border-radius: 6px;
}
.person-card { border: 1px solid var(--border); border-radius: 8px; background: var(--surface); padding: 16px; }
.person-head { display: flex; gap: 16px; justify-content: space-between; align-items: flex-start; flex-wrap: wrap; }
.person-name { margin: 0 0 4px; font-size: 12pt; font-weight: 650; }
.person-name .badge { margin-left: 6px; vertical-align: middle; }
.person-head .note { margin: 0; }
.person-score { text-align: right; }
.person-score-value { display: block; font-size: 20pt; font-weight: 700; font-variant-numeric: tabular-nums; line-height: 1.1; }
.person-reason { margin: 12px 0 0; padding: 10px 12px; border-radius: 6px; background: var(--bg); color: var(--text); }
.person-card h3:first-of-type { margin-top: 16px; }
footer { border-top: 1px solid var(--border); padding-top: 14px; color: var(--muted); }
footer p { margin: 0; }
@media (max-width: 640px) {
  main { padding: 20px 12px 40px; }
  h1 { font-size: 14pt; }
  .picker select { min-width: 0; width: 100%; }
}

/* 인쇄 · PDF 저장. 보고서로 그대로 낼 수 있어야 한다. */
@media print {
  @page { size: A4; margin: 14mm; }
  :root {
    --bg: #ffffff; --surface: #ffffff; --border: #b9bec7;
    --text: #000000; --muted: #3f4650; --accent: #1f3d8a;
    --high-bg: #f4dcd0; --mid-bg: #f6ecd0; --low-bg: #e8eaee;
  }
  body { background: #fff; color: #000; font-size: 10pt; }
  main { max-width: none; padding: 0; }
  .no-print { display: none !important; }
  .table-wrap, .figure { overflow: visible; }
  section { margin-bottom: 18px; break-inside: avoid; }
  h2 { break-after: avoid; }
  tr, .person-card, .mobility-card, .regime-card { break-inside: avoid; }
  thead { display: table-header-group; }
  .badge, .bar, .bar-fill { -webkit-print-color-adjust: exact; print-color-adjust: exact; }
  a { text-decoration: none; color: inherit; }
}
"""
