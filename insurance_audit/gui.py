"""tkinter GUI.

내부망 PC에서 파일 읽기가 보안으로 막혀 있어 엑셀에서 복사한 데이터를
붙여넣기로 입력받는다. 분석 옵션을 고르고 실행까지 한 화면에서 진행한다.
결과 탭에서 주체별 순위와 지표 점수를 바로 확인할 수 있다.
"""

from __future__ import annotations

import os
import sys
import threading
import traceback
from pathlib import Path

import pandas as pd

try:
    import tkinter as tk
    from tkinter import ttk, filedialog, messagebox

    HAS_TK = True
    # 상속할 클래스는 임포트 시점에 정해진다. tkinter 가 없는 환경(CI 등)에서도
    # 모듈은 읽혀야 하므로 대역을 둔다. 그런 환경에서는 창을 만들지 않는다.
    _BASE_WINDOW = tk.Toplevel
except ImportError:
    HAS_TK = False
    _BASE_WINDOW = object

from . import config, console, loader, prep, profile
from . import main as pipeline_mod


def available() -> bool:
    """GUI를 띄울 수 있는 환경인지 확인한다."""
    if not HAS_TK:
        return False
    try:
        root = tk.Tk()
        root.withdraw()
        root.destroy()
        return True
    except Exception:
        return False


def launch(argv: list[str] | None = None) -> int:
    """GUI를 띄운다. 쓸 수 없으면 -1을 돌려준다."""
    if not HAS_TK:
        return -1
    console.setup()
    try:
        root = tk.Tk()
        app = App(root, argv)
        root.mainloop()
        return app.exit_code
    except Exception:
        return -1


# ──────────────────────────────────────────────────────────────
# 색상 팔레트
# ──────────────────────────────────────────────────────────────

_COLORS = {
    "bg": "#f5f6fa",
    "bg_dark": "#e8eaf0",
    "sidebar": "#1e2a3a",
    "sidebar_text": "#c8d0dc",
    "sidebar_active": "#2d4a6f",
    "accent": "#3478f6",
    "accent_hover": "#2860d0",
    "text": "#1a1a2e",
    "text_sub": "#6b7280",
    "card": "#ffffff",
    "card_border": "#e2e5ea",
    "risk_high": "#dc2626",
    "risk_high_bg": "#fef2f2",
    "risk_mid": "#d97706",
    "risk_mid_bg": "#fffbeb",
    "risk_low": "#059669",
    "risk_low_bg": "#ecfdf5",
    "log_bg": "#1e1e2e",
    "log_fg": "#cdd6f4",
    "header_bg": "#f0f2f8",
    "row_alt": "#f8f9fc",
    "border": "#d1d5db",
}

_NONE_CHOICE = "(없음)"


# ──────────────────────────────────────────────────────────────
# 컬럼 매핑 확인 창
# ──────────────────────────────────────────────────────────────


