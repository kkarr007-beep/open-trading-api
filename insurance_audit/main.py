"""실행 진입점.

    python -m insurance_audit.main --input 조사배당.csv --outdir 결과

시트가 나뉘어 반출됐다면 파일을 여러 개 넘기면 이어 붙인다.
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

from . import loader, prep, report_html, report_xlsx, score, stats
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
        "--prefix", default="유착분석",
        help="산출 파일 이름 앞에 붙일 문구",
    )
    return parser


def run(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    started = time.time()

    print("[1/5] 데이터 읽는 중...")
    try:
        raw = loader.read_many(args.input, args.encoding)
    except (loader.ColumnError, FileNotFoundError) as error:
        print(f"오류: {error}", file=sys.stderr)
        return 1
    print(f"      {len(raw):,}행 / {len(raw.columns)}개 컬럼 인식")

    column_check = loader.describe_columns(raw)

    print("[2/5] 전처리 및 분석 단위 분리 중...")
    data = prep.prepare(raw)
    print(
        f"      지급 {len(data['payments']):,}행 → 조사 배당 {len(data['cases']):,}건"
    )
    if not stats.HAS_SCIPY:
        print("      (scipy 없음: 정규근사로 검정합니다)")

    print("[3/5] 지표 산출 중...")
    results = []
    for module in MODULES:
        try:
            result = module.run(data)
        except Exception as error:  # 한 지표가 죽어도 나머지는 살린다
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

    print("[5/5] 보고서 저장 중...")
    outdir = Path(args.outdir)
    stamp = time.strftime("%Y%m%d_%H%M")
    xlsx_path = report_xlsx.write(
        outdir / f"{args.prefix}_{stamp}.xlsx", rankings, results, data, drill, column_check
    )
    html_path = report_html.write(
        outdir / f"{args.prefix}_{stamp}.html", rankings, results, data
    )

    print(f"\n완료 ({time.time() - started:.1f}초)")
    print(f"  엑셀: {xlsx_path}")
    print(f"  HTML: {html_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(run())
