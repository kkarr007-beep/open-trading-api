"""검정·지수 계산 묶음.

내부망에는 scipy가 없는 경우가 있어, 있으면 쓰고 없으면 정규근사로 갈음한다.
근사치라도 순위를 매기는 목적에는 충분하다.
"""

from __future__ import annotations

import math

import numpy as np
import pandas as pd

try:  # scipy가 없어도 전체 분석이 돌아가야 한다.
    from scipy import stats as _scipy_stats
except ImportError:  # pragma: no cover - 환경에 따라 갈린다
    _scipy_stats = None

HAS_SCIPY = _scipy_stats is not None


def binom_tail(k: int, n: int, p: float) -> float:
    """P(X >= k). 기대 배당률 p 대비 실제 k건이 얼마나 나오기 힘든 값인지 본다."""
    if n <= 0 or k <= 0:
        return 1.0
    p = min(max(p, 1e-9), 1 - 1e-9)
    if k > n:
        return 0.0
    if HAS_SCIPY:
        return float(_scipy_stats.binom.sf(k - 1, n, p))

    mean = n * p
    sd = math.sqrt(n * p * (1 - p))
    if sd == 0:
        return 1.0 if k <= mean else 0.0
    z = (k - 0.5 - mean) / sd  # 연속성 보정
    return float(_normal_sf(z))


def _normal_sf(z: float) -> float:
    return 0.5 * math.erfc(z / math.sqrt(2.0))


def neg_log10(p: float) -> float:
    """p값을 점수로 쓰기 위해 뒤집는다. 작을수록 큰 값."""
    if p is None or not np.isfinite(p):
        return 0.0
    return float(-math.log10(max(p, 1e-300)))


def binom_tail_vector(k: np.ndarray, n: np.ndarray, p: np.ndarray) -> np.ndarray:
    """건수가 많을 때 한 번에 계산한다."""
    k = np.asarray(k, dtype=float)
    n = np.asarray(n, dtype=float)
    p = np.clip(np.asarray(p, dtype=float), 1e-9, 1 - 1e-9)

    if HAS_SCIPY:
        with np.errstate(invalid="ignore"):
            result = _scipy_stats.binom.sf(k - 1, n, p)
        return np.where(np.isfinite(result), result, 1.0)

    mean = n * p
    sd = np.sqrt(n * p * (1 - p))
    z = np.divide(k - 0.5 - mean, sd, out=np.zeros_like(mean), where=sd > 0)
    result = 0.5 * np.array([math.erfc(value / math.sqrt(2.0)) for value in z])
    return np.where(sd > 0, result, 1.0)


def hhi(counts: np.ndarray) -> float:
    """허핀달 지수. 한 사람의 배당이 몇 곳에 몰려 있는지 본다.

    0에 가까우면 고르게 분산, 1이면 한 곳에 전량 집중.
    """
    counts = np.asarray(counts, dtype=float)
    total = counts.sum()
    if total <= 0:
        return 0.0
    shares = counts / total
    raw = float((shares ** 2).sum())
    k = len(counts)
    if k <= 1:
        return 1.0
    # 거래처 수가 적으면 자연히 높게 나오므로 하한(1/k)을 걷어낸다.
    return max(0.0, (raw - 1.0 / k) / (1.0 - 1.0 / k))


def rank_biserial_z(group: np.ndarray, rest: np.ndarray) -> tuple[float, float]:
    """Mann-Whitney 순위합 z와 효과크기.

    금액은 한쪽으로 길게 늘어진 분포라 평균 비교가 왜곡된다. 순위로 본다.
    """
    group = np.asarray(group, dtype=float)
    rest = np.asarray(rest, dtype=float)
    group = group[np.isfinite(group)]
    rest = rest[np.isfinite(rest)]
    n1, n2 = len(group), len(rest)
    if n1 < 3 or n2 < 3:
        return 0.0, 0.0

    combined = np.concatenate([group, rest])
    ranks = pd.Series(combined).rank(method="average").to_numpy()
    r1 = ranks[:n1].sum()

    u1 = r1 - n1 * (n1 + 1) / 2.0
    mean = n1 * n2 / 2.0

    _, tie_counts = np.unique(combined, return_counts=True)
    tie_term = float(((tie_counts ** 3) - tie_counts).sum())
    n = n1 + n2
    variance = n1 * n2 / 12.0 * ((n + 1) - tie_term / (n * (n - 1)))
    if variance <= 0:
        return 0.0, 0.0

    z = (u1 - mean) / math.sqrt(variance)
    effect = 2.0 * u1 / (n1 * n2) - 1.0  # -1 ~ +1, 양수면 그룹이 더 큼
    return float(z), float(effect)


