"""tkinter GUI.

내부망 PC에서 파일 읽기가 보안으로 막혀 있어 엑셀에서 복사한 데이터를
붙여넣기로 입력받는다. 분석 옵션을 고르고 실행까지 한 화면에서 진행한다.
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
except ImportError:
    HAS_TK = False

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
        self.years: list[int] = []
        self.year_vars: dict[int, tk.BooleanVar] = {}
        self.all_years_var = tk.BooleanVar(value=True)
        self.org_level_var = tk.StringVar(value="자동")
        self.outdir_var = tk.StringVar(value="분석결과")
        self.running = False
        self.exit_code = 0

        root.title("외부조사 법인 유착 혐의 1차 분석")
        root.geometry("720x760")
        root.minsize(600, 550)

        self._build_ui()

        if argv:
            dropped = [p for p in argv if not p.startswith("-")]
            if dropped:
                self.root.after(200, lambda: self._load_files(dropped))

    # ── UI 구성 ──────────────────────────────────────────

    def _build_ui(self):
        style = ttk.Style()
        try:
            style.theme_use("vista")
        except tk.TclError:
            pass

        main = ttk.Frame(self.root, padding=16)
        main.pack(fill="both", expand=True)

        ttk.Label(
            main,
            text="외부조사 법인 유착 혐의 1차 분석",
            font=("맑은 고딕", 14, "bold"),
        ).pack(pady=(0, 12))

        # ── 데이터 입력 ──
        inp = ttk.LabelFrame(main, text=" 데이터 입력 ", padding=12)
        inp.pack(fill="x", pady=(0, 8))

        btn_row = ttk.Frame(inp)
        btn_row.pack(fill="x")

        self.paste_btn = ttk.Button(
            btn_row,
            text="  엑셀 데이터 붙여넣기  ",
            command=self._paste_clipboard,
        )
        self.paste_btn.pack(side="left", padx=(0, 8), ipady=4)

        self.file_btn = ttk.Button(
            btn_row, text="CSV 파일 열기", command=self._open_file
        )
        self.file_btn.pack(side="left")

        ttk.Label(
            inp,
            text="엑셀에서 머리글 포함 전체를 선택 → Ctrl+C → 위 버튼 클릭",
            foreground="gray",
        ).pack(anchor="w", pady=(6, 0))

        self.data_status = ttk.Label(inp, text="데이터 없음", foreground="red")
        self.data_status.pack(anchor="w", pady=(4, 0))

        self.columns_label = ttk.Label(
            inp, text="", foreground="gray", wraplength=640
        )
        self.columns_label.pack(anchor="w")

        # ── 분석 설정 ──
        settings = ttk.LabelFrame(main, text=" 분석 설정 ", padding=12)
        settings.pack(fill="x", pady=(0, 8))

        ttk.Label(settings, text="기준년:").grid(
            row=0, column=0, sticky="nw", pady=4
        )
        self.year_frame = ttk.Frame(settings)
        self.year_frame.grid(row=0, column=1, sticky="w", padx=(8, 0), pady=4)

        self.all_years_cb = ttk.Checkbutton(
            self.year_frame,
            text="전체",
            variable=self.all_years_var,
            command=self._toggle_all_years,
        )
        self.all_years_cb.pack(side="left")

        self._year_hint = ttk.Label(
            self.year_frame, text="(데이터를 먼저 입력하세요)", foreground="gray"
        )
        self._year_hint.pack(side="left", padx=(8, 0))

        ttk.Label(settings, text="조직계층:").grid(
            row=1, column=0, sticky="w", pady=4
        )
        ttk.Combobox(
            settings,
            textvariable=self.org_level_var,
            values=["자동", "소속1", "소속2", "소속3"],
            state="readonly",
            width=12,
        ).grid(row=1, column=1, sticky="w", padx=(8, 0), pady=4)

        ttk.Label(settings, text="결과 폴더:").grid(
            row=2, column=0, sticky="w", pady=4
        )
        out_row = ttk.Frame(settings)
        out_row.grid(row=2, column=1, sticky="we", padx=(8, 0), pady=4)
        ttk.Entry(out_row, textvariable=self.outdir_var, width=30).pack(
            side="left", fill="x", expand=True
        )
        ttk.Button(
            out_row, text="선택", width=6, command=self._browse_outdir
        ).pack(side="left", padx=(4, 0))

        settings.columnconfigure(1, weight=1)

        # ── 실행 ──
        self.run_btn = ttk.Button(
            main,
            text="  분석 시작  ",
            command=self._run_analysis,
            state="disabled",
        )
        self.run_btn.pack(pady=8, ipady=4)

        # ── 진행 상황 ──
        log_frame = ttk.LabelFrame(main, text=" 진행 상황 ", padding=8)
        log_frame.pack(fill="both", expand=True)

        self.log_text = tk.Text(
            log_frame,
            height=12,
            state="disabled",
            font=("Consolas", 9),
            wrap="word",
            bg="#1e1e1e",
            fg="#cccccc",
            insertbackground="#cccccc",
        )
        scrollbar = ttk.Scrollbar(log_frame, command=self.log_text.yview)
        self.log_text.config(yscrollcommand=scrollbar.set)
        scrollbar.pack(side="right", fill="y")
        self.log_text.pack(side="left", fill="both", expand=True)

    # ── 데이터 입력 ────────────────────────────────────

    def _paste_clipboard(self):
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
            self.raw_data = loader.read_clipboard_text(text)
        except loader.ColumnError as exc:
            messagebox.showerror("컬럼 오류", str(exc))
            return
        except Exception as exc:
            messagebox.showerror(
                "읽기 실패",
                f"클립보드 데이터를 해석하지 못했습니다.\n\n{exc}",
            )
            return

        self._on_data_loaded()

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
            self.raw_data = loader.read_table(path)
        except (loader.ColumnError, FileNotFoundError) as exc:
            messagebox.showerror("파일 오류", str(exc))
            return
        except Exception as exc:
            messagebox.showerror("읽기 실패", str(exc))
            return
        self._on_data_loaded()

    def _load_files(self, paths: list[str]):
        try:
            self.raw_data = loader.read_many(paths)
        except (loader.ColumnError, FileNotFoundError) as exc:
            messagebox.showerror("파일 오류", str(exc))
            return
        except Exception as exc:
            messagebox.showerror("읽기 실패", str(exc))
            return
        self._on_data_loaded()

    def _on_data_loaded(self):
        n_rows = len(self.raw_data)
        n_cols = len(self.raw_data.columns)

        self.data_status.config(
            text=f"{n_rows:,}행 / {n_cols}개 컬럼 인식", foreground="green"
        )
        self.columns_label.config(
            text="인식 컬럼: " + ", ".join(self.raw_data.columns.tolist())
        )

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
                self.year_frame, text=str(year), variable=var
            ).pack(side="left", padx=(8, 0))

        if not self.years:
            self._year_hint.config(text="(연도 정보 없음)")
            self._year_hint.pack(side="left", padx=(8, 0))

        self.run_btn.config(state="normal")
        self._log(f"데이터 준비 완료: {n_rows:,}행, {n_cols}개 컬럼")

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
                code = pipeline_mod.run_pipeline(
                    raw,
                    outdir=outdir,
                    years=selected_years,
                    org_level=org_level,
                    prefix="유착분석",
                )
                self.exit_code = code
                if code == 0:
                    self.root.after(0, lambda: self._on_done(outdir))
                else:
                    self.root.after(0, self._on_fail)
            except Exception:
                traceback.print_exc()
                self.root.after(0, self._on_fail)
            finally:
                sys.stdout = old_stdout
                sys.stderr = old_stderr
                self.root.after(0, self._unlock)

        threading.Thread(target=worker, daemon=True).start()

    def _on_done(self, outdir: str):
        resolved = Path(outdir).resolve()
        ok = messagebox.askyesno(
            "완료",
            f"분석이 완료되었습니다.\n\n결과 폴더를 열까요?\n{resolved}",
        )
        if ok:
            _open_folder(resolved)

    def _on_fail(self):
        messagebox.showerror(
            "오류",
            "분석 중 오류가 발생했습니다.\n진행 상황 로그를 확인하세요.",
        )

    def _unlock(self):
        self.running = False
        self.run_btn.config(state="normal")
        self.paste_btn.config(state="normal")
        self.file_btn.config(state="normal")


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
