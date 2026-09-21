"""E. 조사자 편중.

법인 단위로는 고르게 나눈 것처럼 보여도 실제로는 그 법인 안의 특정 조사자
한 사람에게만 가는 경우가 있다. 그래서 두 가지를 따로 본다. 하나는 담당자가
전체 조사자 중 누구에게 몰아주는지, 다른 하나는 같은 법인에 보낸 건 안에서
특정 조사자에게 고정되는지다. 뒤쪽이 개인 간 유착에 더 가깝다.
"""

from __future__ import annotations

import pandas as pd

from .. import config
from . import base

NAME = "E_조사자편중"
TITLE = "담당자-조사자 편중"

SCALE_ACTOR = (2.0, 30.0)  # 조사자별 편중강도 합
SCALE_PAIR = (2.0, 12.0)   # 담당자-조사자 한 쌍



def run(data: dict[str, pd.DataFrame]) -> base.MetricResult:
    cases = data["cases"]

    if not base.has_columns(cases, "담당자_id", "조사자_id"):
        return base.empty_result(NAME, TITLE, "조사자 컬럼이 없어 건너뜀")

    work = cases.dropna(subset=["담당자_id", "조사자_id"]).copy()
    if work.empty:
        return base.empty_result(NAME, TITLE, "조사자가 채워진 건이 없음")

    # 점포 안에서 본 편중
    branch = base.concentration_test(
        work, actor="담당자_id", target="조사자_id", scope="소속_id",
        min_pair=config.MIN_CASES_PAIR, min_actor=config.MIN_CASES_PERSON,
    )
    # 같은 법인에 보낸 건 안에서 본 편중
    within = base.concentration_test(
        work, actor="담당자_id", target="조사자_id", scope="법인_id",
        min_pair=config.MIN_CASES_PAIR, min_actor=config.MIN_CASES_PAIR,
    )

    combined = _combine(branch, within)
    if combined.empty:
        return base.empty_result(NAME, TITLE, "표본 기준을 넘는 담당자-조사자 조합이 없음")

    combined = _attach_labels(combined, work)
    result = base.MetricResult(name=NAME, title=TITLE)
    result.detail = combined[
        ["담당자_명", "조사자_명", "법인_명", "배당건수", "실제배당률", "기대배당률",
         "점포기준강도", "법인내강도", "편중강도"]
    ]
    result.add_score("담당자", _handler_scores(combined), scale=SCALE_ACTOR)
    result.add_score("조합", _pair_scores(combined), scale=SCALE_PAIR)
    result.note = f"담당자-조사자 조합 {len(combined):,}건 검정"
    return result


def _combine(branch: pd.DataFrame, within: pd.DataFrame) -> pd.DataFrame:
    keys = ["담당자_id", "조사자_id"]

    if not branch.empty:
        frame = branch.rename(columns={"편중강도": "점포기준강도"})
        frame = frame[keys + ["배당건수", "실제배당률", "기대배당률", "점포기준강도"]]
    else:
        frame = pd.DataFrame(columns=keys + ["배당건수", "실제배당률", "기대배당률", "점포기준강도"])

    if not within.empty:
        inner = within.rename(columns={"편중강도": "법인내강도"})
        inner = inner.groupby(keys, sort=False).agg(
            법인내강도=("법인내강도", "max"),
            법인내건수=("배당건수", "sum"),
            법인내률=("실제배당률", "max"),
            법인내기대=("기대배당률", "mean"),
        ).reset_index()
        frame = frame.merge(inner, on=keys, how="outer")
    else:
        frame["법인내강도"] = 0.0

    if frame.empty:
        return frame

    # 점포 기준 표본이 없어 비어 있으면 법인 내 수치로 채운다.
    for target, source in (
        ("배당건수", "법인내건수"), ("실제배당률", "법인내률"), ("기대배당률", "법인내기대")
    ):
        if source in frame.columns:
            frame[target] = frame[target].fillna(frame[source])

    frame["점포기준강도"] = frame["점포기준강도"].fillna(0.0).round(2)
    frame["법인내강도"] = frame["법인내강도"].fillna(0.0).round(2)
    # 같은 법인 안에서의 고정이 개인 유착에 더 가까우므로 더 무겁게 본다.
    frame["편중강도"] = (frame["점포기준강도"] * 0.4 + frame["법인내강도"] * 0.6).round(2)
    frame = frame[frame["편중강도"] > 0]
    return frame.sort_values("편중강도", ascending=False).reset_index(drop=True)


def _attach_labels(frame: pd.DataFrame, work: pd.DataFrame) -> pd.DataFrame:
    for id_column, name_column in (("담당자_id", "담당자_명"), ("조사자_id", "조사자_명")):
        lookup = (
            work.dropna(subset=[id_column])
            .drop_duplicates(subset=[id_column])
            .set_index(id_column)[name_column]
        )
        frame[name_column] = frame[id_column].map(lookup).fillna(frame[id_column])

    vendor = (
        work.dropna(subset=["조사자_id"])
        .drop_duplicates(subset=["조사자_id"])
        .set_index("조사자_id")["법인_명"]
    )
    frame["법인_명"] = frame["조사자_id"].map(vendor).fillna("")

    for column in ("실제배당률", "기대배당률"):
        frame[column] = (pd.to_numeric(frame[column], errors="coerce") * 100).round(1)
    frame["배당건수"] = pd.to_numeric(frame["배당건수"], errors="coerce").fillna(0).astype(int)
    return frame


def _handler_scores(frame: pd.DataFrame) -> pd.DataFrame:
    strongest = base.strongest_rows(frame, "담당자_id", "편중강도")
    totals = frame.groupby("담당자_id", sort=False)["편중강도"].sum()
    return pd.DataFrame(
        {
            "id": strongest["담당자_id"],
            "명": strongest["담당자_명"],
            "점수": strongest["담당자_id"].map(totals).to_numpy(),
            "사유": strongest.apply(_reason, axis=1),
        }
    )


def _pair_scores(frame: pd.DataFrame) -> pd.DataFrame:
    return pd.DataFrame(
        {
            "id": frame["담당자_id"].astype(str) + "|" + frame["조사자_id"].astype(str),
            "명": frame["담당자_명"].astype(str) + " → " + frame["조사자_명"].astype(str),
            "점수": frame["편중강도"],
            "사유": frame.apply(_reason, axis=1),
        }
    )


def _reason(row: pd.Series) -> str:
    text = f"조사자 {row['조사자_명']}에 {int(row['배당건수'])}건"
    if pd.notna(row.get("실제배당률")):
        text += f" ({row['실제배당률']:.0f}%, 기대 {row['기대배당률']:.0f}%)"
    if row["법인내강도"] > row["점포기준강도"]:
        text += " · 같은 법인 내 특정 조사자 고정"
    return text
