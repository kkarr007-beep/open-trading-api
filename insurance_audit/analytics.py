"""편중 대시보드용 집계.

기존 지표 모듈이 통계 검정으로 '혐의도 점수'를 냈다면, 여기서는 실무자가 바로
읽는 형태로 편중을 계산한다. 핵심은 세 가지다.

1. 누가(담당자·차상위자) 어느 손사법인·조사자에 얼마나 쏠렸나 — HHI와 Top-N 비율
2. 부서·팀 단위로는 어디에 쏠렸나
3. 팀·부서를 옮긴 뒤에도 같은 곳에 계속 쏠리나 — 이동 전후 Top1·HHI 비교

산출물은 전부 JSON으로 직렬화해 대시보드(HTML)가 클라이언트에서 그린다.
"""

from __future__ import annotations

import math

import numpy as np
import pandas as pd

from . import config


def _hhi(counts: pd.Series) -> float:
    """허핀달 지수. 각 상대 점유율의 제곱 합(0~1). 1이면 한 곳에 전부."""
    total = float(counts.sum())
    if total <= 0:
        return 0.0
    shares = counts.to_numpy(dtype=float) / total
    return float(np.square(shares).sum())


def _hhi_grade(value: float) -> str:
    if value >= config.HHI_SEVERE:
        return "심각"
    if value >= config.HHI_HIGH:
        return "고편중"
    return "보통"


def _top_breakdown(counts: pd.Series, n: int) -> list[dict]:
    """상대별 건수·비율 상위 n개. 비율은 퍼센트."""
    total = float(counts.sum())
    rows = []
    for name, cnt in counts.sort_values(ascending=False).head(n).items():
        rows.append(
            {
                "명": str(name),
                "건수": int(cnt),
                "비율": round(float(cnt) / total * 100, 1) if total else 0.0,
            }
        )
    return rows


# ──────────────────────────────────────────────────────────────
# 1. 손사법인별 전체 배당 비율
# ──────────────────────────────────────────────────────────────


def vendor_shares(cases: pd.DataFrame) -> pd.DataFrame:
    """법인별 건수·금액 비율과 전체 평균 대비 배수."""
    if "법인_명" not in cases.columns:
        return pd.DataFrame()
    work = cases.dropna(subset=["법인_id"])
    if work.empty:
        return pd.DataFrame()

    amount = "지급보험금" if "지급보험금" in work.columns else None
    grouped = work.groupby("법인_명", sort=False)
    table = grouped.size().reset_index(name="건수")
    if amount:
        amt = grouped[amount].sum().reset_index(name="금액")
        table = table.merge(amt, on="법인_명", how="left")
    else:
        table["금액"] = 0.0

    total_cnt = table["건수"].sum()
    total_amt = table["금액"].sum()
    n_vendors = len(table)
    avg_share = 100.0 / n_vendors if n_vendors else 0.0

    table["건수비율"] = (table["건수"] / total_cnt * 100).round(2)
    table["금액비율"] = (table["금액"] / total_amt * 100).round(2) if total_amt else 0.0
    table["평균대비배수"] = (table["건수비율"] / avg_share).round(2) if avg_share else 0.0
    table["편중의심"] = table["평균대비배수"] >= config.VENDOR_RATIO_FLAG
    # 건수 비율과 금액 비율이 벌어지면(고액 편중) 따로 표시할 근거가 된다.
    table["비율차"] = (table["금액비율"] - table["건수비율"]).round(2)
    return table.sort_values("건수", ascending=False).reset_index(drop=True)


# ──────────────────────────────────────────────────────────────
# 2. 인물별(담당자·차상위자) 편중
# ──────────────────────────────────────────────────────────────


def person_concentration(cases: pd.DataFrame, role: str) -> pd.DataFrame:
    """인물별 법인·조사자 HHI와 Top-N. role 은 '담당자' 또는 '결재자'."""
    key, name = f"{role}_id", f"{role}_명"
    if key not in cases.columns or "법인_id" not in cases.columns:
        return pd.DataFrame()

    work = cases.dropna(subset=[key, "법인_id"])
    if work.empty:
        return pd.DataFrame()

    has_inv = "조사자_명" in work.columns
    rows = []
    for pid, block in work.groupby(key, sort=False):
        total = len(block)
        if total < config.MIN_CASES_PERSON:
            continue

        vendor_counts = block["법인_명"].value_counts()
        v_hhi = _hhi(vendor_counts)
        top_v = _top_breakdown(vendor_counts, config.TOP_VENDORS_PER_PERSON)
        top1 = top_v[0] if top_v else {"명": "", "비율": 0.0, "건수": 0}

        record = {
            "id": str(pid),
            "명": str(block[name].iloc[0]) if name in block.columns else str(pid),
            "부서": _first(block, "소속2"),
            "팀": _first(block, "소속3"),
            "총건수": total,
            "법인HHI": round(v_hhi, 3),
            "법인편중": _hhi_grade(v_hhi),
            "거래법인수": int(vendor_counts.size),
            "최다법인": top1["명"],
            "최다법인비율": top1["비율"],
            "최다법인건수": top1["건수"],
            "top법인": top_v,
        }

        if has_inv:
            inv_counts = block.dropna(subset=["조사자_명"])["조사자_명"].value_counts()
            if inv_counts.size:
                i_hhi = _hhi(inv_counts)
                top_i = _top_breakdown(inv_counts, config.TOP_VENDORS_PER_PERSON)
                record.update(
                    {
                        "조사자HHI": round(i_hhi, 3),
                        "조사자편중": _hhi_grade(i_hhi),
                        "최다조사자": top_i[0]["명"],
                        "최다조사자비율": top_i[0]["비율"],
                        "조사자편중의심": top_i[0]["비율"] >= config.INVESTIGATOR_SHARE_FLAG * 100,
                        "top조사자": top_i,
                    }
                )
        rows.append(record)

    table = pd.DataFrame(rows)
    if table.empty:
        return table
    return table.sort_values("법인HHI", ascending=False).reset_index(drop=True)


