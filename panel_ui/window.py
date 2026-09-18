"""Окно панели: оформление, шапка, вкладки и общее обновление.

Здесь нет ни одного запроса к базе и ни одной таблицы: каждая вкладка живёт в своём файле
(`panel_ui/overview.py`, `panel_ui/transactions.py`, …) и сама знает, что показывать.
Окно отвечает за то, что общее: стиль, шапку, строку состояния и порядок вкладок.
"""
import os
import tkinter as tk
from datetime import datetime
from tkinter import ttk

from config import DB_PATH
from panel_ui.analytics import AnalyticsTab
from panel_ui.budget import BudgetTab
from panel_ui.export import ExportTab
from panel_ui.overview import OverviewTab
from panel_ui.products import ProductsTab
from panel_ui.receipts import ReceiptsTab
from panel_ui.theme import (ACCENT, BACKGROUND, FIELD, FONT, FONT_BOLD, FONT_TITLE, FONT_VALUE,
                            MUTED, PANEL, TEXT)
from panel_ui.transactions import TransactionsTab
from panel_ui.users import UsersTab

# Порядок вкладок в окне — он же порядок, в котором их строит Tk.
TAB_CLASSES = (OverviewTab, TransactionsTab, ReceiptsTab, ProductsTab, AnalyticsTab,
               BudgetTab, UsersTab, ExportTab)


class Panel(tk.Tk):
    """Одно окно с вкладками; данные перечитываются кнопкой «Обновить» или по F5."""

    def __init__(self):
        super().__init__()
        self.title("Finance Bot — панель управления")
        self.geometry("1180x720")
        self.minsize(980, 600)
        self.configure(background=BACKGROUND)
        self._style()
        self._build_header()
        self.notebook = ttk.Notebook(self)
        self.notebook.pack(fill="both", expand=True, padx=10, pady=(0, 6))
        self.status = tk.StringVar(value="Готово")
        self.tabs = [tab_class(self) for tab_class in TAB_CLASSES]
        self.transactions = self.tab(TransactionsTab)
        ttk.Label(self, textvariable=self.status, style="Muted.TLabel",
                  padding=(12, 2, 12, 8)).pack(fill="x")
        self.bind("<F5>", lambda _event: self.refresh_all())
        self.after(200, self.refresh_all)

    def tab(self, tab_class):
        """Вкладка по классу: так вкладки ссылаются друг на друга, не зная своих индексов."""
        return next(tab for tab in self.tabs if isinstance(tab, tab_class))

    def refresh_all(self) -> None:
        """Перечитывает все вкладки: одна кнопка и один код на обновление."""
        for tab in self.tabs:
            tab.refresh()
        self.status_text("Данные обновлены из базы")

    def status_text(self, text: str) -> None:
        self.status.set(f"{text} · {datetime.now().strftime('%H:%M:%S')}")

    # ─── Оформление ──────────────────────────────────────────────────────

    def _style(self) -> None:
        style = ttk.Style(self)
        style.theme_use("clam")
        style.configure(".", background=BACKGROUND, foreground=TEXT, fieldbackground=FIELD,
                        font=FONT, borderwidth=0)
        style.configure("TFrame", background=BACKGROUND)
        style.configure("Panel.TFrame", background=PANEL)
        style.configure("TLabel", background=BACKGROUND, foreground=TEXT)
        style.configure("Muted.TLabel", foreground=MUTED)
        style.configure("Title.TLabel", font=FONT_TITLE, background=BACKGROUND)
        style.configure("Value.TLabel", font=FONT_VALUE, background=PANEL)
        style.configure("TNotebook", background=BACKGROUND, borderwidth=0)
        style.configure("TNotebook.Tab", background=PANEL, foreground=MUTED, padding=(14, 8))
        style.map("TNotebook.Tab", background=[("selected", FIELD)], foreground=[("selected", TEXT)])
        style.configure("TButton", background=FIELD, foreground=TEXT, padding=(10, 6))
        style.map("TButton", background=[("active", "#3d4350")])
        style.configure("Accent.TButton", background="#2f6f52", foreground=TEXT)
        style.configure("Treeview", background=PANEL, fieldbackground=PANEL, foreground=TEXT,
                        rowheight=24)
        style.configure("Treeview.Heading", background=FIELD, foreground=TEXT, font=FONT_BOLD)
        style.map("Treeview", background=[("selected", "#3a5a4a")])
        style.configure("TCombobox", fieldbackground=FIELD, background=FIELD, foreground=TEXT)
        style.configure("TEntry", fieldbackground=FIELD, foreground=TEXT)
        style.configure("TCheckbutton", background=BACKGROUND, foreground=TEXT)

    def _build_header(self) -> None:
        header = ttk.Frame(self, padding=(12, 10, 12, 6))
        header.pack(fill="x")
        ttk.Label(header, text="🖥 Панель управления", style="Title.TLabel").pack(side="left")
        ttk.Label(header, text=f"  база: {DB_PATH}", style="Muted.TLabel").pack(side="left")
        ttk.Button(header, text="🔄 Обновить (F5)", command=self.refresh_all).pack(side="right")


def main() -> None:
    if not os.path.isfile(DB_PATH):
        print(f"⚠️ База не найдена: {DB_PATH}. Запусти бота хотя бы раз (start_bot.bat).")
    Panel().mainloop()
