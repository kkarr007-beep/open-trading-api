"""A. 배당 편중.

담당자가 특정 손사법인에 기대보다 많이 보냈는지 본다. 단순 건수 1위는 업무량이
많다는 뜻일 뿐이므로, 같은 점포 동료들의 배당 비율을 기대치로 잡고 그 대비
초과분을 본다.
"""

from __future__ import annotations

import pandas as pd

from .. import config
from . import base

NAME = "A_배당편중"
TITLE = "담당자-법인 배당 편중"

SCALE_ACTOR = (2.0, 12.0)    # -log10(p): 0.01 부터 신호, 1e-12 에서 만점
SCALE_VENDOR = (3.0, 60.0)  # 여러 담당자의 편중강도 합



def run(data: dict[str, pd.DataFrame]) -> base.MetricResult:
    cases = data["cases"]
    result = base.MetricResult(name=NAME, title=TITLE)

    if not base.has_columns(cases, "담당자_id", "법인_id", "소속_id"):
        return base.empty_result(NAME, TITLE, "담당자·법인·소속 컬럼이 없어 건너뜀")

    pairs = base.concentration_test(
        cases,
        actor="담당자_id",
        target="법인_id",
        scope="소속_id",
        min_pair=config.MIN_CASES_PAIR,
        min_actor=config.MIN_CASES_PERSON,
    )
    if pairs.empty:
        return base.empty_result(NAME, TITLE, "표본 기준을 넘는 담당자-법인 조합이 없음")

    pairs = _attach_labels(pairs, cases)
    detail = pairs[
        [
            "담당자_id", "소속_명", "담당자_명", "법인_명", "배당건수", "행위자총건수",
            "실제배당률", "기대배당률", "기대건수", "초과건수", "p값", "편중강도",
        ]
    ].rename(columns={"행위자총건수": "담당자총건수"})
    detail["실제배당률"] = (detail["실제배당률"] * 100).round(1)
    detail["기대배당률"] = (detail["기대배당률"] * 100).round(1)
    detail["기대건수"] = detail["기대건수"].round(1)
    detail["초과건수"] = detail["초과건수"].round(1)
    detail["편중강도"] = detail["편중강도"].round(2)
    result.detail = detail

    spread = base.concentration_index(
        cases, actor="담당자_id", target="법인_id", min_actor=config.MIN_CASES_PERSON
    )

    result.add_score("담당자", _handler_scores(pairs, spread, cases), scale=SCALE_ACTOR)
    result.add_score("법인", _vendor_scores(pairs), scale=SCALE_VENDOR)
    result.add_score("조합", _pair_scores(pairs), scale=SCALE_ACTOR)
    result.note = f"담당자-법인 조합 {len(pairs):,}건 검정"
    return result


def _attach_labels(pairs: pd.DataFrame, cases: pd.DataFrame) -> pd.DataFrame:
    for id_column, name_column in (
        ("담당자_id", "담당자_명"),
        ("법인_id", "법인_명"),
        ("소속_id", "소속_명"),
    ):
        lookup = (
            cases.dropna(subset=[id_column])
            .drop_duplicates(subset=[id_column])
            .set_index(id_column)[name_column]
        )
        pairs[name_column] = pairs[id_column].map(lookup).fillna(pairs[id_column])
    return pairs


def _handler_scores(
    pairs: pd.DataFrame, spread: pd.DataFrame, cases: pd.DataFrame
) -> pd.DataFrame:
    strongest = base.strongest_rows(pairs, "담당자_id", "편중강도")
    if strongest.empty:
        return pd.DataFrame()

    frame = strongest[
        ["담당자_id", "담당자_명", "법인_명", "배당건수", "실제배당률", "기대배당률", "편중강도"]
    ].copy()

    if not spread.empty:
        frame = frame.merge(
            spread[["담당자_id", "집중도", "상대수"]], on="담당자_id", how="left"
        )
    else:
        frame["집중도"] = 0.0
        frame["상대수"] = 0

    frame["사유"] = frame.apply(
        lambda row: (
            f"{row['법인_명']}에 {int(row['배당건수'])}건 "
            f"({row['실제배당률'] * 100:.0f}%, 기대 {row['기대배당률'] * 100:.0f}%)"
            + (f" · 거래법인 {int(row['상대수'])}곳" if pd.notna(row.get("상대수")) else "")
        ),
        axis=1,
    )
    return pd.DataFrame(
        {
            "id": frame["담당자_id"],
            "명": frame["담당자_명"],
            "점수": frame["편중강도"],
            "사유": frame["사유"],
        }
    )


def _vendor_scores(pairs: pd.DataFrame) -> pd.DataFrame:
    grouped = pairs.groupby(["법인_id", "법인_명"], sort=False).agg(
        편중강도합=("편중강도", "sum"),
        초과건수=("초과건수", "sum"),
        편중담당자수=("편중강도", lambda values: int((values > 2).sum())),
    ).reset_index()
    grouped = grouped[grouped["편중강도합"] > 0]
    if grouped.empty:
        return pd.DataFrame()

    return pd.DataFrame(
        {
            "id": grouped["법인_id"],
            "명": grouped["법인_명"],
            "점수": grouped["편중강도합"],
            "사유": grouped.apply(
                lambda row: (
                    f"편중 담당자 {int(row['편중담당자수'])}명 · "
                    f"기대 대비 +{row['초과건수']:.0f}건"
                ),
                axis=1,
            ),
        }
    )


def _pair_scores(pairs: pd.DataFrame) -> pd.DataFrame:
    active = pairs[pairs["편중강도"] > 0]
    if active.empty:
        return pd.DataFrame()
    return pd.DataFrame(
        {
            "id": active["담당자_id"].astype(str) + "|" + active["법인_id"].astype(str),
            "명": active["담당자_명"].astype(str) + " → " + active["법인_명"].astype(str),
            "점수": active["편중강도"],
            "사유": active.apply(
                lambda row: (
                    f"{int(row['배당건수'])}건 / 기대 {row['기대건수']:.0f}건"
                ),
                axis=1,
            ),
        }
    )
