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
        [
            InlineKeyboardButton(text="🗓 Дайджест недели", callback_data="report_digest"),
            InlineKeyboardButton(text="📈 Личная инфляция", callback_data="report_inflation"),
        ],
        [
            InlineKeyboardButton(text="🔁 Регулярные платежи", callback_data="report_recurring"),
            InlineKeyboardButton(text="🏷 Мои цены", callback_data="report_prices"),
        ],
        [
            InlineKeyboardButton(text="📉 Необязательные покупки", callback_data="report_waste"),
            InlineKeyboardButton(text="🚫 Не брать", callback_data="report_bans"),
        ],
        [
            InlineKeyboardButton(text="🎯 Цель на месяц", callback_data="report_goal"),
            InlineKeyboardButton(text="🛒 Список покупок", callback_data="report_shopping"),
        ],
    ])


def get_recurring_kb(items: list[dict], muted: list[dict] | None = None) -> InlineKeyboardMarkup:
    """Управление найденными сериями: отключить ложную подписку или вернуть прежнюю.

    callback_data несёт короткий хеш серии, а не индекс: список пересчитывается заново
    на каждом экране, и индекс указывал бы на разные серии после новых записей.
    """
    from services.mutelist import digest

    rows = [[InlineKeyboardButton(text=f"🔕 {item['name']}",
                                  callback_data=f"recurring_mute:{digest(item['key'])}")]
            for item in (items or [])[:8]]
    rows += [[InlineKeyboardButton(text=f"🔔 Вернуть: {item['name']}",
                                   callback_data=f"recurring_unmute:{digest(item['key'])}")]
             for item in (muted or [])[:8]]
    rows.append([InlineKeyboardButton(text="📊 К отчётам", callback_data="menu_report")])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def get_digest_kb() -> InlineKeyboardMarkup:
    """Под дайджестом: то, что из него логично продолжить смотреть."""
    return InlineKeyboardMarkup(inline_keyboard=[
        [
            InlineKeyboardButton(text="🛒 Список покупок", callback_data="report_shopping"),
            InlineKeyboardButton(text="🏷 Мои цены", callback_data="report_prices"),
        ],
        [
            InlineKeyboardButton(text="📊 Отчёт за месяц", callback_data="report_month"),
            InlineKeyboardButton(text="📈 Диаграмма", callback_data="report_chart"),
        ],
        [
            InlineKeyboardButton(text="📉 Необязательные покупки", callback_data="report_waste"),
            InlineKeyboardButton(text="🎯 Цель на месяц", callback_data="report_goal"),
        ],
    ])


