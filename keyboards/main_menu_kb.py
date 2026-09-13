"""Клавиатуры главного меню, быстрых действий и служебных экранов."""
from aiogram.types import (InlineKeyboardButton, InlineKeyboardMarkup,
                           KeyboardButton, ReplyKeyboardMarkup)

from keyboards.debt_kb import get_debt_payment_kb  # noqa: F401  (реэкспорт для совместимости)
from keyboards.report_kb import get_report_kb  # noqa: F401  (единый вид во всех экранах)


# Главное меню — кнопки внизу экрана (всегда видны)
def get_main_menu_kb() -> ReplyKeyboardMarkup:
    return ReplyKeyboardMarkup(
        keyboard=[
            [
                KeyboardButton(text="💸 Добавить трату"),
                KeyboardButton(text="📷 Скан чека"),
            ],
            [
                KeyboardButton(text="📊 Отчёт"),
                KeyboardButton(text="💳 Долги"),
            ],
            [
                KeyboardButton(text="💰 Доход"),
                KeyboardButton(text="⚙️ Настройки"),
            ],
            [
                KeyboardButton(text="🕘 История"),
                KeyboardButton(text="↩️ Отменить последнюю"),
            ],
        ],
        resize_keyboard=True,
        input_field_placeholder="Напиши трату словами или выбери кнопку 👇",
    )


# Быстрые действия под приветствием
def get_main_menu_inline_kb() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[
        [
            InlineKeyboardButton(text="💸 Трата", callback_data="menu_expense"),
            InlineKeyboardButton(text="📷 Чек", callback_data="menu_receipt"),
        ],
        [
            InlineKeyboardButton(text="📊 Отчёт", callback_data="menu_report"),
            InlineKeyboardButton(text="💳 Долги", callback_data="menu_debts"),
        ],
        [
            InlineKeyboardButton(text="💰 Доход", callback_data="menu_income"),
            InlineKeyboardButton(text="⚙️ Настройки", callback_data="menu_settings"),
        ],
        [
            InlineKeyboardButton(text="📈 Диаграмма", callback_data="report_chart"),
            InlineKeyboardButton(text="🕘 История", callback_data="menu_history"),
        ],
        [
            InlineKeyboardButton(text="❓ Как пользоваться", callback_data="menu_help"),
        ],
    ])


# Инлайн-кнопки для подтверждения траты
def get_confirm_kb() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[
        [
            InlineKeyboardButton(text="✅ Записать", callback_data="confirm_expense"),
            InlineKeyboardButton(text="✏️ Изменить", callback_data="edit_expense"),
        ],
        [
            InlineKeyboardButton(text="❌ Отмена", callback_data="cancel"),
        ],
    ])


# Инлайн-кнопки для выбора категории
def get_categories_kb(prefix="cat_") -> InlineKeyboardMarkup:
    categories = [
        ("🍕 Еда", "еда"),
        ("🚗 Транспорт", "транспорт"),
        ("🏠 Жилье", "жилье"),
        ("🎮 Досуг", "досуг"),
        ("👕 Одежда", "одежда"),
        ("💊 Здоровье", "здоровье"),
        ("💼 Работа", "работа"),
        ("💻 Техника", "техника"),
        ("💳 Долги", "долги"),
        ("📦 Прочее", "прочее"),
    ]
    rows, row = [], []
    for name, value in categories:
        row.append(InlineKeyboardButton(text=name, callback_data=f"{prefix}{value}"))
        if len(row) == 2:
            rows.append(row)
            row = []
    if row:
        rows.append(row)
    return InlineKeyboardMarkup(inline_keyboard=rows)


# Инлайн-кнопки для долгов
def get_debts_kb() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[
        [
            InlineKeyboardButton(text="💳 Сбербанк", callback_data="debt_sber"),
            InlineKeyboardButton(text="💳 Т-Банк", callback_data="debt_tbank"),
        ],
        [
            InlineKeyboardButton(text="💳 Яндекс", callback_data="debt_yandex"),
            InlineKeyboardButton(text="💳 Основной", callback_data="debt_main"),
        ],
        [
            InlineKeyboardButton(text="📈 Прогноз погашения", callback_data="debt_forecast"),
        ],
    ])


# Кнопки экрана настроек
def get_settings_kb() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[
        [
            InlineKeyboardButton(text="🎯 Бюджет и лимиты", callback_data="budget_menu"),
        ],
        [
            InlineKeyboardButton(text="🩺 Статус сервисов", callback_data="health_check"),
        ],
        [
            InlineKeyboardButton(text="📊 Отчёт за месяц", callback_data="report_month"),
            InlineKeyboardButton(text="📈 Диаграмма", callback_data="report_chart"),
        ],
        [
            InlineKeyboardButton(text="🚀 Пройти настройку заново", callback_data="onb:0"),
        ],
        [
            InlineKeyboardButton(text="❓ Как пользоваться", callback_data="menu_help"),
        ],
    ])


def get_onboarding_kb(step: int, title: str, *, back: bool = True) -> InlineKeyboardMarkup:
    """Кнопки приветственной настройки: вперёд/назад и «пропустить».

    Точки внизу показывают, сколько шагов уже пройдено — как в приложениях-конкурентах.
    """
    dots = "".join("●" if index == step else "○" for index in range(ONBOARDING_STEPS))
    rows = [[InlineKeyboardButton(text=f"{dots}  {title}", callback_data="onb:noop")]]
    nav = []
    if back and step:
        nav.append(InlineKeyboardButton(text="⬅️ Назад", callback_data=f"onb:{step - 1}"))
    nav.append(InlineKeyboardButton(text="Продолжить ➡️", callback_data=f"onb:{step + 1}"))
    rows.append(nav)
    rows.append([InlineKeyboardButton(text="Пропустить настройку", callback_data="onb:skip")])
    return InlineKeyboardMarkup(inline_keyboard=rows)


ONBOARDING_STEPS = 5


def get_history_kb(rows) -> InlineKeyboardMarkup:
    """Кнопки для мгновенного повторения последних расходов."""
    buttons = []
    for row in rows[:6]:
        if row["tx_type"] != "expense":
            continue
        label = (row["description"] or row["category"] or "трата")[:24]
        buttons.append([InlineKeyboardButton(
            text=f"🔁 Повторить {label} · {row['amount']:.0f} ₽",
            callback_data=f"repeat_tx:{row['id']}")])
    return InlineKeyboardMarkup(inline_keyboard=buttons or [[
        InlineKeyboardButton(text="💸 Добавить трату", callback_data="menu_expense")
    ]])


def get_health_kb() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[
        [InlineKeyboardButton(text="🔄 Проверить снова", callback_data="health_check")],
    ])
