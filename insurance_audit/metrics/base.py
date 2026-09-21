"""지표 모듈이 공유하는 계산 골격."""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
import pandas as pd

from .. import stats


@dataclass
class MetricResult:
    """지표 하나의 산출물.

    detail은 엑셀 시트로 그대로 나가고, scores는 주체별 채점에 들어간다.
    """

    name: str
    title: str
    detail: pd.DataFrame = field(default_factory=pd.DataFrame)
    scores: dict[str, pd.DataFrame] = field(default_factory=dict)
    scales: dict[str, tuple[float, float]] = field(default_factory=dict)
    note: str = ""

    def add_score(
        self, entity: str, frame: pd.DataFrame, scale: tuple[float, float] | None = None
    ) -> None:
        """entity별 채점표를 등록한다. 컬럼은 id/명/점수/사유.

        scale은 (하한, 포화점)이다. 그 지표의 검정 통계량에서 어디부터 신호로
        보고 어디서 최고점을 줄지 정한다. 없으면 백분위로 환산한다.
        """
        if frame is None or frame.empty:
            return
        required = {"id", "명", "점수"}
        missing = required - set(frame.columns)
        if missing:
            raise ValueError(f"{self.name} {entity} 채점표에 {missing} 컬럼이 없습니다")
        if "사유" not in frame.columns:
            frame = frame.assign(사유="")
        self.scores[entity] = frame.reset_index(drop=True)
        if scale is not None:
            self.scales[entity] = scale


def empty_result(name: str, title: str, note: str) -> MetricResult:
    return MetricResult(name=name, title=title, note=note)


def has_columns(frame: pd.DataFrame, *columns: str) -> bool:
    return all(column in frame.columns for column in columns)


def concentration_test(
    frame: pd.DataFrame,
    actor: str,
    target: str,
    scope: str,
    min_pair: int,
    min_actor: int,
) -> pd.DataFrame:
    """행위자가 특정 상대에게 기대보다 몰아줬는지 검정한다.

    기대치는 같은 scope(점포 등) 안에서 그 상대가 차지하는 배당 비율로 잡되,
    행위자 본인 건은 빼고 계산한다. 본인 물량이 커서 기대치를 끌어올리면
    편중이 정상으로 보이는 문제가 생기기 때문이다.
    """
    needed = [actor, target, scope]
    if not has_columns(frame, *needed):
        return pd.DataFrame()

    work = frame.dropna(subset=[actor, target]).copy()
    if work.empty:
        return pd.DataFrame()

    pair = (
        work.groupby([scope, actor, target], dropna=False, sort=False)
        .size()
        .reset_index(name="배당건수")
    )
    actor_total = (
        work.groupby([scope, actor], dropna=False, sort=False)
        .size()
        .reset_index(name="행위자총건수")
    )
    target_total = (
        work.groupby([scope, target], dropna=False, sort=False)
        .size()
        .reset_index(name="상대총건수")
    )
    scope_total = work.groupby(scope, dropna=False, sort=False).size().reset_index(name="점포총건수")

    merged = (
        pair.merge(actor_total, on=[scope, actor], how="left")
        .merge(target_total, on=[scope, target], how="left")
        .merge(scope_total, on=scope, how="left")
    )

    merged = merged[
        (merged["배당건수"] >= min_pair) & (merged["행위자총건수"] >= min_actor)
    ].copy()
    if merged.empty:
        return pd.DataFrame()

    # 본인을 제외한 나머지 인원이 그 상대에게 보낸 비율이 기대 배당률이다.
    # 아무도 안 쓰는 상대면 기대율이 0이 되어 검정이 발산하므로 약하게 보정한다.
    others_target = merged["상대총건수"] - merged["배당건수"] + 0.5
    others_total = merged["점포총건수"] - merged["행위자총건수"] + 1.0
    expected = np.divide(
        others_target,
        others_total,
        out=np.full(len(merged), np.nan),
        where=others_total > 0,
    )
    # 점포에 그 행위자밖에 없으면 비교 대상이 없다. 점포 전체 비율로 대신한다.
    fallback = merged["상대총건수"] / merged["점포총건수"]
    merged["기대배당률"] = pd.Series(expected, index=merged.index).fillna(fallback)

    merged["기대건수"] = merged["행위자총건수"] * merged["기대배당률"]
    merged["초과건수"] = merged["배당건수"] - merged["기대건수"]
    merged["실제배당률"] = merged["배당건수"] / merged["행위자총건수"]

    merged["p값"] = stats.binom_tail_vector(
        merged["배당건수"].to_numpy(),
        merged["행위자총건수"].to_numpy(),
        merged["기대배당률"].to_numpy(),
    )
    merged["편중강도"] = [stats.neg_log10(value) for value in merged["p값"]]
    # 기대보다 적게 보낸 건은 유착 신호가 아니다.
    merged.loc[merged["초과건수"] <= 0, "편중강도"] = 0.0

    return merged.sort_values("편중강도", ascending=False).reset_index(drop=True)


def concentration_index(
    frame: pd.DataFrame, actor: str, target: str, min_actor: int
) -> pd.DataFrame:
    """행위자별 집중도 지수(HHI)와 최다 상대 비중."""
    if not has_columns(frame, actor, target):
        return pd.DataFrame()

    work = frame.dropna(subset=[actor, target])
    if work.empty:
        return pd.DataFrame()

    rows = []
    for actor_id, group in work.groupby(actor, dropna=False, sort=False):
        counts = group[target].value_counts()
        total = int(counts.sum())
        if total < min_actor:
            continue
        rows.append(
            {
                actor: actor_id,
                "총건수": total,
                "상대수": int(len(counts)),
                "집중도": round(stats.hhi(counts.to_numpy()), 4),
                "최다상대": counts.index[0],
                "최다상대건수": int(counts.iloc[0]),
                "최다상대비중": round(float(counts.iloc[0]) / total * 100, 1),
            }
        )
    return pd.DataFrame(rows)


