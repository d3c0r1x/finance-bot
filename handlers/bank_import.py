"""Импорт банковской выписки (PDF «Справка о движении средств»).

Путь пользователя: /statement или любой PDF-документ → сводка с честной сверкой
сумм против итогов банка → «Импортировать» → пакетная запись покупок и
пополнений с категоризацией магазинов → итог и возможность отменить всё одним
нажатием. Переводы себе и снятия в аналитику не попадают, повторная загрузка
того же файла дублей не создаёт (сверка по дате/времени/сумме/описанию).
Разобранная выписка живёт в FSM-состоянии между сводкой и подтверждением.
"""
import json
from pathlib import Path

from aiogram import F, Router
from aiogram.filters import Command
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, InlineKeyboardButton, InlineKeyboardMarkup, Message

from ai.llm import classify_merchants
from database.db import (add_transactions_bulk, delete_transactions_by_ids,
                         get_transactions)
from services.bank_statement import BankOp, Statement, parse_statement_pdf
from utils.filters import AccessFilter
from utils.formatting import format_amount, md_safe

router = Router()
router.message.filter(AccessFilter())
router.callback_query.filter(AccessFilter())

BANK_MERCHANTS_KEY = "bank_merchants:{user_id}"  # кэш категорий магазинов между импортами


def _op_key(op: BankOp) -> str:
    """Ключ операции для дедупликации: дата, время, сумма, описание."""
    return f"{op.date}T{op.time};{op.amount:.2f};{op.description[:40]}"


def _existing_keys(rows: list[dict]) -> set[str]:
    """Ключи уже записанных операций за год — против дублей при повторной загрузке."""
    keys = set()
    for row in rows:
        created = str(row.get("created_at") or "")
        if len(created) < 16:
            continue
        keys.add(f"{created[:10]}T{created[11:16]};{float(row.get('amount') or 0):.2f};"
                 f"{str(row.get('description') or '')[:40]}")
    return keys


def _split(ops: list[BankOp]) -> tuple[list[BankOp], list[BankOp], list[BankOp]]:
    """(покупки, пополнения, прочее). Прочее — переводы и снятия, они не пишутся."""
    purchases = [o for o in ops if o.amount < 0 and o.kind == "purchase"]
    incomes = [o for o in ops if o.amount > 0 and o.kind == "income"]
    taken = {id(o) for o in purchases} | {id(o) for o in incomes}
    return purchases, incomes, [o for o in ops if id(o) not in taken]


def _expense(purchases: list[BankOp]) -> float:
    return round(-sum(o.amount for o in purchases), 2)


def _income(incomes: list[BankOp]) -> float:
    return round(sum(o.amount for o in incomes), 2)


def _fmt_date(iso_date: str) -> str:
    year, month, day = iso_date.split("-")
    return f"{day}.{month}.{year}"


def summary_text(st: Statement) -> str:
    """Сводка выписки с результатом сверки против итогов банка."""
    purchases, incomes, skipped = _split(st.ops)
    lines = ["🏦 **Выписка распознана**", ""]
    if st.period:
        start, end = st.period
        lines.append(f"📅 Период: **{_fmt_date(start)} — {_fmt_date(end)}** · операций: {len(st.ops)}")
    lines.append("")
    lines.append(f"🛒 Покупок: **{len(purchases)}** на {format_amount(_expense(purchases))}")
    lines.append(f"💰 Пополнений: **{len(incomes)}** на {format_amount(_income(incomes))}")
    if skipped:
        lines.append(f"↩️ Пропущу (переводы себе и снятия): {len(skipped)}")
    lines.append("")
    if st.totals_found:
        if st.check_ok:
            lines.append("✅ Суммы сошлись с итогами банка копейка в копейку.")
        else:
            diff_e = abs(st.expense_sum - st.expected_expense)
            diff_i = abs(st.income_sum - st.expected_income)
            lines.append("⚠️ **Сверка не сошлась** — часть операций могла не прочитаться:")
            lines.append(f"• покупки: {format_amount(st.expense_sum)} из {format_amount(st.expected_expense)} "
                         f"(разница {format_amount(diff_e)})")
            lines.append(f"• пополнения: {format_amount(st.income_sum)} из {format_amount(st.expected_income)} "
                         f"(разница {format_amount(diff_i)})")
            lines.append("Если разница большая — проверь файл и пришли ещё раз.")
    else:
        lines.append("ℹ️ Итогов банка в файле нет — сверять не с чем, суммы посчитаны по операциям.")
    lines.append("")
    lines.append("Импортирую покупки и пополнения; переводы самому себе тратой не считаю.")
    return "\n".join(lines)


