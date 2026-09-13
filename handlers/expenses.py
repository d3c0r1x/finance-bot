"""Добавление трат: текст и фото чека (OCR + ИИ), карточка, правки, разбор корзины."""
from aiogram import F, Router
from aiogram.fsm.context import FSMContext
from aiogram.fsm.state import State, StatesGroup
from aiogram.types import CallbackQuery, Message

from ai.llm import analyze_basket, parse_transaction
from ai.ocr import receipt_path
from ai.receipts import (LOW_QUALITY_HINT, apply_review_rules, basket_text, items_list_text,
                         items_summary, leisure_hint, parse_receipt)
from database.db import add_receipt_items, add_transaction, get_debt, get_monthly_spending
from keyboards.expense_kb import (EXPENSE_CATEGORIES, get_amount_kb, get_categories_kb,
                                  get_edit_kb, get_receipt_kb, get_subcategory_kb)
from keyboards.main_menu_kb import get_confirm_kb, get_main_menu_kb
from services import budget
from services.purchase_history import compare_items, history_text
from services.alerts import category_status, check_limits_alert
from utils.filters import AccessFilter
from utils.formatting import (DEBT_NAMES, format_amount, get_category_emoji, md_code, md_safe,
                              plural_ru)

router = Router()
router.message.filter(AccessFilter())
router.callback_query.filter(AccessFilter())

TEXT_HINT = (
    "✍️ Напиши трату своими словами — я сам пойму сумму и категорию.\n\n"
    "Примеры:\n"
    "• Заправка 2000\n"
    "• Пятёрочка 3450,50\n"
    "• ДНС 5000\n"
    "• Зарплата 150000\n"
    "• Платёж Т-Банк 3000"
)


class ExpenseStates(StatesGroup):
    waiting_for_confirmation = State()
    waiting_for_subcategory = State()
    waiting_for_amount_edit = State()


# ─── Карточка записи ─────────────────────────────────────────────────────

def _reading_note(parsed: dict) -> str:
    """Чем прочитан чек и насколько ему можно верить.

    Разница между моделью зрения и Tesseract большая (магазин и названия позиций), и человек
    должен видеть, какой путь сработал, прежде чем записывать трату.
    """
    reader = parsed.get("read_by")
    model = parsed.get("vision_model")
    if reader in ("vision", "ai") and model:
        note = f"🔎 Прочитано моделью зрения ({md_code(model)})"
        if parsed.get("vision_passes", 1) > 1:
            note += ", с проверкой вторым проходом"
        return note + "."
    if parsed.get("vision_error"):
        return (f"🔎 Читал Tesseract: {md_safe(parsed['vision_error'])} — "
                "магазин и названия позиций могут быть неточными.")
    return "🔎 Читал Tesseract — надёжные цифры, но названия могут быть неточными."

def _confidence_note(parsed: dict) -> str:
    """Человеческая подсказка, если сумма/категория пришли из фолбэка."""
    confidence = parsed.get("confidence")
    if confidence is None or confidence >= 0.75:
        return ""
    if confidence >= 0.5:
        return "🟡 Категория определена неуверенно — проверь перед записью."
    return "🟠 Сумму удалось найти, но разбор был по правилам — проверь категорию."


def _card_text(parsed: dict, spending: dict, limits: dict, source: str = "text") -> str:
    """Единый вид карточки подтверждения для трат, доходов, платежей и чеков."""
    tx_type = parsed.get("tx_type", "expense")
    lines = ["🧾 **Проверь запись:**", ""]
    lines.append(f"💵 Сумма: **{format_amount(parsed['amount'])}**")
    confidence_note = _confidence_note(parsed)
    if confidence_note:
        lines.append(confidence_note)

    if source == "photo":
        lines.append(f"🏪 Магазин: **{md_safe(parsed.get('store') or parsed.get('description') or 'неизвестен')}**")
        if parsed.get("receipt_date"):
            lines.append(f"📅 Дата чека: {parsed['receipt_date']}")
        reading = _reading_note(parsed)
        if reading:
            lines.append(reading)

    if tx_type == "income":
        lines.append("💰 Тип: **доход**")
        lines.append(f"📝 Источник: {md_safe(parsed.get('description'))}")
    elif tx_type == "debt_payment":
        target = DEBT_NAMES.get(parsed.get("debt_target") or "", "кредит")
        lines.append(f"💳 Платёж по: **{target}**")
    else:
        emoji = get_category_emoji(parsed["category"])
        category = f"{emoji} Категория: **{parsed['category']}**"
        if parsed.get("subcategory"):
            category += f" / {md_safe(parsed['subcategory'])}"
        lines.append(category)
        if source != "photo":
            lines.append(f"📝 Описание: {md_safe(parsed.get('description'))}")

    items = parsed.get("items") or []
    if items:
        count = len(items)
        lines += ["", f"📋 Позиции ({count} {plural_ru(count, 'товар', 'товара', 'товаров')}):"]
        lines.append(items_summary(items, limit=5))
        recovered = sum(1 for item in items if item.get("recovered"))
        if recovered:
            lines.append(f"⚠️ {recovered} {plural_ru(recovered, 'позиция добрана', 'позиции добраны', 'позиций добрано')} "
                         "по арифметике чека — название может быть неточным.")
        if parsed.get("items_mismatch"):
            lines.append("⚠️ Часть цен позиций OCR прочитал неуверенно — итог чека всё равно верный.")
        hint = leisure_hint(parsed)
        if hint:
            lines += ["", hint]

    if tx_type == "expense":
        status = category_status(parsed["category"], spending, limits)
        if status:
            lines += ["", status]
    return "\n".join(lines)


