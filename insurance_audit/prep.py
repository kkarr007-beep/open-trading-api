"""전처리와 분석 단위 분리.

이 파일의 핵심은 단위 분리다. 반출된 표는 한 청구건이 담보별·지급처별로 여러
행에 나뉘어 있어 행을 그대로 세면 조사 건수가 아니라 지급 건수가 잡힌다.
배당 편중을 행 단위로 집계하면 지급처가 많은 사고가 상위로 올라올 뿐이므로
청구번호 단위로 접은 표를 따로 만들어 쓴다.
"""

from __future__ import annotations

import re
import warnings

import numpy as np
import pandas as pd

from . import config

_DIGITS = re.compile(r"\D")

# DB 반출 값에 제어문자가 섞여 오는 경우가 있다. 그대로 두면 분석을 다 끝낸 뒤
# 엑셀 저장 단계에서 openpyxl 이 거부해 결과가 통째로 날아간다. 적재 때 걷어낸다.
_CONTROL_CHARS = r"[\x00-\x08\x0b-\x0c\x0e-\x1f\x7f]"


def _parse_dates(series: pd.Series) -> pd.Series:
    text = series.astype("string").str.strip()
    # 날짜에 시각이 붙어 오는 경우가 있어 앞 토큰만 쓴다.
    text = text.str.split().str[0]
    compact = text.str.replace(r"[^0-9]", "", regex=True)

    parsed = pd.to_datetime(compact.where(compact.str.len() == 8), format="%Y%m%d", errors="coerce")
    remaining = parsed.isna() & text.notna()
    if remaining.any():
        # 표기가 제각각인 나머지를 pandas 추론에 맡긴다. 못 읽으면 NaT 로 두면 되므로
        # 형식이 모호하다는 경고까지 화면에 띄울 필요는 없다.
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            parsed.loc[remaining] = pd.to_datetime(text[remaining], errors="coerce")
    return parsed


def _parse_numeric(series: pd.Series) -> pd.Series:
    text = series.astype("string").str.strip()
    # 콤마와 원 표기가 섞여 들어와도 받아낸다. 괄호 음수 표기도 처리.
    negative = text.str.startswith("(") & text.str.endswith(")")
    text = text.str.replace(r"[^\d.\-]", "", regex=True)
    value = pd.to_numeric(text, errors="coerce")
    value = value.mask(negative, -value.abs())
    return value


def _identity(frame: pd.DataFrame, role: str) -> pd.Series:
    """사번·코드가 있으면 그쪽을 식별자로 쓴다. 동명이인을 한 사람으로 묶지 않기 위함."""
    for column in config.IDENTITY_PREFERENCE[role]:
        if column in frame.columns:
            series = frame[column].astype("string").str.strip()
            if series.notna().any():
                return series.replace("", pd.NA)
    return pd.Series(pd.NA, index=frame.index, dtype="string")


def _label(frame: pd.DataFrame, role: str) -> pd.Series:
    """보고서에 띄울 이름. 이름 컬럼이 없으면 식별자를 그대로 쓴다."""
    name_column = config.IDENTITY_PREFERENCE[role][-1]
    if name_column in frame.columns:
        series = frame[name_column].astype("string").str.strip().replace("", pd.NA)
        if series.notna().any():
            return series
    return _identity(frame, role)


def prepare(frame: pd.DataFrame) -> dict[str, pd.DataFrame]:
    """원본 표를 지급 단위와 배당 단위로 나눠 돌려준다."""
    payments = frame.copy()

    for column in config.DATE_COLUMNS:
        if column in payments.columns:
            payments[column] = _parse_dates(payments[column])

    for column in config.NUMERIC_COLUMNS:
        if column in payments.columns:
            payments[column] = _parse_numeric(payments[column])

    for column in payments.columns:
        if payments[column].dtype == object:
            payments[column] = (
                payments[column]
                .astype("string")
                .str.replace(_CONTROL_CHARS, "", regex=True)
                .str.strip()
                .replace("", pd.NA)
            )

    for role in ("담당자", "결재자", "법인", "조사자"):
        payments[f"{role}_id"] = _identity(payments, role)
        payments[f"{role}_명"] = _label(payments, role)

    payments["청구번호"] = payments["청구번호"].astype("string").str.strip()

    # 소속은 가장 아래 단계(점포)를 비교 기준으로 쓴다. 기대 배당률을 낼 때
    # 전사 평균이 아니라 같은 점포 안에서 비교해야 업무 특성 차이를 걷어낼 수 있다.
    payments["소속_id"] = _first_available(
        payments, ("소속3코드", "소속3", "소속2코드", "소속2", "소속1코드", "소속1")
    )
    payments["소속_명"] = _first_available(
        payments, ("소속3", "소속3코드", "소속2", "소속2코드", "소속1", "소속1코드")
    )

    payments["연도"] = _resolve_year(payments)
    payments = _add_intervals(payments)
    cases = _collapse_to_cases(payments)
    return {"payments": payments, "cases": cases}


