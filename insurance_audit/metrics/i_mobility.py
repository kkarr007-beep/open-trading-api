"""I. 인물 이동 추적.

담당자나 결재자가 소속을 옮긴 뒤에도 같은 업체로 계속 위임하는지 본다.

한 지점에서만 특정 업체에 몰리는 것은 그 지점의 거래 관행일 수 있다. 그래서
각 소속의 기대 배당률을 그 소속 동료들에게서 따로 뽑고, 본인의 초과분이 옮긴
뒤에도 유지되는지를 본다. 업체 선호가 조직이 아니라 사람을 따라다니면 지점
관행으로는 설명되지 않는다.
"""

from __future__ import annotations

import pandas as pd

from .. import config
from . import base

NAME = "I_인물이동"
TITLE = "소속 이동 후 업체 편중 유지"

SCALE = (2.0, 12.0)  # 가장 약한 소속에서의 -log10(p)

# 한 소속에서 이 정도는 돼야 그곳에서도 편중이 있었다고 본다.
POSTING_THRESHOLD = 2.0

ROLES = (("담당자", "담당자_id", "담당자_명"), ("결재자", "결재자_id", "결재자_명"))


def run(data: dict[str, pd.DataFrame]) -> base.MetricResult:
    cases = data["cases"]
    result = base.MetricResult(name=NAME, title=TITLE)

    if not base.has_columns(cases, "법인_id", "소속_id"):
        return base.empty_result(NAME, TITLE, "소속 또는 법인 컬럼이 없어 건너뜀")

    details = []
    for role, id_column, name_column in ROLES:
        if id_column not in cases.columns:
            continue
        table = _role_table(cases, id_column, name_column)
        if table.empty:
            continue
        table.insert(0, "역할", role)
        details.append(table)
        result.add_score(role, _scores(table, id_column, name_column), scale=SCALE)

    if not details:
        return base.empty_result(NAME, TITLE, "소속을 옮긴 인물이 없거나 표본 부족")

    combined = pd.concat(details, ignore_index=True)
    result.detail = combined[
        ["역할", "인물", "법인_명", "유지소속수", "소속", "기간", "배당건수",
         "본인배당률", "소속기대율", "편중강도", "지속강도"]
    ]
    result.note = (
        f"이동 인물 {combined['인물'].nunique():,}명 · "
        f"유지 사례 {len(combined.drop_duplicates(['인물', '법인_명'])):,}건"
    )
    return result


def _role_table(cases: pd.DataFrame, id_column: str, name_column: str) -> pd.DataFrame:
    work = cases.dropna(subset=[id_column, "법인_id", "소속_id"]).copy()
    if work.empty:
        return pd.DataFrame()

    movers = _movers(work, id_column)
    if not movers:
        return pd.DataFrame()

    # 소속마다 따로 검정해야 하므로 행위자 키에 소속을 묶어 넣는다. 검정은 전체
    # 인원으로 돌린다. 이동자만 남기고 돌리면 그 소속의 기대 배당률을 낼 동료가
    # 사라져 기준선이 본인 실적으로 대체된다.
    work["_인물소속"] = work[id_column].astype(str) + "@" + work["소속_id"].astype(str)

    pairs = base.concentration_test(
        work,
        actor="_인물소속",
        target="법인_id",
        scope="소속_id",
        min_pair=config.MIN_CASES_PAIR,
        min_actor=config.MIN_CASES_PER_POSTING,
    )
    if pairs.empty:
        return pd.DataFrame()

    keys = pairs["_인물소속"].str.split("@", n=1, expand=True)
    pairs[id_column] = keys[0]
    pairs["소속_id"] = keys[1]
    pairs = pairs[pairs[id_column].isin(movers)]
    if pairs.empty:
        return pd.DataFrame()

    kept = pairs[pairs["편중강도"] >= POSTING_THRESHOLD]
    if kept.empty:
        return pd.DataFrame()

    # 같은 업체 편중이 서로 다른 소속에서 반복될 때만 인물을 따라온 것으로 본다.
    postings = kept.groupby([id_column, "법인_id"], sort=False)["소속_id"].nunique()
    carried = postings[postings >= config.MIN_POSTINGS].index
    if len(carried) == 0:
        return pd.DataFrame()

    kept = kept.set_index([id_column, "법인_id"]).loc[carried].reset_index()

    # 가장 약한 소속에서의 강도를 대표값으로 쓴다. 어느 지점에서든 유지됐다는 뜻이다.
    weakest = kept.groupby([id_column, "법인_id"], sort=False)["편중강도"].transform("min")
    spread = kept.groupby([id_column, "법인_id"], sort=False)["소속_id"].transform("nunique")
    kept["유지소속수"] = spread
    kept["지속강도"] = (weakest * (1 + 0.5 * (spread - config.MIN_POSTINGS))).round(2)

    return _decorate(kept, cases, id_column, name_column)


