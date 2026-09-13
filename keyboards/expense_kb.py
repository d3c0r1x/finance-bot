"""Клавиатуры редактирования траты: категории, подкатегории, суммы, действия."""
from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

# Категории с подкатегориями
EXPENSE_CATEGORIES = {
    "еда": ["продукты", "фастфуд", "доставка"],
    "транспорт": ["бензин", "такси", "общественный"],
    "жилье": ["аренда", "коммуналка", "ремонт"],
    "досуг": ["кафе", "кино", "игры", "развлечения"],
    "одежда": ["одежда", "обувь", "аксессуары"],
    "здоровье": ["аптека", "врач", "спорт"],
    "работа": ["перекус", "обед", "канцелярия"],
    "техника": ["комплектующие", "гаджеты", "аксессуары", "бытовая техника"],
    "долги": [],
    "прочее": [],
}

CATEGORY_EMOJI = {
    "еда": "🍕", "транспорт": "🚗", "жилье": "🏠", "досуг": "🎮",
    "одежда": "👕", "здоровье": "💊", "работа": "💼", "техника": "💻",
    "долги": "💳", "прочее": "📦",
}


def get_categories_kb():
    """Выбор категории при изменении траты."""
    rows, row = [], []
    for category in EXPENSE_CATEGORIES:
        row.append(InlineKeyboardButton(text=f"{CATEGORY_EMOJI.get(category, '📦')} {category}",
                                        callback_data=f"edit_cat:{category}"))
        if len(row) == 2:
            rows.append(row)
            row = []
    if row:
        rows.append(row)
    rows.append([InlineKeyboardButton(text="◀️ Назад", callback_data="edit_back")])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def get_subcategory_kb(category: str):
    """Выбор подкатегории для категории."""
    subs = EXPENSE_CATEGORIES.get(category, [])
    rows, row = [], []
    for sub in subs:
        row.append(InlineKeyboardButton(text=sub, callback_data=f"edit_sub:{sub}"))
        if len(row) == 2:
            rows.append(row)
            row = []
    if row:
        rows.append(row)
    rows.append([InlineKeyboardButton(text="⏭ Пропустить", callback_data="edit_sub:")])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def get_edit_kb(has_subcategories: bool = False, has_items: bool = False):
    """Меню изменения траты."""
    rows = [
        [InlineKeyboardButton(text="✅ Записать", callback_data="edit_ok")],
        [
            InlineKeyboardButton(text="💵 Сумма", callback_data="edit_amount_menu"),
            InlineKeyboardButton(text="📂 Категория", callback_data="edit_cat_menu"),
        ],
    ]
    if has_subcategories:
        rows.append([InlineKeyboardButton(text="🏷 Подкатегория", callback_data="edit_sub_menu")])
    if has_items:
        rows.append([InlineKeyboardButton(text="📋 Позиции чека", callback_data="receipt_items")])
    rows.append([InlineKeyboardButton(text="❌ Отмена", callback_data="cancel")])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def get_receipt_kb(leisure: bool = False):
    """Кнопки под карточкой чека. leisure — в чеке есть алкоголь/развлечения."""
    rows = [
        [
            InlineKeyboardButton(text="✅ Записать", callback_data="confirm_expense"),
            InlineKeyboardButton(text="✏️ Изменить", callback_data="edit_expense"),
        ],
    ]
    if leisure:
        rows.append([InlineKeyboardButton(text="🎮 Это досуг, не еда",
                                         callback_data="receipt_leisure")])
    rows += [
        [InlineKeyboardButton(text="📋 Все позиции", callback_data="receipt_items")],
        [InlineKeyboardButton(text="❌ Отмена", callback_data="cancel")],
    ]
    return InlineKeyboardMarkup(inline_keyboard=rows)


def get_amount_kb():
    """Быстрые суммы для изменения."""
    return InlineKeyboardMarkup(inline_keyboard=[
        [
            InlineKeyboardButton(text="100", callback_data="edit_amount:100"),
            InlineKeyboardButton(text="500", callback_data="edit_amount:500"),
            InlineKeyboardButton(text="1 000", callback_data="edit_amount:1000"),
        ],
        [
            InlineKeyboardButton(text="2 000", callback_data="edit_amount:2000"),
            InlineKeyboardButton(text="5 000", callback_data="edit_amount:5000"),
            InlineKeyboardButton(text="10 000", callback_data="edit_amount:10000"),
        ],
        [
            InlineKeyboardButton(text="✍️ Ввести свою сумму", callback_data="edit_amount_custom"),
        ],
        [
            InlineKeyboardButton(text="◀️ Назад", callback_data="edit_back"),
        ],
    ])
