"""Долги: сводка, карточки, запись платежа, прогноз погашения."""
import re

from aiogram import F, Router
from aiogram.fsm.context import FSMContext
from aiogram.fsm.state import State, StatesGroup
from aiogram.types import CallbackQuery, Message

from database.db import add_transaction, get_debt, get_debts
from keyboards.debt_kb import get_cancel_kb, get_debt_detail_kb
from keyboards.main_menu_kb import get_debts_kb, get_main_menu_kb
from services.analytics import forecast_debt_payoff
from utils.formatting import DEBT_NAMES, format_amount, progress_bar

router = Router()


class DebtStates(StatesGroup):
    waiting_for_payment_amount = State()


def _paid_percent(debt) -> int:
    initial = debt["initial_amount"] or 0
    if not initial:
        return 0
    paid = initial - debt["current_amount"]
    return max(0, min(100, int(paid / initial * 100)))


async def build_debts_overview() -> str:
    """Сводка по всем активным кредитам с прогресс-барами."""
    debts = await get_debts()
    if not debts:
        return "🎉 **Все кредиты закрыты!** Долгов нет."

    lines = ["💳 **Текущие долги**", ""]
    total = 0
    for debt in debts:
        total += debt["current_amount"]
        percent = _paid_percent(debt)
        lines.append(f"**{debt['name']}** — {format_amount(debt['current_amount'])}")
        lines.append(f"   {progress_bar(percent)} выплачено {percent}%")
        details = []
        if debt["interest_rate"]:
            details.append(f"ставка {debt['interest_rate']:.1f}%")
        if debt["min_payment"]:
            details.append(f"платёж {format_amount(debt['min_payment'])}")
        if details:
            lines.append("   " + " · ".join(details))
    lines += ["", f"**Итого долгов: {format_amount(total)}**"]
    return "\n".join(lines)


@router.message(F.text == "💳 Долги")
async def show_debts(message: Message):
    await message.answer(await build_debts_overview(), reply_markup=get_debts_kb())


@router.callback_query(F.data.startswith("debt_"))
async def debt_detail(callback: CallbackQuery, state: FSMContext):
    debt_id = callback.data.split("_", 1)[1]

    if debt_id == "forecast":
        debts = await get_debts()
        if not debts:
            await callback.message.answer("🎉 Все кредиты закрыты!")
            await callback.answer()
            return
        text = "📈 **Прогноз погашения** (минимальными платежами):\n\n"
        for debt in debts:
            months = forecast_debt_payoff(debt["current_amount"], debt["min_payment"], debt["interest_rate"])
            if months is None:
                text += (f"• {debt['name']}: платёж {format_amount(debt['min_payment'])} "
                         "не покрывает проценты ⚠️\n")
            else:
                years, rest = divmod(months, 12)
                term = f"{years} г. {rest} мес." if years else f"{months} мес."
                text += f"• {debt['name']}: ~{term}\n"
        await callback.message.answer(text)
        await callback.answer()
        return

    debt = await get_debt(debt_id)
    if not debt:
        await callback.answer("Долг не найден", show_alert=True)
        return

    paid = debt["initial_amount"] - debt["current_amount"]
    percent = _paid_percent(debt)
    text = (
        f"💳 **{debt['name']}**\n\n"
        f"Остаток: **{format_amount(debt['current_amount'])}**\n"
        f"{progress_bar(percent)} выплачено {percent}%\n"
        f"Внесено: {format_amount(paid)} из {format_amount(debt['initial_amount'])}"
    )
    if debt["min_payment"]:
        text += f"\nМинимальный платёж: {format_amount(debt['min_payment'])}"
    if debt["interest_rate"]:
        text += f"\nСтавка: {debt['interest_rate']:.1f}%"

    await callback.message.answer(text, reply_markup=get_debt_detail_kb(debt_id))
    await callback.answer()


@router.callback_query(F.data.startswith("pay_"))
async def pay_debt_start(callback: CallbackQuery, state: FSMContext):
    debt_id = callback.data.split("_", 1)[1]
    if debt_id not in DEBT_NAMES:
        await callback.answer("Неизвестный долг", show_alert=True)
        return

    debt = await get_debt(debt_id)
    if not debt or debt["status"] != "active":
        await callback.answer("Долг уже закрыт 🎉", show_alert=True)
        return

    await state.set_state(DebtStates.waiting_for_payment_amount)
    await state.update_data(debt_id=debt_id)
    await callback.message.answer(
        f"💳 **Платёж по «{debt['name']}»**\n"
        f"Остаток: {format_amount(debt['current_amount'])}\n\n"
        "Напиши сумму платежа числом, например: 3000",
        reply_markup=get_cancel_kb(),
    )
    await callback.answer()


@router.message(DebtStates.waiting_for_payment_amount, F.text)
async def process_payment_amount(message: Message, state: FSMContext):
    text = (message.text or "").replace(",", ".").replace(" ", "")
    if not re.match(r"^\d+(\.\d+)?$", text):
        await message.answer("⚠️ Напиши сумму числом, например: 3000")
        return

    amount = float(text)
    data = await state.get_data()
    debt_id = data["debt_id"]

    await add_transaction(
        user_id=message.from_user.id,
        amount=amount,
        category="долги",
        tx_type="debt_payment",
        debt_target=debt_id,
        description=f"Платёж по {DEBT_NAMES[debt_id]}",
        source="manual",
    )

    debt = await get_debt(debt_id)
    await state.clear()

    response = f"✅ Платёж {format_amount(amount)} записан по «{debt['name']}»"
    if debt["current_amount"] <= 0:
        response += "\n\n🎉 **Кредит полностью закрыт! Поздравляю!**"
    else:
        percent = _paid_percent(debt)
        response += (f"\n{progress_bar(percent)} выплачено {percent}%"
                     f"\nОстаток: **{format_amount(debt['current_amount'])}**")
    # возвращаем нижнее меню, иначе после платежа остаётся только «Отмена»
    await message.answer(response, reply_markup=get_main_menu_kb())


@router.message(DebtStates.waiting_for_payment_amount)
async def payment_not_text(message: Message):
    await message.answer("⚠️ Напиши сумму числом текстом, например: 3000",
                         reply_markup=get_cancel_kb())
