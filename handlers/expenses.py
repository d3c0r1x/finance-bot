"""Добавление трат: текст и фото чека (OCR + ИИ), карточка, правки, разбор корзины."""
import asyncio
import re

from datetime import datetime

from aiogram import F, Router
from aiogram.fsm.context import FSMContext
from aiogram.fsm.state import State, StatesGroup
from aiogram.types import CallbackQuery, Message

from ai.llm import analyze_basket, parse_transaction
from ai.ocr import receipt_path
from ai.receipts import (LOW_QUALITY_HINT, apply_review_rules, basket_text, items_list_text,
                         items_summary, leisure_hint, parse_receipt, verdict_rows)
from database.db import (add_receipt_items, add_transaction, find_similar_transaction, get_debt,
                         get_monthly_spending, get_receipt_items, get_receipt_verdicts,
                         get_transaction, save_receipt_verdicts)
from keyboards.expense_kb import (EXPENSE_CATEGORIES, ITEMS_PER_PAGE, get_amount_kb,
                                  get_categories_kb, get_duplicate_kb, get_edit_kb, get_item_edit_kb,
                                  get_items_edit_kb, get_receipt_kb, get_review_fix_kb,
                                  get_subcategory_kb)
from keyboards.main_menu_kb import get_confirm_kb, get_main_menu_kb
from services import advice, budget
from services.forecast import CATEGORY as FOOD_CATEGORY, food_week_status, limit_text
from services.purchase_history import compare_items, history_text, product_key
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
    waiting_for_item_edit = State()


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
        corrected = sum(1 for item in items if item.get("corrected"))
        if corrected:
            lines.append(f"✏️ {corrected} {plural_ru(corrected, 'цена поправлена', 'цены поправлены', 'цен поправлено')} "
                         "по остатку чека.")
        if parsed.get("total_estimated"):
            lines.append("ℹ️ Итог на чеке не прочитан — показана сумма по позициям.")
        if parsed.get("over_total"):
            lines.append("⚠️ Позиции дороже итога чека — часть цен прочитана неверно, загляни в список позиций.")
        if parsed.get("items_edited"):
            lines.append("✏️ Позиции исправлены вручную.")
        elif parsed.get("items_mismatch") and not parsed.get("over_total"):
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
    limits = await budget.get_limits(message.from_user.id)
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

