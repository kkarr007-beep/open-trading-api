"""D. 결재 라인 이상.

담당자가 올린 건을 차상위 결재자가 그대로 통과시키는 구조를 본다. 두 가지를
같이 본다. 하나는 담당자-결재자 쌍이 특정 법인으로만 위임을 보내는지, 다른
하나는 그 건들이 상신 당일 결재되는 비율이 유독 높은지다. 둘이 겹치는 3자
조합이 결탁 혐의의 핵심이다.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

from .. import config, stats
from . import base

NAME = "D_결재라인"
TITLE = "결재 라인 편중 및 즉시결재"

SCALE_ACTOR = (3.0, 60.0)   # 조합별 종합강도 합
SCALE_TRIPLE = (3.0, 25.0)  # 3자 조합 하나의 종합강도


PAIR = "_담당자결재자"


def run(data: dict[str, pd.DataFrame]) -> base.MetricResult:
    cases = data["cases"]

    if not base.has_columns(cases, "담당자_id", "결재자_id", "법인_id"):
        return base.empty_result(NAME, TITLE, "결재자 컬럼이 없어 건너뜀")

    work = cases.dropna(subset=["담당자_id", "결재자_id", "법인_id"]).copy()
    if work.empty:
        return base.empty_result(NAME, TITLE, "결재 정보가 채워진 건이 없음")

    work[PAIR] = work["담당자_id"].astype(str) + "|" + work["결재자_id"].astype(str)

    triples = _triple_table(work)
    if triples.empty:
        return base.empty_result(NAME, TITLE, "표본 기준을 넘는 3자 조합이 없음")

    pair_focus = base.concentration_test(
        work, actor=PAIR, target="법인_id", scope="소속_id",
        min_pair=config.MIN_CASES_TRIPLE, min_actor=config.MIN_CASES_PAIR,
    )
    triples = _merge_concentration(triples, pair_focus)
    triples = _attach_labels(triples, work)
    triples["종합강도"] = (triples["편중강도"] + triples["즉시결재강도"]).round(2)
    triples = triples.sort_values("종합강도", ascending=False).reset_index(drop=True)

    result = base.MetricResult(name=NAME, title=TITLE)
    result.detail = triples[
        [
            "담당자_명", "결재자_명", "법인_명", "건수", "즉시결재건수", "즉시결재율",
            "기대즉시결재율", "결재소요일중위", "편중강도", "즉시결재강도", "종합강도",
        ]
    ]

    result.add_score("결재자", _by_actor(triples, "결재자_id", "결재자_명"), scale=SCALE_ACTOR)
    result.add_score("담당자", _by_actor(triples, "담당자_id", "담당자_명"), scale=SCALE_ACTOR)
    result.add_score("조합", _triple_scores(triples), scale=SCALE_TRIPLE)
    result.note = (
        f"3자 조합 {len(triples):,}건 · 전체 즉시결재율 "
        f"{work['_즉시결재'].mean() * 100:.1f}%"
        if "_즉시결재" in work.columns
        else f"3자 조합 {len(triples):,}건"
    )
    return result


def _triple_table(work: pd.DataFrame) -> pd.DataFrame:
    if "결재소요일" in work.columns:
        work["_즉시결재"] = (work["결재소요일"] <= config.SAME_DAY_APPROVAL_DAYS).astype(float)
        work.loc[work["결재소요일"].isna(), "_즉시결재"] = np.nan
    else:
        work["_즉시결재"] = np.nan

    keys = ["담당자_id", "결재자_id", "법인_id"]
    triples = work.groupby(keys, dropna=False, sort=False).agg(
        건수=("청구번호", "size"),
        즉시결재건수=("_즉시결재", "sum"),
        결재판정건수=("_즉시결재", "count"),
        결재소요일중위=("결재소요일", "median"),
    ).reset_index()

    triples = triples[triples["건수"] >= config.MIN_CASES_TRIPLE].copy()
    if triples.empty:
        return triples

    total_same = float(work["_즉시결재"].sum(skipna=True))
    total_judged = float(work["_즉시결재"].count())

    # 자기 건을 뺀 전사 즉시결재율이 기대치다.
    others_same = total_same - triples["즉시결재건수"].fillna(0)
    others_judged = total_judged - triples["결재판정건수"]
    expected = np.divide(
        others_same, others_judged,
        out=np.full(len(triples), np.nan), where=others_judged > 0,
    )
    triples["기대즉시결재율"] = expected

    triples["즉시결재강도"] = 0.0
    valid = triples["결재판정건수"] > 0
    if valid.any() and np.isfinite(expected[valid.to_numpy()]).any():
        p_values = stats.binom_tail_vector(
            triples.loc[valid, "즉시결재건수"].fillna(0).to_numpy(),
            triples.loc[valid, "결재판정건수"].to_numpy(),
            np.nan_to_num(triples.loc[valid, "기대즉시결재율"].to_numpy(), nan=0.5),
        )
        triples.loc[valid, "즉시결재강도"] = [stats.neg_log10(value) for value in p_values]

    triples["즉시결재율"] = np.divide(
        triples["즉시결재건수"].fillna(0).to_numpy(dtype=float),
        triples["결재판정건수"].to_numpy(dtype=float),
        out=np.zeros(len(triples)),
        where=triples["결재판정건수"].to_numpy() > 0,
    )
    # 기대보다 느리게 결재된 건은 신호가 아니다.
    triples.loc[triples["즉시결재율"] <= triples["기대즉시결재율"].fillna(1.0), "즉시결재강도"] = 0.0

    triples["즉시결재율"] = (triples["즉시결재율"] * 100).round(1)
    triples["기대즉시결재율"] = (triples["기대즉시결재율"] * 100).round(1)
    triples["즉시결재강도"] = triples["즉시결재강도"].round(2)
    triples["즉시결재건수"] = triples["즉시결재건수"].fillna(0).astype(int)
    return triples


def _merge_concentration(triples: pd.DataFrame, pair_focus: pd.DataFrame) -> pd.DataFrame:
    if pair_focus.empty:
        triples["편중강도"] = 0.0
        return triples

    keys = pair_focus[PAIR].str.split("|", n=1, expand=True)
    pair_focus = pair_focus.assign(담당자_id=keys[0], 결재자_id=keys[1])
    merged = triples.merge(
        pair_focus[["담당자_id", "결재자_id", "법인_id", "편중강도"]],
        on=["담당자_id", "결재자_id", "법인_id"],
        how="left",
    )
    merged["편중강도"] = merged["편중강도"].fillna(0.0).round(2)
    return merged


def _attach_labels(triples: pd.DataFrame, work: pd.DataFrame) -> pd.DataFrame:
    for id_column, name_column in (
        ("담당자_id", "담당자_명"),
        ("결재자_id", "결재자_명"),
        ("법인_id", "법인_명"),
    ):
        lookup = (
            work.dropna(subset=[id_column])
            .drop_duplicates(subset=[id_column])
            .set_index(id_column)[name_column]
        )
        triples[name_column] = triples[id_column].map(lookup).fillna(triples[id_column])
    return triples


def _by_actor(triples: pd.DataFrame, id_column: str, name_column: str) -> pd.DataFrame:
    active = triples[triples["종합강도"] > 0]
    if active.empty:
        return pd.DataFrame()

    strongest = base.strongest_rows(active, id_column, "종합강도")
    totals = active.groupby(id_column, sort=False)["종합강도"].sum()

    return pd.DataFrame(
        {
            "id": strongest[id_column],
            "명": strongest[name_column],
            "점수": strongest[id_column].map(totals).to_numpy(),
            "사유": strongest.apply(_reason, axis=1),
        }
    )


def _triple_scores(triples: pd.DataFrame) -> pd.DataFrame:
    active = triples[triples["종합강도"] > 0]
    if active.empty:
        return pd.DataFrame()
    return pd.DataFrame(
        {
            "id": (
                active["담당자_id"].astype(str) + "|"
                + active["결재자_id"].astype(str) + "|"
                + active["법인_id"].astype(str)
            ),
            "명": (
                active["담당자_명"].astype(str) + " → "
                + active["결재자_명"].astype(str) + " → "
                + active["법인_명"].astype(str)
            ),
            "점수": active["종합강도"],
            "사유": active.apply(_reason, axis=1),
        }
    )


def _reason(row: pd.Series) -> str:
    parts = [f"{row['담당자_명']}-{row['결재자_명']}-{row['법인_명']} {int(row['건수'])}건"]
    if row["즉시결재강도"] > 2:
        parts.append(
            f"당일결재 {row['즉시결재율']:.0f}% (평균 {row['기대즉시결재율']:.0f}%)"
        )
    if row["편중강도"] > 2:
        parts.append("해당 법인으로 위임 편중")
    return " · ".join(parts)