def _top_merchants(purchases: list[BankOp], limit: int = 8) -> list[tuple[str, float]]:
    """Самые «дорогие» магазины выписки — для строки «куда уходит»."""
    by_merchant: dict[str, float] = {}
    for op in purchases:
        name = op.merchant or op.description[:30] or "Без названия"
        by_merchant[name] = by_merchant.get(name, 0.0) - op.amount
    return sorted(by_merchant.items(), key=lambda kv: -kv[1])[:limit]


async def _cached_merchant_categories(new_merchants: set[str], user_id: int) -> dict[str, str]:
    """Категории магазинов с кэшем в базе: модель зовётся только для незнакомых."""
    from database.db import get_setting, set_setting
    try:
        categories = dict(json.loads(await get_setting(
            BANK_MERCHANTS_KEY.format(user_id=user_id), "{}")) or {})
    except Exception:
        categories = {}
    missing = sorted(name for name in new_merchants if name and name not in categories)
    if missing:
        categories.update(await classify_merchants(missing))
        await set_setting(BANK_MERCHANTS_KEY.format(user_id=user_id),
                          json.dumps(categories, ensure_ascii=False))
    return categories


def _row(op: BankOp, category: str) -> tuple:
    """Строка bulk-вставки из операции. Дата — дата операции банка."""
    return (op.amount, category, op.merchant or None, op.description[:100],
            "expense" if op.amount < 0 else "income",
            None, "bank", f"{op.date} {op.time}:00")


def _confirm_kb(count: int) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[
        [InlineKeyboardButton(text=f"✅ Импортировать {count} операций", callback_data="bank_do")],
        [InlineKeyboardButton(text="❌ Отмена", callback_data="bank_cancel")],
    ])


def _undo_kb() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[
        [InlineKeyboardButton(text="↩️ Отменить импорт", callback_data="bank_undo")],
        [InlineKeyboardButton(text="📊 К отчётам", callback_data="menu_report")],
    ])


async def _download_and_parse(message: Message) -> Statement:
    """Скачивает документ во временный файл и разбирает; файл удаляется в любом случае."""
    buf = Path("data/_statement_tmp.pdf")
    try:
        await message.bot.download(message.document, destination=buf)
        return parse_statement_pdf(buf)
    finally:
        buf.unlink(missing_ok=True)


@router.callback_query(F.data == "bank_hint")
async def bank_hint(callback: CallbackQuery) -> None:
    """Подсказка из меню отчётов: как выглядит путь импорта."""
    await callback.message.answer(
        "🏦 **Импорт выписки**\n\n"
        "Пришли PDF «Справка о движении средств» из приложения банка — сведу её с ботом.\n\n"
        "Что произойдёт:\n"
        "• покупки и пополнения запишутся с категориями магазинов;\n"
        "• переводы самому себе и снятия тратами не станут;\n"
        "• повторная загрузка того же файла дублей не создаст;\n"
        "• после импорта всё можно отменить одной кнопкой.")
    await callback.answer()


@router.message(Command("statement"))
async def statement_command(message: Message) -> None:
    await message.answer(
        "🏦 Пришли PDF-выписку банка («Справка о движении средств») — сведу её с ботом.\n"
        "Импортирую покупки и пополнения с категориями; переводы самому себе и\n"
        "снятия наличных тратами не считаю. Повторная загрузка дублей не создаёт.")


