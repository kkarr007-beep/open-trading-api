"""CSV 적재와 컬럼 검증.

SQL에서 내려받아 엑셀을 거친 파일이라 머리글의 공백과 표기가 원본과 어긋나는
경우가 많다. 공백을 지우고 별칭표와 대조해 내부키로 바꾼 뒤 넘긴다.
"""

from __future__ import annotations

import difflib
import io
import re
import unicodedata
from pathlib import Path

import pandas as pd

from . import config

# 내부망 반출 파일은 CP949로 떨어지는 경우가 대부분이지만 UTF-8도 섞여 들어온다.
ENCODING_CANDIDATES = ("utf-8-sig", "cp949", "euc-kr", "utf-8")

# 머리글이 별칭표와 한 글자만 달라도 실패하던 문제 때문에 느슨하게 맞춘다.
# 다만 순서로 추측하지는 않는다. 조용히 엉뚱한 컬럼으로 분석이 돌아가는 것이
# 읽기 실패보다 나쁘다. 확신이 서는 것만 자동으로 붙이고 나머지는 사용자에게 묻는다.
MIN_MATCH_SCORE = 0.62
CONFIDENT_SCORE = 0.93

# 뜻을 바꾸지 않는 표기 흔들림만 걷어낸다. "코드"·"번호"·"id"는 컬럼을
# 구분하는 데 쓰이므로(법인 vs 법인코드) 건드리지 않는다.
_HEADER_SUBSTITUTIONS = (("성명", "명"), ("일자", "일"))
_TRAILING_NOISE = ("명",)


class ColumnError(RuntimeError):
    pass


def _normalize_header(name: object) -> str:
    text = unicodedata.normalize("NFKC", str(name))
    text = re.sub(r"\s+", "", text)
    text = re.sub(r"[()\[\]{}<>_\-./·]", "", text)
    return text.strip().lower()


def _loosen(text: str) -> str:
    """표기 흔들림을 걷어낸 비교용 형태. '보상담당자명'과 '보상담당자'를 같게 만든다."""
    for old, new in _HEADER_SUBSTITUTIONS:
        text = text.replace(old, new)
    for suffix in _TRAILING_NOISE:
        if len(text) > len(suffix) and text.endswith(suffix):
            text = text[: -len(suffix)]
    return text


def _pair_score(header: str, alias: str) -> float:
    """머리글과 별칭이 같은 것을 가리킬 가능성. 1.0이 정확 일치."""
    if not header or not alias:
        return 0.0
    if header == alias:
        return 1.0

    loose_header, loose_alias = _loosen(header), _loosen(alias)
    if loose_header == loose_alias:
        return 0.95

    if loose_header in loose_alias or loose_alias in loose_header:
        short, long = sorted((loose_header, loose_alias), key=len)
        return 0.60 + 0.35 * (len(short) / len(long))

    ratio = difflib.SequenceMatcher(None, loose_header, loose_alias).ratio()
    return ratio * 0.9 if ratio >= 0.70 else 0.0


def suggest_columns(raw_columns: list[object]) -> dict[str, object]:
    """머리글을 내부키에 배정하고 확신 정도를 함께 돌려준다.

    한 머리글이 여러 내부키에 걸리므로 점수가 높은 짝부터 차례로 확정한다.
    """
    scored: list[tuple[float, object, str]] = []
    for column in raw_columns:
        header = _normalize_header(column)
        if not header:
            continue
        for key, aliases in config.COLUMN_ALIASES.items():
            score = max(
                _pair_score(header, _normalize_header(alias)) for alias in aliases
            )
            if score >= MIN_MATCH_SCORE:
                scored.append((score, column, key))

    scored.sort(key=lambda item: (-item[0], str(item[1])))

    mapping: dict[object, str] = {}
    confidence: dict[str, str] = {}
    taken_keys: set[str] = set()
    taken_columns: set[object] = set()
    for score, column, key in scored:
        if key in taken_keys or column in taken_columns:
            continue
        mapping[column] = key
        confidence[key] = "정확" if score >= CONFIDENT_SCORE else "확인"
        taken_keys.add(key)
        taken_columns.add(column)

    return {
        "mapping": mapping,
        "confidence": confidence,
        "unmatched": [c for c in raw_columns if c not in taken_columns],
        "missing_required": [k for k in config.REQUIRED if k not in taken_keys],
    }


def resolve_columns(raw_columns: list[object]) -> dict[object, str]:
    """엑셀 머리글을 내부키로 매핑한다. 미등록 머리글은 매핑에서 빠진다."""
    return suggest_columns(raw_columns)["mapping"]


