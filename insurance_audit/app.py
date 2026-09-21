"""더블클릭 실행용 진입점.

명령창 사용이 익숙하지 않은 환경을 위해 묻고 답하는 방식으로 진행한다.
CSV 파일을 실행 파일 위에 끌어다 놓아도 되고, 그냥 두 번 눌러 경로를 적어도 된다.

실행 파일(exe)로 묶을 때 이 파일이 시작점이 된다.
"""

from __future__ import annotations

import sys
import traceback
from pathlib import Path

BANNER = """
==================================================
  외부조사 법인 유착 혐의 1차 분석
==================================================
"""


def _prompt(message: str) -> str:
    """입력을 받되 끝에 도달하면 빈 답으로 본다.

    자동 점검처럼 입력을 미리 넣어 돌리는 경우 EOF 가 나는데, 이때 오류를
    띄우면 실제 문제와 구분이 안 된다.
    """
    try:
        return input(message).strip()
    except EOFError:
        print()
        return ""


def main(argv: list[str] | None = None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    print(BANNER)

    try:
        inputs = _resolve_inputs(argv)
        if not inputs:
            return _close(1)

        years = _ask_years(inputs)
        outdir = _ask_outdir(inputs[0])

        args = ["--input", *[str(path) for path in inputs], "--outdir", str(outdir)]
        if years:
            args += ["--year", *[str(year) for year in years]]

        from . import main as pipeline

        code = pipeline.run(args)
        if code == 0:
            print()
            print("=" * 50)
            print(f"  결과 폴더: {outdir.resolve()}")
            print("  HTML 파일을 두 번 눌러 열어 보세요.")
            print("=" * 50)
        return _close(code)

    except KeyboardInterrupt:
        print("\n중단했습니다.")
        return _close(1)
    except Exception:
        print("\n" + "=" * 50)
        print("  오류가 발생했습니다. 아래 내용을 담당자에게 전달해 주세요.")
        print("=" * 50)
        traceback.print_exc()
        return _close(1)


def _resolve_inputs(argv: list[str]) -> list[Path]:
    """끌어다 놓은 파일을 우선 쓰고, 없으면 경로를 묻는다."""
    dropped = [Path(item) for item in argv if not item.startswith("-")]
    if dropped:
        missing = [path for path in dropped if not path.exists()]
        if missing:
            print("다음 파일을 찾을 수 없습니다:")
            for path in missing:
                print(f"  {path}")
            return []
        print("분석할 파일:")
        for path in dropped:
            print(f"  {path.name}")
        return dropped

    print("분석할 CSV 파일을 지정하세요.")
    print("  - 파일을 이 창 안으로 끌어다 놓거나")
    print("  - 경로를 직접 입력하세요")
    print("  - 여러 개면 한 줄에 하나씩 넣고 마지막에 빈 줄을 입력하세요")
    print()

    paths: list[Path] = []
    while True:
        raw = _prompt(f"파일 {len(paths) + 1}: ").strip('"').strip("'")
        if not raw:
            if paths:
                break
            print("  파일을 지정해야 합니다.")
            continue

        path = Path(raw)
        if not path.exists():
            print(f"  찾을 수 없습니다: {path}")
            continue
        paths.append(path)
        print(f"  추가됨: {path.name}")

    return paths


def _ask_years(inputs: list[Path]) -> list[int]:
    """기준년 선택. 어떤 해가 들어 있는지 먼저 보여 주고 고르게 한다."""
    print()
    answer = _prompt("전체 기간을 분석할까요? (Enter=전체, n=연도 선택): ").lower()
    if answer not in {"n", "no", "아니오", "ㅜ"}:
        return []

    years = _peek_years(inputs)
    if years:
        print(f"  데이터에 있는 기준년: {', '.join(map(str, years))}")

    raw = _prompt("  분석할 연도 (띄어쓰기로 구분, 예: 2024 2025): ")
    picked = []
    for token in raw.split():
        try:
            picked.append(int(token))
        except ValueError:
            print(f"  숫자가 아니라 건너뜁니다: {token}")
    return picked


def _peek_years(inputs: list[Path]) -> list[int]:
    """앞부분만 훑어 연도를 뽑는다. 전체를 읽으면 고르기도 전에 오래 걸린다."""
    from . import loader, prep, profile

    try:
        frame = loader.read_table(inputs[0], nrows=50_000)
        return profile.available_years(prep.prepare(frame)["cases"])
    except Exception:
        return []


def _ask_outdir(first: Path) -> Path:
    default = first.parent / "분석결과"
    print()
    raw = _prompt(f"결과를 저장할 폴더 (Enter={default.name}): ").strip('"')
    return Path(raw) if raw else default


def _close(code: int) -> int:
    print()
    _prompt("창을 닫으려면 Enter 를 누르세요...")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