@router.message(F.document, F.document.mime_type.contains("pdf"))
async def statement_document(message: Message, state: FSMContext) -> None:
    """PDF-документ → пробуем разобрать как выписку, иначе вежливо отказываем."""
    status = await message.answer("📄 Разбираю выписку…")
    try:
        st = await _download_and_parse(message)
    except ValueError as exc:
        await status.edit_text(f"⚠️ {md_safe(str(exc))}\n\n"
                               "Если это выписка — пришли её как есть, без переименования.")
        return
    except Exception:
        await status.edit_text("⚠️ Не смог прочитать PDF. Пришли файл в исходном виде из приложения банка.")
        return

    purchases, incomes, _ = _split(st.ops)
    top = _top_merchants(purchases)
    text = summary_text(st)
    if top:
        text += "\n\n**Куда уходит:** " + ", ".join(
            f"{md_safe(name)} — {format_amount(amount)}" for name, amount in top)
    # Выписка ждёт подтверждения в состоянии: словарь операций + итоги для сверки.
    await state.update_data(bank_ops=[vars(op) for op in st.ops],
                            bank_expected=[st.expected_expense, st.expected_income],
                            bank_totals_found=st.totals_found)
    await status.edit_text(text, reply_markup=_confirm_kb(len(purchases) + len(incomes)))


def _statement_from_state(data: dict) -> Statement | None:
    """Восстанавливает выписку из состояния; None — состояние устарело."""
    raw_ops = data.get("bank_ops")
    if not raw_ops:
        return None
    ops = [BankOp(**raw) for raw in raw_ops]
    expected = data.get("bank_expected") or [0.0, 0.0]
    return Statement(ops=ops, expected_expense=expected[0], expected_income=expected[1],
                     totals_found=bool(data.get("bank_totals_found")))


@router.callback_query(F.data == "bank_cancel")
async def bank_cancel(callback: CallbackQuery, state: FSMContext) -> None:
    await state.update_data(bank_ops=None, bank_expected=None, bank_totals_found=None)
    await callback.message.edit_reply_markup(reply_markup=None)
    await callback.answer("Импорт отменён")


@router.callback_query(F.data == "bank_do")
async def bank_do(callback: CallbackQuery, state: FSMContext) -> None:
    """Импорт: дедупликация, категории магазинов, пакетная вставка, итог + undo."""
    user_id = callback.from_user.id
    data = await state.get_data()
    st = _statement_from_state(data)
    if st is None:
        await callback.answer("Выписка устарела — пришли файл заново", show_alert=True)
        return
    await callback.message.edit_reply_markup(reply_markup=None)
    await state.update_data(bank_ops=None)

    purchases, incomes, _ = _split(st.ops)
    history = [dict(r) for r in await get_transactions(user_id=user_id, days=370)]
    existing = _existing_keys(history)
    fresh = [op for op in purchases + incomes if _op_key(op) not in existing]

    status = await callback.message.answer(
        f"📥 К записи: {len(fresh)} операций (дублей пропущено: {len(purchases) + len(incomes) - len(fresh)})")
    if not fresh:
        await status.edit_text("ℹ️ Все операции из этой выписки уже в базе.")
        return

    categories = await _cached_merchant_categories(
        {op.merchant for op in fresh if op.kind == "purchase" and op.merchant}, user_id)
    rows = [_row(op, categories.get(op.merchant, "прочее") if op.kind == "purchase" else "прочее")
            for op in fresh]
    inserted = await add_transactions_bulk(user_id, rows)
    await state.update_data(bank_ids=inserted)

    spent = sum(abs(op.amount) for op in fresh if op.amount < 0)
    earned = sum(op.amount for op in fresh if op.amount > 0)
    n_exp = sum(1 for op in fresh if op.amount < 0)
    n_inc = len(fresh) - n_exp
    lines = ["✅ **Импорт готов**", "",
             f"🛒 Покупок: {n_exp} на {format_amount(round(spent, 2))}",
             f"💰 Пополнений: {n_inc} на {format_amount(round(earned, 2))}",
             "",
             "Теперь отчёты, бюджет и совет ИИ видят полную картину — включая карту."]
    await status.edit_text("\n".join(lines), reply_markup=_undo_kb())


@router.callback_query(F.data == "bank_undo")
async def bank_undo(callback: CallbackQuery, state: FSMContext) -> None:
    """Отмена последнего импорта: удаляются только строки с source='bank'."""
    data = await state.get_data()
    ids = data.get("bank_ids") or []
    removed = await delete_transactions_by_ids(callback.from_user.id, ids, source="bank")
    await state.update_data(bank_ids=None)
    await callback.message.edit_reply_markup(reply_markup=None)
    await callback.answer(f"Удалено операций: {removed}" if removed else "Удалять нечего",
                          show_alert=True)
