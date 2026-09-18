"""Вкладка «Бюджет»: семейные лимиты, недельный лимит на продукты и долги."""
import tkinter as tk
from tkinter import messagebox, simpledialog, ttk

from database import panel_data as data
from database.models import CATEGORIES
from panel_ui.base import Tab
from panel_ui.theme import FONT_BOLD, MUTED, PANEL, run, strip_emoji
from services import budget as budget_service, charts
from utils.formatting import format_amount, get_category_emoji

MONTH_DAYS = 31        # траты за месяц считаются так же, как в отчёте бота


class BudgetTab(Tab):
    """Лимиты по категориям, лимит на продукты за неделю и остатки по кредитам."""

    title = "Бюджет"

    def build(self) -> None:
        self._food_rows: list[dict] = []
        ttk.Label(self.frame, text="Семейные лимиты: правятся здесь и сразу видны боту",
                  style="Muted.TLabel").pack(anchor="w")
        self.income_note = ttk.Label(self.frame, text="", style="Muted.TLabel")
        self.income_note.pack(anchor="w", pady=(2, 10))

        grid = ttk.Frame(self.frame, style="Panel.TFrame", padding=12)
        grid.pack(fill="x")
        for column, title in enumerate(("Категория", "Лимит, ₽", "Потрачено за месяц", "Остаток")):
            ttk.Label(grid, text=title, background=PANEL, foreground=MUTED,
                      font=FONT_BOLD).grid(row=0, column=column, sticky="w", padx=8, pady=4)

        self.limit_entries: dict[str, ttk.Entry] = {}
        self.spent_vars: dict[str, tk.StringVar] = {}
        self.left_vars: dict[str, tk.StringVar] = {}
        for index, category in enumerate(CATEGORIES, start=1):
            ttk.Label(grid, text=f"{strip_emoji(get_category_emoji(category))} {category}",
                      background=PANEL).grid(row=index, column=0, sticky="w", padx=8, pady=2)
            entry = ttk.Entry(grid, width=14)
            entry.grid(row=index, column=1, padx=8, pady=2)
            self.limit_entries[category] = entry
            # Траты и остаток — переменные, а не готовые подписи: иначе они застывали бы
            # на момент открытия окна и не менялись до перезапуска панели.
            spent_var = tk.StringVar(value="—")
            ttk.Label(grid, textvariable=spent_var, background=PANEL).grid(
                row=index, column=2, sticky="w", padx=8)
            self.spent_vars[category] = spent_var
            left_var = tk.StringVar(value="")
            ttk.Label(grid, textvariable=left_var, background=PANEL).grid(
                row=index, column=3, sticky="w", padx=8)
            self.left_vars[category] = left_var

        last = len(CATEGORIES) + 1
        ttk.Label(grid, text="Всего за месяц", background=PANEL,
                  font=FONT_BOLD).grid(row=last, column=0, sticky="w", padx=8, pady=(8, 2))
        self.total_entry = ttk.Entry(grid, width=14)
        self.total_entry.grid(row=last, column=1, padx=8, pady=(8, 2))

        buttons = ttk.Frame(self.frame)
        buttons.pack(fill="x", pady=12)
        ttk.Button(buttons, text="💾 Сохранить лимиты", style="Accent.TButton",
                   command=self.save_limits).pack(side="left")
        ttk.Button(buttons, text="📉 Предложить по доходу",
                   command=self.propose_limits).pack(side="left", padx=8)
        ttk.Button(buttons, text="↩️ Сбросить к стартовым",
                   command=self.reset_limits).pack(side="left")
        ttk.Button(buttons, text="📈 Показать графики бота",
                   command=self.preview_charts).pack(side="right")

        food_frame = ttk.Frame(self.frame, style="Panel.TFrame", padding=12)
        food_frame.pack(fill="x", pady=(12, 0))
        ttk.Label(food_frame, text="🍎 Продукты в неделю (личный лимит)", background=PANEL,
                  font=FONT_BOLD).pack(anchor="w")
        self.food_note = ttk.Label(food_frame, text="", background=PANEL, foreground=MUTED)
        self.food_note.pack(anchor="w", pady=(2, 6))
        self.food_tree = self.tree(food_frame,
                                   ("user", "limit", "spent", "left", "state"),
                                   ("Пользователь", "Лимит", "За 7 дней", "Осталось", ""),
                                   (200, 120, 120, 120, 120), height=4)
        ttk.Button(food_frame, text="✏️ Изменить лимит продуктов",
                   command=self.edit_food_limit).pack(anchor="w", pady=6)

        debts_frame = ttk.Frame(self.frame, style="Panel.TFrame", padding=12)
        debts_frame.pack(fill="both", expand=True, pady=(12, 0))
        ttk.Label(debts_frame, text="Долги", background=PANEL, font=FONT_BOLD).pack(anchor="w")
        self.debt_tree = self.tree(debts_frame, ("name", "current", "rate", "payment", "status"),
                                   ("Кредит", "Остаток", "Ставка", "Платёж", "Статус"),
                                   (240, 140, 90, 110, 100), height=5)
        ttk.Button(debts_frame, text="✏️ Изменить остаток",
                   command=self.edit_debt).pack(anchor="w", pady=6)

    def refresh(self) -> None:
        limits = data.limits()
        spend = data.category_totals(days=MONTH_DAYS)
        for category, entry in self.limit_entries.items():
            limit = limits.get(category, 0)
            entry.delete(0, "end")
            entry.insert(0, f"{limit:.0f}" if limit else "0")
            spent = spend.get(category, 0)
            self.spent_vars[category].set(format_amount(spent) if spent else "—")
            if not limit:
                self.left_vars[category].set("без лимита" if not spent else "")
                continue
            left = limit - spent
            self.left_vars[category].set(("осталось " if left >= 0 else "перерасход ")
                                         + format_amount(abs(left)))
        self.total_entry.delete(0, "end")
        self.total_entry.insert(0, f"{limits.get('total', 0):.0f}")
        self.income_note.configure(text=self._income_note())
        self.refresh_food_week()
        self.fill(self.debt_tree, [
            (row["name"], format_amount(row["current_amount"]),
             f"{row['interest_rate'] or 0:.0f}%", format_amount(row["min_payment"] or 0),
             "закрыт" if row["status"] == "closed" else "активен")
            for row in data.debts()])

    def _income_note(self) -> str:
        """План дохода и оговорка про личные лимиты: без неё цифры двух интерфейсов разошлись бы."""
        plans = self._income_plans()
        incomes = [f"{self.user_name(user_id)}: {format_amount(value)}"
                   for user_id, value in plans.items()]
        note = ("План дохода — " + ", ".join(incomes)) if incomes \
            else "План дохода не задан: задайте его на вкладке «Пользователи»"
        personal = [self.user_name(user_id) for user_id in data.personal_limit_users()]
        if personal:
            note += " · свои лимиты в боте: " + ", ".join(personal)
        return note

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

    # ─── Лимиты по категориям ────────────────────────────────────────────

    def save_limits(self) -> None:
        try:
            for category, entry in self.limit_entries.items():
                data.set_limit(category, float(entry.get().replace(" ", "") or 0))
            data.set_limit("total", float(self.total_entry.get().replace(" ", "") or 0))
        except ValueError:
            messagebox.showerror("Нужно число", "Лимиты вводятся числами, например: 5000")
            return
        self.status("Лимиты сохранены — бот уже считает по новым")
        self.app.refresh_all()

    def propose_limits(self) -> None:
        plans = self._income_plans()
        if not plans:
            messagebox.showinfo("Нет плана дохода",
                                "Задай план дохода на вкладке «Пользователи»")
            return
        income = max(plans.values())
        limits, total = budget_service.proposal_for_income(income, run(budget_service.get_limits()))
        for category, value in limits.items():
            data.set_limit(category, value)
        data.set_limit("total", total)
        self.status(f"Предложен бюджет по доходу {format_amount(income)}: {format_amount(total)}")
        self.app.refresh_all()

    def reset_limits(self) -> None:
        if not messagebox.askyesno("Сброс лимитов", "Вернуть стартовые лимиты из config.py?"):
            return
        run(budget_service.reset_limits())
        self.status("Лимиты сброшены к стартовым")
        self.app.refresh_all()

    def preview_charts(self) -> None:
        """Показывает ту же картинку, что бот отправляет в Telegram."""
        if not charts.CHARTS_AVAILABLE:
            messagebox.showinfo("Нет matplotlib", "Установи matplotlib: pip install matplotlib")
            return
        spending = data.category_totals(days=MONTH_DAYS)
        if not spending:
            messagebox.showinfo("Нет данных", "За этот месяц трат ещё нет")
            return
        stats = data.totals()
        current_limits = data.limits()
        image = run(charts.month_card(spending, stats["income"], stats["spent"],
                                      current_limits.get("total", 0), limits=current_limits))
        if not self.show_chart(image):
            messagebox.showinfo("Нет картинки", "Не получилось нарисовать диаграмму")

    # ─── Продукты в неделю ───────────────────────────────────────────────

    def refresh_food_week(self) -> None:
        """Недельные лимиты на продукты: считает их тот же сервис, что и в боте."""
        rows = data.food_week_overview()
        self._food_rows = rows
        self.fill(self.food_tree, [
            (self.user_name(row["user_id"]),
             format_amount(row["limit"]) if row["limit"] else "не задан",
             format_amount(row["current"]),
             (format_amount(row["left"]) if row["limit"] else "—"),
             "🚨 превышен" if row["over"] else "⚠️ почти" if row["near"] else "")
            for row in rows])
        self.food_note.configure(
            text=("Лимит считается по скользящим семи дням, у каждого свой. Строка без лимита "
                  "показывает только сумму за неделю" if rows
                  else "Лимиты не заданы, а трат на еду за неделю нет"))

    def edit_food_limit(self) -> None:
        """Правка недельного лимита выбранного пользователя (0 — отключить)."""
        selection = self.food_tree.selection()
        if not selection:
            messagebox.showinfo("Нужен пользователь", "Выбери строку в таблице")
            return
        index = self.food_tree.index(selection[0])
        if index >= len(self._food_rows):
            return
        user_id = self._food_rows[index]["user_id"]
        raw = simpledialog.askstring(
            "Продукты в неделю",
            f"Сколько можно тратить на еду за 7 дней для «{self.user_name(user_id)}», ₽:",
            initialvalue="", parent=self.app)
        if not raw:
            return
        try:
            value = float(raw.replace(" ", "").replace(",", "."))
        except ValueError:
            messagebox.showerror("Нужно число", "Введи сумму числом, например: 4000")
            return
        data.set_food_week_limit(user_id, value)
        self.status("Недельный лимит на продукты обновлён — бот сразу считает по новому" if value
                    else "Недельный лимит на продукты отключён")
        self.refresh_food_week()

    # ─── Долги ───────────────────────────────────────────────────────────

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
                                     initialvalue=f"{row['current_amount']:.0f}", parent=self.app)
        if not raw:
            return
        try:
            value = float(raw.replace(" ", "").replace(",", "."))
        except ValueError:
            messagebox.showerror("Нужно число", "Введи сумму числом")
            return
        data.update_debt(row["id"], current_amount=value)
        self.status(f"Остаток «{name}» обновлён: {format_amount(value)}")
        self.app.refresh_all()
