"""Главное меню: /start с мини-сводкой, кнопки, справка и свободный ввод трат."""
from aiogram import F, Router
from aiogram.exceptions import TelegramAPIError
from aiogram.filters import Command
from aiogram.fsm.context import FSMContext
from aiogram.types import CallbackQuery, MenuButtonCommands, Message

from config import HIDE_MENU_BUTTON
from database.db import (delete_transaction, ensure_user, get_debts, get_month_income,
                         get_monthly_spending, get_recent_transactions,
                         get_total_spent_this_month, has_transactions)
from handlers.debts import build_debts_overview
from handlers.expenses import TEXT_HINT, parse_free_text
from keyboards.main_menu_kb import (get_debts_kb, get_history_kb, get_main_menu_inline_kb,
                                   get_main_menu_kb, get_report_kb)
from services import budget, profile
from services.analytics import budget_pace
from services.forecast import food_week_line
from utils.filters import AccessFilter
from utils.formatting import format_amount, get_category_emoji, month_name_ru, progress_bar

router = Router()
router.message.filter(AccessFilter())
router.callback_query.filter(AccessFilter())

HELP_TEXT = (
    "❓ **Как пользоваться**\n\n"
    "• ✍️ **Траты** — просто напиши: «Заправка 2000», «Пятёрочка 3450,50»\n"
    "• 📷 **Чек** — пришли фото: распознаю магазин, сумму и все позиции;\n"
    "   для продуктового чека ещё и разберу корзину\n"
    "• 🎯 **Бюджет** — свои лимиты задаются в ⚙️ Настройки (у каждого они свои),\n"
    "   ИИ предложит бюджет по твоей истории трат\n"
    "• 💰 **Доход** — «Зарплата 150000»\n"
    "• 💳 **Платёж по кредиту** — «Платёж Т-Банк 3000»\n"
    "• 📊 **Отчёты** — месяц, неделя, 90 дней, совместный\n"
    "• 🔁 **Регулярные платежи** — подписки, найденные по твоей истории, и что списывается на днях\n"
    "• 📈 **Диаграмма** — куда ушёл месяц, картинкой\n"
    "• 💳 **Долги** — остатки, платежи, прогноз погашения\n"
    "• 🕘 **История** — последние записи и быстрый контроль расходов\n"
    "• ↩️ **Отменить последнюю** — удалить ошибочную последнюю запись и вернуть платёж по кредиту\n"
    "• 🔍 **Цена товара** — «/price молоко»: обычная цена по твоим чекам, история покупок, где было дешевле и не пора ли брать снова\n"
    "• 🛒 **Список покупок** — что пора взять, судя по ритму твоих чеков\n"
    "• 🖥 **Панель** — база, чеки, лимиты и выгрузка CSV в panel.py на компьютере\n\n"
    "Команды: /start — меню, /menu — кнопки, /price — цена товара, /help — эта справка.\n"
    "Перед сохранением траты всегда показываю карточку — можно исправить сумму и категорию."
)


async def build_dashboard(name: str, user_id: int | None = None) -> str:
    """Приветствие с мини-сводкой за текущий месяц — главный экран бота."""
    total = await get_total_spent_this_month(user_id)
    spending = await get_monthly_spending(user_id)
    income = await get_month_income(user_id)
    debts = await get_debts()
    # Лимит — личный: траты в сводке тоже личные, иначе проценты считались бы от чужого бюджета
    total_limit = await budget.get_total_limit(user_id)

    percent = min(100, int((total / total_limit) * 100)) if total_limit else 0
    lines = [
        f"👋 Привет, **{name}**!",
        "",
        f"📅 **{month_name_ru().capitalize()}**",
        f"💸 Потрачено: **{format_amount(total)}** из {format_amount(total_limit)}",
        f"{progress_bar(percent)} {percent}%",
    ]
    if income:
        lines.append(f"💰 Доход: {format_amount(income)}")
    if spending:
        top_category, top_amount = max(spending.items(), key=lambda item: item[1])
        lines.append(f"{get_category_emoji(top_category)} Больше всего: {top_category} — {format_amount(top_amount)}")
    if debts:
        debt_total = sum(d["current_amount"] for d in debts)
        lines.append(f"💳 Долги: {format_amount(debt_total)}")
    if user_id is not None:
        # Продукты — самая частая трата недели, и следить за ней удобно прямо на входе.
        # Запрос узкий (только семь дней), чтобы главный экран не тормозил.
        food = await food_week_line(user_id)
        if food:
            lines.append(food)
        # «безопасно тратить в день» — привычка из приложений-конкурентов
        safe = await profile.safe_to_spend(user_id, total, income)
        if safe:
            lines += ["", safe]
    pace = budget_pace(total, total_limit)
    if pace:
        lines += ["", pace]

    lines += ["", "Напиши трату словами или выбери действие:"]
    return "\n".join(lines)


