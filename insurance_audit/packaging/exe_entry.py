"""실행 파일(exe) 시작점.

PyInstaller 는 패키지 밖의 평범한 스크립트를 시작점으로 받으므로, 상대 임포트를
쓰는 app.py 를 직접 넘기지 않고 이 파일을 거친다.

기본 동작은 GUI 실행이고, --console 을 붙이면 이전처럼 콘솔 대화 방식으로 돈다.
"""

from __future__ import annotations

import multiprocessing
import sys


def _own_console():
    """이 프로세스가 만든 콘솔 창 핸들. 남의 창이면 None.

    명령창에서 실행했다면 그 창은 사용자 것이므로 건드리면 안 된다.
    """
    if sys.platform != "win32":
        return None
    try:
        import ctypes

        window = ctypes.windll.kernel32.GetConsoleWindow()
        if not window:
            return None
        owner = ctypes.c_uint()
        ctypes.windll.user32.GetWindowThreadProcessId(window, ctypes.byref(owner))
        if owner.value != ctypes.windll.kernel32.GetCurrentProcessId():
            return None
        return window
    except Exception:
        return None


def _console_visible(window, visible: bool) -> None:
    """콘솔 창을 감추거나 되돌린다.

    콘솔을 붙여 빌드해야 --console 모드가 입출력을 쓸 수 있다. 그래서
    빌드 플래그로 떼지 않고 GUI 로 뜰 때만 실행 시점에 감춘다.
    """
    if window is None:
        return
    try:
        import ctypes

        ctypes.windll.user32.ShowWindow(window, 5 if visible else 0)  # SW_SHOW / SW_HIDE
    except Exception:
        pass


def _main() -> int:
    if "--console" in sys.argv:
        sys.argv.remove("--console")
        from insurance_audit.app import main

        return main()

    console = _own_console()
    _console_visible(console, False)

    from insurance_audit import gui

    code = gui.launch(sys.argv[1:])
    if code == -1:
        # GUI 를 띄우지 못했다. 콘솔로 되돌려야 사용자가 뭐라도 할 수 있다.
        _console_visible(console, True)
        from insurance_audit.app import main

        return main()
    return code


if __name__ == "__main__":
    multiprocessing.freeze_support()
    raise SystemExit(_main())
