"""위임 현황 집계.

탐지가 아니라 현황 파악용이다. 연도별로 어느 업체에 얼마나 나갔는지, 조직과
담당자별 구성이 어떤지를 표로 낸다. 혐의 순위를 보기 전에 전체 그림을 먼저
잡아야 상위 대상이 왜 튀는지 읽힌다.
"""

from __future__ import annotations

import pandas as pd

from . import config


def available_years(cases: pd.DataFrame) -> list[int]:
    if "연도" not in cases.columns:
        return []
    years = pd.to_numeric(cases["연도"], errors="coerce").dropna().astype(int)
    return sorted(years.unique().tolist())


def filter_years(data: dict[str, pd.DataFrame], years: list[int]) -> dict[str, pd.DataFrame]:
    """기준년으로 데이터를 좁힌다. 지급 단위와 배당 단위를 함께 걸러야 한다."""
    if not years:
        return data
    selected = set(years)
    filtered = {}
    for key, frame in data.items():
        if isinstance(frame, pd.DataFrame) and "연도" in frame.columns:
            filtered[key] = frame[frame["연도"].isin(selected)].copy()
        else:
            filtered[key] = frame
    return filtered


def vendor_by_year(cases: pd.DataFrame) -> pd.DataFrame:
    """업체별 연도별 위임 건수·위임율·순위."""
    if not {"연도", "법인_명"} <= set(cases.columns):
        return pd.DataFrame()

    work = cases.dropna(subset=["연도", "법인_id"])
    if work.empty:
        return pd.DataFrame()

    counts = (
        work.groupby(["연도", "법인_명"], dropna=False, sort=False)
        .size()
        .reset_index(name="위임건수")
    )
    totals = counts.groupby("연도", sort=False)["위임건수"].transform("sum")
    counts["위임율"] = (counts["위임건수"] / totals * 100).round(2)
    counts["순위"] = (
        counts.groupby("연도", sort=False)["위임건수"].rank(ascending=False, method="min").astype(int)
    )
    counts["연도전체건수"] = totals

    # 전년 대비 변동을 같이 실어야 어느 해에 무엇이 바뀌었는지 보인다.
    counts = counts.sort_values(["법인_명", "연도"])
    counts["전년위임율"] = counts.groupby("법인_명", sort=False)["위임율"].shift()
    counts["증감"] = (counts["위임율"] - counts["전년위임율"]).round(2)
    counts["전년순위"] = counts.groupby("법인_명", sort=False)["순위"].shift()
    counts["순위변동"] = (counts["전년순위"] - counts["순위"]).astype("Int64")

    return counts.sort_values(["연도", "순위"]).reset_index(drop=True)


def vendor_share_matrix(cases: pd.DataFrame, top_n: int) -> pd.DataFrame:
    """업체 × 연도 위임율 표. 도해(히트맵)와 엑셀에 함께 쓴다."""
    table = vendor_by_year(cases)
    if table.empty:
        return table

    leaders = (
        table.groupby("법인_명", sort=False)["위임건수"].sum().nlargest(top_n).index
    )
    matrix = table[table["법인_명"].isin(leaders)].pivot(
        index="법인_명", columns="연도", values="위임율"
    )
    order = table[table["법인_명"].isin(leaders)].groupby("법인_명")["위임건수"].sum()
    return matrix.loc[order.sort_values(ascending=False).index].fillna(0.0).round(2)


def vendor_by_org(cases: pd.DataFrame, level: str) -> pd.DataFrame:
    """소속 계층별 업체 구성비."""
    if level not in cases.columns or "법인_명" not in cases.columns:
        return pd.DataFrame()

    work = cases.dropna(subset=[level, "법인_id"])
    if work.empty:
        return pd.DataFrame()

    counts = (
        work.groupby([level, "법인_명"], dropna=False, sort=False)
        .size()
        .reset_index(name="위임건수")
    )
    totals = counts.groupby(level, sort=False)["위임건수"].transform("sum")
    counts["조직총건수"] = totals
    counts["구성비"] = (counts["위임건수"] / totals * 100).round(2)
    counts["조직내순위"] = (
        counts.groupby(level, sort=False)["위임건수"].rank(ascending=False, method="min").astype(int)
    )
    return counts.sort_values([level, "조직내순위"]).reset_index(drop=True)


def person_vendor_mix(
    cases: pd.DataFrame, role: str, ids: list[str] | None = None
) -> pd.DataFrame:
    """인물별 업체 구성비. 업체를 접지 않고 전부 낸다.

    ids 를 주지 않으면 전원을 낸다. 누가 어느 업체에 얼마나 보냈는지는
    상위 몇 명만 봐서는 판단이 서지 않는다.
    """
    key, name = f"{role}_id", f"{role}_명"
    if key not in cases.columns or "법인_명" not in cases.columns:
        return pd.DataFrame()

    work = cases.dropna(subset=[key, "법인_id"])
    if ids is not None:
        work = work[work[key].isin(ids)]
    if work.empty:
        return pd.DataFrame()

    counts = (
        work.groupby([key, name, "법인_명"], dropna=False, sort=False)
        .size()
        .reset_index(name="위임건수")
    )
    totals = counts.groupby(key, sort=False)["위임건수"].transform("sum")
    counts["총건수"] = totals
    counts["구성비"] = (counts["위임건수"] / totals * 100).round(2)
    counts["업체순위"] = (
        counts.groupby(key, sort=False)["위임건수"]
        .rank(ascending=False, method="min").astype(int)
    )
    return counts.sort_values(
        [key, "위임건수"], ascending=[True, False]
    ).reset_index(drop=True)


def handler_vendor_mix(
    cases: pd.DataFrame, handlers: list[str] | None = None
) -> pd.DataFrame:
    """담당자별 업체 구성비."""
    return person_vendor_mix(cases, "담당자", handlers)


def composition_series(
    frame: pd.DataFrame, key: str, label: str, value: str, cap: int
) -> tuple[list[str], pd.DataFrame]:
    """구성비 도해용으로 상위 업체만 색을 주고 나머지는 '기타'로 접는다.

    색 슬롯은 한정돼 있고, 슬롯을 넘겨 색을 더 만들면 색약 조건에서 구분이
    무너진다. 꼬리는 묶는 편이 읽기에도 낫다.
    """
    if frame.empty:
        return [], frame

    leaders = frame.groupby(label, sort=False)[value].sum().nlargest(cap).index.tolist()
    folded = frame.copy()
    folded[label] = folded[label].where(folded[label].isin(leaders), "기타")
    folded = folded.groupby([key, label], dropna=False, sort=False)[value].sum().reset_index()

    order = leaders + (["기타"] if (folded[label] == "기타").any() else [])
    return order, folded


def org_level_for(cases: pd.DataFrame) -> str:
    """전환 분석에 쓸 조직 계층. 표본이 모자라면 상위 계층으로 올린다."""
    for level in config.ORG_LEVELS:
        if level not in cases.columns:
            continue
        sized = cases.groupby([level, "연도"], dropna=False, sort=False)["청구번호"].transform("size")
        if (sized >= config.MIN_CASES_ORG_YEAR).mean() >= 0.5:
            return level
    return next((level for level in config.ORG_LEVELS if level in cases.columns), "소속_명")
