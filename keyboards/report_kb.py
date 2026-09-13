"""Клавиатуры отчётов: выбор периода, совместный отчёт, диаграмма."""
from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup


def get_report_kb() -> InlineKeyboardMarkup:
    """Основное меню отчётов."""
    return InlineKeyboardMarkup(inline_keyboard=[
        [
            InlineKeyboardButton(text="📅 Месяц", callback_data="report_month"),
            InlineKeyboardButton(text="📆 Неделя", callback_data="report_week"),
        ],
        [
            InlineKeyboardButton(text="🗓 90 дней", callback_data="report_quarter"),
            InlineKeyboardButton(text="👥 Совместный", callback_data="report_joint"),
        ],
        [
            InlineKeyboardButton(text="📈 Диаграмма", callback_data="report_chart"),
            InlineKeyboardButton(text="🤖 Совет ИИ", callback_data="report_advice"),
        ],
    ])


def get_period_kb() -> InlineKeyboardMarkup:
    """Компактный выбор периода отчёта."""
    return InlineKeyboardMarkup(inline_keyboard=[
        [
            InlineKeyboardButton(text="📆 7 дней", callback_data="report_week"),
            InlineKeyboardButton(text="📅 30 дней", callback_data="report_month"),
            InlineKeyboardButton(text="🗓 90 дней", callback_data="report_quarter"),
        ],
        [
            InlineKeyboardButton(text="👥 Совместный", callback_data="report_joint"),
            InlineKeyboardButton(text="📈 Диаграмма", callback_data="report_chart"),
        ],
    ])