def _first(block: pd.DataFrame, column: str) -> str:
    if column not in block.columns:
        return ""
    values = block[column].dropna()
    return str(values.iloc[0]) if len(values) else ""


# ──────────────────────────────────────────────────────────────
# 3. 조직별(부서·팀) 편중
# ──────────────────────────────────────────────────────────────


def org_concentration(cases: pd.DataFrame, level: str) -> pd.DataFrame:
    """부서(소속2)·팀(소속3)별 법인 HHI, 최다 법인, Top-N 분포."""
    if level not in cases.columns or "법인_명" not in cases.columns:
        return pd.DataFrame()
    work = cases.dropna(subset=[level, "법인_id"])
    if work.empty:
        return pd.DataFrame()

    rows = []
    for org, block in work.groupby(level, sort=False):
        total = len(block)
        vendor_counts = block["법인_명"].value_counts()
        v_hhi = _hhi(vendor_counts)
        top_v = _top_breakdown(vendor_counts, config.TOP_VENDORS_PER_PERSON)
        rows.append(
            {
                "조직": str(org),
                "총건수": total,
                "인원수": int(block[f"담당자_id"].nunique()) if "담당자_id" in block.columns else 0,
                "법인HHI": round(v_hhi, 3),
                "법인편중": _hhi_grade(v_hhi),
                "최다법인": top_v[0]["명"] if top_v else "",
                "최다법인비율": top_v[0]["비율"] if top_v else 0.0,
                "top법인": top_v,
            }
        )
    table = pd.DataFrame(rows)
    if table.empty:
        return table
    return table.sort_values("법인HHI", ascending=False).reset_index(drop=True)


def org_member_matrix(cases: pd.DataFrame, level: str, org: str) -> dict:
    """한 조직 안 담당자 × 법인 건수 히트맵. 팀 내 개인별 쏠림 비교용."""
    if level not in cases.columns:
        return {}
    block = cases[(cases[level] == org)].dropna(subset=["담당자_id", "법인_id"])
    if block.empty:
        return {}

    # 팀 안에서 많이 쓰인 법인만 열로 둔다. 너무 많으면 히트맵이 못 읽힌다.
    top_vendors = block["법인_명"].value_counts().head(8).index.tolist()
    members = (
        block.groupby(["담당자_id", "담당자_명"], sort=False).size()
        .sort_values(ascending=False).head(20)
    )
    pivot = (
        block[block["법인_명"].isin(top_vendors)]
        .groupby(["담당자_명", "법인_명"], sort=False).size()
        .unstack(fill_value=0)
    )
    rows = []
    for _, name in members.index:
        if name not in pivot.index:
            continue
        series = pivot.loc[name].reindex(top_vendors, fill_value=0)
        total = int(series.sum())
        rows.append(
            {
                "명": str(name),
                "총건수": total,
                "건수": [int(v) for v in series.tolist()],
            }
        )
    return {"vendors": [str(v) for v in top_vendors], "members": rows}


# ──────────────────────────────────────────────────────────────
# 4. 팀·부서 이동 후 편중 유지 추적
# ──────────────────────────────────────────────────────────────


