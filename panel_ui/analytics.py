"""Вкладка «Аналитика»: личная инфляция, продуктовая неделя и регулярные платежи.

Ни одного расчёта здесь нет: числа приносит `database/panel_data.analytics`, а тот зовёт
сервисы бота. Поэтому вкладка не может показать цифру, которой нет в Telegram.
"""
import tkinter as tk
from tkinter import messagebox, ttk

from database import panel_data as data
from panel_ui.base import Tab
from panel_ui.theme import MUTED, PANEL, run
from services import charts, recurring
from services.advice import (TREND_NOISE, effects_line, recalc_note, source_split_text,
                             saving_scale_line as scale_line)
from services.forecast import food_line, forecast_note
from utils.formatting import format_amount, plural_ru

CARD_TITLES = ("Личная инфляция", "Продукты за 7 дней", "Подписки в месяц",
               "Необязательное за 90 дней", "Потолок в месяц")
PRODUCTS_LIMIT = 20


class AnalyticsTab(Tab):
    """Три карточки-итога, пояснение к ним и две таблицы с товарами и подписками."""

    title = "Аналитика"

    def build(self) -> None:
        ttk.Label(self.frame, text="Те же цифры, что бот показывает в Telegram: их считают те же "
                                   "сервисы, а не второй расчёт в панели",
                  style="Muted.TLabel").pack(anchor="w")

        filters = ttk.Frame(self.frame)
        filters.pack(fill="x", pady=8)
        self.user_box = self.user_filter(filters, on_change=self.refresh)

        cards = ttk.Frame(self.frame)
        cards.pack(fill="x")
        self.cards: dict[str, tk.StringVar] = {}
        for title in CARD_TITLES:
            card = ttk.Frame(cards, style="Panel.TFrame", padding=12)
            card.pack(side="left", expand=True, fill="x", padx=(0, 10))
            ttk.Label(card, text=title, style="Muted.TLabel", background=PANEL).pack(anchor="w")
            variable = tk.StringVar(value="—")
            ttk.Label(card, textvariable=variable, style="Value.TLabel").pack(anchor="w")
            self.cards[title] = variable

        self.note = ttk.Label(self.frame, text="", style="Muted.TLabel", wraplength=1080,
                              justify="left")
        self.note.pack(anchor="w", pady=(10, 4))
        ttk.Button(self.frame, text="📈 Динамика необязательного",
                   command=self.show_waste_trend).pack(anchor="w", pady=(0, 12))

        tables = ttk.Frame(self.frame)
        tables.pack(fill="both", expand=True)
        price_frame = ttk.Frame(tables, style="Panel.TFrame", padding=10)
        price_frame.pack(side="left", fill="both", expand=True, padx=(0, 10))
        ttk.Label(price_frame, text="Корзина: что подорожало и что подешевело",
                  background=PANEL, foreground=MUTED).pack(anchor="w")
        self.inflation_tree = self.tree(price_frame,
                                       ("name", "old", "new", "change", "count"),
                                       ("Товар", "Было", "Стало", "Изменение", "Покупок"),
                                       (240, 100, 100, 100, 80), height=10)

        subscription_frame = ttk.Frame(tables, style="Panel.TFrame", padding=10)
        subscription_frame.pack(side="left", fill="both", expand=True)
        ttk.Label(subscription_frame, text="Регулярные платежи: что спишется и когда",
                  background=PANEL, foreground=MUTED).pack(anchor="w")
        self.recurring_tree = self.tree(subscription_frame,
                                       ("name", "amount", "period", "last", "next"),
                                       ("Платёж", "Сумма", "Раз в", "Последний раз", "Спишется"),
                                       (220, 100, 90, 120, 150), height=10)

    def refresh(self) -> None:
        user_id = self.selected_user(self.user_box)
        stats = data.analytics(user_id, income=self._month_income(user_id),
                               limit=self._total_limit(user_id))
        inflation = stats["inflation"]
        status = stats["grocery_status"]
        self.cards["Личная инфляция"].set(
            f"{inflation['index'] * 100:+.1f}%".replace(".", ",") if inflation else "мало данных")
        self.cards["Продукты за 7 дней"].set(
            f"{format_amount(stats['weekly_spend'])} из {format_amount(status['limit'])}"
            if status else format_amount(stats["weekly_spend"]))
        self.cards["Подписки в месяц"].set(
            format_amount(stats["recurring_month"])
            if stats["recurring"] or stats["muted"] else "не найдены")
        waste = stats["waste"]
        self.cards["Необязательное за 90 дней"].set(
            f"{format_amount(waste['waste'])} · {round(waste['share'] * 100)}%"
            if waste else "нет разборов")
        saving = stats["saving"]
        self.cards["Потолок в месяц"].set(
            f"~{format_amount(saving['monthly'])}" if saving else "нет привычек")
        self.note.configure(text=" ".join(self._notes(stats)))

        self.fill(self.inflation_tree, [
            (item["name"], format_amount(item["old"]), format_amount(item["new"]),
             f"{round((item['ratio'] - 1) * 100):+d}%", item["count"])
            for item in (inflation or {}).get("products", [])[:PRODUCTS_LIMIT]])
        self.fill(self.recurring_tree, [
            (item["name"], format_amount(item["amount"]), item["period_label"],
             item["last_date"].strftime("%d.%m.%Y"),
             f"{item['next_date']:%d.%m.%Y} ({recurring.when_label(item['days_left'])})")
            for item in stats["recurring"][:PRODUCTS_LIMIT]])

    def _month_income(self, user_id: int | None) -> float:
        """Доход месяца считает та же функция бота — своей арифметики в панели нет."""
        from database.db import get_month_income

        return float(run(get_month_income(user_id)) or 0)

    def _total_limit(self, user_id: int | None) -> float:
        """Лимит месяца берётся у `services/budget.py`: у пользователя могут быть личные лимиты."""
        from services import budget as budget_service

        return float(run(budget_service.get_total_limit(user_id)) or 0)

    def show_waste_trend(self) -> None:
        """Показывает ту же картинку динамики, что бот отправляет в отчёте по необязательным."""
        if not charts.CHARTS_AVAILABLE:
            messagebox.showinfo("Нет matplotlib", "Установи matplotlib: pip install matplotlib")
            return
        trend = data.analytics(self.selected_user(self.user_box))["waste_trend"]
        image = run(charts.waste_trend_card(trend))
        if not self.show_chart(image, filename="panel_waste_trend.png"):
            messagebox.showinfo("Нет данных", "Нужны разобранные чеки как минимум за две недели: "
                                               "по одной точке динамики не видно")

    def _notes(self, stats: dict) -> list[str]:
        """Пояснение под карточками: из чего сложились цифры и чего не хватает для расчёта."""
        notes = []
        inflation = stats["inflation"]
        if inflation:
            difference = inflation["basket_now"] - inflation["basket_before"]
            notes.append(f"Корзина из {inflation['count']} товаров: по прежним ценам "
                         f"{format_amount(inflation['basket_before'])}, по нынешним "
                         f"{format_amount(inflation['basket_now'])} "
                         f"({'дороже на' if difference >= 0 else 'дешевле на'} "
                         f"{format_amount(abs(difference))}). Окно сравнения — "
                         f"{inflation['window_days']} дней.")
        else:
            notes.append("Личная инфляция: нужно минимум три товара, купленных дважды до "
                         "последних трёх месяцев и хотя бы раз внутри них.")
        status = stats["grocery_status"]
        notes.append(food_line(stats["weekly_spend"], status["limit"] if status else 0)
                     or "Продукты: за неделю трат не было.")
        if stats["grocery"]:
            notes.append(forecast_note(stats["grocery"]))
        if stats["muted"]:
            notes.append("🔕 Отключены в боте: "
                         + ", ".join(item["name"] for item in stats["muted"]) + ".")
        waste = stats["waste"]
        if waste:
            notes.append(f"Необязательные покупки: {format_amount(waste['waste'])} из "
                         f"{format_amount(waste['total'])} разобранных позиций за "
                         f"{waste['days']} дней по вердиктам разбора корзины.")
            # Та же формулировка, что в отчёте бота: происхождение считает советник.
            split = source_split_text(waste)
            if split:
                notes.append(split.replace("\n", " "))
            if waste["repeats"]:
                notes.append("Повторяется чаще всего: "
                             + ", ".join(f"{item['name']} (×{item['count']})"
                                         for item in waste["repeats"]) + ".")
        else:
            notes.append("Необязательные покупки: пока нет разобранных чеков — вердикты "
                         "появляются вместе с разбором корзины.")
        if stats["waste_corrected"]:
            fixed = stats["waste_corrected"]
            notes.append("🔧 Поправлено человеком: "
                         + ", ".join(f"{item['name']} ({format_amount(item['sum'])})"
                                     for item in fixed)
                         + " — эти позиции из необязательного убраны.")
        trend = stats["waste_trend"]
        if trend:
            first, last = trend["first"], trend["last"]
            # Одна шкала движения на бота и панель: «стало меньше / больше / без изменений».
            direction = ("стало меньше" if trend["delta"] < -TREND_NOISE
                         else "стало больше" if trend["delta"] > TREND_NOISE else "без изменений")
            notes.append(f"Доля необязательного по неделям: "
                         f"{round(first['share'] * 100)}% → {round(last['share'] * 100)}% — "
                         f"{direction}.")
            # Та же оговорка, что в отчёте бота: движение могло прийти от пересчёта разборов.
            recalc_line = recalc_note(stats["recalc"], trend)
            if recalc_line:
                notes.append(recalc_line.replace("\n", " "))
        if stats["saving"]:
            count = stats["saving"]["count"]
            word = plural_ru(count, "товару", "товарам", "товарам")
            which = plural_ru(count, "который", "которые", "которые")
            kind = plural_ru(count, "необязательным", "необязательными", "необязательными")
            notes.append(f"Потолок в месяц: ~{format_amount(stats['saving']['monthly'])} по "
                         f"{count} {word}, {which} разбор уже дважды называл {kind}. "
                         "Это пересчёт прошлого темпа на месяц, а не достигнутая "
                         "экономия: брать их снова или нет — решаешь ты.")
            scale = scale_line(stats["saving"])
            if scale:
                notes.append(scale)
        # Строка одна на дайджест и панель: формулировка живёт в советнике, а не здесь.
        effect_line = effects_line(stats["effects"])
        if effect_line:
            notes.append(effect_line)
        if stats["banned"]:
            notes.append(f"🚫 В личном списке «не брать»: {len(stats['banned'])} — из списка "
                         "покупок эти товары убраны.")
        if stats["goal"]:
            # Цель — из того же текста, что в боте: панель не считает ход заново.
            notes.append(stats["goal"].replace("\n", " "))
        if stats.get("goal_history"):
            # История итогов: «сдержано N из M» — формулировка тоже из советника.
            notes.append(stats["goal_history"].replace("\n", " "))
        if stats["banned_guesses"]:
            # Догадки модели видны и в панели: они не прячут товары, но и не исчезают молча.
            notes.append("❓ Только модель называла необязательным: "
                         + ", ".join(item["name"] for item in stats["banned_guesses"])
                         + " — правило такого не говорило, из списка покупок они не убраны.")
        return notes
