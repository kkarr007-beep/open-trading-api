"""CSV 적재와 컬럼 검증.

SQL에서 내려받아 엑셀을 거친 파일이라 머리글의 공백과 표기가 원본과 어긋나는
경우가 많다. 공백을 지우고 별칭표와 대조해 내부키로 바꾼 뒤 넘긴다.
"""

from __future__ import annotations

import re
import unicodedata
from pathlib import Path

import pandas as pd

from . import config

# 내부망 반출 파일은 CP949로 떨어지는 경우가 대부분이지만 UTF-8도 섞여 들어온다.
ENCODING_CANDIDATES = ("utf-8-sig", "cp949", "euc-kr", "utf-8")


class ColumnError(RuntimeError):
    pass


def _normalize_header(name: object) -> str:
    text = unicodedata.normalize("NFKC", str(name))
    text = re.sub(r"\s+", "", text)
    return text.strip().lower()


def _build_lookup() -> dict[str, str]:
    lookup: dict[str, str] = {}
    for key, aliases in config.COLUMN_ALIASES.items():
        for alias in aliases:
            lookup[_normalize_header(alias)] = key
    return lookup


def resolve_columns(raw_columns: list[object]) -> dict[object, str]:
    """엑셀 머리글을 내부키로 매핑한다. 미등록 머리글은 매핑에서 빠진다."""
    lookup = _build_lookup()
    mapping: dict[object, str] = {}
    used: set[str] = set()
    for column in raw_columns:
        key = lookup.get(_normalize_header(column))
        # 같은 내부키에 여러 원본 컬럼이 걸리면 첫 번째만 채택한다.
        if key and key not in used:
            mapping[column] = key
            used.add(key)
    return mapping


def read_table(
    path: str | Path, encoding: str | None = None, nrows: int | None = None
) -> pd.DataFrame:
    """CSV/엑셀을 읽어 내부키 컬럼명으로 바꾼 표를 돌려준다.

    nrows 는 어떤 값이 들어 있는지만 미리 볼 때 쓴다. 10만 행을 다 읽고 나서
    앞부분만 보는 것은 의미가 없다.
    """
    path = Path(path)
    if not path.exists():
        raise FileNotFoundError(f"입력 파일을 찾을 수 없습니다: {path}")

    if path.suffix.lower() in {".xlsx", ".xlsm", ".xls"}:
        frame = pd.read_excel(path, dtype=str, nrows=nrows)
    else:
        frame = _read_csv(path, encoding, nrows)

    mapping = resolve_columns(list(frame.columns))
    missing = [key for key in config.REQUIRED if key not in mapping.values()]
    if missing:
        raise ColumnError(
            "필수 컬럼을 찾지 못했습니다: "
            + ", ".join(missing)
            + "\n읽어들인 머리글: "
            + ", ".join(str(column) for column in frame.columns[:40])
        )

    frame = frame[list(mapping)].rename(columns=mapping)
    return frame


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