def mobility_tracking(cases: pd.DataFrame, role: str, level: str) -> pd.DataFrame:
    """인물이 조직(팀/부서)을 옮긴 전후로 편중이 유지되는지 추적한다.

    데이터가 연도 단위이므로 claim_date 대신 연도 순서로 구간을 나눈다.
    변경 전(첫 소속) 구간과 변경 후(마지막 소속) 구간으로 갈라 각 구간의
    Top1 법인·HHI를 비교한다. Top1 법인이 같으면 편중 유지로 본다.
    """
    key, name = f"{role}_id", f"{role}_명"
    order = "연도" if "연도" in cases.columns else None
    if key not in cases.columns or level not in cases.columns or "법인_명" not in cases.columns:
        return pd.DataFrame()

    work = cases.dropna(subset=[key, level, "법인_id"]).copy()
    if order:
        work = work.dropna(subset=[order])
    if work.empty:
        return pd.DataFrame()

    rows = []
    for pid, block in work.groupby(key, sort=False):
        # 인물이 실제로 거친 소속 순서. 각 소속에서 최소 건수를 채운 것만 발령으로 본다.
        postings = _ordered_postings(block, level, order)
        if len(postings) < 2:
            continue

        before_org, before = postings[0]
        after_org, after = postings[-1]
        b_seg = _segment_stats(before)
        a_seg = _segment_stats(after)
        if b_seg is None or a_seg is None:
            continue

        kept = b_seg["top1"] == a_seg["top1"] and b_seg["top1"] != ""
        rows.append(
            {
                "id": str(pid),
                "명": str(block[name].iloc[0]) if name in block.columns else str(pid),
                "이전소속": str(before_org),
                "이후소속": str(after_org),
                "이전기간": b_seg["기간"],
                "이후기간": a_seg["기간"],
                "이전건수": b_seg["건수"],
                "이후건수": a_seg["건수"],
                "이전Top1법인": b_seg["top1"],
                "이후Top1법인": a_seg["top1"],
                "이전Top1비율": b_seg["top1비율"],
                "이후Top1비율": a_seg["top1비율"],
                "이전HHI": b_seg["hhi"],
                "이후HHI": a_seg["hhi"],
                "HHI변화": round(a_seg["hhi"] - b_seg["hhi"], 3),
                "편중유지": kept,
                "이전top법인": b_seg["top"],
                "이후top법인": a_seg["top"],
            }
        )

    table = pd.DataFrame(rows)
    if table.empty:
        return table
    # 편중 유지 + 이후 HHI 높은 순으로. 소명 우선순위다.
    return table.sort_values(
        ["편중유지", "이후HHI"], ascending=[False, False]
    ).reset_index(drop=True)


def _ordered_postings(block: pd.DataFrame, level: str, order: str | None):
    """(소속, 그 소속 건들)을 시간 순으로. 최소 건수 못 채운 소속은 버린다."""
    if order:
        first_year = block.groupby(level)[order].min()
        seq = first_year.sort_values().index.tolist()
    else:
        seq = block[level].value_counts().index.tolist()

    postings = []
    for org in seq:
        seg = block[block[level] == org]
        if len(seg) >= config.MIN_CASES_PER_POSTING:
            postings.append((org, seg))
    return postings


def _segment_stats(seg: pd.DataFrame) -> dict | None:
    counts = seg["법인_명"].value_counts()
    if counts.empty:
        return None
    top = _top_breakdown(counts, config.TOP_VENDORS_PER_PERSON)
    years = seg["연도"].dropna() if "연도" in seg.columns else pd.Series(dtype=int)
    span = ""
    if len(years):
        lo, hi = int(years.min()), int(years.max())
        span = f"{lo}" if lo == hi else f"{lo}~{hi}"
    return {
        "건수": int(len(seg)),
        "hhi": round(_hhi(counts), 3),
        "top1": top[0]["명"],
        "top1비율": top[0]["비율"],
        "기간": span,
        "top": top,
    }


# ──────────────────────────────────────────────────────────────
# 통합
# ──────────────────────────────────────────────────────────────


def build(data: dict[str, pd.DataFrame]) -> dict:
    """대시보드에 넘길 모든 표를 한데 모은다."""
    cases = data["cases"]
    result = {
        "vendor_shares": vendor_shares(cases),
        "담당자": person_concentration(cases, "담당자"),
        "결재자": person_concentration(cases, "결재자"),
        "부서": org_concentration(cases, "소속2"),
        "팀": org_concentration(cases, "소속3"),
        "이동_담당자_팀": mobility_tracking(cases, "담당자", "소속3"),
        "이동_담당자_부서": mobility_tracking(cases, "담당자", "소속2"),
        "이동_결재자_팀": mobility_tracking(cases, "결재자", "소속3"),
        "이동_결재자_부서": mobility_tracking(cases, "결재자", "소속2"),
    }
    return result


def _json_safe(value):
    if isinstance(value, float):
        return None if (math.isnan(value) or math.isinf(value)) else value
    if isinstance(value, (np.integer,)):
        return int(value)
    if isinstance(value, (np.floating,)):
        v = float(value)
        return None if math.isnan(v) else v
    if isinstance(value, (np.bool_,)):
        return bool(value)
    return value


def to_records(frame: pd.DataFrame) -> list[dict]:
    if not isinstance(frame, pd.DataFrame) or frame.empty:
        return []
    out = []
    for record in frame.to_dict(orient="records"):
        out.append({k: _json_safe(v) for k, v in record.items()})
    return out
