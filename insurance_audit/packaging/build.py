"""내부망 반입용 설치본 생성.

내부망 PC는 외부 인터넷이 없고 설치 권한도 막혀 있는 경우가 많다. 파이썬만
깔려 있으면 나머지를 전부 오프라인으로 설치할 수 있도록, 필요한 설치 파일을
미리 받아 프로그램과 함께 묶는다.

파이썬 버전마다 컴파일된 설치 파일이 달라 버전별로 따로 만든다. 반입 통로의
용량 제한이 흔하므로 버전별로 나눠야 한 묶음이 30MB를 넘지 않는다.

    python insurance_audit/packaging/build.py --outdir 배포

인터넷이 되는 곳에서 한 번 만들어 두고, 만들어진 zip만 내부망으로 옮긴다.
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

PACKAGES = ("pandas", "numpy", "openpyxl")
PYTHON_VERSIONS = ("3.9", "3.10", "3.11", "3.12", "3.13")
PLATFORM = "win_amd64"

ROOT = Path(__file__).resolve().parent.parent
SCRIPTS = ("설치.bat", "분석실행.bat", "읽어보기.txt")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="내부망 반입용 설치본 생성")
    parser.add_argument("--outdir", default="배포", help="zip을 저장할 폴더")
    parser.add_argument(
        "--versions", nargs="+", default=list(PYTHON_VERSIONS),
        help="대상 파이썬 버전. 예: --versions 3.11 3.12",
    )
    parser.add_argument("--platform", default=PLATFORM, help="대상 플랫폼 태그")
    args = parser.parse_args(argv)

    outdir = Path(args.outdir)
    outdir.mkdir(parents=True, exist_ok=True)
    work = outdir / "_작업"

    for version in args.versions:
        tag = "cp" + version.replace(".", "")
        staging = work / tag
        if staging.exists():
            shutil.rmtree(staging)
        (staging / "wheels").mkdir(parents=True)

        print(f"[{version}] 설치 파일 내려받는 중...")
        target = staging / "wheels" / "_내려받기"
        result = subprocess.run(
            [
                sys.executable, "-m", "pip", "download", "--quiet",
                "--only-binary=:all:",
                "--platform", args.platform,
                "--python-version", version,
                "-d", str(target),
                *PACKAGES,
            ],
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            print(f"      실패: {result.stderr.strip().splitlines()[-1:]}")
            shutil.rmtree(staging)
            continue

        # 순수 파이썬 설치 파일은 버전을 안 타므로 따로 모은다.
        shared = staging / "wheels" / "공통"
        specific = staging / "wheels" / tag
        shared.mkdir()
        specific.mkdir()
        for wheel in target.glob("*.whl"):
            destination = shared if wheel.name.endswith("none-any.whl") else specific
            shutil.move(str(wheel), destination / wheel.name)
        shutil.rmtree(target)

        shutil.copytree(
            ROOT, staging / "insurance_audit",
            ignore=shutil.ignore_patterns("__pycache__", "packaging", "CLAUDE.md"),
        )
        for name in SCRIPTS:
            shutil.copy(ROOT / "packaging" / name, staging / name)

        base = outdir / f"유착분석_설치본_파이썬{version}"
        shutil.make_archive(str(base), "zip", staging)
        # with_suffix 를 쓰면 버전 번호의 점을 확장자로 보고 3.12 가 3 이 된다.
        archive = base.parent / f"{base.name}.zip"
        size = archive.stat().st_size / 1_048_576
        print(f"      완료: {archive.name} ({size:.0f}MB)")

    if work.exists():
        shutil.rmtree(work)
    print(f"\n{outdir} 폴더를 확인하세요.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
