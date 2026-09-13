"""Панель управления ботом на Tkinter: база, чеки, бюджет, пользователи и выгрузка CSV.

Запуск: `start_panel.bat` (или `venv\\Scripts\\python.exe panel.py`). Панель работает с той же
базой, что и бот, поэтому её можно держать открытой рядом: блокировки пережидаются
(`database/panel_data.py`), а Telegram продолжает принимать траты.

Выгрузка CSV переехала сюда из бота: файл удобнее смотреть на компьютере, чем пересылать
в чат. Формат файла тот же — его собирает `services/export.py`, чтобы не расходиться.
"""
import asyncio
import os
import tkinter as tk
from datetime import datetime
from tkinter import filedialog, messagebox, simpledialog, ttk

import pandas as pd

from config import DB_PATH, USERS
from database import panel_data as data
from database.models import CATEGORIES
from services import budget as budget_service, charts, profile
from services.export import export_csv
from utils.formatting import format_amount, get_category_emoji

BACKGROUND = "#1e2128"
PANEL = "#282c34"
FIELD = "#333844"
TEXT = "#e9ebee"
MUTED = "#9aa0a6"
ACCENT = "#4fd48c"
DANGER = "#ff6b6b"
FONT = ("Segoe UI", 10)
FONT_BOLD = ("Segoe UI", 10, "bold")
FONT_TITLE = ("Segoe UI", 14, "bold")

PERIODS = {"7 дней": 7, "30 дней": 30, "90 дней": 90, "Год": 365, "Всё время": None}
TX_TYPES = {"расходы": "expense", "доходы": "income", "платежи по долгам": "debt_payment",
            "все": None}
TX_TYPE_RU = {"expense": "расход", "income": "доход", "debt_payment": "платёж"}
ALL_USERS = "Все пользователи"


def _run(coro):
    """Выполняет корутину сервисов бота (они асинхронные) из синхронного Tk."""
    return asyncio.run(coro)


def _user_name(user_id: int) -> str:
    if user_id is None:
        return ALL_USERS
    return (USERS.get(user_id) or {}).get("name", str(user_id))