@router.message(Command("start"))
async def cmd_start(message: Message, state: FSMContext):
    user_id = message.from_user.id
    telegram_name = profile.telegram_display_name(message.from_user)
    await ensure_user(user_id, telegram_name)
    await profile.sync_telegram_name(user_id, message.from_user)
    await state.clear()
    if HIDE_MENU_BUTTON:
        # на случай, если при старте бота чата ещё не было в списке
        try:
            await message.bot.set_chat_menu_button(chat_id=user_id, menu_button=MenuButtonCommands())
        except TelegramAPIError:
            pass
    if not await profile.is_onboarded(user_id):
        if await has_transactions(user_id):
            await profile.mark_onboarded(user_id)  # человек уже пользуется ботом
        else:
            from handlers import onboarding  # импорт здесь: onboarding сам зовёт главный экран

            await onboarding.start(message, user_id, state)
            return
    name = await profile.display_name(user_id)
    await message.answer(
        await build_dashboard(name, user_id),
        reply_markup=get_main_menu_kb(),
    )
    await message.answer("⚡ Быстрые действия:", reply_markup=get_main_menu_inline_kb())


@router.message(Command("menu"))
async def cmd_menu(message: Message, state: FSMContext):
    await state.clear()
    await message.answer(
        "🧭 **Меню**\n\nНапиши трату словами или выбери действие.",
        reply_markup=get_main_menu_kb(),
    )
    await message.answer("⚡ Быстрые действия:", reply_markup=get_main_menu_inline_kb())


@router.message(Command("help"))
async def cmd_help(message: Message):
    await message.answer(HELP_TEXT)


# ─── Кнопки нижнего меню ─────────────────────────────────────────────────

@router.message(F.text == "📊 Отчёт")
async def show_report_menu(message: Message):
    await message.answer("📊 **Выбери отчёт:**", reply_markup=get_report_kb())


@router.message(F.text == "🕘 История")
async def history_button(message: Message):
    rows = await get_recent_transactions(message.from_user.id, limit=8)
    if not rows:
        await message.answer("🕘 История пока пуста — добавь первую трату.", reply_markup=get_main_menu_kb())
        return
    lines = ["🕘 **Последние записи:**", ""]
    for row in rows:
        icon = "💰" if row["tx_type"] == "income" else ("💳" if row["tx_type"] == "debt_payment" else "💸")
        description = row["description"] or row["category"]
        lines.append(f"{icon} {format_amount(row['amount'])} — {description} · {row['category']}")
    await message.answer("\n".join(lines), reply_markup=get_history_kb(rows))


@router.message(F.text == "↩️ Отменить последнюю")
async def undo_last_button(message: Message):
    rows = await get_recent_transactions(message.from_user.id, limit=1)
    if not rows:
        await message.answer("↩️ Отменять пока нечего.", reply_markup=get_main_menu_kb())
        return
    row = rows[0]
    deleted = await delete_transaction(row["id"], message.from_user.id)
    if not deleted:
        await message.answer("⚠️ Запись уже изменилась — обнови историю.", reply_markup=get_main_menu_kb())
        return
    description = row["description"] or row["category"]
    await message.answer(
        f"↩️ Отменил последнюю запись: **{format_amount(row['amount'])}** — {description}\n"
        "Данные и позиции чека удалены. Если это был платёж по кредиту, остаток восстановлен.",
        reply_markup=get_main_menu_kb(),
    )