def apply_mapping(
    frame: pd.DataFrame, mapping: dict[object, str]
) -> pd.DataFrame:
    """주어진 매핑대로 컬럼을 내부키로 바꾼다. 필수 컬럼이 없으면 막는다."""
    usable = {
        column: key for column, key in mapping.items() if column in frame.columns
    }
    missing = [key for key in config.REQUIRED if key not in usable.values()]
    if missing:
        raise ColumnError(
            "필수 컬럼을 찾지 못했습니다: "
            + ", ".join(missing)
            + "\n읽어들인 머리글: "
            + ", ".join(str(column) for column in frame.columns[:40])
        )
    return frame[list(usable)].rename(columns=usable)


def _apply_mapping(frame: pd.DataFrame) -> pd.DataFrame:
    """컬럼을 내부키로 바꾸고 필수 컬럼을 확인한다."""
    return apply_mapping(frame, resolve_columns(list(frame.columns)))


def parse_clipboard_text(text: str) -> pd.DataFrame:
    """클립보드 텍스트를 머리글 그대로인 표로 바꾼다. 매핑은 하지 않는다."""
    return pd.read_csv(
        io.StringIO(text),
        dtype=str,
        sep="\t",
        keep_default_na=False,
        na_values=("", "NULL", "null", "NaN"),
    )


def read_clipboard_text(text: str) -> pd.DataFrame:
    """클립보드에서 복사한 탭 구분 텍스트를 내부키 컬럼 표로 바꾼다."""
    return _apply_mapping(parse_clipboard_text(text))


def parse_table(
    path: str | Path, encoding: str | None = None, nrows: int | None = None
) -> pd.DataFrame:
    """CSV/엑셀을 머리글 그대로 읽는다. 매핑은 하지 않는다."""
    path = Path(path)
    if not path.exists():
        raise FileNotFoundError(f"입력 파일을 찾을 수 없습니다: {path}")

    if path.suffix.lower() in {".xlsx", ".xlsm", ".xls"}:
        return pd.read_excel(path, dtype=str, nrows=nrows)
    return _read_csv(path, encoding, nrows)


def read_table(
    path: str | Path, encoding: str | None = None, nrows: int | None = None
) -> pd.DataFrame:
    """CSV/엑셀을 읽어 내부키 컬럼명으로 바꾼 표를 돌려준다.

    nrows 는 어떤 값이 들어 있는지만 미리 볼 때 쓴다. 10만 행을 다 읽고 나서
    앞부분만 보는 것은 의미가 없다.
    """
    return _apply_mapping(parse_table(path, encoding, nrows))


def _read_csv(path: Path, encoding: str | None, nrows: int | None = None) -> pd.DataFrame:
    candidates = (encoding,) if encoding else ENCODING_CANDIDATES
    last_error: Exception | None = None
    for candidate in candidates:
        try:
            return pd.read_csv(
                path,
                dtype=str,
                encoding=candidate,
                sep=None,
                engine="python",
                keep_default_na=False,
                na_values=("", "NULL", "null", "NaN"),
                nrows=nrows,
            )
        except (UnicodeDecodeError, LookupError) as error:
            last_error = error
    raise ColumnError(
        f"파일 인코딩을 판별하지 못했습니다. --encoding 으로 직접 지정하세요. ({last_error})"
    )


def read_many(paths: list[str | Path], encoding: str | None = None) -> pd.DataFrame:
    """시트가 나뉘어 있거나 분할 반출된 경우 여러 파일을 이어 붙인다."""
    frames = [read_table(path, encoding) for path in paths]
    if not frames:
        raise ColumnError("입력 파일이 지정되지 않았습니다.")
    combined = pd.concat(frames, ignore_index=True, sort=False)
    return combined


def describe_columns(frame: pd.DataFrame) -> pd.DataFrame:
    """적재 결과 점검표. 어느 컬럼이 비었는지 먼저 보고 들어가야 한다."""
    rows = []
    total = len(frame)
    for column in frame.columns:
        series = frame[column]
        filled = int(series.notna().sum())
        rows.append(
            {
                "컬럼": column,
                "채움건수": filled,
                "채움률": round(filled / total * 100, 1) if total else 0.0,
                "고유값수": int(series.nunique(dropna=True)),
                "예시": next((str(v) for v in series.dropna().head(1)), ""),
            }
        )
    return pd.DataFrame(rows)