def get_goal_kb(candidates: list[dict], has_goal: bool = False, limit: int = 3,
                unit: str = "count", can_switch: bool = False) -> InlineKeyboardMarkup:
    """Под экраном цели: взять предложенную, переключить единицу счёта или убрать старую.

    Кнопка несёт отпечаток ключа товара, а не номер строки: список кандидатов пересчитывается
    на каждом экране, и номер указывал бы уже на другой товар. Надпись кнопки повторяет шаг
    в той единице, в которой он будет считаться, — иначе человек увидит одну единицу в тексте,
    а возьмёт цель в другой.
    """
    from services.mutelist import digest

    def label(item: dict) -> str:
        if item.get("unit") == "sum":
            return (f"💰 Не больше {int(item['limit'])} ₽: {item['name']}")
        return f"🎯 Не чаще {item['target']} раз: {item['name']}"

    rows = [[InlineKeyboardButton(text=label(item),
                                  callback_data=f"goal_take:{digest(item['key'])}")]
            for item in (candidates or [])[:limit]]
    # Переключение единицы показывается только когда есть что переключать: если у кандидатов
    # денежного шага нет, кнопка вела бы в пустоту.
    if can_switch:
        other = "count" if unit == "sum" else "sum"
        text = "🔢 Считать в разах" if other == "count" else "💸 Считать в деньгах"
        rows.append([InlineKeyboardButton(text=text, callback_data=f"goal_unit:{other}")])
    if has_goal:
        rows.append([InlineKeyboardButton(text="↩️ Убрать цель", callback_data="goal_drop")])
    rows.append([InlineKeyboardButton(text="📉 Необязательные покупки", callback_data="report_waste")])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def get_shopping_kb(items: list[dict], muted: list[dict] | None = None) -> InlineKeyboardMarkup:
    """Управление списком покупок: отметить купленным или больше не напоминать.

    В callback_data едет короткий отпечаток ключа товара: список пересчитывается на каждом
    экране, и номер позиции указывал бы уже на другой товар.
    """
    from services.mutelist import digest

    rows = [[InlineKeyboardButton(text=f"✅ Уже купил: {item['name']}",
                                  callback_data=f"shopping_bought:{digest(item['key'])}")]
            for item in (items or [])[:6]]
    rows += [[InlineKeyboardButton(text=f"🔕 Не напоминать: {item['name']}",
                                   callback_data=f"shopping_mute:{digest(item['key'])}")]
             for item in (items or [])[:6]]
    rows += [[InlineKeyboardButton(text=f"🔔 Вернуть: {item['name']}",
                                   callback_data=f"shopping_unmute:{digest(item['key'])}")]
             for item in (muted or [])[:6]]
    rows.append([InlineKeyboardButton(text="🚫 Не брать", callback_data="report_bans")])
    rows.append([InlineKeyboardButton(text="📊 К отчётам", callback_data="menu_report")])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def get_bans_kb(items: list[dict], allowed: list[dict] | None = None,
                guesses: list[dict] | None = None,
                recalc: int = 0) -> InlineKeyboardMarkup:
    """Личный список «не брать»: вернуть товар в список покупок или убрать его снова.

    Кнопка несёт короткий отпечаток ключа товара, а не номер строки: список пересчитывается
    на каждом экране, и номер указывал бы уже на другой товар. У догадок модели две кнопки —
    подтвердить или сказать, что всё нормально: без решения человека бот их не прячет.
    """
    from services.mutelist import digest

    rows = [[InlineKeyboardButton(text=f"🔔 Всё равно напоминать: {item['name']}",
                                  callback_data=f"ban_allow:{digest(item['key'])}")]
            for item in (items or [])[:6]]
    rows += [[InlineKeyboardButton(text=f"🚫 Снова не брать: {item['name']}",
                                   callback_data=f"ban_block:{digest(item['key'])}")]
             for item in (allowed or [])[:6]]
    for item in (guesses or [])[:6]:
        rows.append([InlineKeyboardButton(text=f"🚫 Не брать: {item['name']}",
                                          callback_data=f"ban_confirm:{digest(item['key'])}")])
        rows.append([InlineKeyboardButton(text=f"🔕 Это нормально: {item['name']}",
                                          callback_data=f"ban_allow:{digest(item['key'])}")])
    # Кнопка пересчёта появляется только тогда, когда он действительно что-то меняет:
    # она обещает результат, а не «просто действие».
    if recalc:
        rows.append([InlineKeyboardButton(
            text=f"🔄 Пересчитать старые разборы ({recalc})", callback_data="recalc_verdicts")])
    rows.append([InlineKeyboardButton(text="🛒 Список покупок", callback_data="report_shopping")])
    rows.append([InlineKeyboardButton(text="📊 К отчётам", callback_data="menu_report")])
    return InlineKeyboardMarkup(inline_keyboard=rows)


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
        [
            InlineKeyboardButton(text="🔁 Регулярные платежи", callback_data="report_recurring"),
        ],
        [
            InlineKeyboardButton(text="🏷 Мои цены", callback_data="report_prices"),
            InlineKeyboardButton(text="🛒 Список покупок", callback_data="report_shopping"),
        ],
    ])
