"""J. 조직 위임처 전환.

한 지점의 주력 위임 업체가 해를 넘기며 갈아치워지는지 본다. 그리고 그 시점에
결재자나 담당자가 바뀌었는지를 붙인다.

담당자 한 사람의 편중은 개인 문제로 끝나지만, 팀장이 교체되면서 지점 전체의
위임처가 통째로 옮겨가는 것은 성격이 다르다. 전환 자체는 정상적인 업체 평가의
결과일 수도 있으므로, 전환과 인사 이동이 같은 시점에 겹치는지를 함께 본다.
"""

from __future__ import annotations

import pandas as pd

from .. import config, profile, stats
from . import base

NAME = "J_조직전환"
TITLE = "조직 단위 위임처 전환"

SCALE = (2.5, 12.0)  # 점유율 변동의 두 비율 검정 z


def run(data: dict[str, pd.DataFrame]) -> base.MetricResult:
    cases = data["cases"]

    if not base.has_columns(cases, "법인_id", "연도"):
        return base.empty_result(NAME, TITLE, "연도 또는 법인 컬럼이 없어 건너뜀")

    level = data.get("옵션", {}).get("조직계층") or profile.org_level_for(cases)
    if level not in cases.columns:
        return base.empty_result(NAME, TITLE, "소속 계층 컬럼이 없어 건너뜀")

    work = cases.dropna(subset=[level, "법인_id", "연도"]).copy()
    if work["연도"].nunique() < 2:
        return base.empty_result(NAME, TITLE, "연도가 하나뿐이라 전환을 볼 수 없음")

    shifts = _shift_table(work, level)
    if shifts.empty:
        return base.empty_result(NAME, TITLE, "기준을 넘는 위임처 전환이 없음")

    shifts = _attach_staff_changes(shifts, work, level)

    result = base.MetricResult(name=NAME, title=TITLE)
    result.detail = shifts[
        ["조직", "전환", "이탈업체", "이탈업체_이전", "이탈업체_이후",
         "신규주력업체", "신규업체_이전", "신규업체_이후", "변동폭", "전환강도",
         "결재자교체", "신규결재자", "담당자교체율"]
    ]
    result.add_score("소속", _org_scores(shifts), scale=SCALE)
    result.add_score("법인", _vendor_scores(shifts), scale=SCALE)
    result.note = f"조직 계층: {level} · 전환 {len(shifts):,}건 탐지"
    return result


def _shift_table(work: pd.DataFrame, level: str) -> pd.DataFrame:
    """조직별 연도 간 업체 점유율 변동을 찾는다."""
    counts = (
        work.groupby([level, "연도", "법인_id", "법인_명"], dropna=False, sort=False)
        .size()
        .reset_index(name="건수")
    )
    totals = (
        work.groupby([level, "연도"], dropna=False, sort=False)
        .size()
        .reset_index(name="연도계")
    )
    totals = totals[totals["연도계"] >= config.MIN_CASES_ORG_YEAR]
    if totals.empty:
        return pd.DataFrame()

    counts = counts.merge(totals, on=[level, "연도"], how="inner")

    # 전년에 없던 업체는 건수 0으로 채워야 신규 진입을 잡을 수 있다.
    grid = (
        counts[[level, "연도", "연도계"]]
        .drop_duplicates()
        .merge(counts[[level, "법인_id", "법인_명"]].drop_duplicates(), on=level, how="left")
    )
    counts = grid.merge(
        counts[[level, "연도", "법인_id", "건수"]], on=[level, "연도", "법인_id"], how="left"
    )
    counts["건수"] = counts["건수"].fillna(0.0)
    counts["점유율"] = counts["건수"] / counts["연도계"] * 100

    counts = counts.sort_values([level, "법인_id", "연도"])
    grouped = counts.groupby([level, "법인_id"], sort=False)
    counts["이전건수"] = grouped["건수"].shift()
    counts["이전계"] = grouped["연도계"].shift()
    counts["이전점유율"] = grouped["점유율"].shift()
    counts["이전연도"] = grouped["연도"].shift()

    step = counts.dropna(subset=["이전건수"]).copy()
    # 연도가 건너뛴 구간은 전환 시점을 특정할 수 없다.
    step = step[step["연도"] - step["이전연도"] == 1]
    if step.empty:
        return pd.DataFrame()

    step["변동폭"] = (step["점유율"] - step["이전점유율"]).round(1)
    step["z"] = stats.two_proportion_z(
        step["건수"].to_numpy(), step["연도계"].to_numpy(),
        step["이전건수"].to_numpy(), step["이전계"].to_numpy(),
    )

    return _pair_rise_and_fall(step, level)