def _strip_emoji(text: str) -> str:
    """В Tk нет эмодзи-шрифта: из заголовков категорий эмодзи убираем."""
    return "".join(char for char in text if ord(char) < 0x2190).strip()


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
        self._build_overview()
        self._build_transactions()
        self._build_receipts()
        self._build_budget()
        self._build_users()
        self._build_export()
        self._build_status()
        self.bind("<F5>", lambda _event: self.refresh_all())
        self.after(200, self.refresh_all)

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
        style.configure("Value.TLabel", font=("Segoe UI", 16, "bold"), background=PANEL)
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

    def _build_status(self) -> None:
        self.status = tk.StringVar(value="Готово")
        ttk.Label(self, textvariable=self.status, style="Muted.TLabel",
                  padding=(12, 2, 12, 8)).pack(fill="x")

    def _build_overview(self) -> None:
        tab = ttk.Frame(self.notebook, padding=12)
        self.notebook.add(tab, text="Обзор")

        filters = ttk.Frame(tab)
        filters.pack(fill="x")
        ttk.Label(filters, text="Пользователь:").pack(side="left")
        self.overview_user = ttk.Combobox(filters, state="readonly", width=24,
                                          values=self._user_choices())
        self.overview_user.set(ALL_USERS)
        self.overview_user.pack(side="left", padx=(6, 16))
        self.overview_user.bind("<<ComboboxSelected>>", lambda _e: self.refresh_overview())
        ttk.Label(filters, text="Период:").pack(side="left")
        self.overview_period = ttk.Combobox(filters, state="readonly", width=14,
                                            values=list(PERIODS))
        self.overview_period.set("30 дней")
        self.overview_period.pack(side="left", padx=6)
        self.overview_period.bind("<<ComboboxSelected>>", lambda _e: self.refresh_overview())

        cards = ttk.Frame(tab)
        cards.pack(fill="x", pady=12)
        self.kpi_vars = {}
        for title in ("Потрачено", "Доход", "Баланс", "Долги"):
            card = ttk.Frame(cards, style="Panel.TFrame", padding=12)
            card.pack(side="left", expand=True, fill="x", padx=(0, 10))
            ttk.Label(card, text=title, style="Muted.TLabel", background=PANEL).pack(anchor="w")
            variable = tk.StringVar(value="—")
            ttk.Label(card, textvariable=variable, style="Value.TLabel").pack(anchor="w")
            self.kpi_vars[title] = variable

        chart_frame = ttk.Frame(tab, style="Panel.TFrame", padding=10)
        chart_frame.pack(fill="both", expand=True)
        ttk.Label(chart_frame, text="Траты по категориям", background=PANEL,
                  foreground=MUTED).pack(anchor="w")
        self.chart_canvas = tk.Canvas(chart_frame, background=PANEL, highlightthickness=0)
        self.chart_canvas.pack(fill="both", expand=True)
        self.chart_canvas.bind("<Configure>", lambda _e: self._draw_chart())

    def _build_transactions(self) -> None:
        tab = ttk.Frame(self.notebook, padding=12)
        self.notebook.add(tab, text="Транзакции")

        filters = ttk.Frame(tab)
        filters.pack(fill="x")
        ttk.Label(filters, text="Пользователь:").pack(side="left")
        self.tx_user = ttk.Combobox(filters, state="readonly", width=20,
                                    values=self._user_choices())
        self.tx_user.set(ALL_USERS)
        self.tx_user.pack(side="left", padx=(6, 12))
        ttk.Label(filters, text="Период:").pack(side="left")
        self.tx_period = ttk.Combobox(filters, state="readonly", width=12, values=list(PERIODS))
        self.tx_period.set("30 дней")
        self.tx_period.pack(side="left", padx=(6, 12))
        ttk.Label(filters, text="Тип:").pack(side="left")
        self.tx_type = ttk.Combobox(filters, state="readonly", width=18, values=list(TX_TYPES))
        self.tx_type.set("расходы")
        self.tx_type.pack(side="left", padx=(6, 12))
        ttk.Label(filters, text="Поиск:").pack(side="left")
        self.tx_search = ttk.Entry(filters, width=22)
        self.tx_search.pack(side="left", padx=6)
        for widget in (self.tx_user, self.tx_period, self.tx_type):
            widget.bind("<<ComboboxSelected>>", lambda _e: self.refresh_transactions())
        self.tx_search.bind("<Return>", lambda _e: self.refresh_transactions())
        ttk.Button(filters, text="Найти", command=self.refresh_transactions).pack(side="left")

        buttons = ttk.Frame(tab)
        buttons.pack(fill="x", pady=8)
        ttk.Button(buttons, text="➕ Добавить", command=self.add_transaction).pack(side="left")
        ttk.Button(buttons, text="✏️ Изменить", command=self.edit_selected).pack(side="left", padx=6)
        ttk.Button(buttons, text="🗑 Удалить", command=self.delete_selected).pack(side="left")
        self.tx_count = tk.StringVar(value="")
        ttk.Label(buttons, textvariable=self.tx_count,
                  style="Muted.TLabel").pack(side="left", padx=12)

        columns = ("id", "date", "user", "amount", "category", "subcategory", "description", "type")
        titles = ("ID", "Дата", "Пользователь", "Сумма", "Категория", "Подкатегория", "Описание",
                  "Тип")
        widths = (60, 130, 130, 100, 110, 120, 260, 80)
        container = ttk.Frame(tab)
        container.pack(fill="both", expand=True)
        self.tx_tree = self._tree(container, columns, titles, widths,
                                  height=16, on_double=self.edit_selected)
        self.tx_tree.bind("<<TreeviewSelect>>", lambda _e: self.show_selected_items())

        self.items_label = ttk.Label(tab, text="Позиции чека: выбери запись с чеком",
                                     style="Muted.TLabel")
        self.items_label.pack(anchor="w", pady=(8, 2))
        self.items_tree = self._tree(tab, ("name", "qty", "price", "sum"),
                                     ("Товар", "Кол-во", "Цена", "Сумма"), (420, 80, 100, 110),
                                     height=6)

    def _build_receipts(self) -> None:
        tab = ttk.Frame(self.notebook, padding=12)
        self.notebook.add(tab, text="Чеки")
        ttk.Label(tab, text="Чеки, прочитанные с фото: магазин, итог, позиции",
                  style="Muted.TLabel").pack(anchor="w")

        columns = ("id", "date", "user", "store", "amount", "category", "items")
        titles = ("ID", "Дата", "Пользователь", "Магазин", "Итог чека", "Категория", "Позиций")
        container = ttk.Frame(tab)
        container.pack(fill="both", expand=True, pady=8)
        self.receipt_tree = self._tree(container, columns, titles,
                                       (60, 130, 130, 240, 110, 110, 80), height=12)
        self.receipt_tree.bind("<<TreeviewSelect>>", lambda _e: self.show_receipt_items())

        buttons = ttk.Frame(tab)
        buttons.pack(fill="x")
        ttk.Button(buttons, text="🗑 Удалить чек", command=self.delete_receipt).pack(side="left")
        self.receipt_sum = tk.StringVar(value="")
        ttk.Label(buttons, textvariable=self.receipt_sum,
                  style="Muted.TLabel").pack(side="left", padx=12)

        self.receipt_items_tree = self._tree(tab, ("name", "qty", "price", "sum"),
                                             ("Товар", "Кол-во", "Цена", "Сумма"),
                                             (520, 80, 100, 120), height=8)

    def _build_budget(self) -> None:
        tab = ttk.Frame(self.notebook, padding=12)
        self.notebook.add(tab, text="Бюджет")
        ttk.Label(tab, text="Лимиты месяца: правятся здесь и сразу видны боту",
                  style="Muted.TLabel").pack(anchor="w")
        self.budget_income_note = ttk.Label(tab, text="", style="Muted.TLabel")
        self.budget_income_note.pack(anchor="w", pady=(2, 10))

        grid = ttk.Frame(tab, style="Panel.TFrame", padding=12)
        grid.pack(fill="x")
        for column, title in enumerate(("Категория", "Лимит, ₽", "Потрачено за месяц", "Остаток")):
            ttk.Label(grid, text=title, background=PANEL, foreground=MUTED,
                      font=FONT_BOLD).grid(row=0, column=column, sticky="w", padx=8, pady=4)

        self.budget_entries: dict[str, ttk.Entry] = {}
        self.budget_left: dict[str, tuple[tk.StringVar, float]] = {}
        spent_by_category = data.category_totals(days=31)
        for index, category in enumerate(CATEGORIES, start=1):
            ttk.Label(grid, text=f"{_strip_emoji(get_category_emoji(category))} {category}",
                      background=PANEL).grid(row=index, column=0, sticky="w", padx=8, pady=2)
            entry = ttk.Entry(grid, width=14)
            entry.grid(row=index, column=1, padx=8, pady=2)
            self.budget_entries[category] = entry
            spent = spent_by_category.get(category, 0)
            ttk.Label(grid, text=format_amount(spent) if spent else "—",
                      background=PANEL).grid(row=index, column=2, sticky="w", padx=8)
            variable = tk.StringVar(value="")
            ttk.Label(grid, textvariable=variable, background=PANEL).grid(
                row=index, column=3, sticky="w", padx=8)
            self.budget_left[category] = (variable, spent)

        last = len(CATEGORIES) + 1
        ttk.Label(grid, text="Всего за месяц", background=PANEL,
                  font=FONT_BOLD).grid(row=last, column=0, sticky="w", padx=8, pady=(8, 2))
        self.budget_total_entry = ttk.Entry(grid, width=14)
        self.budget_total_entry.grid(row=last, column=1, padx=8, pady=(8, 2))

        buttons = ttk.Frame(tab)
        buttons.pack(fill="x", pady=12)
        ttk.Button(buttons, text="💾 Сохранить лимиты", style="Accent.TButton",
                   command=self.save_budget).pack(side="left")
        ttk.Button(buttons, text="📉 Предложить по доходу",
                   command=self.propose_budget).pack(side="left", padx=8)
        ttk.Button(buttons, text="↩️ Сбросить к стартовым",
                   command=self.reset_budget).pack(side="left")
        ttk.Button(buttons, text="📈 Показать графики бота",
                   command=self.preview_charts).pack(side="right")

        debts_frame = ttk.Frame(tab, style="Panel.TFrame", padding=12)
        debts_frame.pack(fill="both", expand=True, pady=(12, 0))
        ttk.Label(debts_frame, text="Долги", background=PANEL, font=FONT_BOLD).pack(anchor="w")
        self.debt_tree = self._tree(debts_frame, ("name", "current", "rate", "payment", "status"),
                                    ("Кредит", "Остаток", "Ставка", "Платёж", "Статус"),
                                    (240, 140, 90, 110, 100), height=5)
        ttk.Button(debts_frame, text="✏️ Изменить остаток",
                   command=self.edit_debt).pack(anchor="w", pady=6)

    def _build_users(self) -> None:
        tab = ttk.Frame(self.notebook, padding=12)
        self.notebook.add(tab, text="Пользователи")
        ttk.Label(tab, text="Настройки каждого пользователя: имя, план дохода, настройка бота",
                  style="Muted.TLabel").pack(anchor="w")
        columns = ("id", "name", "role", "income", "onboarded", "transactions", "spent", "income",
                   "last")
        titles = ("Telegram ID", "Имя", "Роль", "План дохода", "Настройка", "Записей",
                  "Потрачено", "Доход", "Последняя запись")
        container = ttk.Frame(tab)
        container.pack(fill="both", expand=True, pady=8)
        self.users_tree = self._tree(container, columns, titles,
                                     (100, 150, 90, 110, 90, 80, 120, 120, 140), height=10)
        self.users_tree.bind("<Double-1>", lambda _event: self.rename_user())

        buttons = ttk.Frame(tab)
        buttons.pack(fill="x")
        ttk.Button(buttons, text="✏️ Имя", command=self.rename_user).pack(side="left")
        ttk.Button(buttons, text="💰 План дохода", command=self.set_income_plan).pack(side="left",
                                                                                     padx=6)
        ttk.Button(buttons, text="🚀 Пройти настройку заново",
                   command=self.reset_onboarding).pack(side="left")
        ttk.Button(buttons, text="📄 Показать транзакции",
                   command=self.show_user_transactions).pack(side="left", padx=6)
        self.user_note = ttk.Label(tab, text="", style="Muted.TLabel")
        self.user_note.pack(anchor="w", pady=8)

    def _build_export(self) -> None:
        tab = ttk.Frame(self.notebook, padding=12)
        self.notebook.add(tab, text="Экспорт CSV")
        ttk.Label(tab, text="Выгрузка транзакций в CSV (Excel-совместимый, разделитель «;»)",
                  style="Muted.TLabel").pack(anchor="w")
        ttk.Label(tab, text="Раньше эта кнопка была в Telegram — файлы удобнее хранить на компьютере.",
                  style="Muted.TLabel").pack(anchor="w", pady=(2, 12))

        filters = ttk.Frame(tab)
        filters.pack(fill="x")
        ttk.Label(filters, text="Пользователь:").pack(side="left")
        self.export_user = ttk.Combobox(filters, state="readonly", width=24,
                                        values=self._user_choices())
        self.export_user.set(ALL_USERS)
        self.export_user.pack(side="left", padx=(6, 16))
        ttk.Label(filters, text="Период:").pack(side="left")
        self.export_period = ttk.Combobox(filters, state="readonly", width=14, values=list(PERIODS))
        self.export_period.set("Год")
        self.export_period.pack(side="left", padx=6)

        ttk.Button(tab, text="💾 Сохранить CSV...", style="Accent.TButton",
                   command=self.export_csv_file).pack(anchor="w", pady=14)
        self.export_info = ttk.Label(tab, text="", style="Muted.TLabel")
        self.export_info.pack(anchor="w")

    # ─── Вспомогательные элементы ────────────────────────────────────────

    def _user_choices(self) -> list[str]:
        ids = {row["user_id"] for row in data.query("SELECT DISTINCT user_id FROM transactions")}
        ids |= set(USERS)
        return [ALL_USERS] + [f"{_user_name(user_id)} ({user_id})" for user_id in sorted(ids)]

    def _selected_user(self, combobox) -> int | None:
        value = combobox.get()
        if value == ALL_USERS or "(" not in value:
            return None
        try:
            return int(value.rsplit("(", 1)[1].rstrip(")"))
        except ValueError:
            return None

    def _selected_period(self, combobox) -> int | None:
        return PERIODS.get(combobox.get(), 30)

    def _tree(self, parent, columns, titles, widths, height=10, on_double=None):
        container = parent
        tree = ttk.Treeview(container, columns=columns, show="headings", height=height)
        for column, title, width in zip(columns, titles, widths):
            tree.heading(column, text=title)
            tree.column(column, width=width, anchor="w", stretch=False)
        scrollbar = ttk.Scrollbar(container, orient="vertical", command=tree.yview)
        tree.configure(yscrollcommand=scrollbar.set)
        tree.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")
        if on_double:
            tree.bind("<Double-1>", lambda _event: on_double())
        return tree

    def _fill_tree(self, tree, rows: list[tuple]) -> None:
        tree.delete(*tree.get_children())
        for row in rows:
            tree.insert("", "end", values=row)

    def _status(self, text: str) -> None:
        self.status.set(f"{text} · {datetime.now().strftime('%H:%M:%S')}")

    # ─── Обновление данных ───────────────────────────────────────────────

    def refresh_all(self) -> None:
        self.refresh_overview()
        self.refresh_transactions()
        self.refresh_receipts()
        self.refresh_budget()
        self.refresh_users()
        self._status("Данные обновлены из базы")

    def refresh_overview(self) -> None:
        user_id = self._selected_user(self.overview_user)
        days = self._selected_period(self.overview_period)
        stats = data.period_totals(days, user_id)
        debts_total = sum(row["current_amount"] for row in data.debts() if row["status"] == "active")
        self.kpi_vars["Потрачено"].set(format_amount(stats["spent"]))
        self.kpi_vars["Доход"].set(format_amount(stats["income"]))
        self.kpi_vars["Баланс"].set(format_amount(stats["income"] - stats["spent"]))
        self.kpi_vars["Долги"].set(format_amount(debts_total))
        self._chart_values = data.category_totals(days, user_id)
        self._draw_chart()

    def _draw_chart(self) -> None:
        """Полосы по категориям прямо на Canvas — без картинок и внешних окон."""
        canvas = getattr(self, "chart_canvas", None)
        values = getattr(self, "_chart_values", None)
        if canvas is None or not values:
            return
        canvas.delete("all")
        width = max(canvas.winfo_width(), 320)
        height = max(canvas.winfo_height(), 200)
        items = list(values.items())[:10]
        top = max(values.values()) or 1
        row_height = min(30, max(20, (height - 20) // max(len(items), 1)))
        label_width = 150
        for index, (category, amount) in enumerate(items):
            y = 10 + index * row_height
            canvas.create_text(10, y + row_height / 2, anchor="w", fill=TEXT,
                               text=_strip_emoji(get_category_emoji(category)) + " " + category)
            bar_width = (width - label_width - 170) * amount / top
            color = charts.COLORS[index % len(charts.COLORS)] if charts.CHARTS_AVAILABLE \
                else ACCENT
            canvas.create_rectangle(label_width, y + 4, label_width + max(bar_width, 2),
                                    y + row_height - 4, fill=color, outline="")
            canvas.create_text(label_width + bar_width + 12, y + row_height / 2, anchor="w",
                               fill=MUTED, text=format_amount(amount))
        if not items:
            canvas.create_text(20, 30, anchor="w", fill=MUTED, text="Трат за период нет")

    def refresh_transactions(self) -> None:
        rows = data.transactions(user_id=self._selected_user(self.tx_user),
                                 days=self._selected_period(self.tx_period),
                                 tx_type=TX_TYPES.get(self.tx_type.get()),
                                 search=self.tx_search.get().strip())
        self._fill_tree(self.tx_tree, [
            (row["id"], (row["created_at"] or "")[:16],
             (USERS.get(row["user_id"]) or {}).get("name", row["user_id"]),
             format_amount(row["amount"]), row["category"], row["subcategory"] or "",
             row["description"] or "", TX_TYPE_RU.get(row["tx_type"], row["tx_type"]))
            for row in rows])
        self.tx_count.set(f"записей: {len(rows)}")
        total = sum(row["amount"] for row in rows if row["tx_type"] == "expense")
        self.items_label.configure(text=f"Позиции чека · расход за выборку: {format_amount(total)}")
        self._fill_tree(self.items_tree, [])

    def refresh_receipts(self) -> None:
        rows = data.receipt_transactions()
        self._fill_tree(self.receipt_tree, [
            (row["id"], (row["created_at"] or "")[:16],
             (USERS.get(row["user_id"]) or {}).get("name", row["user_id"]),
             row["description"] or "—", format_amount(row["amount"]), row["category"],
             row["items_count"])
            for row in rows])
        self._fill_tree(self.receipt_items_tree, [])
        self.receipt_sum.set(f"чеков с позициями: {len(rows)}")

    def refresh_budget(self) -> None:
        limits = data.limits()
        spend = data.category_totals(days=31)
        for category, entry in self.budget_entries.items():
            entry.delete(0, "end")
            limit = limits.get(category, 0)
            entry.insert(0, f"{limit:.0f}" if limit else "0")
            variable, spent = self.budget_left[category]
            if not limit:
                variable.set("без лимита")
                continue
            left = limit - spent
            variable.set(("осталось " if left >= 0 else "перерасход ") + format_amount(abs(left)))
        self.budget_total_entry.delete(0, "end")
        self.budget_total_entry.insert(0, f"{limits.get('total', 0):.0f}")
        incomes = [f"{_user_name(user_id)}: {format_amount(value)}"
                   for user_id, value in self._income_plans().items()]
        self.budget_income_note.configure(
            text=("План дохода — " + ", ".join(incomes)) if incomes
            else "План дохода не задан: задайте его на вкладке «Пользователи»")
        self._fill_tree(self.debt_tree, [
            (row["name"], format_amount(row["current_amount"]),
             f"{row['interest_rate'] or 0:.0f}%", format_amount(row["min_payment"] or 0),
             "закрыт" if row["status"] == "closed" else "активен")
            for row in data.debts()])

    def _income_plans(self) -> dict[int, float]:
        """План дохода по пользователям — из тех же настроек, что пишет бот."""
        plans = {}
        for key, value in data.settings_map().items():
            if not (key.startswith("profile:") and key.endswith(":income")):
                continue
            try:
                plans[int(key.split(":")[1])] = float(value)
            except (ValueError, IndexError):
                continue
        return plans

    def refresh_users(self) -> None:
        self._fill_tree(self.users_tree, [
            (row["user_id"], row["name"], row["role"], row["income_plan"], row["onboarded"],
             row["transactions"], format_amount(row["spent"]), format_amount(row["income"]),
             row["last"])
            for row in data.users_overview()])

    # ─── Действия: транзакции ────────────────────────────────────────────

    def _selected_tx_id(self) -> int | None:
        selection = self.tx_tree.selection()
        if not selection:
            messagebox.showinfo("Нужна запись", "Выбери запись в таблице")
            return None
        return int(self.tx_tree.item(selection[0], "values")[0])

    def show_selected_items(self) -> None:
        tx_id = self.tx_tree.selection()
        if not tx_id:
            return
        row_id = int(self.tx_tree.item(tx_id[0], "values")[0])
        items = data.receipt_items(row_id)
        self._fill_tree(self.items_tree, [
            (item["name"], f"{item['qty']:.0f}" if item["qty"] else "1",
             format_amount(item["price"]), format_amount(item["sum"])) for item in items])

    def add_transaction(self) -> None:
        dialog = TransactionDialog(self, title="Новая запись")
        self.wait_window(dialog)
        if dialog.result:
            data.add_transaction(**dialog.result)
            self._status("Запись добавлена")
            self.refresh_transactions()
            self.refresh_overview()

    def edit_selected(self) -> None:
        tx_id = self._selected_tx_id()
        if not tx_id:
            return
        row = data.transaction(tx_id)
        dialog = TransactionDialog(self, title=f"Запись #{tx_id}", transaction=row)
        self.wait_window(dialog)
        if dialog.result:
            data.update_transaction(tx_id, **dialog.result)
            self._status(f"Запись #{tx_id} обновлена")
            self.refresh_transactions()
            self.refresh_overview()

    def delete_selected(self) -> None:
        tx_id = self._selected_tx_id()
        if not tx_id:
            return
        row = data.transaction(tx_id)
        question = (f"Удалить запись #{tx_id}: {format_amount(row['amount'])} "
                    f"({row['description'] or row['category']})?")
        note = "\nЕсли это платёж по долгу, остаток кредита вернётся." \
            if row["tx_type"] == "debt_payment" else ""
        if messagebox.askyesno("Удаление", question + note):
            data.delete_transactions([tx_id])
            self._status(f"Запись #{tx_id} удалена")
            self.refresh_all()

    # ─── Действия: чеки ──────────────────────────────────────────────────

    def show_receipt_items(self) -> None:
        selection = self.receipt_tree.selection()
        if not selection:
            return
        row_id = int(self.receipt_tree.item(selection[0], "values")[0])
        items = data.receipt_items(row_id)
        self._fill_tree(self.receipt_items_tree, [
            (item["name"], f"{item['qty']:.0f}" if item["qty"] else "1",
             format_amount(item["price"]), format_amount(item["sum"])) for item in items])
        self.receipt_sum.set(f"позиций: {len(items)} · "
                             f"сумма позиций {format_amount(sum(i['sum'] for i in items))}")

    def delete_receipt(self) -> None:
        selection = self.receipt_tree.selection()
        if not selection:
            messagebox.showinfo("Нужен чек", "Выбери чек в таблице")
            return
        row_id = int(self.receipt_tree.item(selection[0], "values")[0])
        values = self.receipt_tree.item(selection[0], "values")
        if messagebox.askyesno("Удаление чека",
                               f"Удалить чек №{row_id} ({values[3]}, {values[4]}) "
                               "вместе с позициями?"):
            data.delete_transactions([row_id])
            self._status(f"Чек №{row_id} удалён")
            self.refresh_all()

    # ─── Действия: бюджет ────────────────────────────────────────────────

    def save_budget(self) -> None:
        try:
            for category, entry in self.budget_entries.items():
                data.set_limit(category, float(entry.get().replace(" ", "") or 0))
            data.set_limit("total", float(self.budget_total_entry.get().replace(" ", "") or 0))
        except ValueError:
            messagebox.showerror("Нужно число", "Лимиты вводятся числами, например: 5000")
            return
        self._status("Лимиты сохранены — бот уже считает по новым")
        self.refresh_budget()
        self.refresh_overview()

    def propose_budget(self) -> None:
        plans = self._income_plans()
        if not plans:
            messagebox.showinfo("Нет плана дохода",
                                "Задай план дохода на вкладке «Пользователи»")
            return
        income = max(plans.values())
        limits, total = budget_service.proposal_for_income(income, _run(budget_service.get_limits()))
        for category, value in limits.items():
            data.set_limit(category, value)
        data.set_limit("total", total)
        self._status(f"Предложен бюджет по доходу {format_amount(income)}: {format_amount(total)}")
        self.refresh_budget()

    def reset_budget(self) -> None:
        if not messagebox.askyesno("Сброс лимитов", "Вернуть стартовые лимиты из config.py?"):
            return
        _run(budget_service.reset_limits())
        self._status("Лимиты сброшены к стартовым")
        self.refresh_budget()

    def preview_charts(self) -> None:
        """Показывает ту же картинку, что бот отправляет в Telegram."""
        if not charts.CHARTS_AVAILABLE:
            messagebox.showinfo("Нет matplotlib", "Установи matplotlib: pip install matplotlib")
            return
        spending = data.category_totals(days=31)
        if not spending:
            messagebox.showinfo("Нет данных", "За этот месяц трат ещё нет")
            return
        stats = data.totals()
        panel_limits = data.limits()
        image = _run(charts.month_card(spending, stats["income"], stats["spent"],
                                       panel_limits.get("total", 0),
                                       limits=panel_limits))
        if not image:
            messagebox.showinfo("Нет картинки", "Не получилось нарисовать диаграмму")
            return
        path = os.path.join(os.path.dirname(DB_PATH), "panel_chart.png")
        with open(path, "wb") as file:
            file.write(image)
        ChartWindow(self, path)
        self._status(f"Диаграмма сохранена: {path}")

    def edit_debt(self) -> None:
        selection = self.debt_tree.selection()
        if not selection:
            messagebox.showinfo("Нужен кредит", "Выбери кредит в таблице")
            return
        name = self.debt_tree.item(selection[0], "values")[0]
        row = next((item for item in data.debts() if item["name"] == name), None)
        if not row:
            return
        raw = simpledialog.askstring("Остаток по кредиту",
                                     f"Новый остаток по «{name}», ₽:",
                                     initialvalue=f"{row['current_amount']:.0f}", parent=self)
        if not raw:
            return
        try:
            value = float(raw.replace(" ", "").replace(",", "."))
        except ValueError:
            messagebox.showerror("Нужно число", "Введи сумму числом")
            return
        data.update_debt(row["id"], current_amount=value)
        self._status(f"Остаток «{name}» обновлён: {format_amount(value)}")
        self.refresh_budget()
        self.refresh_overview()

    # ─── Действия: пользователи ──────────────────────────────────────────

    def _selected_user_id(self) -> int | None:
        selection = self.users_tree.selection()
        if not selection:
            messagebox.showinfo("Нужен пользователь", "Выбери пользователя в таблице")
            return None
        return int(self.users_tree.item(selection[0], "values")[0])

    def rename_user(self) -> None:
        user_id = self._selected_user_id()
        if not user_id:
            return
        current = self.users_tree.item(self.users_tree.selection()[0], "values")[1]
        name = simpledialog.askstring("Имя пользователя", "Как обращаться в боте?",
                                      initialvalue=str(current), parent=self)
        if not name:
            return
        _run(profile.set_name(user_id, name))
        self._status(f"Имя пользователя {user_id} обновлено")
        self.refresh_users()

    def set_income_plan(self) -> None:
        user_id = self._selected_user_id()
        if not user_id:
            return
        raw = simpledialog.askstring("План дохода", "Ожидаемый доход в месяц, ₽:",
                                     initialvalue="", parent=self)
        if not raw:
            return
        try:
            value = float(raw.replace(" ", "").replace(",", "."))
        except ValueError:
            messagebox.showerror("Нужно число", "Введи сумму числом, например: 150000")
            return
        _run(profile.set_planned_income(user_id, value))
        self._status(f"План дохода {format_amount(value)} сохранён — бот покажет «безопасно тратить»")
        self.refresh_users()
        self.refresh_budget()

    def reset_onboarding(self) -> None:
        user_id = self._selected_user_id()
        if not user_id:
            return
        if not messagebox.askyesno("Настройка заново",
                                   "Пользователь пройдёт приветственную настройку при "
                                   "следующем /start?"):
            return
        _run(profile.mark_onboarded(user_id, False))
        self._status("Настройка будет показана заново при следующем /start")
        self.refresh_users()

    def show_user_transactions(self) -> None:
        user_id = self._selected_user_id()
        if not user_id:
            return
        value = next((item for item in self.tx_user["values"]
                      if item.endswith(f"({user_id})")), ALL_USERS)
        self.tx_user.set(value)
        self.tx_period.set("30 дней")
        self.tx_type.set("все")
        self.notebook.select(1)
        self.refresh_transactions()

    # ─── Экспорт CSV ─────────────────────────────────────────────────────

    def export_csv_file(self) -> None:
        user_id = self._selected_user(self.export_user)
        days = self._selected_period(self.export_period)
        rows = data.transactions(user_id=user_id, days=days, tx_type=None, limit=100_000)
        if not rows:
            messagebox.showinfo("Пусто", "За выбранный период записей нет")
            return
        default = f"finance_{datetime.now().strftime('%Y%m%d_%H%M')}.csv"
        path = filedialog.asksaveasfilename(title="Сохранить CSV", defaultextension=".csv",
                                            initialfile=default,
                                            filetypes=[("CSV", "*.csv")])
        if not path:
            return
        # Формат файла собирает сервис бота: один владелец формата на панель и бота
        _filename, content = _run(export_csv(pd.DataFrame([dict(row) for row in rows])))
        with open(path, "wb") as file:
            file.write(content)
        self.export_info.configure(text=f"✅ Сохранено {len(rows)} записей: {path}")
        self._status(f"CSV выгружен: {path}")
        if hasattr(os, "startfile"):
            os.startfile(os.path.dirname(path))  # Windows: показать файл в проводнике


class TransactionDialog(tk.Toplevel):
    """Окно добавления и правки записи: те же поля, что у карточки в боте."""

    def __init__(self, parent, title: str, transaction=None):
        super().__init__(parent)
        self.title(title)
        self.configure(background=BACKGROUND)
        self.resizable(False, False)
        self.result: dict | None = None
        row = transaction

        def field(label: str, value: str, row_index: int, options: list[str] | None = None):
            ttk.Label(self, text=label).grid(row=row_index, column=0, sticky="w", padx=12, pady=6)
            if options:
                widget = ttk.Combobox(self, values=options, state="readonly", width=28)
                widget.set(value)
            else:
                widget = ttk.Entry(self, width=30)
                widget.insert(0, value)
            widget.grid(row=row_index, column=1, padx=12, pady=6)
            return widget

        user_ids = sorted({row["user_id"] for row in data.query(
            "SELECT DISTINCT user_id FROM transactions")} | set(USERS))
        user_choices = [f"{_user_name(user_id)} ({user_id})" for user_id in user_ids]
        default_user = next((choice for choice in user_choices
                             if str(row["user_id"]) + ")" in choice), user_choices[0]) if row \
            else (user_choices[0] if user_choices else "")
        self.user_box = field("Пользователь", default_user, 0, user_choices) if user_choices else None
        self.amount = field("Сумма, ₽", f"{row['amount']:.2f}" if row else "", 1)
        self.type_box = field("Тип", (TX_TYPE_RU.get(row["tx_type"], "расход") if row else "расход"),
                              2, list(TX_TYPE_RU.values()))
        self.category_box = field("Категория", row["category"] if row else "прочее", 3,
                                  list(CATEGORIES))
        self.subcategory = field("Подкатегория", (row["subcategory"] or "") if row else "", 4)
        self.description = field("Описание", (row["description"] or "") if row else "", 5)
        debt_ids = [item["id"] for item in data.debts()]
        self.debt_box = field("Долг (для платежа)",
                              (row["debt_target"] or "") if row else "", 6, [""] + debt_ids)
        self.date = field("Дата (ГГГГ-ММ-ДД ЧЧ:ММ)",
                          (row["created_at"] or "")[:16] if row
                          else datetime.now().strftime("%Y-%m-%d %H:%M"), 7)

        buttons = ttk.Frame(self)
        buttons.grid(row=8, column=0, columnspan=2, pady=12)
        ttk.Button(buttons, text="💾 Сохранить", style="Accent.TButton",
                   command=self._save).pack(side="left")
        ttk.Button(buttons, text="Отмена", command=self.destroy).pack(side="left", padx=8)
        self.bind("<Return>", lambda _event: self._save())
        self.bind("<Escape>", lambda _event: self.destroy())
        self.transient(parent)
        self.grab_set()

    def _save(self) -> None:
        try:
            amount = float(self.amount.get().replace(" ", "").replace(",", "."))
            if amount <= 0:
                raise ValueError
        except ValueError:
            messagebox.showerror("Нужно число", "Сумма вводится числом, например: 3450,50")
            return
        user_id = self._user_id()
        reverse_types = {value: key for key, value in TX_TYPE_RU.items()}
        tx_type = reverse_types.get(self.type_box.get(), "expense")
        self.result = {
            "user_id": user_id,
            "amount": amount,
            "category": self.category_box.get() or "прочее",
            "subcategory": self.subcategory.get().strip() or None,
            "description": self.description.get().strip() or None,
            "tx_type": tx_type,
            "debt_target": (self.debt_box.get() or None) if tx_type == "debt_payment" else None,
            "created_at": self._date(),
        }
        self.destroy()

    def _user_id(self) -> int:
        value = self.user_box.get() if self.user_box else ""
        try:
            return int(value.rsplit("(", 1)[1].rstrip(")"))
        except (ValueError, IndexError):
            return next(iter(USERS), 0)

    def _date(self) -> str:
        """Проверяет дату и возвращает её в формате базы."""
        raw = self.date.get().strip()
        for pattern in ("%Y-%m-%d %H:%M", "%Y-%m-%d", "%d.%m.%Y %H:%M", "%d.%m.%Y"):
            try:
                return datetime.strptime(raw, pattern).isoformat(sep=" ")
            except ValueError:
                continue
        messagebox.showwarning("Дата непонятна", "Оставлю текущее время")
        return datetime.now().isoformat(sep=" ")


class ChartWindow(tk.Toplevel):
    """Окно с картинкой отчёта — той же, что бот отправляет в Telegram."""

    def __init__(self, parent, path: str):
        super().__init__(parent)
        self.title("Диаграмма отчёта")
        self.configure(background=BACKGROUND)
        try:
            from PIL import Image, ImageTk

            image = Image.open(path)
            self._photo = ImageTk.PhotoImage(image)
            tk.Label(self, image=self._photo, background=BACKGROUND).pack(padx=8, pady=8)
        except Exception:  # pragma: no cover — без Pillow просто покажем путь
            ttk.Label(self, text=f"Картинка сохранена: {path}").pack(padx=12, pady=12)
        ttk.Button(self, text="Закрыть", command=self.destroy).pack(pady=(0, 10))


def main() -> None:
    if not os.path.isfile(DB_PATH):
        print(f"⚠️ База не найдена: {DB_PATH}. Запусти бота хотя бы раз (start_bot.bat).")
    Panel().mainloop()


if __name__ == "__main__":
    main()
