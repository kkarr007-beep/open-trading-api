"""B. 조사결과 편향.

특정 법인의 조사결과가 한쪽으로 쏠려 있는지 본다. 비교 전에 담보구분과
사고원인을 맞춰 두지 않으면 사건 난이도 차이를 편향으로 잘못 읽는다.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

from .. import config, stats
from . import base

NAME = "B_결과편향"
TITLE = "법인별 조사결과 편향"

SCALE = (4.0, 200.0)  # 표준화 잔차 제곱합. 잔차 2 하나면 4.



def run(data: dict[str, pd.DataFrame]) -> base.MetricResult:
    cases = data["cases"]
    outcome = "조사결과코드" if "조사결과코드" in cases.columns else "조사결과"

    if not base.has_columns(cases, "법인_id", outcome):
        return base.empty_result(NAME, TITLE, "조사결과 컬럼이 없어 건너뜀")

    work = cases.dropna(subset=["법인_id", outcome]).copy()
    if work.empty:
        return base.empty_result(NAME, TITLE, "조사결과가 채워진 건이 없음")

    work, strata = base.stratified_expectation(
        work, config.STRATA_OUTCOME, config.MIN_STRATUM_SIZE
    )
    if work.empty:
        return base.empty_result(NAME, TITLE, "층화 후 남은 표본이 없음")

    table = _expected_vs_observed(work, strata, "법인_id", outcome)
    if table.empty:
        return base.empty_result(NAME, TITLE, "비교 가능한 층이 없음")

    table = _attach_labels(table, work, outcome)
    result = base.MetricResult(name=NAME, title=TITLE)
    result.detail = table[
        ["법인_명", "결과명", "실제건수", "기대건수", "실제비율", "기대비율", "잔차", "법인총건수"]
    ]
    result.add_score("법인", _vendor_scores(table), scale=SCALE)
    result.note = (
        f"층화 기준: {', '.join(strata) if strata else '없음'} · "
        f"법인 {table['법인_id'].nunique()}곳 비교"
    )
    return result


def _expected_vs_observed(
    work: pd.DataFrame, strata: tuple[str, ...], actor: str, outcome: str
) -> pd.DataFrame:
    """층별 기대 분포를 쌓아 법인별 기대건수를 만든다."""
    keys = list(strata)

    if keys:
        observed = work.groupby(keys + [actor, outcome], dropna=False, sort=False).size()
        observed = observed.reset_index(name="관측")
        by_outcome = work.groupby(keys + [outcome], dropna=False, sort=False).size()
        by_outcome = by_outcome.reset_index(name="층결과계")
        by_actor = work.groupby(keys + [actor], dropna=False, sort=False).size()
        by_actor = by_actor.reset_index(name="층법인계")
        by_stratum = work.groupby(keys, dropna=False, sort=False).size()
        by_stratum = by_stratum.reset_index(name="층계")
        grid = by_actor.merge(by_outcome, on=keys, how="inner").merge(
            by_stratum, on=keys, how="left"
        )
        grid = grid.merge(observed, on=keys + [actor, outcome], how="left")
    else:
        observed = work.groupby([actor, outcome], dropna=False, sort=False).size()
        observed = observed.reset_index(name="관측")
        by_outcome = work.groupby(outcome, dropna=False, sort=False).size()
        by_outcome = by_outcome.reset_index(name="층결과계")
        by_actor = work.groupby(actor, dropna=False, sort=False).size()
        by_actor = by_actor.reset_index(name="층법인계")
        grid = by_actor.merge(by_outcome, how="cross")
        grid["층계"] = len(work)
        grid = grid.merge(observed, on=[actor, outcome], how="left")

    grid["관측"] = grid["관측"].fillna(0.0)

    # 자기 건을 뺀 나머지 법인의 결과 분포가 기대치다.
    others_outcome = grid["층결과계"] - grid["관측"]
    others_total = grid["층계"] - grid["층법인계"]
    rate = np.divide(
        others_outcome, others_total,
        out=np.full(len(grid), np.nan), where=others_total > 0,
    )
    grid["기대"] = grid["층법인계"] * rate
    grid = grid.dropna(subset=["기대"])
    if grid.empty:
        return pd.DataFrame()

    rolled = grid.groupby([actor, outcome], sort=False).agg(
        실제건수=("관측", "sum"), 기대건수=("기대", "sum")
    ).reset_index()

    totals = rolled.groupby(actor, sort=False)["실제건수"].transform("sum")
    rolled = rolled[totals >= config.MIN_CASES_VENDOR].copy()
    if rolled.empty:
        return pd.DataFrame()

    rolled["법인총건수"] = rolled.groupby(actor, sort=False)["실제건수"].transform("sum")
    rolled["실제비율"] = (rolled["실제건수"] / rolled["법인총건수"] * 100).round(1)
    rolled["기대비율"] = (rolled["기대건수"] / rolled["법인총건수"] * 100).round(1)
    rolled["잔차"] = stats.standardized_residual(
        rolled["실제건수"].to_numpy(), rolled["기대건수"].to_numpy()
    ).round(2)
    rolled["기대건수"] = rolled["기대건수"].round(1)
    return rolled.sort_values("잔차", ascending=False).reset_index(drop=True)


def _attach_labels(table: pd.DataFrame, work: pd.DataFrame, outcome: str) -> pd.DataFrame:
    vendor = (
        work.drop_duplicates(subset=["법인_id"]).set_index("법인_id")["법인_명"]
    )
    table["법인_명"] = table["법인_id"].map(vendor).fillna(table["법인_id"])

    if outcome == "조사결과코드" and "조사결과" in work.columns:
        labels = (
            work.dropna(subset=["조사결과코드"])
            .drop_duplicates(subset=["조사결과코드"])
            .set_index("조사결과코드")["조사결과"]
        )
        table["결과명"] = table[outcome].map(labels).fillna(table[outcome])
    else:
        table["결과명"] = table[outcome]
    return table


def _vendor_scores(table: pd.DataFrame) -> pd.DataFrame:
    positive = table[table["잔차"] > 0]
    if positive.empty:
        return pd.DataFrame()

    strongest = base.strongest_rows(positive, "법인_id", "잔차")
    chi2 = table.assign(제곱=table["잔차"] ** 2).groupby("법인_id", sort=False)["제곱"].sum()

    strongest = strongest.assign(카이제곱=strongest["법인_id"].map(chi2))
    return pd.DataFrame(
        {
            "id": strongest["법인_id"],
            "명": strongest["법인_명"],
            "점수": strongest["카이제곱"],
            "사유": strongest.apply(
                lambda row: (
                    f"{row['결과명']} 비율 {row['실제비율']:.0f}% "
                    f"(유사건 기대 {row['기대비율']:.0f}%)"
                ),
                axis=1,
            ),
        }
    )