def _human_ago(created_at: str | None) -> str:
    """«5 минут назад» / «2 часа назад» / «вчера» — без этого непонятно, тот ли это чек."""
    try:
        moment = datetime.fromisoformat(created_at or "")
    except (TypeError, ValueError):
        return "только что"
    minutes = int((datetime.now() - moment).total_seconds() // 60)
    if minutes < 1:
        return "только что"
    if minutes < 60:
        return f"{minutes} {plural_ru(minutes, 'минуту', 'минуты', 'минут')} назад"
    hours = minutes // 60
    if hours < 24:
        return f"{hours} {plural_ru(hours, 'час', 'часа', 'часов')} назад"
    return "вчера или раньше"


async def _save(message: Message, state: FSMContext, parsed: dict, source: str,
                user_id: int) -> None:
    """Записывает транзакцию (и позиции чека) и показывает результат с меню."""
    # Повторно присланное фото чека удвоило бы расход и сломало бюджет, поэтому про дубль
    # спрашиваем один раз и только про чеки: текстовые записи человек набирает осознанно.
    if source == "photo" and not parsed.get("duplicate_checked"):
        twin = await find_similar_transaction(user_id, parsed["amount"], parsed["tx_type"])
        if twin:
            parsed["duplicate_checked"] = True
            # запись не состоялась — снимаем флаг «записываю», иначе кнопка «Записать»
            # навсегда отвечает «уже записываю»
            await state.update_data(parsed=parsed, saving=False)
            await message.answer(
                "⚠️ **Похоже, этот чек уже записан:**\n"
                f"• {format_amount(twin['amount'])} — "
                f"{md_safe(twin['description'] or twin['category'])}, {_human_ago(twin['created_at'])}\n\n"
                "Записать ещё раз или это случайный дубль?",
                reply_markup=get_duplicate_kb(),
            )
            return

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
    reminder = ""
    if items and parsed["tx_type"] == "expense":
        await add_receipt_items(transaction_id, items)
        # Напоминание в момент покупки, пока позиции на экране. История читается без текущего
        # чека и до сохранения его вердиктов: иначе чек находил бы сам себя.
        try:
            history = [dict(row) for row in await get_receipt_verdicts(
                user_id, exclude_transaction_id=transaction_id)]
            reminder = advice.repeat_text(
                advice.repeat_warnings(items, history, await advice.allowed_keys(user_id)))
        except Exception:
            # Напоминание — дополнительная возможность: запись чека из-за неё падать не должна.
            reminder = ""

    icons = {"expense": "✅", "income": "💰", "debt_payment": "💳"}
    label = {"expense": "Записал", "income": "Доход записан", "debt_payment": "Платёж записан"}
    lines = [f"{icons.get(parsed['tx_type'], '✅')} **{label.get(parsed['tx_type'], 'Записал')}:** "
             f"{format_amount(parsed['amount'])} — {parsed['category']}"]

    spending = await get_monthly_spending(user_id=user_id)
    limits = await budget.get_limits(user_id)
    if parsed["tx_type"] == "expense":
        alert = check_limits_alert(parsed["category"], spending, limits)
        status = category_status(parsed["category"], spending, limits)
        if alert:
            lines += ["", alert]
        elif status:
            lines += ["", f"Остаток лимита → {status}"]
        # Недельный лимит на продукты — прямо в момент покупки, пока её ещё видно на экране:
        # сообщение о перерасходе через день в сводке уже не свяжется с конкретным чеком.
        if parsed["category"] == FOOD_CATEGORY:
            status = await food_week_status(user_id)
            week = limit_text(status)
            if week:
                lines += ["", week]
                if status and status["over"]:
                    # Лимит — ориентир, а не приговор: если он тесен, это видно здесь и его
                    # можно поправить той же кнопкой, что его задаёт.
                    lines.append("Поправить: ⚙️ Настройки → 🎯 Бюджет → 🍎 Продукты в неделю.")
    if reminder:
        lines += ["", reminder]
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
        # Вердикты сохраняются вместе с позициями: тогда позже можно ответить на вопрос
        # «сколько ушло на то, что советовали не брать», не спрашивая модель заново.
        try:
            await save_receipt_verdicts(transaction_id, verdict_rows(analysis, items))
        except Exception:
            # Совет — дополнительная возможность; запись чека из-за неё падать не должна.
            pass
        text = basket_text(analysis, items, parsed.get("store") or "",
                           float(parsed.get("amount") or 0) or None)
        if text:
            changes = (analysis or {}).get("history_changes") or []
            text += history_text(changes)
            # Кнопка на каждую позицию, которую разбор назвал необязательной: спор с разбором
            # случается именно здесь, пока рекомендация на экране. Клавиатура инлайн, поэтому
            # главное меню снизу остаётся — его прислала карточка записи выше.
            waste_items = await _review_waste_items(transaction_id, user_id)
            await message.answer(text, reply_markup=get_review_fix_kb(transaction_id, waste_items)
                                 or get_main_menu_kb())


async def _review_waste_items(transaction_id: int, user_id: int) -> list[dict]:
    """Позиции чека, которые разбор записал в необязательные: именно на них и спорят.

    Читается из базы, а не из разбора в памяти: к моменту спора состояние уже очищено,
    а вердикты сохранены вместе с позициями. Порядок — по сумме убыванию и без уже
    поправленных: он одинаков на всех страницах клавиатуры, иначе листание путало бы местами.
    """
    waste = dict(advice.WASTE_VERDICTS)
    try:
        rows = await get_receipt_items(transaction_id)
        allowed = await advice.allowed_keys(user_id)
        found = [{"id": row["id"], "name": row["name"], "sum": float(row["sum"] or 0)}
                 for row in rows
                 if (row["verdict"] or "").strip().lower() in waste
                 and product_key(row["name"]) not in allowed]
    except Exception:
        # Поправка — дополнительная возможность, как и сам разбор: без неё чек уже записан.
        return []
    return sorted(found, key=lambda item: -item["sum"])


@router.callback_query(F.data.startswith("review_ok:"))
async def review_ok(callback: CallbackQuery):
    """Правка вердикта человеком: разбор назвал вещь лишней, а человек с этим не согласен.

    Правка — это и есть «товар разрешён»: тот же ключ настроек, что у кнопки в списке
    «не брать», поэтому и отменяется она там же, а второго механизма поправок не появляется.
    """
    parts = callback.data.split(":")
    # Номера приходят в callback_data и всегда наши, но устаревшая кнопка после перезапуска
    # не должна ронять обработчик: дешевле ответить, чем ловить исключение в логах.
    if len(parts) != 4 or not all(part.isdigit() for part in parts[1:]):
        await callback.answer("Кнопка устарела — открой чек заново", show_alert=True)
        return
    _, raw_tx, raw_item, raw_page = parts
    user_id = callback.from_user.id
    if not await get_transaction(int(raw_tx), user_id):
        await callback.answer("Чек не найден — открой список заново", show_alert=True)
        return
    item = next((row for row in await get_receipt_items(int(raw_tx))
                 if row["id"] == int(raw_item)), None)
    if item is None:
        await callback.answer("Позиции больше нет в чеке", show_alert=True)
        return
    await advice.set_allowed(user_id, product_key(item["name"]), True)
    await callback.answer("Учёл")
    # Спор снимается вместе с кнопкой: повторно предлагать ту же позицию было бы уже спором
    # с человеком, а не разбором. Когда поправлять нечего, клавиатура убирается целиком.
    await _redraw_review_fix(callback, int(raw_tx), user_id, int(raw_page))
    await callback.message.answer(advice.fixed_text(item["name"]))


async def _redraw_review_fix(callback: CallbackQuery, transaction_id: int, user_id: int,
                             page: int = 0) -> None:
    """Перерисовывает кнопки поправки: одна точка на снятие кнопки и на листание страниц.

    Правки и листание могут прийти от одного и того же сообщения, поэтому список берётся
    заново из базы — иначе страница после поправки показывала бы уже поправленный товар,
    а номера страниц разъехались бы с содержимым.
    """
    items = await _review_waste_items(transaction_id, user_id)
    try:
        await callback.message.edit_reply_markup(
            reply_markup=get_review_fix_kb(transaction_id, items, page))
    except Exception:
        # Сообщение могло устареть: тогда кнопки просто остаются как были.
        pass


@router.callback_query(F.data.startswith("review_page:"))
async def review_page(callback: CallbackQuery):
    """Листание спорных позиций: длинный чек не должен оставлять позиции без поправки."""
    parts = callback.data.split(":")
    if len(parts) != 3 or not (parts[1].isdigit() and parts[2].isdigit()):
        await callback.answer("Кнопка устарела — открой чек заново", show_alert=True)
        return
    _, raw_tx, raw_page = parts
    user_id = callback.from_user.id
    if not await get_transaction(int(raw_tx), user_id):
        await callback.answer("Чек не найден — открой список заново", show_alert=True)
        return
    await callback.answer("Показываю дальше")
    await _redraw_review_fix(callback, int(raw_tx), user_id, int(raw_page))


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


@router.callback_query(F.data == "confirm_duplicate")
async def confirm_duplicate(callback: CallbackQuery, state: FSMContext):
    """Записывает чек, который пользователь признал не дублем."""
    data = await state.get_data()
    parsed = data.get("parsed")
    if not parsed:
        await callback.answer("Данные устарели, отправь чек заново", show_alert=True)
        return
    parsed["duplicate_checked"] = True
    await state.update_data(parsed=parsed, saving=True)
    await _save(callback.message, state, parsed, data.get("source", "photo"),
                callback.from_user.id)
    await callback.answer("Записал")


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
    limits = await budget.get_limits(callback.from_user.id)
    await callback.message.edit_text(
        _card_text(parsed, spending, limits, data.get("source", "text")),
        reply_markup=_card_keyboard(parsed),
    )
    await callback.answer("Запишем как досуг")


@router.callback_query(F.data == "receipt_items")
async def show_receipt_items(callback: CallbackQuery, state: FSMContext):
    """Список позиций с правкой: чек ценен именно позициями, а OCR читает их неидеально."""
    data = await state.get_data()
    parsed = data.get("parsed") or {}
    items = parsed.get("items") or []
    if not items:
        await callback.answer("Позиции не распознаны", show_alert=True)
        return
    await callback.message.answer(items_list_text(items, parsed.get("store") or "", parsed),
                                  reply_markup=get_items_edit_kb(items))
    await callback.answer()


# ─── Правка позиций чека вручную ─────────────────────────────────────────

PRICE_TAIL_RE = re.compile(r"(\d[\d\s]*(?:[.,]\d{1,2})?)\s*(?:₽|руб\.?|р\.?)?$", re.IGNORECASE)


def _parse_item_input(text: str) -> tuple[str | None, float | None]:
    """Разбирает строку правки: «Сыр 320,50», «320,50» или «Сыр Российский».

    Цена — число в конце строки, всё до него — название. Так один и тот же ввод годится
    и для исправления прочитанной позиции (новое название и/или цена), и для добавления
    пропущенной.
    """
    raw = " ".join((text or "").split())
    if not raw:
        return None, None
    match = PRICE_TAIL_RE.search(raw)
    if not match:
        return raw, None
    price = float(match.group(1).replace(" ", "").replace(",", "."))
    name = raw[:match.start()].strip(" -–—,;:.")
    return (name or None), price


def _mark_items_edited(parsed: dict) -> None:
    """Помечает, что список позиций поправил человек.

    Пометки авторазбора («цены неуверенны», «позиции дороже итога») после ручной правки
    врут: список теперь человеческий. Если итог чека кассы прочитать не удалось, сумма
    чека должна следовать за позициями — их и правит человек.
    """
    parsed["items_edited"] = True
    parsed["items_mismatch"] = False
    parsed["over_total"] = False
    if parsed.get("total_estimated"):
        parsed["amount"] = round(sum(item.get("sum") or 0 for item in parsed.get("items") or []), 2)


def _items_markup(parsed: dict, page: int = 0):
    """Клавиатура списка позиций, а когда позиций не осталось — клавиатура карточки."""
    items = parsed.get("items") or []
    if items:
        return get_items_edit_kb(items, page)
    return get_receipt_kb(bool(parsed.get("leisure")) and parsed.get("category") != "досуг")


async def _render_items(callback: CallbackQuery, state: FSMContext, note: str = "",
                        page: int = 0) -> None:
    """Перерисовывает список позиций на месте, чтобы правки шли в одном сообщении."""
    data = await state.get_data()
    parsed = data.get("parsed") or {}
    text = items_list_text(parsed.get("items") or [], parsed.get("store") or "", parsed)
    if note:
        text = f"{note}\n\n{text}"
    try:
        await callback.message.edit_text(text, reply_markup=_items_markup(parsed, page))
    except Exception:
        # сообщение могло быть слишком старым для правки — тогда просто пишем новое
        await callback.message.answer(text, reply_markup=_items_markup(parsed, page))


async def _refresh_card(callback: CallbackQuery, state: FSMContext) -> None:
    """Показывает карточку чека заново после правок: суммой и позициями можно управлять."""
    data = await state.get_data()
    parsed = data.get("parsed") or {}
    spending = await get_monthly_spending(user_id=callback.from_user.id)
    limits = await budget.get_limits(callback.from_user.id)
    await callback.message.answer(_card_text(parsed, spending, limits, data.get("source", "text")),
                                  reply_markup=_card_keyboard(parsed))


@router.callback_query(F.data.startswith("items_page:"))
async def items_page(callback: CallbackQuery, state: FSMContext):
    """Листает страницы длинного чека: кнопки правки должны дойти до каждой позиции."""
    try:
        page = int(callback.data.split(":", 1)[1])
    except ValueError:
        page = 0
    await _render_items(callback, state, page=page)
    await callback.answer()


@router.callback_query(F.data.startswith("item_del:"))
async def item_delete(callback: CallbackQuery, state: FSMContext):
    """Убирает позицию, которую OCR выдумал или прочитал дважды."""
    data = await state.get_data()
    parsed = data.get("parsed") or {}
    items = parsed.get("items") or []
    try:
        index = int(callback.data.split(":", 1)[1])
    except ValueError:
        await callback.answer("Не понял, какую позицию убрать", show_alert=True)
        return
    if not 0 <= index < len(items):
        await callback.answer("Список уже изменился, открой его заново", show_alert=True)
        return
    removed = items.pop(index)
    parsed["items"] = items
    _mark_items_edited(parsed)
    await state.update_data(parsed=parsed)
    await _render_items(callback, state, f"🗑 Убрал: {md_safe(removed.get('name') or 'позиция')}",
                        page=index // ITEMS_PER_PAGE)
    await callback.answer("Позиция убрана")


@router.callback_query(F.data.startswith("item_fix:"))
async def item_fix(callback: CallbackQuery, state: FSMContext):
    """Спрашивает новое название и цену прочитанной позиции."""
    data = await state.get_data()
    items = (data.get("parsed") or {}).get("items") or []
    try:
        index = int(callback.data.split(":", 1)[1])
    except ValueError:
        await callback.answer("Не понял, какую позицию исправить", show_alert=True)
        return
    if not 0 <= index < len(items):
        await callback.answer("Список уже изменился, открой его заново", show_alert=True)
        return
    item = items[index]
    await state.update_data(item_index=index)
    await state.set_state(ExpenseStates.waiting_for_item_edit)
    await callback.message.edit_text(
        f"✏️ **Позиция {index + 1}:** {md_safe(item.get('name') or 'позиция')} — "
        f"{format_amount(item.get('sum') or 0)}\n\n"
        "Пришли новое название и цену одним сообщением.\n"
        "• `Сыр Российский 320,50` — заменить и название, и цену\n"
        "• `320,50` — поправить только цену",
        reply_markup=get_item_edit_kb(),
    )
    await callback.answer()


@router.callback_query(F.data == "item_add")
async def item_add(callback: CallbackQuery, state: FSMContext):
    """Спрашивает позицию, которую OCR пропустил."""
    data = await state.get_data()
    if not data.get("parsed"):
        await callback.answer("Данные устарели, отправь чек заново", show_alert=True)
        return
    await state.update_data(item_index=None)
    await state.set_state(ExpenseStates.waiting_for_item_edit)
    await callback.message.edit_text(
        "➕ **Пропущенная позиция**\n\nПришли название и цену одним сообщением:\n"
        "• `Сыр Российский 320,50`",
        reply_markup=get_item_edit_kb(),
    )
    await callback.answer()


@router.message(ExpenseStates.waiting_for_item_edit, F.text)
async def set_item_edit(message: Message, state: FSMContext):
    """Применяет правку позиции: правит существующую или добавляет новую."""
    data = await state.get_data()
    parsed = data.get("parsed") or {}
    items = parsed.get("items") or []
    name, price = _parse_item_input(message.text)
    if price is None and not name:
        await message.answer("⚠️ Нужно название, цена или и то и другое: `Сыр Российский 320,50`")
        return

    index = data.get("item_index")
    if index is not None and 0 <= index < len(items):
        item = items[index]
        if name:
            item["name"] = name
        if price is not None:
            item.update(qty=1.0, price=price, sum=price)
        item.update(manual=True, verified=True, recovered=False, corrected=False)
        note = f"✏️ Исправил позицию {index + 1}."
    elif price is not None:
        items.append({"name": name or "Позиция", "qty": 1.0, "price": price, "sum": price,
                      "manual": True, "verified": True})
        note = "➕ Добавил позицию."
    else:
        await message.answer("⚠️ У новой позиции должна быть цена: `Сыр Российский 320,50`")
        return

    parsed["items"] = items
    _mark_items_edited(parsed)
    await state.update_data(parsed=parsed, item_index=None)
    await state.set_state(ExpenseStates.waiting_for_confirmation)
    # остаёмся на той же странице: после правки 15-й позиции первая страница читалась бы
    # как «моя правка потерялась».
    page = index // ITEMS_PER_PAGE if index is not None else (len(items) - 1) // ITEMS_PER_PAGE
    await message.answer(f"{note}\n\n" + items_list_text(items, parsed.get("store") or "", parsed),
                         reply_markup=_items_markup(parsed, page))


@router.callback_query(F.data == "receipt_sync_total")
async def receipt_sync_total(callback: CallbackQuery, state: FSMContext):
    """Делает сумму чека равной сумме позиций — после правки списка это единственный ориентир."""
    data = await state.get_data()
    parsed = data.get("parsed") or {}
    items = parsed.get("items") or []
    if not items:
        await callback.answer("В чеке нет позиций", show_alert=True)
        return
    parsed["amount"] = round(sum(item.get("sum") or 0 for item in items), 2)
    parsed["total_estimated"] = False
    await state.update_data(parsed=parsed)
    await callback.answer(f"Сумма чека: {format_amount(parsed['amount'])}")
    await _refresh_card(callback, state)


@router.callback_query(F.data == "receipt_card")
async def receipt_card(callback: CallbackQuery, state: FSMContext):
    """Возврат от правки позиций к карточке чека."""
    data = await state.get_data()
    if not data.get("parsed"):
        await callback.answer("Данные устарели, отправь чек заново", show_alert=True)
        return
    await state.set_state(ExpenseStates.waiting_for_confirmation)
    await _refresh_card(callback, state)
    await callback.answer()


@router.callback_query(F.data == "edit_expense")
async def edit_expense(callback: CallbackQuery, state: FSMContext):
    data = await state.get_data()
    parsed = data.get("parsed")
    if not parsed:
        await callback.answer("Данные устарели, отправь трату заново", show_alert=True)
        return
    spending = await get_monthly_spending(user_id=callback.from_user.id)
    limits = await budget.get_limits(callback.from_user.id)
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
    limits = await budget.get_limits(callback.from_user.id)
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
    limits = await budget.get_limits(message.from_user.id)
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
    limits = await budget.get_limits(callback.from_user.id)
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
        limits = await budget.get_limits(callback.from_user.id)
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
    limits = await budget.get_limits(callback.from_user.id)
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

async def _progress_tick(status: Message, delay: float, text: str) -> None:
    """Заменяет «читаю чек» на пошаговый текст: долгое молчание читается как зависание."""
    try:
        await asyncio.sleep(delay)
        await status.edit_text(text)
    except Exception:
        pass  # сообщение могло устареть — это только украшение ожидания


@router.message(F.photo)
async def process_receipt_photo(message: Message, state: FSMContext):
    status = await message.answer("📸 Читаю чек, это займёт несколько секунд...")
    ticker = asyncio.create_task(_progress_tick(
        status, 5.0, "🔎 Разбираю таблицу и сверяю суммы с итогом чека, ещё немного..."))

    photo = message.photo[-1]
    file_path = receipt_path(photo.file_unique_id)
    await message.bot.download(photo, destination=file_path)

    try:
        receipt = await parse_receipt(file_path)
    finally:
        ticker.cancel()
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
        "total_estimated": receipt.get("total_estimated", False),
        "over_total": receipt.get("over_total", False),
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