def stratified_expectation(
    frame: pd.DataFrame,
    strata: tuple[str, ...],
    min_size: int,
) -> tuple[pd.DataFrame, tuple[str, ...]]:
    """층화 기준을 적용한다.

    층이 잘게 쪼개져 표본이 모자라면 뒤쪽 기준부터 하나씩 떼어내며 다시 시도한다.
    비교 전에 사건 성격을 맞추지 않으면 '그 법인은 원래 어려운 건만 받는다'는
    반박에 결과가 무너진다.
    """
    available = tuple(column for column in strata if column in frame.columns)
    while available:
        sized = frame.groupby(list(available), dropna=False, sort=False)["청구번호"].transform("size")
        if (sized >= min_size).mean() >= 0.5:
            return frame[sized >= min_size].copy(), available
        available = available[:-1]
    return frame.copy(), ()


def stratified_rank_test(
    frame: pd.DataFrame,
    strata: tuple[str, ...],
    group: str,
    value: str,
    min_group: int,
) -> pd.DataFrame:
    """층별 순위합 검정을 층 전체에 걸쳐 합산한다(Stouffer).

    금액은 한쪽으로 길게 늘어진 분포라 평균 비교가 왜곡된다. 층 안에서 순위로
    비교한 뒤 층별 z를 표본수로 가중해 합친다. 건수가 많아 반복문 대신
    groupby로 한 번에 계산한다.
    """
    columns = [group, value, *strata]
    if not has_columns(frame, *columns):
        return pd.DataFrame()

    work = frame.dropna(subset=[group, value]).copy()
    work[value] = pd.to_numeric(work[value], errors="coerce")
    work = work[work[value].notna()]
    if work.empty:
        return pd.DataFrame()

    keys = list(strata)
    if keys:
        work["_순위"] = work.groupby(keys, dropna=False, sort=False)[value].rank(method="average")
        stratum = work.groupby(keys, dropna=False, sort=False).size().reset_index(name="층계")
        ties = (
            work.groupby(keys + [value], dropna=False, sort=False)
            .size()
            .reset_index(name="동률수")
        )
        ties["보정"] = ties["동률수"] ** 3 - ties["동률수"]
        ties = ties.groupby(keys, dropna=False, sort=False)["보정"].sum().reset_index()
        cell = (
            work.groupby(keys + [group], dropna=False, sort=False)
            .agg(그룹수=(value, "size"), 순위합=("_순위", "sum"))
            .reset_index()
        )
        cell = cell.merge(stratum, on=keys, how="left").merge(ties, on=keys, how="left")
    else:
        work["_순위"] = work[value].rank(method="average")
        counts = work[value].value_counts()
        cell = (
            work.groupby(group, dropna=False, sort=False)
            .agg(그룹수=(value, "size"), 순위합=("_순위", "sum"))
            .reset_index()
        )
        cell["층계"] = len(work)
        cell["보정"] = float(((counts ** 3) - counts).sum())

    n1 = cell["그룹수"].to_numpy(dtype=float)
    total = cell["층계"].to_numpy(dtype=float)
    n2 = total - n1
    u1 = cell["순위합"].to_numpy(dtype=float) - n1 * (n1 + 1) / 2.0
    mean = n1 * n2 / 2.0
    with np.errstate(divide="ignore", invalid="ignore"):
        variance = n1 * n2 / 12.0 * ((total + 1) - cell["보정"].to_numpy(dtype=float) / (total * (total - 1)))
        z = np.divide(u1 - mean, np.sqrt(variance), out=np.zeros_like(mean), where=variance > 0)
    cell["z"] = np.where(np.isfinite(z) & (n2 > 0), z, 0.0)
    cell = cell[n2 > 0]
    if cell.empty:
        return pd.DataFrame()

    weight = np.sqrt(cell["그룹수"].to_numpy(dtype=float))
    cell["_가중z"] = weight * cell["z"]
    cell["_가중제곱"] = weight ** 2

    rolled = cell.groupby(group, sort=False).agg(
        표본수=("그룹수", "sum"),
        층수=("그룹수", "size"),
        _가중z=("_가중z", "sum"),
        _가중제곱=("_가중제곱", "sum"),
    ).reset_index()

    denominator = np.sqrt(rolled["_가중제곱"].to_numpy(dtype=float))
    rolled["Z"] = np.divide(
        rolled["_가중z"].to_numpy(dtype=float),
        denominator,
        out=np.zeros(len(rolled)),
        where=denominator > 0,
    )
    rolled = rolled.drop(columns=["_가중z", "_가중제곱"])
    return rolled[rolled["표본수"] >= min_group].reset_index(drop=True)


def strongest_rows(frame: pd.DataFrame, key: str, order_by: str) -> pd.DataFrame:
    """주체별로 근거가 가장 강한 행만 남긴다. 보고서 사유 문구에 쓴다."""
    if frame.empty or key not in frame.columns:
        return pd.DataFrame()
    return (
        frame.sort_values(order_by, ascending=False)
        .drop_duplicates(subset=[key])
        .reset_index(drop=True)
    )