def _card_keyboard(parsed: dict):
    """Клавиатура карточки: чек — свои кнопки, текст — обычное подтверждение."""
    if parsed.get("items"):
        return get_receipt_kb(bool(parsed.get("leisure")) and parsed.get("category") != "досуг")
    return get_confirm_kb()


async def _show_card(message: Message, state: FSMContext, parsed: dict,
                     source: str = "text") -> None:
    """Сохраняет разбор в состояние и показывает карточку с кнопками."""
    spending = await get_monthly_spending(user_id=message.from_user.id)
    limits = await budget.get_limits()
    await state.update_data(parsed=parsed, source=source)
    await state.set_state(ExpenseStates.waiting_for_confirmation)
    await message.answer(_card_text(parsed, spending, limits, source),
                         reply_markup=_card_keyboard(parsed))


# ─── Ввод траты текстом ──────────────────────────────────────────────────

async def parse_free_text(message: Message, state: FSMContext) -> None:
    """Разбирает свободный текст и предлагает карточку. Вызывается и из меню, и из любого экрана."""
    if not message.text:
        return
    status = await message.answer("🤖 Разбираю...")
    parsed = await parse_transaction(message.text)
    try:
        await status.delete()
    except Exception:
        pass

    if not parsed.get("amount"):
        await message.answer(
            "⚠️ Не понял сумму. Напиши её вместе с описанием, например: «Заправка 2000».\n"
            "Или нажми «💸 Добавить трату» для подсказок."
        )
        return

    await _show_card(message, state, parsed, source="text")


@router.message(F.text == "💸 Добавить трату")
async def add_expense_button(message: Message, state: FSMContext):
    """Кнопка «💸 Добавить трату»: подсказка, разбор произойдёт по следующему сообщению."""
    await message.answer(TEXT_HINT)


@router.message(F.text == "📷 Скан чека")
async def scan_receipt_hint(message: Message):
    await message.answer(
        "📸 Пришли фото чека целиком и ровно — распознаю магазин, сумму и все позиции.\n"
        "Потом можно поправить сумму и категорию перед записью."
    )


# ─── Подтверждение / изменение / отмена ──────────────────────────────────

