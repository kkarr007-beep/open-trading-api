"""실행 파일(exe) 시작점.

PyInstaller 는 패키지 밖의 평범한 스크립트를 시작점으로 받으므로, 상대 임포트를
쓰는 app.py 를 직접 넘기지 않고 이 파일을 거친다.
"""

from __future__ import annotations

import multiprocessing

from insurance_audit.app import main

if __name__ == "__main__":
    # numpy 가 내부적으로 프로세스를 띄울 때 실행 파일이 자기 자신을 다시
    # 실행하며 창이 계속 열리는 것을 막는다.
    multiprocessing.freeze_support()
    raise SystemExit(main())
