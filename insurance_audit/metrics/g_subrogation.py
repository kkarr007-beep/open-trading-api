"""G. 구상 포기.

구상 가능한 사고인데 특정 법인을 거친 건에서 유독 구상이 잡히지 않는지 본다.
구상 여부는 사고원인에 크게 좌우되므로 원인을 맞춘 뒤 비교한다.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

from .. import config, stats
from . import base

NAME = "G_구상포기"
TITLE = "법인별 구상 포기"

SCALE = (2.0, 15.0)  # 표준화 잔차 절댓값


# 구상이 성립한 것으로 보는 값. 코드 체계가 회사마다 달라 흔한 표기를 모두 받는다.
POSITIVE = {"Y", "1", "O", "여", "유", "구상", "T", "TRUE", "YES"}


def run(data: dict[str, pd.DataFrame]) -> base.MetricResult:
    cases = data["cases"]
    column = "구상여부코드" if "구상여부코드" in cases.columns else "구상여부"

    if not base.has_columns(cases, "법인_id", column):
        return base.empty_result(NAME, TITLE, "구상여부 컬럼이 없어 건너뜀")

    work = cases.dropna(subset=["법인_id", column]).copy()
    work["_구상"] = work[column].astype(str).str.strip().str.upper().isin(POSITIVE).astype(float)
    if work.empty or work["_구상"].sum() == 0:
        return base.empty_result(NAME, TITLE, "구상 성립 건이 없어 비교 불가")

    work, strata = base.stratified_expectation(
        work, config.STRATA_SUBROGATION, config.MIN_STRATUM_SIZE
    )
    if work.empty:
        return base.empty_result(NAME, TITLE, "층화 후 남은 표본이 없음")

    table = _vendor_table(work, strata)
    if table.empty:
        return base.empty_result(NAME, TITLE, "비교 가능한 법인이 없음")

    result = base.MetricResult(name=NAME, title=TITLE)
    result.detail = table[
        ["법인_명", "총건수", "구상건수", "구상률", "기대구상률", "차이", "포기강도"]
    ]
    result.add_score("법인", _scores(table), scale=SCALE)
    result.note = f"층화 기준: {', '.join(strata) if strata else '없음'}"
    return result


def _vendor_table(work: pd.DataFrame, strata: tuple[str, ...]) -> pd.DataFrame:
    keys = list(strata)
    group_keys = keys + ["법인_id"] if keys else ["법인_id"]

    cell = work.groupby(group_keys, dropna=False, sort=False).agg(
        건수=("_구상", "size"), 구상=("_구상", "sum")
    ).reset_index()

    if keys:
        stratum = work.groupby(keys, dropna=False, sort=False).agg(
            층건수=("_구상", "size"), 층구상=("_구상", "sum")
        ).reset_index()
        cell = cell.merge(stratum, on=keys, how="left")
    else:
        cell["층건수"] = len(work)
        cell["층구상"] = work["_구상"].sum()

    # 자기 건을 뺀 나머지 법인의 구상률이 기대치다.
    others_sub = cell["층구상"] - cell["구상"]
    others_total = cell["층건수"] - cell["건수"]
    rate = np.divide(
        others_sub, others_total,
        out=np.full(len(cell), np.nan), where=others_total > 0,
    )
    cell["기대구상"] = cell["건수"] * rate
    cell = cell.dropna(subset=["기대구상"])
    if cell.empty:
        return pd.DataFrame()

    rolled = cell.groupby("법인_id", sort=False).agg(
        총건수=("건수", "sum"), 구상건수=("구상", "sum"), 기대구상건수=("기대구상", "sum")
    ).reset_index()
    rolled = rolled[rolled["총건수"] >= config.MIN_CASES_VENDOR].copy()
    if rolled.empty:
        return pd.DataFrame()

    rolled["구상률"] = (rolled["구상건수"] / rolled["총건수"] * 100).round(1)
    rolled["기대구상률"] = (rolled["기대구상건수"] / rolled["총건수"] * 100).round(1)
    rolled["차이"] = (rolled["구상률"] - rolled["기대구상률"]).round(1)

    # 기대보다 적게 구상한 쪽만 신호로 본다.
    residual = stats.standardized_residual(
        rolled["구상건수"].to_numpy(), rolled["기대구상건수"].to_numpy()
    )
    rolled["포기강도"] = np.where(residual < 0, -residual, 0.0).round(2)
    rolled["구상건수"] = rolled["구상건수"].astype(int)

    vendor = (
        work.drop_duplicates(subset=["법인_id"]).set_index("법인_id")["법인_명"]
    )
    rolled["법인_명"] = rolled["법인_id"].map(vendor).fillna(rolled["법인_id"])
    return rolled.sort_values("포기강도", ascending=False).reset_index(drop=True)


def _scores(table: pd.DataFrame) -> pd.DataFrame:
    active = table[table["포기강도"] > 0]
    if active.empty:
        return pd.DataFrame()
    return pd.DataFrame(
        {
            "id": active["법인_id"],
            "명": active["법인_명"],
            "점수": active["포기강도"],
            "사유": active.apply(
                lambda row: (
                    f"구상률 {row['구상률']:.0f}% "
                    f"(유사 사고원인 기대 {row['기대구상률']:.0f}%)"
                ),
                axis=1,
            ),
        }
    )