def _resolve_year(frame: pd.DataFrame) -> pd.Series:
    """분석 기준 연도. 기준년 컬럼을 우선하고 없으면 사고일에서 뽑는다."""
    year = pd.Series(pd.NA, index=frame.index, dtype="Int64")
    if "기준년" in frame.columns:
        digits = frame["기준년"].astype("string").str.replace(r"\D", "", regex=True)
        year = pd.to_numeric(digits.str[:4], errors="coerce").astype("Int64")
    if "사고일" in frame.columns:
        year = year.fillna(frame["사고일"].dt.year.astype("Int64"))
    return year


def _first_available(frame: pd.DataFrame, candidates: tuple[str, ...]) -> pd.Series:
    result = pd.Series(pd.NA, index=frame.index, dtype="string")
    for column in candidates:
        if column in frame.columns:
            result = result.fillna(frame[column].astype("string"))
    return result.fillna("미상")


def _add_intervals(frame: pd.DataFrame) -> pd.DataFrame:
    def delta(later: str, earlier: str) -> pd.Series:
        if later in frame.columns and earlier in frame.columns:
            return (frame[later] - frame[earlier]).dt.days
        return pd.Series(np.nan, index=frame.index)

    frame["결재소요일"] = delta("결재일", "상신일")
    frame["사고후상신일"] = delta("상신일", "사고일")
    frame["상신후지급일"] = delta("지급일", "상신일")
    frame["사고후지급일"] = delta("지급일", "사고일")

    # 역전된 값은 입력 오류이거나 재상신 건이다. 통계를 흔들므로 결측 처리한다.
    for column in ("결재소요일", "사고후상신일", "상신후지급일", "사고후지급일"):
        frame[column] = frame[column].where(frame[column] >= 0)
    return frame


def _collapse_to_cases(payments: pd.DataFrame) -> pd.DataFrame:
    """청구번호×법인 단위로 접는다. 이것이 조사 배당 1건에 해당한다."""
    keys = ["청구번호", "법인_id"]

    # 합의금은 피해자별로 확정되는 금액이라 지급 행마다 같은 값이 반복된다.
    # 행을 그대로 더하면 부풀려지므로 피해자 단위로 접은 뒤 합산한다.
    settlement = _settlement_by_case(payments, keys)

    aggregations: dict[str, tuple[str, str]] = {"지급건수": ("청구번호", "size")}
    if "지급보험금" in payments.columns:
        aggregations["지급보험금"] = ("지급보험금", "sum")

    carry = [
        "담당자_id", "담당자_명", "결재자_id", "결재자_명", "법인_명",
        "조사자_id", "조사자_명", "소속_id", "소속_명",
        "조사결과", "조사결과코드", "조사방법", "조사방법코드",
        "담보구분", "담보구분코드", "사고원인", "사고원인코드",
        "피해유형", "피해유형코드", "대표재해코드", "상품코드",
        "구상여부", "구상여부코드", "피보험자id", "피보험자",
        "사고일", "상신일", "결재일", "지급일", "기준년", "연도",
        "소속1", "소속2", "소속2코드", "소속3", "소속3코드",
        "결재소요일", "사고후상신일", "상신후지급일", "사고후지급일",
    ]
    for column in carry:
        if column in payments.columns:
            aggregations[column] = (column, "first")

    cases = payments.groupby(keys, dropna=False, sort=False).agg(**aggregations).reset_index()
    cases = cases.merge(settlement, on=keys, how="left")

    cases["총수령액"] = cases.get(
        "지급보험금", pd.Series(0.0, index=cases.index)
    ).fillna(0) + cases["합의금"].fillna(0)
    return cases


def _settlement_by_case(payments: pd.DataFrame, keys: list[str]) -> pd.DataFrame:
    columns = [c for c in ("대인합의금", "대물합의금") if c in payments.columns]
    if not columns:
        return pd.DataFrame({**{k: [] for k in keys}, "합의금": []})

    victim_keys = keys + [c for c in ("피해자id", "피해유형코드") if c in payments.columns]
    per_victim = payments.drop_duplicates(subset=victim_keys)
    per_victim = per_victim.assign(합의금=per_victim[columns].sum(axis=1, min_count=1))
    return per_victim.groupby(keys, dropna=False, sort=False)["합의금"].sum().reset_index()