@router.callback_query(F.data == "menu_history")
async def history_callback(callback: CallbackQuery):
    rows = await get_recent_transactions(callback.from_user.id, limit=8)
    if not rows:
        text = "🕘 История пока пуста — добавь первую трату."
    else:
        lines = ["🕘 **Последние записи:**", ""]
        for row in rows:
            icon = "💰" if row["tx_type"] == "income" else ("💳" if row["tx_type"] == "debt_payment" else "💸")
            lines.append(f"{icon} {format_amount(row['amount'])} — {row['description'] or row['category']} · {row['category']}")
        text = "\n".join(lines)
    await callback.message.answer(text, reply_markup=get_history_kb(rows))
    await callback.answer()


@router.callback_query(F.data.startswith("repeat_tx:"))
async def repeat_transaction(callback: CallbackQuery, state: FSMContext):
    try:
        transaction_id = int(callback.data.split(":", 1)[1])
    except ValueError:
        await callback.answer("Некорректная запись", show_alert=True)
        return
    from database.db import get_transaction
    row = await get_transaction(transaction_id, callback.from_user.id)
    if not row or row["tx_type"] != "expense":
        await callback.answer("Запись не найдена", show_alert=True)
        return
    parsed = {
        "amount": row["amount"], "category": row["category"],
        "subcategory": row["subcategory"], "description": row["description"],
        "tx_type": "expense", "debt_target": None,
    }
    await state.update_data(parsed=parsed, source="repeat")
    from handlers.expenses import ExpenseStates, _card_keyboard, _card_text
    spending = await get_monthly_spending(callback.from_user.id)
    limits = await budget.get_limits(callback.from_user.id)
    await state.set_state(ExpenseStates.waiting_for_confirmation)
    await callback.message.answer(
        "🔁 **Повторяю прошлую трату**\n\n" +
        _card_text(parsed, spending, limits, "text"),
        reply_markup=_card_keyboard(parsed),
    )
    await callback.answer("Проверь и подтверди")


@router.message(F.text == "💰 Доход")
async def add_income_hint(message: Message):
    await message.answer(
        "💰 **Доход**\n\nНапиши сумму и источник одним сообщением:\n"
        "• Зарплата 150000\n"
        "• Аванс 50000\n"
        "• Вернули долг 10000"
    )


# ─── Быстрые действия (инлайн) ───────────────────────────────────────────

@router.callback_query(F.data == "menu_expense")
async def menu_expense(callback: CallbackQuery):
    await callback.message.answer(TEXT_HINT)
    await callback.answer()


@router.callback_query(F.data == "menu_receipt")
async def menu_receipt(callback: CallbackQuery):
    await callback.message.answer("📸 Пришли фото чека — распознаю магазин, сумму и категорию автоматически.")
    await callback.answer()


@router.callback_query(F.data == "menu_report")
async def menu_report(callback: CallbackQuery):
    await callback.message.answer("📊 **Выбери отчёт:**", reply_markup=get_report_kb())
    await callback.answer()


@router.callback_query(F.data == "menu_income")
async def menu_income(callback: CallbackQuery):
    await callback.message.answer(
        "💰 Напиши доход одним сообщением, например: «Зарплата 150000»."
    )
    await callback.answer()


@router.callback_query(F.data == "menu_help")
async def menu_help(callback: CallbackQuery):
    await callback.message.answer(HELP_TEXT)
    await callback.answer()


@router.callback_query(F.data == "menu_debts")
async def menu_debts(callback: CallbackQuery):
    await callback.message.answer(await build_debts_overview(), reply_markup=get_debts_kb())
    await callback.answer()


# ─── Свободный ввод: любое сообщение = трата ─────────────────────────────
# Регистрируется последним в самом последнем роутере, поэтому кнопки меню
# и состояния FSM перехватываются раньше.

@router.message(F.text & ~F.text.startswith("/"))
async def free_text_expense(message: Message, state: FSMContext):
    await parse_free_text(message, state)
