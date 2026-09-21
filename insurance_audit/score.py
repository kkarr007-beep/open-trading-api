"""지표 점수를 주체별 혐의도로 합친다.

지표마다 단위가 달라 그대로 더할 수 없으므로 각 지표 안에서 백분위로 바꾼 뒤
가중합한다. 어떤 주체에게 해당 지표가 잡히지 않았다면 그 지표는 0으로 둔다.
"""

from __future__ import annotations

import pandas as pd

from . import config, stats
from .metrics.base import MetricResult


def build_rankings(
    results: list[MetricResult], data: dict[str, pd.DataFrame]
) -> dict[str, pd.DataFrame]:
    volumes = _case_volumes(data["cases"])
    rankings: dict[str, pd.DataFrame] = {}

    for entity, metric_names in config.ENTITY_METRICS.items():
        frames = {}
        scales = {}
        for result in results:
            if entity in result.scores and result.name in metric_names:
                frames[result.name] = result.scores[entity]
                if entity in result.scales:
                    scales[result.name] = result.scales[entity]
        if not frames:
            continue
        rankings[entity] = _combine(entity, frames, scales, volumes.get(entity))

    return rankings


def _case_volumes(cases: pd.DataFrame) -> dict[str, pd.Series]:
    """주체별 담당 건수. 점수 옆에 모수를 같이 보여줘야 해석이 된다."""
    volumes: dict[str, pd.Series] = {}
    for entity, column in (("담당자", "담당자_id"), ("결재자", "결재자_id"), ("법인", "법인_id")):
        if column in cases.columns:
            volumes[entity] = cases.groupby(column, sort=False).size()
    return volumes


def _combine(
    entity: str,
    frames: dict[str, pd.DataFrame],
    scales: dict[str, tuple[float, float]],
    volume: pd.Series | None,
) -> pd.DataFrame:
    names: dict[str, str] = {}
    converted: dict[str, pd.Series] = {}
    reasons: dict[str, pd.Series] = {}

    for metric, frame in frames.items():
        frame = frame.dropna(subset=["id"]).drop_duplicates(subset=["id"])
        indexed = frame.set_index("id")
        scale = scales.get(metric)
        # 척도가 있으면 유의도 기준으로 환산하고, 없으면 순위로 갈음한다.
        converted[metric] = (
            stats.signal_score(indexed["점수"], *scale)
            if scale
            else stats.percentile_score(indexed["점수"])
        )
        reasons[metric] = indexed["사유"]
        names.update(indexed["명"].astype(str).to_dict())

    raw = pd.DataFrame(converted)
    if raw.empty:
        return raw

    # 어느 지표에 해당 기록이 있었는지. 조합 가중치를 다시 나눌 때 쓴다.
    present = raw.notna()
    table = raw.fillna(0.0)

    weights = pd.Series(
        {metric: config.METRIC_WEIGHTS[metric] for metric in table.columns}
    )
    if entity in config.ROWWISE_WEIGHT_ENTITIES:
        divisor = present.mul(weights, axis=1).sum(axis=1).replace(0, pd.NA)
    else:
        divisor = float(weights.sum())

    breadth = table.mul(weights, axis=1).sum(axis=1) / divisor
    peak = table.max(axis=1)

    table["가중합"] = breadth.fillna(0.0).round(1)
    table["최고지표"] = peak.round(1)
    table["혐의도"] = (
        breadth.fillna(0.0) * config.BREADTH_WEIGHT + peak * config.PEAK_WEIGHT
    ).round(1)

    table = table.reset_index().rename(columns={"index": "id"})
    table.insert(1, "명", table["id"].map(names).fillna(table["id"]))
    if volume is not None:
        table.insert(2, "담당건수", table["id"].map(volume).fillna(0).astype(int))

    table["주요사유"] = _summarize(table, reasons, weights.to_dict())
    table["등급"] = [
        stats.risk_band(row["혐의도"], row["최고지표"], config.RISK_RULES, config.DEFAULT_BAND)
        for _, row in table.iterrows()
    ]

    ordered = ["id", "명"]
    if "담당건수" in table.columns:
        ordered.append("담당건수")
    ordered += ["혐의도", "등급", "주요사유", "가중합", "최고지표"] + list(frames)
    return table[ordered].sort_values("혐의도", ascending=False).reset_index(drop=True)


def _summarize(
    table: pd.DataFrame, reasons: dict[str, pd.Series], weights: dict[str, float]
) -> pd.Series:
    """근거 두 줄을 붙인다.

    첫 줄은 가장 강한 신호, 둘째 줄은 점수 기여가 가장 큰 다른 지표로 잡는다.
    가중 기여만 보면 가중치가 낮은 지표에서 터진 극단값이 사유에서 빠진다.
    """
    summaries = []
    for _, row in table.iterrows():
        active = [metric for metric in weights if row.get(metric, 0) > 0]
        if not active:
            summaries.append("")
            continue

        primary = max(active, key=lambda metric: row[metric])
        rest = [metric for metric in active if metric != primary]
        picked = [primary]
        if rest:
            picked.append(max(rest, key=lambda metric: row[metric] * weights[metric]))

        parts = []
        for metric in picked:
            text = reasons[metric].get(row["id"])
            if isinstance(text, str) and text and text != "특이사항 없음":
                parts.append(f"[{metric.split('_')[1]}] {text}")
        summaries.append(" / ".join(parts))
    return pd.Series(summaries, index=table.index)


def drilldown(
    rankings: dict[str, pd.DataFrame], data: dict[str, pd.DataFrame]
) -> pd.DataFrame:
    """상위 혐의 담당자의 건별 목록. 소명 요청에 그대로 쓸 수 있도록 뽑는다."""
    cases = data["cases"]
    if "담당자" not in rankings or rankings["담당자"].empty:
        return pd.DataFrame()

    top = rankings["담당자"].head(config.DRILLDOWN_TOP_N)
    targets = set(top["id"])
    subset = cases[cases["담당자_id"].isin(targets)].copy()
    if subset.empty:
        return pd.DataFrame()

    scores = top.set_index("id")["혐의도"]
    subset["담당자혐의도"] = subset["담당자_id"].map(scores)

    columns = [
        "담당자혐의도", "소속_명", "담당자_명", "결재자_명", "법인_명", "조사자_명",
        "청구번호", "사고일", "담보구분", "사고원인", "조사결과", "조사방법",
        "상신일", "결재일", "결재소요일", "지급일", "지급보험금", "합의금", "총수령액",
        "구상여부",
    ]
    available = [column for column in columns if column in subset.columns]
    return subset[available].sort_values(
        ["담당자혐의도", "담당자_명", "법인_명"], ascending=[False, True, True]
    ).reset_index(drop=True)
