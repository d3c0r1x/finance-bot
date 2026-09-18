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


# Позиций на страницу правки: чек бывает и на 20+ строк, а клавиатура — не простыня
ITEMS_PER_PAGE = 8


def get_items_edit_kb(items: list[dict], page: int = 0, per_page: int = ITEMS_PER_PAGE):
    """Правка позиций чека: исправить или убрать каждую, добавить пропущенную.

    Позиции — то, ради чего чек и сканируют, а OCR читает их неидеально («набор заколок»
    вместо колбасы). Длинные чеки листаются: страницы не дают клавиатуре вырасти в простыню.
    """
    pages = max(1, -(-len(items) // per_page))
    page = min(max(page, 0), pages - 1)
    start = page * per_page
    rows = []
    for offset, item in enumerate(items[start:start + per_page]):
        index = start + offset
        name = " ".join((item.get("name") or "Позиция").split())
        label = name if len(name) <= 20 else name[:19] + "…"
        rows.append([
            InlineKeyboardButton(text=f"✏️ {index + 1}. {label}", callback_data=f"item_fix:{index}"),
            InlineKeyboardButton(text=f"🗑 {index + 1}", callback_data=f"item_del:{index}"),
        ])
    if pages > 1:
        navigation = []
        if page:
            navigation.append(InlineKeyboardButton(text="◀️", callback_data=f"items_page:{page - 1}"))
        navigation.append(InlineKeyboardButton(text=f"{page + 1} / {pages}",
                                               callback_data=f"items_page:{page}"))
        if page < pages - 1:
            navigation.append(InlineKeyboardButton(text="▶️", callback_data=f"items_page:{page + 1}"))
        rows.append(navigation)
    rows.append([InlineKeyboardButton(text="➕ Добавить пропущенную", callback_data="item_add")])
    if items:
        rows.append([InlineKeyboardButton(text="🔄 Сумма чека = сумма позиций",
                                          callback_data="receipt_sync_total")])
    rows.append([InlineKeyboardButton(text="◀️ К чеку", callback_data="receipt_card")])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def get_item_edit_kb():
    """Кнопки под запросом новой позиции: вернуться к списку или отменить чек."""
    return InlineKeyboardMarkup(inline_keyboard=[
        [InlineKeyboardButton(text="📋 Все позиции", callback_data="receipt_items")],
        [InlineKeyboardButton(text="❌ Отмена", callback_data="cancel")],
    ])


# Сколько позиций чека показать на одной странице поправки: больше шести кнопок — уже
# простыня, а дальше видны не сами товары, а список. Остальные — на следующих страницах.
REVIEW_FIX_LIMIT = 6


def get_review_fix_kb(transaction_id: int, waste_items: list[dict],
                      page: int = 0) -> InlineKeyboardMarkup | None:
    """Поправка вердикта прямо на разборе: разбор ошибся («колбаса» как пакет), и это видно.

    Кнопка несёт id позиции из базы, а не её номер на экране: разбор перерисовывается,
    и номер указывал бы уже на другой товар. Длинный чек листается: без этого у чека
    с десятком спорных позиций половина из них была бы недоступна для поправки.
    Возвращает None, если поправлять нечего, — тогда клавиатура не появляется вовсе.
    """
    items = waste_items or []
    pages = max(1, -(-len(items) // REVIEW_FIX_LIMIT))
    page = min(max(page, 0), pages - 1)
    start = page * REVIEW_FIX_LIMIT
    rows = []
    for item in items[start:start + REVIEW_FIX_LIMIT]:
        name = " ".join((item.get("name") or "Позиция").split())
        label = name if len(name) <= 24 else name[:23] + "…"
        # Номер страницы едет в кнопке: после поправки сообщение перерисовывается на той же
        # странице, а не выбрасывает человека в начало списка.
        rows.append([InlineKeyboardButton(text=f"🔧 Не согласен: {label}",
                                          callback_data=f"review_ok:{transaction_id}:{item['id']}:{page}")])
    if not rows:
        return None
    if pages > 1:
        navigation = []
        if page:
            navigation.append(InlineKeyboardButton(text="◀️",
                                                   callback_data=f"review_page:{transaction_id}:{page - 1}"))
        navigation.append(InlineKeyboardButton(text=f"{page + 1} / {pages}",
                                               callback_data=f"review_page:{transaction_id}:{page}"))
        if page < pages - 1:
            navigation.append(InlineKeyboardButton(text="▶️",
                                                   callback_data=f"review_page:{transaction_id}:{page + 1}"))
        rows.append(navigation)
    return InlineKeyboardMarkup(inline_keyboard=rows)


def get_duplicate_kb():
    """Кнопки под предупреждением о повторной записи: решает человек, а не таймер."""
    return InlineKeyboardMarkup(inline_keyboard=[
        [InlineKeyboardButton(text="✅ Записать ещё раз", callback_data="confirm_duplicate")],
        [InlineKeyboardButton(text="❌ Не записывать", callback_data="cancel")],
    ])


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
