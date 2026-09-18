"""Клавиатура бюджета: лимиты по категориям, общий лимит, ИИ-предложение."""
from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

from utils.formatting import format_amount, get_category_emoji


def get_budget_kb(limits: dict, total_limit: float, categories: tuple = (),
                  food_week: float = 0) -> InlineKeyboardMarkup:
    """Список категорий с лимитами пользователя — по кнопке на категорию."""
    rows = []
    items = categories or tuple(limits.keys())
    for category in items:
        if category == "долги":  # платежи по кредитам считаются отдельно
            continue
        limit = limits.get(category, 0)
        value = format_amount(limit) if limit else "не задан"
        rows.append([InlineKeyboardButton(
            text=f"{get_category_emoji(category)} {category}: {value}",
            callback_data=f"budget_set:{category}")])
    rows.append([InlineKeyboardButton(text=f"🎯 Всего за месяц: {format_amount(total_limit)}",
                                      callback_data="budget_set:total")])
    rows.append([InlineKeyboardButton(
        text=f"🍎 Продукты в неделю: {format_amount(food_week) if food_week else 'не задан'}",
        callback_data="budget_set:food_week")])
    rows.append([
        InlineKeyboardButton(text="🤖 Предложить бюджет", callback_data="budget_ai"),
    ])
    rows.append([
        InlineKeyboardButton(text="♻️ Вернуть семейные", callback_data="budget_reset"),
        InlineKeyboardButton(text="⚙️ Настройки", callback_data="menu_settings"),
    ])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def get_budget_proposal_kb() -> InlineKeyboardMarkup:
    """Кнопки под предложением ИИ."""
    return InlineKeyboardMarkup(inline_keyboard=[
        [
            InlineKeyboardButton(text="✅ Применить", callback_data="budget_apply"),
            InlineKeyboardButton(text="❌ Отмена", callback_data="budget_menu"),
        ],
    ])


def get_budget_amount_kb(category: str) -> InlineKeyboardMarkup:
    """Быстрые суммы при вводе лимита."""
    # Недельный лимит на продукты — суммы другого порядка, чем месячный бюджет.
    quick = ([2_000, 3_000, 5_000, 8_000] if category == "food_week"
             else [5_000, 10_000, 20_000, 30_000])
    rows = [[InlineKeyboardButton(text=f"{value:,}".replace(",", " "),
                                  callback_data=f"budget_value:{value}")
             for value in quick[:2]],
            [InlineKeyboardButton(text=f"{value:,}".replace(",", " "),
                                  callback_data=f"budget_value:{value}")
             for value in quick[2:]]]
    rows.append([InlineKeyboardButton(text="0 — без лимита", callback_data="budget_value:0")])
    rows.append([InlineKeyboardButton(text="◀️ К бюджету", callback_data="budget_menu")])
    return InlineKeyboardMarkup(inline_keyboard=rows)