async def _save(message: Message, state: FSMContext, parsed: dict, source: str,
                user_id: int) -> None:
    """Записывает транзакцию (и позиции чека) и показывает результат с меню."""
    transaction_id = await add_transaction(
        user_id=user_id,
        amount=parsed["amount"],
        category=parsed["category"],
        subcategory=parsed.get("subcategory"),
        description=parsed.get("description"),
        tx_type=parsed["tx_type"],
        debt_target=parsed.get("debt_target"),
        source=source,
    )

    items = parsed.get("items") or []
    if items and parsed["tx_type"] == "expense":
        await add_receipt_items(transaction_id, items)

    icons = {"expense": "✅", "income": "💰", "debt_payment": "💳"}
    label = {"expense": "Записал", "income": "Доход записан", "debt_payment": "Платёж записан"}
    lines = [f"{icons.get(parsed['tx_type'], '✅')} **{label.get(parsed['tx_type'], 'Записал')}:** "
             f"{format_amount(parsed['amount'])} — {parsed['category']}"]

    spending = await get_monthly_spending(user_id=user_id)
    limits = await budget.get_limits()
    if parsed["tx_type"] == "expense":
        alert = check_limits_alert(parsed["category"], spending, limits)
        status = category_status(parsed["category"], spending, limits)
        if alert:
            lines += ["", alert]
        elif status:
            lines += ["", f"Остаток лимита → {status}"]
    if parsed["tx_type"] == "debt_payment" and parsed.get("debt_target"):
        debt = await get_debt(parsed["debt_target"])
        if debt:
            if debt["current_amount"] <= 0:
                lines += ["", f"🎉 **{debt['name']} закрыт полностью!**"]
            else:
                lines += ["", f"Остаток по «{debt['name']}»: **{format_amount(debt['current_amount'])}**"]

    await message.answer("\n".join(lines), reply_markup=get_main_menu_kb())
    await state.clear()

    # Продуктовый чек — сразу разбираем корзину: что полезно, что вредно, от чего отказаться
    if items and parsed.get("is_grocery"):
        session = await message.answer("🤖 Смотрю, что можно было взять выгоднее...")
        analysis = apply_review_rules(await analyze_basket(items, parsed.get("store") or ""), items)
        try:
            from database.db import get_receipt_price_history
            history = await get_receipt_price_history(message.from_user.id)
            if analysis is None:
                analysis = {"items": {}}
            analysis["history_changes"] = compare_items(items, history)
        except Exception:
            # История — дополнительная возможность, она не должна блокировать запись чека.
            if analysis is not None:
                analysis["history_changes"] = []
        try:
            await session.delete()
        except Exception:
            pass
        text = basket_text(analysis, items, parsed.get("store") or "",
                           float(parsed.get("amount") or 0) or None)
        if text:
            changes = (analysis or {}).get("history_changes") or []
            text += history_text(changes)
            await message.answer(text, reply_markup=get_main_menu_kb())


@router.callback_query(F.data == "confirm_expense")
async def confirm_expense(callback: CallbackQuery, state: FSMContext):
    data = await state.get_data()
    parsed = data.get("parsed")
    if not parsed:
        await callback.answer("Данные устарели, отправь трату заново", show_alert=True)
        return
    if data.get("saving"):
        await callback.answer("Уже записываю…")
        return
    # Двойной тап по «Записать» не должен создать две одинаковые операции.
    await state.update_data(saving=True)
    await _save(callback.message, state, parsed, data.get("source", "text"), callback.from_user.id)
    await callback.answer("Готово")


@router.callback_query(F.data == "receipt_leisure")
async def receipt_to_leisure(callback: CallbackQuery, state: FSMContext):
    """Алкоголь и снеки — это досуг, а не траты на еду."""
    data = await state.get_data()
    parsed = data.get("parsed")
    if not parsed:
        await callback.answer("Данные устарели, отправь чек заново", show_alert=True)
        return
    parsed["category"] = "досуг"
    parsed["subcategory"] = "развлечения"
    parsed["leisure"] = False
    await state.update_data(parsed=parsed)
    spending = await get_monthly_spending(user_id=callback.from_user.id)
    limits = await budget.get_limits()
    await callback.message.edit_text(
        _card_text(parsed, spending, limits, data.get("source", "text")),
        reply_markup=_card_keyboard(parsed),
    )
    await callback.answer("Запишем как досуг")


@router.callback_query(F.data == "receipt_items")
async def show_receipt_items(callback: CallbackQuery, state: FSMContext):
    data = await state.get_data()
    parsed = data.get("parsed") or {}
    items = parsed.get("items") or []
    if not items:
        await callback.answer("Позиции не распознаны", show_alert=True)
        return
    await callback.message.answer(items_list_text(items, parsed.get("store") or ""),
                                  reply_markup=get_receipt_kb())
    await callback.answer()


@router.callback_query(F.data == "edit_expense")
async def edit_expense(callback: CallbackQuery, state: FSMContext):
    data = await state.get_data()
    parsed = data.get("parsed")
    if not parsed:
        await callback.answer("Данные устарели, отправь трату заново", show_alert=True)
        return
    spending = await get_monthly_spending(user_id=callback.from_user.id)
    limits = await budget.get_limits()
    await callback.message.edit_text(
        _card_text(parsed, spending, limits, data.get("source", "text")) + "\n\n✏️ **Что изменить?**",
        reply_markup=get_edit_kb(bool(EXPENSE_CATEGORIES.get(parsed["category"])),
                                 bool(parsed.get("items"))),
    )
    await callback.answer()


@router.callback_query(F.data == "edit_back")
async def edit_back(callback: CallbackQuery, state: FSMContext):
    data = await state.get_data()
    parsed = data.get("parsed")
    if not parsed:
        await callback.answer("Данные устарели, отправь трату заново", show_alert=True)
        return
    spending = await get_monthly_spending(user_id=callback.from_user.id)
    limits = await budget.get_limits()
    await state.set_state(ExpenseStates.waiting_for_confirmation)
    await callback.message.edit_text(
        _card_text(parsed, spending, limits, data.get("source", "text")),
        reply_markup=_card_keyboard(parsed))
    await callback.answer()


