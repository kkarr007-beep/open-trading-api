"""F. 지급처 이상.

같은 예금주가 서로 다른 피보험자의 건으로 반복해서 돈을 받는 구조를 찾는다.
정상 거래에서는 수리업체·병원처럼 반복이 자연스러운 지급처가 있으므로,
건수만으로 보지 않고 동일 금액 반복과 담당자 편중을 함께 본다.
"""

from __future__ import annotations

import pandas as pd

from .. import stats
from . import base

NAME = "F_지급처이상"
TITLE = "지급처·예금주 반복 수령"

SCALE = (60.0, 600.0)  # 이상 예금주들의 이상도 합

# 이상도 환산 기준. 수리업체·병원처럼 반복이 정상인 지급처가 있어 절대 기준을
# 둔다. 서로 다른 피보험자 2명까지는 흔한 일이고, 15명을 넘으면 그 자체로 이상.
VICTIM_RANGE = (2, 15)
REPEAT_RANGE = (1, 5)


def run(data: dict[str, pd.DataFrame]) -> base.MetricResult:
    payments = data["payments"]

    if not base.has_columns(payments, "예금주id", "청구번호"):
        return base.empty_result(NAME, TITLE, "예금주 컬럼이 없어 건너뜀")

    work = payments.dropna(subset=["예금주id"]).copy()
    if "피보험자id" in work.columns:
        # 본인 계좌 수령은 정상이므로 제외한다.
        work = work[work["예금주id"].astype(str) != work["피보험자id"].astype(str)]
    if work.empty:
        return base.empty_result(NAME, TITLE, "제3자 지급 건이 없음")

    table = _payee_table(work)
    if table.empty:
        return base.empty_result(NAME, TITLE, "복수 피보험자에 걸친 예금주가 없음")

    result = base.MetricResult(name=NAME, title=TITLE)
    result.detail = table[
        ["예금주_명", "피보험자수", "청구건수", "총수령액", "동일금액최다반복",
         "관련담당자수", "관련법인수", "주담당자_명", "주담당자비중", "이상도"]
    ]
    result.add_score("담당자", _attribute(table, work, "담당자_id", "담당자_명"), scale=SCALE)
    result.add_score("법인", _attribute(table, work, "법인_id", "법인_명"), scale=SCALE)
    if "결재자_id" in work.columns:
        result.add_score("결재자", _attribute(table, work, "결재자_id", "결재자_명"), scale=SCALE)
    result.note = f"제3자 예금주 {len(table):,}명 검토"
    return result


def _payee_table(work: pd.DataFrame) -> pd.DataFrame:
    amount = "지급보험금" if "지급보험금" in work.columns else None

    aggregations = {
        "청구건수": ("청구번호", "nunique"),
        "지급행수": ("청구번호", "size"),
    }
    if "피보험자id" in work.columns:
        aggregations["피보험자수"] = ("피보험자id", "nunique")
    if "담당자_id" in work.columns:
        aggregations["관련담당자수"] = ("담당자_id", "nunique")
    if "법인_id" in work.columns:
        aggregations["관련법인수"] = ("법인_id", "nunique")
    if amount:
        aggregations["총수령액"] = (amount, "sum")

    table = work.groupby("예금주id", dropna=False, sort=False).agg(**aggregations).reset_index()

    for column in ("피보험자수", "관련담당자수", "관련법인수", "총수령액"):
        if column not in table.columns:
            table[column] = 0

    # 서로 다른 피보험자 건으로 반복 수령하는 경우만 본다.
    table = table[table["피보험자수"] >= 2].copy()
    if table.empty:
        return table

    repeats = _repeat_counts(work, amount)
    table["동일금액최다반복"] = table["예금주id"].map(repeats).fillna(0).astype(int)
    table = _attach_names(table, work)
    table = _attach_primary_handler(table, work)

    table["이상도"] = (
        stats.signal_score(table["피보험자수"], *VICTIM_RANGE) * 0.45
        + stats.signal_score(table["동일금액최다반복"], *REPEAT_RANGE) * 0.25
        + table["주담당자비중"].clip(upper=100) * 0.20
        + stats.percentile_score(table["총수령액"]) * 0.10
    ).round(1)

    table["총수령액"] = table["총수령액"].round(0)
    return table.sort_values("이상도", ascending=False).reset_index(drop=True)