def _movers(work: pd.DataFrame, id_column: str) -> list[str]:
    """소속을 옮긴 인물. 각 소속에서 최소 건수를 채운 경우만 발령으로 본다."""
    postings = (
        work.groupby([id_column, "소속_id"], dropna=False, sort=False)
        .size()
        .reset_index(name="건수")
    )
    postings = postings[postings["건수"] >= config.MIN_CASES_PER_POSTING]
    counts = postings.groupby(id_column, sort=False)["소속_id"].nunique()
    return counts[counts >= config.MIN_POSTINGS].index.tolist()


def _decorate(
    kept: pd.DataFrame, cases: pd.DataFrame, id_column: str, name_column: str
) -> pd.DataFrame:
    for source, target in ((id_column, name_column), ("법인_id", "법인_명"), ("소속_id", "소속_명")):
        lookup = (
            cases.dropna(subset=[source])
            .drop_duplicates(subset=[source])
            .set_index(source)[target]
        )
        kept[target] = kept[source].map(lookup).fillna(kept[source])

    kept["인물"] = kept[name_column]
    kept["소속"] = kept["소속_명"]
    kept["기간"] = _posting_years(kept, cases, id_column)
    kept["본인배당률"] = (kept["실제배당률"] * 100).round(1)
    kept["소속기대율"] = (kept["기대배당률"] * 100).round(1)
    kept["편중강도"] = kept["편중강도"].round(2)
    kept[id_column] = kept[id_column]
    return kept.sort_values(["지속강도", "인물"], ascending=[False, True]).reset_index(drop=True)


def _posting_years(kept: pd.DataFrame, cases: pd.DataFrame, id_column: str) -> pd.Series:
    """그 인물이 그 소속에 있던 기간. 발령 시점을 읽을 수 있게 붙인다."""
    if "연도" not in cases.columns:
        return pd.Series("", index=kept.index)

    span = (
        cases.dropna(subset=[id_column, "소속_id", "연도"])
        .groupby([id_column, "소속_id"], sort=False)["연도"]
        .agg(["min", "max"])
    )
    labels = []
    for _, row in kept.iterrows():
        key = (row[id_column], row["소속_id"])
        if key in span.index:
            low, high = span.loc[key, "min"], span.loc[key, "max"]
            labels.append(f"{low}" if low == high else f"{low}~{high}")
        else:
            labels.append("")
    return pd.Series(labels, index=kept.index)


def _scores(table: pd.DataFrame, id_column: str, name_column: str) -> pd.DataFrame:
    strongest = base.strongest_rows(table, id_column, "지속강도")
    if strongest.empty:
        return pd.DataFrame()

    routes = (
        table.sort_values("배당건수", ascending=False)
        .groupby([id_column, "법인_id"], sort=False)["소속"]
        .apply(lambda values: " → ".join(dict.fromkeys(values)))
    )

    reasons = []
    for _, row in strongest.iterrows():
        route = routes.get((row[id_column], row["법인_id"]), "")
        reasons.append(
            f"{route} 이동 후에도 {row['법인_명']} 편중 유지 "
            f"(소속 {int(row['유지소속수'])}곳)"
        )

    return pd.DataFrame(
        {
            "id": strongest[id_column],
            "명": strongest[name_column],
            "점수": strongest["지속강도"],
            "사유": reasons,
        }
    )
