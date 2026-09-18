"""Общая основа вкладок: контракт «построить / обновить» и помощники.

Каждая вкладка — свой файл и свой класс. Общее здесь ровно то, что действительно общее:
доступ к таблицам, выбор пользователя и периода, оформление строк. Вкладка не знает
о других вкладках напрямую — только о `app`, и через него просит общее обновление.
"""
import os
from tkinter import ttk

from config import DB_PATH, USERS
from database import panel_data as data
from panel_ui.dialogs import ChartWindow
from panel_ui.theme import ALL_USERS, PERIODS


class Tab:
    """Вкладка панели: сама строит свои элементы и обновляет только свои данные."""

    title = ""

    def __init__(self, app):
        self.app = app
        self.frame = ttk.Frame(app.notebook, padding=12)
        app.notebook.add(self.frame, text=self.title)
        self.build()

    def build(self) -> None:
        """Создаёт элементы вкладки. Вызывается один раз, при открытии панели."""
        raise NotImplementedError

    def refresh(self) -> None:
        """Перечитывает данные из базы: кнопка «Обновить», F5 и правки в других вкладках."""

    # ─── Помощники, общие для всех вкладок ───────────────────────────────

    def tree(self, parent, columns, titles, widths, height=10, on_double=None):
        tree = ttk.Treeview(parent, columns=columns, show="headings", height=height)
        for column, title, width in zip(columns, titles, widths):
            tree.heading(column, text=title)
            tree.column(column, width=width, anchor="w", stretch=False)
        scrollbar = ttk.Scrollbar(parent, orient="vertical", command=tree.yview)
        tree.configure(yscrollcommand=scrollbar.set)
        tree.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")
        if on_double:
            tree.bind("<Double-1>", lambda _event: on_double())
        return tree

    def fill(self, tree, rows: list[tuple]) -> None:
        tree.delete(*tree.get_children())
        for row in rows:
            tree.insert("", "end", values=row)

    def status(self, text: str) -> None:
        self.app.status_text(text)

    def show_chart(self, image: bytes | None, filename: str = "panel_chart.png") -> bool:
        """Показывает картинку отчёта в окне — ту же, что бот отправляет в Telegram.

        Одна точка входа на все вкладки: сохранить PNG рядом с базой и открыть окно. Вкладке
        остаётся только получить картинку у `services/charts.py`.
        """
        if not image:
            return False
        path = os.path.join(os.path.dirname(DB_PATH), filename)
        with open(path, "wb") as file:
            file.write(image)
        ChartWindow(self.app, path)
        self.status(f"Диаграмма сохранена: {path}")
        return True

    def user_name(self, user_id) -> str:
        """Подпись пользователя: имя из бота, затем из базы и config, иначе id."""
        if user_id is None:
            return ALL_USERS
        return data.user_label(user_id)

    def selected_user(self, combobox) -> int | None:
        value = combobox.get()
        if value == ALL_USERS or "(" not in value:
            return None
        try:
            return int(value.rsplit("(", 1)[1].rstrip(")"))
        except ValueError:
            return None

    def selected_period(self, combobox) -> int | None:
        return PERIODS.get(combobox.get(), 30)

    # ─── Одни и те же фильтры на нескольких вкладках ─────────────────────

    def user_filter(self, parent, width: int = 24, on_change=None):
        ids = {row["user_id"] for row in data.query("SELECT DISTINCT user_id FROM transactions")}
        ids |= set(USERS)
        ttk.Label(parent, text="Пользователь:").pack(side="left")
        box = ttk.Combobox(parent, state="readonly", width=width,
                           values=[ALL_USERS] + [f"{self.user_name(user_id)} ({user_id})"
                                                 for user_id in sorted(ids)])
        box.set(ALL_USERS)
        box.pack(side="left", padx=(6, 16))
        if on_change:
            box.bind("<<ComboboxSelected>>", lambda _event: on_change())
        return box

    def period_filter(self, parent, width: int = 14, initial: str = "30 дней",
                      on_change=None, padx: int = 6):
        ttk.Label(parent, text="Период:").pack(side="left")
        box = ttk.Combobox(parent, state="readonly", width=width, values=list(PERIODS))
        box.set(initial)
        box.pack(side="left", padx=padx)
        if on_change:
            box.bind("<<ComboboxSelected>>", lambda _event: on_change())
        return box
