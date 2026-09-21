"""실행 진입점.

    python -m insurance_audit.main --input 조사배당.csv --outdir 결과

시트가 나뉘어 반출됐다면 파일을 여러 개 넘기면 이어 붙인다.
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import pandas as pd

from . import config, console, loader, prep, profile, report_html, report_xlsx, score, stats
from .metrics import MODULES


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="insurance_audit",
        description="외부조사 법인 유착 혐의 1차 발굴",
    )
    parser.add_argument(
        "--input", "-i", nargs="+", required=True,
        help="조사 배당 데이터 (CSV 또는 엑셀). 여러 개를 넘기면 이어 붙입니다.",
    )
    parser.add_argument(
        "--outdir", "-o", default="결과",
        help="보고서를 저장할 폴더 (기본: 결과)",
    )
    parser.add_argument(
        "--encoding", "-e", default=None,
        help="CSV 인코딩을 직접 지정 (예: cp949). 생략하면 자동 판별합니다.",
    )
    parser.add_argument(
        "--year", "-y", nargs="+", type=int, default=None,
        help="분석할 기준년. 생략하면 전체. 예: --year 2024 2025",
    )
    parser.add_argument(
        "--org-level", default=None, choices=("소속1", "소속2", "소속3"),
        help="조직별 집계와 전환 분석에 쓸 소속 계층. 생략하면 표본을 보고 정합니다.",
    )
    parser.add_argument(
        "--prefix", default="유착분석",
        help="산출 파일 이름 앞에 붙일 문구",
    )
    return parser


def run(argv: list[str] | None = None) -> int:
    console.setup()
    args = build_parser().parse_args(argv)

    try:
        raw = loader.read_many(args.input, args.encoding)
    except (loader.ColumnError, FileNotFoundError) as error:
        print(f"오류: {error}", file=sys.stderr)
        return 1

    return run_pipeline(
        raw,
        outdir=args.outdir,
        years=args.year,
        org_level=args.org_level,
        prefix=args.prefix,
    )


def run_pipeline(
    raw: pd.DataFrame,
    *,
    outdir: str = "결과",
    years: list[int] | None = None,
    org_level: str | None = None,
    prefix: str = "유착분석",
) -> int:
    """데이터프레임부터 보고서 산출까지. CLI와 GUI가 함께 쓴다."""
    started = time.time()
    print(f"[1/5] 데이터 확인: {len(raw):,}행 / {len(raw.columns)}개 컬럼")
    column_check = loader.describe_columns(raw)

    print("[2/5] 전처리 및 분석 단위 분리 중...")
    data = prep.prepare(raw)
    print(
        f"      지급 {len(data['payments']):,}행 → 조사 배당 {len(data['cases']):,}건"
    )

    avail_years = profile.available_years(data["cases"])
    if years:
        unknown = sorted(set(years) - set(avail_years))
        if unknown:
            print(
                f"오류: 데이터에 없는 기준년입니다: {unknown} (가능: {avail_years})",
                file=sys.stderr,
            )
            return 1
        data = profile.filter_years(data, years)
        print(f"      기준년 {', '.join(map(str, years))} 선택 → {len(data['cases']):,}건")
    elif avail_years:
        print(f"      기준년 {avail_years[0]}~{avail_years[-1]} 전체 ({len(avail_years)}개 연도)")

    if data["cases"].empty:
        print("오류: 선택한 조건에 해당하는 건이 없습니다.", file=sys.stderr)
        return 1
    data["옵션"] = {"조직계층": org_level}
    if not stats.HAS_SCIPY:
        print("      (scipy 없음: 정규근사로 검정합니다)")

    print("[3/5] 지표 산출 중...")
    results = []
    for module in MODULES:
        try:
            result = module.run(data)
        except Exception as error:
            print(f"      {module.NAME}: 실패 ({error})", file=sys.stderr)
            continue
        results.append(result)
        mark = "O" if result.scores else "-"
        print(f"      [{mark}] {result.name} {result.note}")

    print("[4/5] 혐의도 집계 중...")
    rankings = score.build_rankings(results, data)
    drill = score.drilldown(rankings, data)
    for entity, frame in rankings.items():
        high = int((frame["등급"] == "높음").sum())
        print(f"      {entity}: {len(frame):,}건 (높음 {high}건)")

    print("[5/5] 현황 집계 및 보고서 저장 중...")
    views = _build_views(data, rankings, org_level)
    outdir_path = Path(outdir)
    stamp = time.strftime("%Y%m%d_%H%M")
    xlsx_path = report_xlsx.write(
        outdir_path / f"{prefix}_{stamp}.xlsx",
        rankings, results, data, drill, column_check, views,
    )
    html_path = report_html.write(
        outdir_path / f"{prefix}_{stamp}.html", rankings, results, data, views
    )

    print(f"\n완료 ({time.time() - started:.1f}초)")
    print(f"  엑셀: {xlsx_path}")
    print(f"  HTML: {html_path}")
    return 0


def _build_views(
    data: dict[str, pd.DataFrame],
    rankings: dict[str, pd.DataFrame],
    org_level: str | None,
) -> dict[str, object]:
    """현황 표를 한데 모은다. 엑셀 시트와 HTML 도해가 같은 표를 쓴다."""
    cases = data["cases"]
    level = org_level or profile.org_level_for(cases)

    top_handlers = (
        rankings["담당자"]["id"].head(config.CHART_TOP_HANDLERS).tolist()
        if "담당자" in rankings and not rankings["담당자"].empty
        else []
    )

    return {
        "조직계층": level,
        "연도별업체": profile.vendor_by_year(cases),
        "업체연도행렬": profile.vendor_share_matrix(cases, config.CHART_TOP_VENDORS),
        "소속2별업체": profile.vendor_by_org(cases, "소속2"),
        "소속3별업체": profile.vendor_by_org(cases, "소속3"),
        "조직별업체": profile.vendor_by_org(cases, level),
        "담당자별업체": profile.handler_vendor_mix(cases, top_handlers),
    }


if __name__ == "__main__":
    raise SystemExit(run())