# ─── Изменение суммы ─────────────────────────────────────────────────────

@router.callback_query(F.data == "edit_amount_menu")
async def edit_amount_menu(callback: CallbackQuery, state: FSMContext):
    data = await state.get_data()
    if not data.get("parsed"):
        await callback.answer("Данные устарели, отправь трату заново", show_alert=True)
        return
    await callback.message.edit_text(
        "💵 **Новая сумма**\n\nВыбери быстрый вариант или введи свою сумму числом.",
        reply_markup=get_amount_kb(),
    )
    await callback.answer()


@router.callback_query(F.data == "edit_amount_custom")
async def edit_amount_custom(callback: CallbackQuery, state: FSMContext):
    await state.set_state(ExpenseStates.waiting_for_amount_edit)
    await callback.message.edit_text("✍️ Напиши новую сумму числом, например: 3450,50")
    await callback.answer()


@router.message(ExpenseStates.waiting_for_amount_edit, F.text)
async def set_custom_amount(message: Message, state: FSMContext):
    raw = (message.text or "").replace(" ", "").replace(",", ".")
    try:
        amount = float(raw)
        if amount <= 0:
            raise ValueError
    except ValueError:
        await message.answer("⚠️ Нужна положительная сумма числом, например: 3450,50")
        return

    data = await state.get_data()
    parsed = data.get("parsed") or {}
    parsed["amount"] = amount
    await state.update_data(parsed=parsed)
    await state.set_state(ExpenseStates.waiting_for_confirmation)
    spending = await get_monthly_spending(user_id=message.from_user.id)
    limits = await budget.get_limits()
    await message.answer(_card_text(parsed, spending, limits),
                         reply_markup=_card_keyboard(parsed))


@router.callback_query(F.data.startswith("edit_amount:"))
async def edit_amount(callback: CallbackQuery, state: FSMContext):
    data = await state.get_data()
    parsed = data.get("parsed") or {}
    try:
        parsed["amount"] = float(callback.data.split(":", 1)[1])
    except ValueError:
        await callback.answer("Некорректная сумма", show_alert=True)
        return
    await state.update_data(parsed=parsed)
    await state.set_state(ExpenseStates.waiting_for_confirmation)
    spending = await get_monthly_spending(user_id=callback.from_user.id)
    limits = await budget.get_limits()
    await callback.message.edit_text(_card_text(parsed, spending, limits),
                                    reply_markup=_card_keyboard(parsed))
    await callback.answer(f"Сумма: {format_amount(parsed['amount'])}")


# ─── Изменение категории и подкатегории ──────────────────────────────────

@router.callback_query(F.data == "edit_cat_menu")
async def edit_cat_menu(callback: CallbackQuery, state: FSMContext):
    data = await state.get_data()
    if not data.get("parsed"):
        await callback.answer("Данные устарели, отправь трату заново", show_alert=True)
        return
    await callback.message.edit_text(
        f"{get_category_emoji((data['parsed'] or {}).get('category', 'прочее'))} **Выбери категорию:**",
        reply_markup=get_categories_kb(),
    )
    await callback.answer()


@router.callback_query(F.data.startswith("edit_cat:"))
async def edit_category(callback: CallbackQuery, state: FSMContext):
    new_category = callback.data.split(":", 1)[1]
    data = await state.get_data()
    parsed = data.get("parsed") or {}
    parsed["category"] = new_category
    parsed["subcategory"] = None
    if new_category == "долги":
        parsed["tx_type"] = "debt_payment"
    elif parsed.get("tx_type") == "debt_payment":
        parsed["tx_type"] = "expense"
    await state.update_data(parsed=parsed)

    if EXPENSE_CATEGORIES.get(new_category):
        await state.set_state(ExpenseStates.waiting_for_subcategory)
        await callback.message.edit_text(
            f"{get_category_emoji(new_category)} Категория: **{new_category}**\n\n"
            "Уточни подкатегорию:",
            reply_markup=get_subcategory_kb(new_category),
        )
    else:
        await state.set_state(ExpenseStates.waiting_for_confirmation)
        spending = await get_monthly_spending(user_id=callback.from_user.id)
        limits = await budget.get_limits()
        await callback.message.edit_text(
            _card_text(parsed, spending, limits),
            reply_markup=get_edit_kb(False, bool(parsed.get("items"))),
        )
    await callback.answer()


