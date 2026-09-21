"""실행 파일(exe) 시작점.

PyInstaller 는 패키지 밖의 평범한 스크립트를 시작점으로 받으므로, 상대 임포트를
쓰는 app.py 를 직접 넘기지 않고 이 파일을 거친다.

기본 동작은 GUI 실행이고, --console 을 붙이면 이전처럼 콘솔 대화 방식으로 돈다.
"""

from __future__ import annotations

import multiprocessing
import sys


def _main() -> int:
    if "--console" in sys.argv:
        sys.argv.remove("--console")
        from insurance_audit.app import main

        return main()

    from insurance_audit import gui

    code = gui.launch(sys.argv[1:])
    if code == -1:
        from insurance_audit.app import main

        return main()
    return code


if __name__ == "__main__":
    multiprocessing.freeze_support()
    raise SystemExit(_main())
