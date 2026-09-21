"""콘솔 출력 준비.

윈도우 콘솔은 기본 코드페이지가 한글을 담지 못하는 경우가 있다. 그 상태로
한글을 출력하면 UnicodeEncodeError 로 프로그램이 통째로 죽는다. 분석은 다
끝내 놓고 결과를 알리는 줄에서 죽으면 원인을 짚기도 어렵다.

출력 인코딩을 UTF-8 로 맞추고, 그래도 안 되는 옛 환경에서는 표현 못 하는
글자를 대체 문자로 흘려보내 죽지 않게만 한다.
"""

from __future__ import annotations

import sys


def setup() -> None:
    _set_windows_codepage()
    for stream in (sys.stdout, sys.stderr):
        _reconfigure(stream)


def _set_windows_codepage() -> None:
    if sys.platform != "win32":
        return
    try:
        import ctypes

        # 65001 = UTF-8. 콘솔 자체가 UTF-8 을 받도록 바꿔야 글자가 제대로 찍힌다.
        ctypes.windll.kernel32.SetConsoleOutputCP(65001)
        ctypes.windll.kernel32.SetConsoleCP(65001)
    except Exception:
        # 옛 윈도우나 콘솔이 없는 환경. 아래 대체 처리로 넘어간다.
        pass


def _reconfigure(stream) -> None:
    reconfigure = getattr(stream, "reconfigure", None)
    if reconfigure is None:
        return
    try:
        reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        try:
            # 인코딩은 그대로 두고 죽지 않게만 한다.
            reconfigure(errors="replace")
        except Exception:
            pass