@router.callback_query(F.data == "edit_sub_menu")
async def edit_sub_menu(callback: CallbackQuery, state: FSMContext):
    data = await state.get_data()
    parsed = data.get("parsed") or {}
    category = parsed.get("category", "прочее")
    if not EXPENSE_CATEGORIES.get(category):
        await callback.answer("Для этой категории нет подкатегорий", show_alert=True)
        return
    await state.set_state(ExpenseStates.waiting_for_subcategory)
    await callback.message.edit_text(
        f"{get_category_emoji(category)} Категория: **{category}**\n\nУточни подкатегорию:",
        reply_markup=get_subcategory_kb(category),
    )
    await callback.answer()


@router.callback_query(F.data.startswith("edit_sub:"))
async def edit_subcategory(callback: CallbackQuery, state: FSMContext):
    subcategory = callback.data.split(":", 1)[1] or None
    data = await state.get_data()
    parsed = data.get("parsed") or {}
    parsed["subcategory"] = subcategory
    await state.update_data(parsed=parsed)
    await state.set_state(ExpenseStates.waiting_for_confirmation)
    spending = await get_monthly_spending(user_id=callback.from_user.id)
    limits = await budget.get_limits()
    await callback.message.edit_text(
        _card_text(parsed, spending, limits),
        reply_markup=get_edit_kb(bool(EXPENSE_CATEGORIES.get(parsed.get("category", ""))),
                                 bool(parsed.get("items"))),
    )
    await callback.answer()


# ─── Запись и отмена ─────────────────────────────────────────────────────

@router.callback_query(F.data == "edit_ok")
async def edit_done(callback: CallbackQuery, state: FSMContext):
    data = await state.get_data()
    parsed = data.get("parsed")
    if not parsed:
        await callback.answer("Данные устарели, отправь трату заново", show_alert=True)
        return
    if data.get("saving"):
        await callback.answer("Уже записываю…")
        return
    await state.update_data(saving=True)
    await _save(callback.message, state, parsed, data.get("source", "text"), callback.from_user.id)
    await callback.answer("Записал")


@router.callback_query(F.data == "cancel")
async def cancel_expense(callback: CallbackQuery, state: FSMContext):
    await state.clear()
    try:
        await callback.message.edit_text("❌ Отменил, ничего не записывал.", reply_markup=None)
    except Exception:
        pass  # сообщение могло устареть — не мешаем отмене
    # возвращаем нижнее меню: после отмены остаётся клавиатура отмены
    await callback.message.answer("Меню на месте 👇", reply_markup=get_main_menu_kb())
    await callback.answer()


# ─── Фото чека ───────────────────────────────────────────────────────────

@router.message(F.photo)
async def process_receipt_photo(message: Message, state: FSMContext):
    status = await message.answer("📸 Читаю чек, это займёт несколько секунд...")

    photo = message.photo[-1]
    file_path = receipt_path(photo.file_unique_id)
    await message.bot.download(photo, destination=file_path)

    receipt = await parse_receipt(file_path)
    try:
        await status.delete()
    except Exception:
        pass

    if receipt["total"] is None:
        hint = ""
        if "Tesseract не найден" in receipt.get("raw_text", ""):
            hint = f"\n\n_{md_safe(receipt['raw_text'])}_"
        elif not receipt.get("readable"):
            hint = "\n\n" + LOW_QUALITY_HINT
        await message.answer(
            "❌ Не нашёл итоговую сумму на чеке.\n"
            "Сфотографируй чек целиком и ровно — или напиши сумму текстом." + hint
        )
        return

    parsed = {
        "amount": receipt["total"],
        "category": receipt["category"] or "прочее",
        "subcategory": "чек",
        "description": receipt.get("store") or "Чек",
        "store": receipt.get("store"),
        "receipt_date": receipt.get("date"),
        "items": receipt.get("items") or [],
        "items_mismatch": receipt.get("items_mismatch", False),
        "is_grocery": receipt.get("is_grocery", False),
        "leisure": receipt.get("leisure", False),
        "tx_type": "expense",
        "debt_target": None,
        # чек читала модель зрения — ей можно доверять как обычному ИИ-разбору
        "confidence": (0.9 if receipt.get("parsed_by") in ("ai", "vision") else 0.6)
                       * (0.85 if receipt.get("items_mismatch") else 1.0),
        "read_by": receipt.get("reader") or receipt.get("parsed_by"),
        "vision_model": receipt.get("vision_model"),
        "vision_passes": receipt.get("vision_passes", 1),
        "vision_error": receipt.get("vision_error"),
    }
    await _show_card(message, state, parsed, source="photo")