class ColumnMapDialog(_BASE_WINDOW):
    """자동으로 붙인 컬럼 매핑을 보여주고 고치게 한다.

    머리글 표기가 흔들려도 대부분 자동으로 붙지만, 확신이 서지 않는 것은
    사람이 눈으로 확인해야 한다. 엉뚱한 컬럼으로 분석이 돌아가면
    결과가 조용히 틀린다.
    """

    def __init__(self, parent: tk.Tk, frame: pd.DataFrame, suggestion: dict):
        super().__init__(parent)
        self.title("컬럼 매핑 확인")
        self.geometry("760x620")
        self.minsize(640, 480)
        self.configure(bg=_COLORS["bg"])
        self.transient(parent)
        self.result: dict[object, str] | None = None

        self._frame = frame
        self._raw_columns = [str(c) for c in frame.columns]
        # 콤보박스는 문자열만 돌려준다. 엑셀 머리글이 숫자인 경우가 있어
        # 원본 컬럼을 되찾을 수 있게 짝을 남겨 둔다.
        self._origin = {str(c): c for c in frame.columns}
        self._confidence: dict[str, str] = dict(suggestion["confidence"])
        self._vars: dict[str, tk.StringVar] = {}
        self._rows: dict[str, ttk.Frame] = {}
        self._order: list[str] = []
        self._only_check = tk.BooleanVar(value=False)

        current = {key: col for col, key in suggestion["mapping"].items()}
        self._samples = self._collect_samples()

        self._build(current)
        self._refresh_summary()

        self.protocol("WM_DELETE_WINDOW", self._cancel)
        self.grab_set()

    def _collect_samples(self) -> dict[str, str]:
        samples = {}
        head = self._frame.head(20)
        for column in self._frame.columns:
            value = next(
                (str(v) for v in head[column] if pd.notna(v) and str(v).strip()), ""
            )
            samples[str(column)] = value[:22]
        return samples

    def _build(self, current: dict[str, object]):
        top = ttk.Frame(self)
        top.pack(fill="x", padx=14, pady=(14, 0))

        ttk.Label(
            top, text="컬럼 매핑 확인", font=("맑은 고딕", 13, "bold"),
        ).pack(anchor="w")

        self._summary = ttk.Label(
            top, text="", foreground=_COLORS["text_sub"], font=("맑은 고딕", 9),
        )
        self._summary.pack(anchor="w", pady=(4, 0))

        ttk.Label(
            top,
            text="빨간 항목은 반드시 지정해야 합니다. 노란 항목은 자동으로 붙였으나 확인이 필요합니다.",
            foreground=_COLORS["text_sub"], font=("맑은 고딕", 9),
        ).pack(anchor="w", pady=(2, 0))

        ttk.Checkbutton(
            top, text="확인이 필요한 항목만 보기",
            variable=self._only_check, command=self._apply_filter,
        ).pack(anchor="w", pady=(8, 8))

        # 스크롤 영역
        body = ttk.Frame(self)
        body.pack(fill="both", expand=True, padx=14)

        canvas = tk.Canvas(
            body, bg=_COLORS["card"], highlightthickness=1,
            highlightbackground=_COLORS["border"],
        )
        scrollbar = ttk.Scrollbar(body, orient="vertical", command=canvas.yview)
        self._list = ttk.Frame(canvas, style="Card.TFrame")

        self._list.bind(
            "<Configure>",
            lambda e: canvas.configure(scrollregion=canvas.bbox("all")),
        )
        window = canvas.create_window((0, 0), window=self._list, anchor="nw")
        canvas.bind(
            "<Configure>", lambda e: canvas.itemconfig(window, width=e.width),
        )
        canvas.configure(yscrollcommand=scrollbar.set)
        scrollbar.pack(side="right", fill="y")
        canvas.pack(side="left", fill="both", expand=True)
        canvas.bind_all(
            "<MouseWheel>",
            lambda e: canvas.yview_scroll(int(-e.delta / 120), "units"),
        )
        self._canvas = canvas

        choices = [_NONE_CHOICE] + self._raw_columns
        self._order = self._ordered_keys(current)
        for key in self._order:
            self._add_row(key, current.get(key), choices)

        # 하단 버튼
        footer = ttk.Frame(self)
        footer.pack(fill="x", padx=14, pady=12)

        ttk.Button(footer, text="취소", command=self._cancel).pack(side="right")
        ttk.Button(
            footer, text="  적용  ", command=self._accept, style="Accent.TButton",
        ).pack(side="right", padx=(0, 8))
        ttk.Button(
            footer, text="자동 매칭 다시", command=self._reset,
        ).pack(side="left")

    def _ordered_keys(self, current: dict[str, object]) -> list[str]:
        """필수 → 확인 필요 → 배정됨 → 미배정 순으로 세운다."""
        def rank(key: str) -> tuple[int, int]:
            if key in config.REQUIRED:
                return (0, config.REQUIRED.index(key))
            if self._confidence.get(key) == "확인":
                return (1, 0)
            if key in current:
                return (2, 0)
            return (3, 0)

        keys = list(config.COLUMN_ALIASES)
        return sorted(keys, key=lambda k: (rank(k), keys.index(k)))

    def _add_row(self, key: str, column: object | None, choices: list[str]):
        row = ttk.Frame(self._list, style="Card.TFrame", padding=(8, 3))
        row.pack(fill="x")
        self._rows[key] = row

        required = key in config.REQUIRED
        state = self._state_of(key, column)
        dot = {"필수미지정": "●", "확인": "●", "정확": "●"}.get(state, "○")
        color = {
            "필수미지정": _COLORS["risk_high"],
            "확인": _COLORS["risk_mid"],
            "정확": _COLORS["risk_low"],
        }.get(state, _COLORS["text_sub"])

        mark = ttk.Label(
            row, text=dot, foreground=color, background=_COLORS["card"],
            font=("맑은 고딕", 9), width=2,
        )
        mark.pack(side="left")
        self._rows[key + "__mark"] = mark

        ttk.Label(
            row, text=key + (" *" if required else ""), width=16, anchor="w",
            background=_COLORS["card"],
            font=("맑은 고딕", 9, "bold" if required else "normal"),
            foreground=_COLORS["text"] if required else _COLORS["text_sub"],
        ).pack(side="left")

        var = tk.StringVar(value=str(column) if column is not None else _NONE_CHOICE)
        self._vars[key] = var
        combo = ttk.Combobox(
            row, textvariable=var, values=choices, state="readonly", width=26,
        )
        combo.pack(side="left", padx=(4, 8))
        combo.bind("<<ComboboxSelected>>", lambda e, k=key: self._on_pick(k))

        sample = ttk.Label(
            row, text=self._sample_for(var.get()), width=24, anchor="w",
            background=_COLORS["card"], foreground=_COLORS["text_sub"],
            font=("맑은 고딕", 9),
        )
        sample.pack(side="left")
        self._rows[key + "__sample"] = sample

    def _sample_for(self, column: str) -> str:
        if column == _NONE_CHOICE:
            return ""
        return self._samples.get(column, "")

    def _state_of(self, key: str, column: object | None) -> str:
        if column is None or column == _NONE_CHOICE:
            return "필수미지정" if key in config.REQUIRED else "없음"
        return self._confidence.get(key, "정확")

    def _on_pick(self, key: str):
        """사람이 고른 항목은 확인을 마친 것으로 본다."""
        self._confidence[key] = "정확"
        column = self._vars[key].get()
        self._rows[key + "__sample"].config(text=self._sample_for(column))

        state = self._state_of(key, column)
        mark = self._rows[key + "__mark"]
        mark.config(
            text="●" if state != "없음" else "○",
            foreground={
                "필수미지정": _COLORS["risk_high"],
                "확인": _COLORS["risk_mid"],
                "정확": _COLORS["risk_low"],
            }.get(state, _COLORS["text_sub"]),
        )
        self._refresh_summary()

    def _apply_filter(self):
        only = self._only_check.get()
        # 다시 pack 하면 맨 뒤로 붙으므로 전부 떼었다가 차례대로 붙인다.
        for key in self._order:
            self._rows[key].pack_forget()
        for key in self._order:
            state = self._state_of(key, self._vars[key].get())
            if not only or state in ("확인", "필수미지정"):
                self._rows[key].pack(fill="x")

    def _reset(self):
        suggestion = loader.suggest_columns(list(self._frame.columns))
        self._confidence = suggestion["confidence"]
        current = {key: col for col, key in suggestion["mapping"].items()}
        for key, var in self._vars.items():
            column = current.get(key)
            var.set(str(column) if column is not None else _NONE_CHOICE)
            self._rows[key + "__sample"].config(text=self._sample_for(var.get()))
            state = self._state_of(key, column)
            self._rows[key + "__mark"].config(
                text="●" if state != "없음" else "○",
                foreground={
                    "필수미지정": _COLORS["risk_high"],
                    "확인": _COLORS["risk_mid"],
                    "정확": _COLORS["risk_low"],
                }.get(state, _COLORS["text_sub"]),
            )
        self._refresh_summary()
        self._apply_filter()

    def _current_mapping(self) -> dict[object, str]:
        mapping: dict[object, str] = {}
        for key, var in self._vars.items():
            column = var.get()
            if column and column != _NONE_CHOICE:
                mapping[self._origin.get(column, column)] = key
        return mapping

    def _refresh_summary(self):
        mapping = self._current_mapping()
        missing = [k for k in config.REQUIRED if k not in mapping.values()]
        need_check = sum(
            1
            for key, var in self._vars.items()
            if var.get() != _NONE_CHOICE and self._confidence.get(key) == "확인"
        )
        text = f"{len(self._raw_columns)}개 머리글 중 {len(mapping)}개 인식"
        if need_check:
            text += f" · 확인 필요 {need_check}개"
        if missing:
            text += f" · 필수 누락: {', '.join(missing)}"
        self._summary.config(
            text=text,
            foreground=_COLORS["risk_high"] if missing else _COLORS["text_sub"],
        )

    def _accept(self):
        mapping = self._current_mapping()

        duplicates = [
            column
            for column in set(mapping)
            if sum(1 for v in self._vars.values() if v.get() == column) > 1
        ]
        if duplicates:
            messagebox.showwarning(
                "중복 지정",
                "한 컬럼을 여러 항목에 지정했습니다:\n"
                + ", ".join(str(d) for d in duplicates),
                parent=self,
            )
            return

        missing = [k for k in config.REQUIRED if k not in mapping.values()]
        if missing:
            messagebox.showwarning(
                "필수 컬럼",
                "다음 항목은 반드시 지정해야 합니다:\n" + ", ".join(missing),
                parent=self,
            )
            return

        self.result = mapping
        self._close()

    def _cancel(self):
        self.result = None
        self._close()

    def _close(self):
        self._canvas.unbind_all("<MouseWheel>")
        self.grab_release()
        self.destroy()


