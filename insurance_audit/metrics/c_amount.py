"""C. 지급금액 이상.

유사한 사건끼리 묶어 놓고 특정 법인을 거친 건의 지급액이 더 큰지 본다.
금액은 소수의 고액 건이 평균을 끌고 가므로 순위 기반으로 비교한다.
여기에 Benford 첫자리 적합도를 더해 금액이 사람 손을 탄 흔적을 함께 본다.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

from .. import config, stats
from . import base

NAME = "C_금액이상"
TITLE = "유사건 대비 지급금액 이상"

SCALE = (2.0, 12.0)  # 순위합 Z에 Benford 가산을 더한 값

VALUE = "총수령액"


def run(data: dict[str, pd.DataFrame]) -> base.MetricResult:
    cases = data["cases"]
    payments = data["payments"]

    if VALUE not in cases.columns or cases[VALUE].fillna(0).eq(0).all():
        return base.empty_result(NAME, TITLE, "지급금액 컬럼이 비어 있어 건너뜀")

    work, strata = base.stratified_expectation(
        cases.dropna(subset=["법인_id"]), config.STRATA_AMOUNT, config.MIN_STRATUM_SIZE
    )
    if work.empty:
        return base.empty_result(NAME, TITLE, "층화 후 남은 표본이 없음")

    vendor = base.stratified_rank_test(
        work, strata, "법인_id", VALUE, config.MIN_CASES_VENDOR
    )
    if vendor.empty:
        return base.empty_result(NAME, TITLE, "비교 가능한 법인이 없음")

    vendor = _attach_benford(vendor, payments, "법인_id")
    vendor = _attach_median(vendor, work, "법인_id")
    vendor["명"] = _labels(vendor["법인_id"], cases, "법인_id", "법인_명")

    result = base.MetricResult(name=NAME, title=TITLE)
    result.detail = (
        vendor[["명", "표본수", "층수", "Z", "중위금액", "전체중위금액", "Benford표본", "BenfordMAD"]]
        .rename(columns={"명": "법인_명", "Z": "금액편차Z"})
        .sort_values("금액편차Z", ascending=False)
        .reset_index(drop=True)
    )
    result.add_score("법인", _scores(vendor, "법인_id"), scale=SCALE)

    handler = base.stratified_rank_test(
        work, strata, "담당자_id", VALUE, config.MIN_CASES_PERSON
    )
    if not handler.empty:
        handler = _attach_benford(handler, payments, "담당자_id")
        handler = _attach_median(handler, work, "담당자_id")
        handler["명"] = _labels(handler["담당자_id"], cases, "담당자_id", "담당자_명")
        result.add_score("담당자", _scores(handler, "담당자_id"), scale=SCALE)

    result.note = f"층화 기준: {', '.join(strata) if strata else '없음'}"
    return result


def _attach_benford(frame: pd.DataFrame, payments: pd.DataFrame, key: str) -> pd.DataFrame:
    """지급 행 단위 금액으로 Benford 첫자리 적합도를 본다."""
    if "지급보험금" not in payments.columns or key not in payments.columns:
        frame["Benford표본"] = 0
        frame["BenfordMAD"] = np.nan
        return frame

    amounts = payments[[key, "지급보험금"]].dropna()
    amounts = amounts[amounts["지급보험금"] >= config.BENFORD_MIN_AMOUNT]

    records = {}
    for actor, group in amounts.groupby(key, sort=False):
        if len(group) < config.BENFORD_MIN_SAMPLE:
            continue
        records[actor] = stats.benford_first_digit(group["지급보험금"].to_numpy())

    frame["Benford표본"] = frame[key].map(lambda k: records.get(k, {}).get("표본수", 0))
    frame["BenfordMAD"] = frame[key].map(
        lambda k: round(records[k]["MAD"], 4) if k in records else np.nan
    )
    return frame


def _attach_median(frame: pd.DataFrame, work: pd.DataFrame, key: str) -> pd.DataFrame:
    medians = work.groupby(key, sort=False)[VALUE].median()
    frame["중위금액"] = frame[key].map(medians).round(0)
    frame["전체중위금액"] = round(float(work[VALUE].median()), 0)
    return frame


def _labels(ids: pd.Series, cases: pd.DataFrame, id_column: str, name_column: str) -> pd.Series:
    lookup = (
        cases.dropna(subset=[id_column])
        .drop_duplicates(subset=[id_column])
        .set_index(id_column)[name_column]
    )
    return ids.map(lookup).fillna(ids)


def _scores(frame: pd.DataFrame, key: str) -> pd.DataFrame:
    # 순위합 Z를 본값으로 두고, Benford 이탈은 가산점으로 얹는다. 판정선(MAD
    # 0.012)의 몇 배인지로 환산하되 최대 3점까지만 더해 본값을 덮지 않게 한다.
    benford_bonus = (
        (frame["BenfordMAD"].fillna(0) / config.BENFORD_MAD_THRESHOLD).clip(upper=3.0)
    )
    benford_bonus = benford_bonus.where(
        frame["BenfordMAD"] > config.BENFORD_MAD_THRESHOLD, 0.0
    )
    combined = frame["Z"].clip(lower=0) + benford_bonus

    reasons = []
    for _, row in frame.iterrows():
        parts = []
        if row["Z"] > 1.0:
            parts.append(
                f"유사건 대비 지급액 상위 (Z={row['Z']:.1f}, "
                f"중위 {row['중위금액']:,.0f}원 / 전체 {row['전체중위금액']:,.0f}원)"
            )
        if pd.notna(row["BenfordMAD"]) and row["BenfordMAD"] > config.BENFORD_MAD_THRESHOLD:
            parts.append(f"금액 첫자리 분포 이탈 (MAD={row['BenfordMAD']:.3f})")
        reasons.append(" · ".join(parts) if parts else "특이사항 없음")

    return pd.DataFrame(
        {
            "id": frame[key],
            "명": frame["명"],
            "점수": combined.to_numpy(),
            "사유": reasons,
        }
    )