BENFORD_FIRST = np.array([math.log10(1 + 1 / d) for d in range(1, 10)])


def benford_first_digit(values: np.ndarray) -> dict[str, float]:
    """첫자리 분포 적합도. 금액이 사람 손을 탄 흔적을 본다."""
    values = np.asarray(values, dtype=float)
    values = values[np.isfinite(values) & (values > 0)]
    if len(values) == 0:
        return {"표본수": 0, "MAD": float("nan"), "카이제곱": float("nan")}

    leading = np.floor(values / (10 ** np.floor(np.log10(values)))).astype(int)
    leading = leading[(leading >= 1) & (leading <= 9)]
    if len(leading) == 0:
        return {"표본수": 0, "MAD": float("nan"), "카이제곱": float("nan")}

    observed = np.bincount(leading, minlength=10)[1:10].astype(float)
    total = observed.sum()
    expected = BENFORD_FIRST * total

    mad = float(np.abs(observed / total - BENFORD_FIRST).mean())
    chi2 = float((((observed - expected) ** 2) / expected).sum())
    return {"표본수": int(total), "MAD": mad, "카이제곱": chi2}


def standardized_residual(observed: np.ndarray, expected: np.ndarray) -> np.ndarray:
    """카이제곱 표준화 잔차. 기대보다 얼마나 튀는지를 부호와 함께 본다."""
    observed = np.asarray(observed, dtype=float)
    expected = np.asarray(expected, dtype=float)
    with np.errstate(divide="ignore", invalid="ignore"):
        residual = (observed - expected) / np.sqrt(expected)
    return np.where(np.isfinite(residual), residual, 0.0)


def percentile_score(
    series: pd.Series, higher_is_worse: bool = True, positive_only: bool = True
) -> pd.Series:
    """지표값을 0~100 백분위로 바꾼다. 지표마다 단위가 달라 그대로는 더할 수 없다.

    신호가 없는 대상(0 이하)은 순위 계산에서 빼고 0점을 준다. 함께 순위를 매기면
    신호가 없다는 사실만으로 중간 점수를 받아 진짜 이상치와의 간격이 뭉개진다.
    """
    values = pd.to_numeric(series, errors="coerce")
    mask = values > 0 if positive_only else values.notna()
    if mask.sum() == 0:
        return pd.Series(0.0, index=series.index)

    ranked = values[mask].rank(pct=True)
    if not higher_is_worse:
        ranked = 1.0 - ranked

    result = pd.Series(0.0, index=series.index)
    result.loc[mask] = ranked * 100
    return result


def two_proportion_z(
    count_a: np.ndarray, total_a: np.ndarray, count_b: np.ndarray, total_b: np.ndarray
) -> np.ndarray:
    """두 기간의 점유율 차이 검정. 조직의 주력 업체가 실제로 바뀌었는지 본다."""
    count_a = np.asarray(count_a, dtype=float)
    total_a = np.asarray(total_a, dtype=float)
    count_b = np.asarray(count_b, dtype=float)
    total_b = np.asarray(total_b, dtype=float)

    with np.errstate(divide="ignore", invalid="ignore"):
        pooled = (count_a + count_b) / (total_a + total_b)
        variance = pooled * (1 - pooled) * (1 / total_a + 1 / total_b)
        z = (count_a / total_a - count_b / total_b) / np.sqrt(variance)
    return np.where(np.isfinite(z), z, 0.0)


def signal_score(series: pd.Series, floor: float, saturation: float) -> pd.Series:
    """검정 통계량을 0~100으로 편다.

    백분위만 쓰면 차이가 없는 집단에서도 순위만으로 점수가 생긴다. 유의도에
    해당하는 하한 아래는 0으로 깔고, 충분히 강한 값에서 100으로 포화시킨다.
    그래야 '기대 20%인데 22%'가 상위 점수를 받는 일이 없다.
    """
    values = pd.to_numeric(series, errors="coerce").fillna(0.0)
    span = max(saturation - floor, 1e-9)
    return (((values - floor) / span).clip(lower=0.0, upper=1.0) * 100).astype(float)


def risk_band(
    score: float,
    peak: float,
    rules: tuple[tuple[float, float, str], ...],
    default: str,
) -> str:
    """혐의도와 최고지표 중 하나만 기준을 넘어도 그 등급으로 본다."""
    for score_floor, peak_floor, label in rules:
        if score >= score_floor or peak >= peak_floor:
            return label
    return default
