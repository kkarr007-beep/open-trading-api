"""H. 처리 속도 이상.

조사를 거쳤다면 일정 시간이 드는 것이 정상이다. 특정 법인 건만 유독 빨리
끝나면 조사가 형식에 그쳤을 수 있다. 사고 접수부터 지급까지 걸린 일수를
담보 종류를 맞춘 뒤 비교한다.
"""

from __future__ import annotations

import pandas as pd

from .. import config
from . import base

NAME = "H_처리속도"
TITLE = "법인별 처리 속도 이상"

SCALE = (2.0, 15.0)  # 순위합 Z 절댓값


CANDIDATES = ("사고후지급일", "상신후지급일", "사고후상신일")


def run(data: dict[str, pd.DataFrame]) -> base.MetricResult:
    cases = data["cases"]

    value = next(
        (column for column in CANDIDATES
         if column in cases.columns and cases[column].notna().sum() >= config.MIN_CASES_VENDOR),
        None,
    )
    if value is None or "법인_id" not in cases.columns:
        return base.empty_result(NAME, TITLE, "일자 컬럼이 부족해 건너뜀")

    work, strata = base.stratified_expectation(
        cases.dropna(subset=["법인_id", value]), config.STRATA_SPEED, config.MIN_STRATUM_SIZE
    )
    if work.empty:
        return base.empty_result(NAME, TITLE, "층화 후 남은 표본이 없음")

    table = base.stratified_rank_test(
        work, strata, "법인_id", value, config.MIN_CASES_VENDOR
    )
    if table.empty:
        return base.empty_result(NAME, TITLE, "비교 가능한 법인이 없음")

    medians = work.groupby("법인_id", sort=False)[value].median()
    table["중위일수"] = table["법인_id"].map(medians).round(1)
    table["전체중위일수"] = round(float(work[value].median()), 1)

    vendor = (
        work.drop_duplicates(subset=["법인_id"]).set_index("법인_id")["법인_명"]
    )
    table["법인_명"] = table["법인_id"].map(vendor).fillna(table["법인_id"])

    # 순위합 Z가 음수면 남들보다 빨리 끝났다는 뜻이다. 그쪽만 신호로 본다.
    table["단축강도"] = (-table["Z"]).clip(lower=0).round(2)
    table = table.sort_values("단축강도", ascending=False).reset_index(drop=True)

    result = base.MetricResult(name=NAME, title=TITLE)
    result.detail = table[
        ["법인_명", "표본수", "중위일수", "전체중위일수", "Z", "단축강도"]
    ].rename(columns={"Z": "속도편차Z"})
    result.add_score("법인", _scores(table, value), scale=SCALE)
    result.note = f"기준 일수: {value} · 층화: {', '.join(strata) if strata else '없음'}"
    return result


def _scores(table: pd.DataFrame, value: str) -> pd.DataFrame:
    active = table[table["단축강도"] > 0]
    if active.empty:
        return pd.DataFrame()
    return pd.DataFrame(
        {
            "id": active["법인_id"],
            "명": active["법인_명"],
            "점수": active["단축강도"],
            "사유": active.apply(
                lambda row: (
                    f"{value} 중위 {row['중위일수']:.0f}일 "
                    f"(전체 {row['전체중위일수']:.0f}일)"
                ),
                axis=1,
            ),
        }
    )