# ──────────────────────────────────────────────────────────────
# stdout → Text 위젯 전달
# ──────────────────────────────────────────────────────────────


class _TextRedirector:

    def __init__(self, widget: tk.Text):
        self.widget = widget

    def write(self, text: str) -> int:
        self.widget.after(0, self._append, text)
        return len(text)

    def _append(self, text: str):
        self.widget.config(state="normal")
        self.widget.insert("end", text)
        self.widget.see("end")
        self.widget.config(state="disabled")

    def flush(self):
        pass


# ──────────────────────────────────────────────────────────────
# 메인 윈도우
# ──────────────────────────────────────────────────────────────


class App:

    def __init__(self, root: tk.Tk, argv: list[str] | None = None):
        self.root = root
        self.raw_data: pd.DataFrame | None = None
        self.source_frame: pd.DataFrame | None = None
        self.pipeline_info: dict = {}
        self.years: list[int] = []
        self.year_vars: dict[int, tk.BooleanVar] = {}
        self.all_years_var = tk.BooleanVar(value=True)
        self.org_level_var = tk.StringVar(value="자동")
        self.outdir_var = tk.StringVar(value="분석결과")
        self.running = False
        self.dialog_open = False
        self.exit_code = 0

        root.title("외부조사 법인 유착 혐의 1차 분석")
        root.geometry("960x760")
        root.minsize(800, 600)
        root.configure(bg=_COLORS["bg"])

        self._apply_theme()
        self._build_ui()

        if argv:
            dropped = [p for p in argv if not p.startswith("-")]
            if dropped:
                self.root.after(200, lambda: self._load_files(dropped))

    # ── 테마 ────────────────────────────────────────────

    def _apply_theme(self):
        style = ttk.Style()
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass

        style.configure(".", background=_COLORS["bg"], foreground=_COLORS["text"])
        style.configure(
            "TNotebook", background=_COLORS["bg"], borderwidth=0, padding=0,
        )
        style.configure(
            "TNotebook.Tab",
            background=_COLORS["bg_dark"],
            foreground=_COLORS["text"],
            padding=(16, 8),
            font=("맑은 고딕", 10),
        )
        style.map(
            "TNotebook.Tab",
            background=[("selected", _COLORS["card"])],
            foreground=[("selected", _COLORS["accent"])],
        )
        style.configure(
            "TLabelframe", background=_COLORS["card"], bordercolor=_COLORS["card_border"],
        )
        style.configure(
            "TLabelframe.Label", background=_COLORS["card"], foreground=_COLORS["text"],
            font=("맑은 고딕", 10, "bold"),
        )
        style.configure("Card.TFrame", background=_COLORS["card"])
        style.configure("CardTitle.TLabel", background=_COLORS["card"],
                        foreground=_COLORS["text"], font=("맑은 고딕", 11, "bold"))
        style.configure("CardSub.TLabel", background=_COLORS["card"],
                        foreground=_COLORS["text_sub"], font=("맑은 고딕", 9))

        style.configure(
            "Accent.TButton",
            background=_COLORS["accent"],
            foreground="white",
            font=("맑은 고딕", 10, "bold"),
            padding=(20, 8),
        )
        style.map(
            "Accent.TButton",
            background=[("active", _COLORS["accent_hover"]), ("disabled", "#94a3b8")],
        )

        style.configure(
            "Treeview",
            background=_COLORS["card"],
            fieldbackground=_COLORS["card"],
            foreground=_COLORS["text"],
            rowheight=26,
            font=("맑은 고딕", 9),
            borderwidth=0,
        )
        style.configure(
            "Treeview.Heading",
            background=_COLORS["header_bg"],
            foreground=_COLORS["text"],
            font=("맑은 고딕", 9, "bold"),
            borderwidth=1,
            relief="flat",
        )
        style.map("Treeview", background=[("selected", "#dbeafe")])

        style.configure(
            "Risk.High.TLabel", background=_COLORS["risk_high_bg"],
            foreground=_COLORS["risk_high"], font=("맑은 고딕", 9, "bold"),
        )
        style.configure(
            "Risk.Mid.TLabel", background=_COLORS["risk_mid_bg"],
            foreground=_COLORS["risk_mid"], font=("맑은 고딕", 9, "bold"),
        )
        style.configure(
            "Risk.Low.TLabel", background=_COLORS["risk_low_bg"],
            foreground=_COLORS["risk_low"], font=("맑은 고딕", 9),
        )

        style.configure(
            "Summary.TLabel", background=_COLORS["card"],
            foreground=_COLORS["text"], font=("맑은 고딕", 20, "bold"),
        )
        style.configure(
            "SummaryUnit.TLabel", background=_COLORS["card"],
            foreground=_COLORS["text_sub"], font=("맑은 고딕", 9),
        )

    # ── UI 구성 ──────────────────────────────────────────

    def _build_ui(self):
        header = ttk.Frame(self.root)
        header.pack(fill="x", padx=16, pady=(12, 0))
        ttk.Label(
            header,
            text="외부조사 법인 유착 혐의 1차 분석",
            font=("맑은 고딕", 14, "bold"),
        ).pack(side="left")

        self.notebook = ttk.Notebook(self.root)
        self.notebook.pack(fill="both", expand=True, padx=16, pady=(8, 16))

        self.tab_data = ttk.Frame(self.notebook)
        self.tab_run = ttk.Frame(self.notebook)
        self.tab_results = ttk.Frame(self.notebook)

        self.notebook.add(self.tab_data, text="  데이터  ")
        self.notebook.add(self.tab_run, text="  실행  ")
        self.notebook.add(self.tab_results, text="  결과  ")

        self._build_data_tab()
        self._build_run_tab()
        self._build_results_tab()

    # ── 데이터 탭 ────────────────────────────────────────

    def _build_data_tab(self):
        top = ttk.Frame(self.tab_data)
        top.pack(fill="x", padx=12, pady=(12, 0))

        btn_frame = ttk.Frame(top)
        btn_frame.pack(fill="x")

        self.paste_btn = ttk.Button(
            btn_frame, text="  엑셀 데이터 붙여넣기  ",
            command=self._paste_clipboard, style="Accent.TButton",
        )
        self.paste_btn.pack(side="left", padx=(0, 8))

        self.file_btn = ttk.Button(
            btn_frame, text="CSV 파일 열기", command=self._open_file,
        )
        self.file_btn.pack(side="left")

        self.remap_btn = ttk.Button(
            btn_frame, text="컬럼 매핑", command=self._remap_columns,
        )
        self.remap_btn.pack(side="left", padx=(8, 0))

        ttk.Label(
            top,
            text="엑셀에서 머리글 포함 전체를 선택 → Ctrl+C → 위 버튼 클릭",
            foreground=_COLORS["text_sub"],
            font=("맑은 고딕", 9),
        ).pack(anchor="w", pady=(8, 0))

        self.data_status = ttk.Label(
            top, text="데이터 없음", foreground=_COLORS["risk_high"],
            font=("맑은 고딕", 10, "bold"),
        )
        self.data_status.pack(anchor="w", pady=(6, 0))

        self.columns_label = ttk.Label(
            top, text="", foreground=_COLORS["text_sub"], wraplength=900,
            font=("맑은 고딕", 9),
        )
        self.columns_label.pack(anchor="w", pady=(2, 8))

        # 데이터 미리보기
        preview_frame = ttk.LabelFrame(self.tab_data, text=" 데이터 미리보기 ", padding=8)
        preview_frame.pack(fill="both", expand=True, padx=12, pady=(0, 12))

        tree_container = ttk.Frame(preview_frame)
        tree_container.pack(fill="both", expand=True)

        self.preview_tree = ttk.Treeview(
            tree_container, show="headings", selectmode="browse",
        )
        vsb = ttk.Scrollbar(tree_container, orient="vertical", command=self.preview_tree.yview)
        hsb = ttk.Scrollbar(tree_container, orient="horizontal", command=self.preview_tree.xview)
        self.preview_tree.configure(yscrollcommand=vsb.set, xscrollcommand=hsb.set)
        vsb.pack(side="right", fill="y")
        hsb.pack(side="bottom", fill="x")
        self.preview_tree.pack(fill="both", expand=True)
        self.preview_tree.tag_configure("alt", background=_COLORS["row_alt"])

        # Ctrl+V 단축키
        self.root.bind_all("<Control-v>", lambda e: self._paste_clipboard())
        self.root.bind_all("<Control-V>", lambda e: self._paste_clipboard())

    # ── 실행 탭 ──────────────────────────────────────────

    def _build_run_tab(self):
        settings = ttk.LabelFrame(self.tab_run, text=" 분석 설정 ", padding=12)
        settings.pack(fill="x", padx=12, pady=(12, 0))

        # 기준년
        ttk.Label(settings, text="기준년:", font=("맑은 고딕", 10)).grid(
            row=0, column=0, sticky="nw", pady=6,
        )
        self.year_frame = ttk.Frame(settings)
        self.year_frame.grid(row=0, column=1, sticky="w", padx=(8, 0), pady=6)

        self.all_years_cb = ttk.Checkbutton(
            self.year_frame, text="전체", variable=self.all_years_var,
            command=self._toggle_all_years,
        )
        self.all_years_cb.pack(side="left")

        self._year_hint = ttk.Label(
            self.year_frame, text="(데이터를 먼저 입력하세요)",
            foreground=_COLORS["text_sub"], font=("맑은 고딕", 9),
        )
        self._year_hint.pack(side="left", padx=(8, 0))

        # 조직계층
        ttk.Label(settings, text="조직계층:", font=("맑은 고딕", 10)).grid(
            row=1, column=0, sticky="w", pady=6,
        )
        ttk.Combobox(
            settings, textvariable=self.org_level_var,
            values=["자동", "소속1", "소속2", "소속3"],
            state="readonly", width=12,
        ).grid(row=1, column=1, sticky="w", padx=(8, 0), pady=6)

        # 결과 폴더
        ttk.Label(settings, text="결과 폴더:", font=("맑은 고딕", 10)).grid(
            row=2, column=0, sticky="w", pady=6,
        )
        out_row = ttk.Frame(settings)
        out_row.grid(row=2, column=1, sticky="we", padx=(8, 0), pady=6)
        ttk.Entry(out_row, textvariable=self.outdir_var, width=30).pack(
            side="left", fill="x", expand=True,
        )
        ttk.Button(out_row, text="선택", width=6, command=self._browse_outdir).pack(
            side="left", padx=(4, 0),
        )
        settings.columnconfigure(1, weight=1)

        # 지표 가중치 참고
        metrics_frame = ttk.LabelFrame(self.tab_run, text=" 지표 구성 (참고) ", padding=12)
        metrics_frame.pack(fill="x", padx=12, pady=(8, 0))

        sorted_metrics = sorted(
            config.METRIC_WEIGHTS.items(), key=lambda x: x[1], reverse=True,
        )
        for i, (key, weight) in enumerate(sorted_metrics):
            label = config.METRIC_LABELS.get(key, key)
            row_frame = ttk.Frame(metrics_frame)
            row_frame.pack(fill="x", pady=1)

            ttk.Label(
                row_frame, text=key.split("_")[0], width=3,
                font=("Consolas", 9, "bold"), foreground=_COLORS["accent"],
            ).pack(side="left")
            ttk.Label(
                row_frame, text=label, width=22, anchor="w",
                font=("맑은 고딕", 9),
            ).pack(side="left", padx=(4, 8))

            bar_canvas = tk.Canvas(
                row_frame, height=14, bg=_COLORS["bg"],
                highlightthickness=0,
            )
            bar_canvas.pack(side="left", fill="x", expand=True, padx=(0, 8))
            bar_canvas.bind("<Configure>", lambda e, w=weight, c=bar_canvas: self._draw_bar(c, w))

            ttk.Label(
                row_frame, text=f"{weight:.0%}", width=5, anchor="e",
                font=("맑은 고딕", 9), foreground=_COLORS["text_sub"],
            ).pack(side="right")

        # 실행 버튼
        btn_area = ttk.Frame(self.tab_run)
        btn_area.pack(fill="x", padx=12, pady=(12, 0))

        self.run_btn = ttk.Button(
            btn_area, text="  분석 시작  ", command=self._run_analysis,
            state="disabled", style="Accent.TButton",
        )
        self.run_btn.pack(pady=4)

        # 진행 상황
        log_frame = ttk.LabelFrame(self.tab_run, text=" 진행 상황 ", padding=8)
        log_frame.pack(fill="both", expand=True, padx=12, pady=(8, 12))

        self.log_text = tk.Text(
            log_frame, height=10, state="disabled",
            font=("Consolas", 9), wrap="word",
            bg=_COLORS["log_bg"], fg=_COLORS["log_fg"],
            insertbackground=_COLORS["log_fg"],
            selectbackground="#45475a",
            borderwidth=0, padx=8, pady=8,
        )
        scrollbar = ttk.Scrollbar(log_frame, command=self.log_text.yview)
        self.log_text.config(yscrollcommand=scrollbar.set)
        scrollbar.pack(side="right", fill="y")
        self.log_text.pack(side="left", fill="both", expand=True)

    @staticmethod
    def _draw_bar(canvas: tk.Canvas, weight: float):
        canvas.delete("all")
        w = canvas.winfo_width()
        h = canvas.winfo_height()
        if w < 2:
            return
        max_weight = max(config.METRIC_WEIGHTS.values())
        ratio = weight / max_weight if max_weight else 0
        bar_w = max(2, int(w * ratio))
        canvas.create_rectangle(0, 2, bar_w, h - 2, fill=_COLORS["accent"], outline="")

    # ── 결과 탭 ──────────────────────────────────────────

    def _build_results_tab(self):
        self.results_placeholder = ttk.Label(
            self.tab_results,
            text="분석을 실행하면 결과가 여기에 표시됩니다.",
            foreground=_COLORS["text_sub"],
            font=("맑은 고딕", 11),
        )
        self.results_placeholder.pack(expand=True)

    def _populate_results(self, info: dict):
        for widget in self.tab_results.winfo_children():
            widget.destroy()

        rankings: dict[str, pd.DataFrame] = info.get("rankings", {})
        if not rankings:
            ttk.Label(
                self.tab_results, text="결과 없음",
                foreground=_COLORS["text_sub"], font=("맑은 고딕", 11),
            ).pack(expand=True)
            return

        # ── 요약 카드 ──
        summary_bar = ttk.Frame(self.tab_results, style="Card.TFrame")
        summary_bar.pack(fill="x", padx=12, pady=(12, 0))

        cards_data = []
        total_high = 0
        total_mid = 0
        total_count = 0
        for entity, frame in rankings.items():
            n = len(frame)
            h = int((frame["등급"] == "높음").sum())
            m = int((frame["등급"] == "중간").sum())
            total_count += n
            total_high += h
            total_mid += m
            cards_data.append((entity, n, h, m))

        self._add_summary_card(summary_bar, "분석 건수",
                               f'{info.get("cases_count", 0):,}', "건")
        self._add_summary_card(summary_bar, "높음",
                               str(total_high), "건", fg=_COLORS["risk_high"])
        self._add_summary_card(summary_bar, "중간",
                               str(total_mid), "건", fg=_COLORS["risk_mid"])
        self._add_summary_card(summary_bar, "소요 시간",
                               f'{info.get("elapsed", 0):.1f}', "초")

        # ── 주체 선택 ──
        control_bar = ttk.Frame(self.tab_results)
        control_bar.pack(fill="x", padx=12, pady=(10, 0))

        ttk.Label(
            control_bar, text="주체:", font=("맑은 고딕", 10, "bold"),
        ).pack(side="left")

        self._entity_var = tk.StringVar()
        entities = list(rankings.keys())
        self._entity_var.set(entities[0] if entities else "")

        entity_combo = ttk.Combobox(
            control_bar, textvariable=self._entity_var,
            values=entities, state="readonly", width=12,
        )
        entity_combo.pack(side="left", padx=(8, 16))
        entity_combo.bind("<<ComboboxSelected>>", lambda e: self._show_entity())

        self._entity_count_label = ttk.Label(
            control_bar, text="", foreground=_COLORS["text_sub"],
            font=("맑은 고딕", 9),
        )
        self._entity_count_label.pack(side="left")

        # 파일 열기 버튼
        self._open_xlsx_btn = ttk.Button(
            control_bar, text="엑셀 열기",
            command=lambda: _open_file_path(info.get("xlsx_path")),
        )
        self._open_xlsx_btn.pack(side="right", padx=(4, 0))

        self._open_html_btn = ttk.Button(
            control_bar, text="HTML 보고서",
            command=lambda: _open_file_path(info.get("html_path")),
        )
        self._open_html_btn.pack(side="right", padx=(4, 0))

        self._open_folder_btn = ttk.Button(
            control_bar, text="결과 폴더",
            command=lambda: _open_folder(Path(info.get("outdir", "분석결과"))),
        )
        self._open_folder_btn.pack(side="right", padx=(4, 0))

        # ── 순위 테이블 + 상세 패널 ──
        body = ttk.Frame(self.tab_results)
        body.pack(fill="both", expand=True, padx=12, pady=(8, 12))

        # 왼쪽: 순위 테이블
        left = ttk.Frame(body)
        left.pack(side="left", fill="both", expand=True)

        tree_container = ttk.Frame(left)
        tree_container.pack(fill="both", expand=True)

        self.result_tree = ttk.Treeview(
            tree_container, show="headings", selectmode="browse",
        )
        vsb = ttk.Scrollbar(tree_container, orient="vertical", command=self.result_tree.yview)
        hsb = ttk.Scrollbar(tree_container, orient="horizontal", command=self.result_tree.xview)
        self.result_tree.configure(yscrollcommand=vsb.set, xscrollcommand=hsb.set)
        vsb.pack(side="right", fill="y")
        hsb.pack(side="bottom", fill="x")
        self.result_tree.pack(fill="both", expand=True)
        self.result_tree.bind("<<TreeviewSelect>>", lambda e: self._on_row_select())

        self.result_tree.tag_configure("높음", background=_COLORS["risk_high_bg"])
        self.result_tree.tag_configure("중간", background=_COLORS["risk_mid_bg"])
        self.result_tree.tag_configure("낮음", background=_COLORS["risk_low_bg"])
        self.result_tree.tag_configure("alt", background=_COLORS["row_alt"])

        # 오른쪽: 상세 패널
        right = ttk.Frame(body, width=280)
        right.pack(side="right", fill="y", padx=(8, 0))
        right.pack_propagate(False)

        self._detail_frame = ttk.LabelFrame(right, text=" 상세 ", padding=8)
        self._detail_frame.pack(fill="both", expand=True)

        self._detail_label = ttk.Label(
            self._detail_frame, text="행을 선택하세요",
            foreground=_COLORS["text_sub"], font=("맑은 고딕", 9),
            wraplength=250, justify="left",
        )
        self._detail_label.pack(anchor="nw")

        self._detail_widgets: list[tk.Widget] = [self._detail_label]
        self._current_rankings = rankings

        self._show_entity()

    def _add_summary_card(self, parent, title: str, value: str, unit: str,
                          fg: str | None = None):
        card = ttk.Frame(parent, style="Card.TFrame", padding=10)
        card.pack(side="left", fill="x", expand=True, padx=(0, 8))

        ttk.Label(card, text=title, style="CardSub.TLabel").pack(anchor="w")
        val_frame = ttk.Frame(card, style="Card.TFrame")
        val_frame.pack(anchor="w", pady=(2, 0))

        lbl = ttk.Label(val_frame, text=value, style="Summary.TLabel")
        if fg:
            lbl.configure(foreground=fg)
        lbl.pack(side="left")
        ttk.Label(val_frame, text=f" {unit}", style="SummaryUnit.TLabel").pack(
            side="left", anchor="s", pady=(0, 4),
        )

    def _show_entity(self):
        entity = self._entity_var.get()
        rankings = self._current_rankings
        if entity not in rankings:
            return

        frame = rankings[entity]
        n_high = int((frame["등급"] == "높음").sum())
        n_mid = int((frame["등급"] == "중간").sum())
        self._entity_count_label.config(
            text=f"{len(frame):,}건  (높음 {n_high}  중간 {n_mid})",
        )

        self.result_tree.delete(*self.result_tree.get_children())

        display_cols = ["명"]
        if "담당건수" in frame.columns:
            display_cols.append("담당건수")
        display_cols += ["혐의도", "등급", "주요사유"]

        metric_cols = [c for c in frame.columns if c.startswith(("A_", "B_", "C_", "D_", "E_", "F_", "G_", "H_", "I_", "J_"))]
        display_cols += metric_cols

        self.result_tree["columns"] = display_cols
        for col in display_cols:
            width = 60
            anchor = "center"
            if col == "명":
                width = 120
                anchor = "w"
            elif col == "주요사유":
                width = 180
                anchor = "w"
            elif col == "혐의도":
                width = 70
            elif col == "등급":
                width = 55
            elif col.startswith(("A_", "B_", "C_", "D_", "E_", "F_", "G_", "H_", "I_", "J_")):
                width = 55
                col_display = col.split("_")[0]
                self.result_tree.heading(
                    col, text=col_display,
                    command=lambda c=col: self._sort_result_tree(c),
                )
                self.result_tree.column(col, width=width, anchor=anchor, minwidth=40)
                continue

            self.result_tree.heading(
                col, text=col,
                command=lambda c=col: self._sort_result_tree(c),
            )
            self.result_tree.column(col, width=width, anchor=anchor, minwidth=40)

        for i, (_, row) in enumerate(frame.iterrows()):
            values = []
            for col in display_cols:
                val = row.get(col, "")
                if isinstance(val, float):
                    val = f"{val:.1f}" if val != 0 else "-"
                values.append(str(val) if pd.notna(val) else "")

            band = str(row.get("등급", "낮음"))
            tag = band if band in ("높음", "중간", "낮음") else "낮음"
            self.result_tree.insert("", "end", values=values, tags=(tag,))

        self._sort_ascending = {}
        self._clear_detail()

    def _sort_result_tree(self, col: str):
        ascending = not self._sort_ascending.get(col, False)
        self._sort_ascending[col] = ascending

        items = []
        for iid in self.result_tree.get_children():
            val = self.result_tree.set(iid, col)
            try:
                sort_key = float(val) if val not in ("-", "") else -1e9
            except ValueError:
                sort_key = val
            items.append((sort_key, iid))

        reverse = not ascending
        try:
            items.sort(key=lambda x: x[0], reverse=reverse)
        except TypeError:
            items.sort(key=lambda x: str(x[0]), reverse=reverse)

        for idx, (_, iid) in enumerate(items):
            self.result_tree.move(iid, "", idx)

    def _on_row_select(self):
        sel = self.result_tree.selection()
        if not sel:
            return
        entity = self._entity_var.get()
        rankings = self._current_rankings
        if entity not in rankings:
            return

        item = self.result_tree.item(sel[0])
        values = item["values"]
        cols = self.result_tree["columns"]
        if not values or not cols:
            return

        row_dict = dict(zip(cols, values))
        self._show_detail(row_dict, entity)

    def _clear_detail(self):
        for w in self._detail_widgets:
            w.destroy()
        self._detail_widgets.clear()

        lbl = ttk.Label(
            self._detail_frame, text="행을 선택하세요",
            foreground=_COLORS["text_sub"], font=("맑은 고딕", 9),
        )
        lbl.pack(anchor="nw")
        self._detail_widgets.append(lbl)

    def _show_detail(self, row: dict, entity: str):
        for w in self._detail_widgets:
            w.destroy()
        self._detail_widgets.clear()

        name = row.get("명", "")
        band = row.get("등급", "")
        score_val = row.get("혐의도", "")
        reason = row.get("주요사유", "")

        # 이름 + 등급
        name_lbl = ttk.Label(
            self._detail_frame, text=name,
            font=("맑은 고딕", 12, "bold"),
        )
        name_lbl.pack(anchor="nw", pady=(0, 4))
        self._detail_widgets.append(name_lbl)

        band_style = {
            "높음": "Risk.High.TLabel",
            "중간": "Risk.Mid.TLabel",
            "낮음": "Risk.Low.TLabel",
        }.get(band, "Risk.Low.TLabel")
        band_lbl = ttk.Label(
            self._detail_frame, text=f"  {band}  ", style=band_style,
        )
        band_lbl.pack(anchor="nw", pady=(0, 4))
        self._detail_widgets.append(band_lbl)

        info_lbl = ttk.Label(
            self._detail_frame,
            text=f"혐의도: {score_val}",
            font=("맑은 고딕", 10), foreground=_COLORS["text"],
        )
        info_lbl.pack(anchor="nw", pady=(0, 8))
        self._detail_widgets.append(info_lbl)

        if reason:
            r_title = ttk.Label(
                self._detail_frame, text="주요사유",
                font=("맑은 고딕", 9, "bold"), foreground=_COLORS["text_sub"],
            )
            r_title.pack(anchor="nw", pady=(0, 2))
            self._detail_widgets.append(r_title)

            r_lbl = ttk.Label(
                self._detail_frame, text=str(reason),
                font=("맑은 고딕", 9), foreground=_COLORS["text"],
                wraplength=250, justify="left",
            )
            r_lbl.pack(anchor="nw", pady=(0, 8))
            self._detail_widgets.append(r_lbl)

        # 지표별 점수 막대
        sep = ttk.Separator(self._detail_frame, orient="horizontal")
        sep.pack(fill="x", pady=(0, 8))
        self._detail_widgets.append(sep)

        m_title = ttk.Label(
            self._detail_frame, text="지표별 점수",
            font=("맑은 고딕", 9, "bold"), foreground=_COLORS["text_sub"],
        )
        m_title.pack(anchor="nw", pady=(0, 4))
        self._detail_widgets.append(m_title)

        metric_keys = config.ENTITY_METRICS.get(entity, ())
        for key in metric_keys:
            short = key.split("_")[0]
            val_str = row.get(key, "-")
            try:
                val = float(val_str) if val_str not in ("-", "", "0") else 0.0
            except (ValueError, TypeError):
                val = 0.0

            mf = ttk.Frame(self._detail_frame)
            mf.pack(fill="x", pady=1)
            self._detail_widgets.append(mf)

            ttk.Label(
                mf, text=short, width=3,
                font=("Consolas", 9, "bold"), foreground=_COLORS["accent"],
            ).pack(side="left")

            bar_canvas = tk.Canvas(
                mf, height=12, bg=_COLORS["bg_dark"], highlightthickness=0,
            )
            bar_canvas.pack(side="left", fill="x", expand=True, padx=(4, 4))

            color = _COLORS["risk_high"] if val >= 80 else (
                _COLORS["risk_mid"] if val >= 50 else _COLORS["risk_low"]
            )
            bar_canvas.bind(
                "<Configure>",
                lambda e, c=bar_canvas, v=val, cl=color: self._draw_score_bar(c, v, cl),
            )

            ttk.Label(
                mf, text=f"{val:.0f}" if val else "-", width=4, anchor="e",
                font=("맑은 고딕", 9),
            ).pack(side="right")

    @staticmethod
    def _draw_score_bar(canvas: tk.Canvas, value: float, color: str):
        canvas.delete("all")
        w = canvas.winfo_width()
        h = canvas.winfo_height()
        if w < 2:
            return
        ratio = min(value / 100.0, 1.0)
        bar_w = max(1, int(w * ratio))
        canvas.create_rectangle(0, 1, bar_w, h - 1, fill=color, outline="")

    # ── 데이터 입력 ────────────────────────────────────

    def _paste_clipboard(self):
        # Ctrl+V 는 앱 전체에 걸려 있다. 매핑 창이 떠 있거나 분석이 도는 중에
        # 눌리면 창이 겹쳐 뜨므로 막는다.
        if self.running or self.dialog_open:
            return
        try:
            text = self.root.clipboard_get()
        except tk.TclError:
            messagebox.showwarning(
                "붙여넣기",
                "클립보드가 비어 있습니다.\n"
                "엑셀에서 데이터를 복사(Ctrl+C)한 뒤 다시 시도하세요.",
            )
            return

        if not text or not text.strip():
            messagebox.showwarning("붙여넣기", "클립보드에 텍스트가 없습니다.")
            return

        try:
            frame = loader.parse_clipboard_text(text)
        except Exception as exc:
            messagebox.showerror(
                "읽기 실패",
                f"클립보드 데이터를 해석하지 못했습니다.\n\n{exc}",
            )
            return

        self._accept_frame(frame)

    def _open_file(self):
        path = filedialog.askopenfilename(
            title="분석할 파일 선택",
            filetypes=[
                ("CSV 파일", "*.csv"),
                ("엑셀 파일", "*.xlsx *.xls"),
                ("모든 파일", "*.*"),
            ],
        )
        if not path:
            return
        try:
            frame = loader.parse_table(path)
        except (loader.ColumnError, FileNotFoundError) as exc:
            messagebox.showerror("파일 오류", str(exc))
            return
        except Exception as exc:
            messagebox.showerror("읽기 실패", str(exc))
            return
        self._accept_frame(frame)

    def _load_files(self, paths: list[str]):
        try:
            frames = [loader.parse_table(path) for path in paths]
            frame = pd.concat(frames, ignore_index=True, sort=False)
        except (loader.ColumnError, FileNotFoundError) as exc:
            messagebox.showerror("파일 오류", str(exc))
            return
        except Exception as exc:
            messagebox.showerror("읽기 실패", str(exc))
            return
        self._accept_frame(frame)

    # ── 컬럼 매핑 ──────────────────────────────────────

    def _accept_frame(self, frame: pd.DataFrame, force_dialog: bool = False):
        """머리글 그대로인 표를 받아 매핑을 확정한 뒤 적재한다."""
        suggestion = loader.suggest_columns(list(frame.columns))
        needs_review = bool(suggestion["missing_required"]) or any(
            level == "확인" for level in suggestion["confidence"].values()
        )

        mapping = suggestion["mapping"]
        if force_dialog or needs_review:
            self.dialog_open = True
            try:
                dialog = ColumnMapDialog(self.root, frame, suggestion)
                self.root.wait_window(dialog)
            finally:
                self.dialog_open = False
            if dialog.result is None:
                return
            mapping = dialog.result

        try:
            self.raw_data = loader.apply_mapping(frame, mapping)
        except loader.ColumnError as exc:
            messagebox.showerror("컬럼 오류", str(exc))
            return

        self.source_frame = frame
        self._on_data_loaded()

    def _remap_columns(self):
        if self.source_frame is None:
            messagebox.showinfo("컬럼 매핑", "먼저 데이터를 입력하세요.")
            return
        self._accept_frame(self.source_frame, force_dialog=True)

    def _on_data_loaded(self):
        n_rows = len(self.raw_data)
        n_cols = len(self.raw_data.columns)

        self.data_status.config(
            text=f"{n_rows:,}행 / {n_cols}개 컬럼 인식",
            foreground=_COLORS["risk_low"],
        )
        self.columns_label.config(
            text="인식 컬럼: " + ", ".join(self.raw_data.columns.tolist()),
        )

        # 미리보기 채우기
        self._fill_preview()

        # 연도 체크박스
        try:
            temp = prep.prepare(self.raw_data)
            self.years = profile.available_years(temp["cases"])
        except Exception:
            self.years = []

        self._year_hint.pack_forget()
        for widget in list(self.year_frame.winfo_children()):
            if widget not in (self.all_years_cb, self._year_hint):
                widget.destroy()
        self.year_vars.clear()

        for year in self.years:
            var = tk.BooleanVar(value=True)
            self.year_vars[year] = var
            ttk.Checkbutton(
                self.year_frame, text=str(year), variable=var,
            ).pack(side="left", padx=(8, 0))

        if not self.years:
            self._year_hint.config(text="(연도 정보 없음)")
            self._year_hint.pack(side="left", padx=(8, 0))

        self.run_btn.config(state="normal")
        self._log(f"데이터 준비 완료: {n_rows:,}행, {n_cols}개 컬럼\n")
        self.notebook.select(self.tab_run)

    def _fill_preview(self):
        tree = self.preview_tree
        tree.delete(*tree.get_children())
        if self.raw_data is None:
            return

        cols = list(self.raw_data.columns)
        tree["columns"] = cols
        for col in cols:
            tree.heading(col, text=col)
            tree.column(col, width=90, anchor="w", minwidth=50)

        for i, (_, row) in enumerate(self.raw_data.head(200).iterrows()):
            values = [str(v) if pd.notna(v) else "" for v in row]
            tag = ("alt",) if i % 2 else ()
            tree.insert("", "end", values=values, tags=tag)

    # ── 설정 ─────────────────────────────────────────

    def _toggle_all_years(self):
        on = self.all_years_var.get()
        for var in self.year_vars.values():
            var.set(on)

    def _browse_outdir(self):
        folder = filedialog.askdirectory(title="결과 폴더 선택")
        if folder:
            self.outdir_var.set(folder)

    # ── 분석 실행 ────────────────────────────────────

    def _log(self, text: str):
        self.log_text.config(state="normal")
        self.log_text.insert("end", text + "\n")
        self.log_text.see("end")
        self.log_text.config(state="disabled")

    def _run_analysis(self):
        if self.running or self.raw_data is None:
            return

        self.running = True
        self.run_btn.config(state="disabled")
        self.paste_btn.config(state="disabled")
        self.file_btn.config(state="disabled")
        self.remap_btn.config(state="disabled")

        if self.all_years_var.get() or not self.year_vars:
            selected_years = None
        else:
            selected_years = [
                y for y, v in self.year_vars.items() if v.get()
            ]
            if not selected_years:
                selected_years = None

        org = self.org_level_var.get()
        org_level = None if org == "자동" else org
        outdir = self.outdir_var.get() or "분석결과"

        self.log_text.config(state="normal")
        self.log_text.delete("1.0", "end")
        self.log_text.config(state="disabled")

        raw = self.raw_data

        def worker():
            old_stdout, old_stderr = sys.stdout, sys.stderr
            redirector = _TextRedirector(self.log_text)
            sys.stdout = redirector
            sys.stderr = redirector
            try:
                code, info = pipeline_mod.run_pipeline(
                    raw,
                    outdir=outdir,
                    years=selected_years,
                    org_level=org_level,
                    prefix="유착분석",
                )
                self.exit_code = code
                self.pipeline_info = info
                if code == 0:
                    self.root.after(0, lambda: self._on_done(info))
                else:
                    self.root.after(0, self._on_fail)
            except PermissionError as error:
                traceback.print_exc()
                reason = (
                    "결과 파일을 저장하지 못했습니다.\n"
                    "같은 이름의 엑셀 파일을 열어 두었다면 닫고 다시 실행하세요.\n\n"
                    f"{error}"
                )
                self.root.after(0, lambda: self._on_fail(reason))
            except Exception as error:
                traceback.print_exc()
                detail = f"{type(error).__name__}: {error}"
                self.root.after(0, lambda: self._on_fail(detail))
            finally:
                sys.stdout = old_stdout
                sys.stderr = old_stderr
                self.root.after(0, self._unlock)

        threading.Thread(target=worker, daemon=True).start()

    def _on_done(self, info: dict):
        self._populate_results(info)
        self.notebook.select(self.tab_results)

        outdir = info.get("outdir", "분석결과")
        resolved = Path(outdir).resolve()
        ok = messagebox.askyesno(
            "완료",
            f"분석이 완료되었습니다.\n\n결과 폴더를 열까요?\n{resolved}",
        )
        if ok:
            _open_folder(resolved)

    def _on_fail(self, reason: str = ""):
        body = "분석 중 오류가 발생했습니다.\n진행 상황 로그를 확인하세요."
        if reason:
            body = f"{reason}\n\n자세한 내용은 진행 상황 로그에 있습니다."
        messagebox.showerror("오류", body)

    def _unlock(self):
        self.running = False
        self.run_btn.config(state="normal")
        self.paste_btn.config(state="normal")
        self.file_btn.config(state="normal")
        self.remap_btn.config(state="normal")


def _open_folder(path: Path):
    try:
        if sys.platform == "win32":
            os.startfile(str(path))
        elif sys.platform == "darwin":
            os.system(f'open "{path}"')
        else:
            os.system(f'xdg-open "{path}"')
    except Exception:
        pass


def _open_file_path(path: str | None):
    if not path:
        return
    p = Path(path)
    if not p.exists():
        return
    try:
        if sys.platform == "win32":
            os.startfile(str(p))
        elif sys.platform == "darwin":
            os.system(f'open "{p}"')
        else:
            os.system(f'xdg-open "{p}"')
    except Exception:
        pass
