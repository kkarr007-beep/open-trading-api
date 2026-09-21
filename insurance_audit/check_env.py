"""실행 환경 점검.

내부망 PC는 설치 권한이 막혀 있는 경우가 많아, 분석을 돌리기 전에 무엇이
있고 무엇이 없는지 먼저 확인한다.

    python -m insurance_audit.check_env
"""

from __future__ import annotations

import importlib
import sys

REQUIRED = {
    "pandas": "표 처리. 없으면 실행 불가",
    "numpy": "수치 계산. 없으면 실행 불가",
    "openpyxl": "엑셀 저장. 없으면 HTML만 산출",
}
OPTIONAL = {
    "scipy": "정밀 검정. 없으면 정규근사로 대체(결과 순위는 거의 같음)",
}

MIN_PYTHON = (3, 9)


def main() -> int:
    print("실행 환경 점검\n" + "=" * 46)

    version = sys.version_info
    ok_python = version >= MIN_PYTHON
    mark = "O" if ok_python else "X"
    print(f"[{mark}] 파이썬 {version.major}.{version.minor}.{version.micro}"
          f" (필요: {MIN_PYTHON[0]}.{MIN_PYTHON[1]} 이상)")

    missing = []
    print("\n필수 패키지")
    for name, note in REQUIRED.items():
        found = _probe(name)
        print(f"  [{'O' if found else 'X'}] {name:10} {found or note}")
        if not found:
            missing.append(name)

    print("\n선택 패키지")
    for name, note in OPTIONAL.items():
        found = _probe(name)
        print(f"  [{'O' if found else '-'}] {name:10} {found or note}")

    print("\n" + "=" * 46)
    if not ok_python:
        print("파이썬 버전이 낮습니다. 전산 담당자에게 3.9 이상 설치를 요청하세요.")
        return 1
    if missing:
        print("없는 패키지가 있습니다. 설치가 막혀 있다면 다음 순서로 확인하세요.")
        print("  1. 사내 배포 파이썬(Anaconda 등)에 이미 포함돼 있는지")
        print("  2. 사내 패키지 저장소가 열려 있는지")
        print(f"     pip install {' '.join(missing)}")
        print("  3. 둘 다 막혔다면 오프라인 설치 파일(whl)을 반입 신청")
        return 1

    print("바로 실행할 수 있습니다.")
    print("  python -m insurance_audit.make_sample --rows 5000 --out 샘플.csv")
    print("  python -m insurance_audit.main --input 샘플.csv --outdir 검증")
    return 0


def _probe(name: str) -> str:
    try:
        module = importlib.import_module(name)
    except ImportError:
        return ""
    return f"{getattr(module, '__version__', '설치됨')}"


if __name__ == "__main__":
    raise SystemExit(main())