def _pair_rise_and_fall(step: pd.DataFrame, level: str) -> pd.DataFrame:
    """같은 전환 시점에서 가장 많이 오른 업체와 가장 많이 빠진 업체를 짝지운다."""
    rows = []
    for (org, year), group in step.groupby([level, "연도"], sort=False):
        rise = group.loc[group["변동폭"].idxmax()]
        fall = group.loc[group["변동폭"].idxmin()]
        if rise["변동폭"] < config.MIN_SHARE_SHIFT:
            continue
        rows.append(
            {
                "조직": org,
                "전환": f"{int(rise['이전연도'])} → {int(year)}",
                "전환연도": int(year),
                "이전연도": int(rise["이전연도"]),
                "_조직키": org,
                "이탈업체": fall["법인_명"],
                "이탈업체_id": fall["법인_id"],
                "이탈업체_이전": round(fall["이전점유율"], 1),
                "이탈업체_이후": round(fall["점유율"], 1),
                "신규주력업체": rise["법인_명"],
                "신규업체_id": rise["법인_id"],
                "신규업체_이전": round(rise["이전점유율"], 1),
                "신규업체_이후": round(rise["점유율"], 1),
                "변동폭": round(rise["변동폭"], 1),
                "전환강도": round(abs(float(rise["z"])), 2),
            }
        )

    if not rows:
        return pd.DataFrame()
    return pd.DataFrame(rows).sort_values("전환강도", ascending=False).reset_index(drop=True)


def _attach_staff_changes(
    shifts: pd.DataFrame, work: pd.DataFrame, level: str
) -> pd.DataFrame:
    """전환 시점에 결재자·담당자가 바뀌었는지 붙인다."""
    approvers = _roster(work, level, "결재자_명")
    handlers = _roster(work, level, "담당자_id")

    changed, arrived, turnover = [], [], []
    for _, row in shifts.iterrows():
        before_key = (row["_조직키"], row["이전연도"])
        after_key = (row["_조직키"], row["전환연도"])

        before_a = approvers.get(before_key, set())
        after_a = approvers.get(after_key, set())
        new_a = after_a - before_a
        changed.append("예" if new_a or (before_a - after_a) else "아니오")
        arrived.append(", ".join(sorted(new_a)[:4]) if new_a else "")

        before_h = handlers.get(before_key, set())
        after_h = handlers.get(after_key, set())
        union = before_h | after_h
        rate = len(after_h - before_h) / len(union) * 100 if union else 0.0
        turnover.append(round(rate, 1))

    shifts["결재자교체"] = changed
    shifts["신규결재자"] = arrived
    shifts["담당자교체율"] = turnover
    return shifts


def _roster(work: pd.DataFrame, level: str, column: str) -> dict[tuple, set]:
    if column not in work.columns:
        return {}
    grouped = work.dropna(subset=[column]).groupby([level, "연도"], sort=False)[column]
    return {key: set(values) for key, values in grouped.unique().items()}


def _org_scores(shifts: pd.DataFrame) -> pd.DataFrame:
    strongest = base.strongest_rows(shifts, "조직", "전환강도")
    return pd.DataFrame(
        {
            "id": strongest["조직"],
            "명": strongest["조직"],
            "점수": strongest["전환강도"],
            "사유": strongest.apply(_reason, axis=1),
        }
    )


def _vendor_scores(shifts: pd.DataFrame) -> pd.DataFrame:
    """전환으로 물량을 가져간 업체에 점수를 준다."""
    totals = shifts.groupby(["신규업체_id", "신규주력업체"], sort=False).agg(
        전환강도합=("전환강도", "sum"), 조직수=("조직", "nunique")
    ).reset_index()
    strongest = base.strongest_rows(shifts, "신규업체_id", "전환강도")

    merged = totals.merge(
        strongest[["신규업체_id", "조직", "전환", "변동폭", "결재자교체"]],
        on="신규업체_id", how="left",
    )
    return pd.DataFrame(
        {
            "id": merged["신규업체_id"],
            "명": merged["신규주력업체"],
            "점수": merged["전환강도합"],
            "사유": merged.apply(
                lambda row: (
                    f"{row['조직']} {row['전환']} 전환으로 점유 +{row['변동폭']:.0f}%p"
                    + (" · 같은 시점 결재자 교체" if row["결재자교체"] == "예" else "")
                    + (f" · 전환 조직 {int(row['조직수'])}곳" if row["조직수"] > 1 else "")
                ),
                axis=1,
            ),
        }
    )


def _reason(row: pd.Series) -> str:
    text = (
        f"{row['전환']} 주력 위임처가 {row['이탈업체']}"
        f"({row['이탈업체_이전']:.0f}%→{row['이탈업체_이후']:.0f}%)에서 "
        f"{row['신규주력업체']}"
        f"({row['신규업체_이전']:.0f}%→{row['신규업체_이후']:.0f}%)로 이동"
    )
    if row["결재자교체"] == "예" and row["신규결재자"]:
        text += f" · 같은 시점 결재자 교체({row['신규결재자']})"
    return text