def _repeat_counts(work: pd.DataFrame, amount: str | None) -> pd.Series:
    """같은 예금주에게 같은 금액이 몇 번이나 나갔는지. 분할 지급의 흔적을 본다."""
    if not amount:
        return pd.Series(dtype=int)
    counts = (
        work.dropna(subset=[amount])
        .groupby(["예금주id", amount], sort=False)
        .size()
        .reset_index(name="반복")
    )
    return counts.groupby("예금주id", sort=False)["반복"].max()


def _attach_names(table: pd.DataFrame, work: pd.DataFrame) -> pd.DataFrame:
    if "예금주" in work.columns:
        lookup = (
            work.dropna(subset=["예금주id"])
            .drop_duplicates(subset=["예금주id"])
            .set_index("예금주id")["예금주"]
        )
        table["예금주_명"] = table["예금주id"].map(lookup).fillna(table["예금주id"])
    else:
        table["예금주_명"] = table["예금주id"]
    return table


def _attach_primary_handler(table: pd.DataFrame, work: pd.DataFrame) -> pd.DataFrame:
    """한 예금주 건이 특정 담당자에게 몰려 있으면 단순 거래처로 보기 어렵다."""
    if "담당자_id" not in work.columns:
        table["주담당자_명"] = ""
        table["주담당자비중"] = 0.0
        return table

    counts = (
        work.groupby(["예금주id", "담당자_id", "담당자_명"], dropna=False, sort=False)
        .size()
        .reset_index(name="건수")
    )
    totals = counts.groupby("예금주id", sort=False)["건수"].transform("sum")
    counts["비중"] = counts["건수"] / totals * 100
    top = counts.sort_values("건수", ascending=False).drop_duplicates(subset=["예금주id"])

    table = table.merge(
        top[["예금주id", "담당자_명", "비중"]].rename(
            columns={"담당자_명": "주담당자_명", "비중": "주담당자비중"}
        ),
        on="예금주id",
        how="left",
    )
    table["주담당자_명"] = table["주담당자_명"].fillna("")
    table["주담당자비중"] = table["주담당자비중"].fillna(0.0).round(1)
    return table


def _attribute(
    table: pd.DataFrame, work: pd.DataFrame, id_column: str, name_column: str
) -> pd.DataFrame:
    """예금주 이상도를 그 건을 다룬 담당자·법인·결재자에게 귀속시킨다."""
    if id_column not in work.columns:
        return pd.DataFrame()

    flagged = table[table["이상도"] >= 50][["예금주id", "이상도", "예금주_명"]]
    if flagged.empty:
        return pd.DataFrame()

    linked = work[[id_column, name_column, "예금주id"]].dropna(subset=[id_column])
    linked = linked.merge(flagged, on="예금주id", how="inner").drop_duplicates(
        subset=[id_column, "예금주id"]
    )
    if linked.empty:
        return pd.DataFrame()

    rolled = linked.groupby([id_column, name_column], sort=False).agg(
        이상예금주수=("예금주id", "nunique"), 이상도합=("이상도", "sum")
    ).reset_index()

    top = linked.sort_values("이상도", ascending=False).drop_duplicates(subset=[id_column])
    rolled = rolled.merge(top[[id_column, "예금주_명"]], on=id_column, how="left")

    return pd.DataFrame(
        {
            "id": rolled[id_column],
            "명": rolled[name_column],
            "점수": rolled["이상도합"],
            "사유": rolled.apply(
                lambda row: (
                    f"반복 수령 예금주 {int(row['이상예금주수'])}명 관련 "
                    f"(대표: {row['예금주_명']})"
                ),
                axis=1,
            ),
        }
    )
