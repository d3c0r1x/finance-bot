from aiogram.types import InlineKeyboardMarkup, InlineKeyboardButton


def get_debt_payment_kb():
    """Выбор долга для платежа (с процентными ставками)."""
    return InlineKeyboardMarkup(inline_keyboard=[
        [
            InlineKeyboardButton(text="💳 Сбербанк (60%)", callback_data="pay_sber"),
            InlineKeyboardButton(text="💳 Т-Банк (60%)", callback_data="pay_tbank"),
        ],
        [
            InlineKeyboardButton(text="💳 Яндекс", callback_data="pay_yandex"),
            InlineKeyboardButton(text="💳 Основной (37.4%)", callback_data="pay_main"),
        ],
    ])


def get_debt_detail_kb(debt_id: str):
    """Кнопки для конкретного долга."""
    return InlineKeyboardMarkup(inline_keyboard=[
        [InlineKeyboardButton(text="💸 Внести платёж", callback_data=f"pay_{debt_id}")],
        [InlineKeyboardButton(text="◀️ К списку долгов", callback_data="menu_debts")],
    ])


def get_cancel_kb():
    return InlineKeyboardMarkup(inline_keyboard=[
        [InlineKeyboardButton(text="❌ Отмена", callback_data="cancel")],
    ])
