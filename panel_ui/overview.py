"""Вкладка «Обзор»: суммы за период и полосы трат по категориям."""
import tkinter as tk
from tkinter import ttk

from database import panel_data as data
from panel_ui.base import Tab
from panel_ui.theme import ACCENT, MUTED, PANEL, TEXT, strip_emoji
from services import charts
from utils.formatting import format_amount, get_category_emoji

KPI_TITLES = ("Потрачено", "Доход", "Баланс", "Долги")
TOP_CATEGORIES = 10


class OverviewTab(Tab):
    """Карточки KPI и полосатая диаграмма: то, что нужно увидеть первым."""

    title = "Обзор"

    def build(self) -> None:
        filters = ttk.Frame(self.frame)
        filters.pack(fill="x")
        self.user_box = self.user_filter(filters, on_change=self.refresh)
        self.period_box = self.period_filter(filters, on_change=self.refresh)

        cards = ttk.Frame(self.frame)
        cards.pack(fill="x", pady=12)
        self.kpi_vars: dict[str, tk.StringVar] = {}
        for title in KPI_TITLES:
            card = ttk.Frame(cards, style="Panel.TFrame", padding=12)
            card.pack(side="left", expand=True, fill="x", padx=(0, 10))
            ttk.Label(card, text=title, style="Muted.TLabel", background=PANEL).pack(anchor="w")
            variable = tk.StringVar(value="—")
            ttk.Label(card, textvariable=variable, style="Value.TLabel").pack(anchor="w")
            self.kpi_vars[title] = variable

        chart_frame = ttk.Frame(self.frame, style="Panel.TFrame", padding=10)
        chart_frame.pack(fill="both", expand=True)
        ttk.Label(chart_frame, text="Траты по категориям", background=PANEL,
                  foreground=MUTED).pack(anchor="w")
        self.chart_canvas = tk.Canvas(chart_frame, background=PANEL, highlightthickness=0)
        self.chart_canvas.pack(fill="both", expand=True)
        self.chart_canvas.bind("<Configure>", lambda _event: self._draw_chart())

    def refresh(self) -> None:
        user_id = self.selected_user(self.user_box)
        days = self.selected_period(self.period_box)
        stats = data.period_totals(days, user_id)
        debts_total = sum(row["current_amount"] for row in data.debts()
                          if row["status"] == "active")
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
        items = list(values.items())[:TOP_CATEGORIES]
        top = max(values.values()) or 1
        row_height = min(30, max(20, (height - 20) // max(len(items), 1)))
        label_width = 150
        for index, (category, amount) in enumerate(items):
            y = 10 + index * row_height
            canvas.create_text(10, y + row_height / 2, anchor="w", fill=TEXT,
                               text=strip_emoji(get_category_emoji(category)) + " " + category)
            bar_width = (width - label_width - 170) * amount / top
            color = charts.COLORS[index % len(charts.COLORS)] if charts.CHARTS_AVAILABLE \
                else ACCENT
            canvas.create_rectangle(label_width, y + 4, label_width + max(bar_width, 2),
                                    y + row_height - 4, fill=color, outline="")
            canvas.create_text(label_width + bar_width + 12, y + row_height / 2, anchor="w",
                               fill=MUTED, text=format_amount(amount))
        if not items:
            canvas.create_text(20, 30, anchor="w", fill=MUTED, text="Трат за период нет")
