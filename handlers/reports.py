"""Отчёты и аналитика: месяц, неделя, 90 дней, совместный, совет ИИ, диаграммы.

Выгрузка CSV живёт в панели управления (panel.py), а не в боте: файлы удобнее смотреть
на компьютере, а не пересылать в чат.
"""
from aiogram import F, Router
from aiogram.filters import Command
from aiogram.types import BufferedInputFile, CallbackQuery, Message

from ai.llm import get_recommendation
from config import USERS
from database.db import (get_debts, get_month_income, get_monthly_spending,
                         get_total_spent_this_month, get_transactions)
from keyboards.report_kb import get_report_kb
from services import budget, charts
from services.analytics import (build_month_report, build_period_report, by_category,
                               forecast_end_of_month)
from utils.filters import AccessFilter
from utils.formatting import DEBT_NAMES, format_amount, md_safe

router = Router()
router.message.filter(AccessFilter())
router.callback_query.filter(AccessFilter())


async def _advice_context(user_id: int) -> str:
    """Собирает данные для совета ИИ: лимиты, категории, доход, долги."""
    spending = await get_monthly_spending(user_id=user_id)
    total_spent = await get_total_spent_this_month(user_id=user_id)
    income = await get_month_income(user_id=user_id)
    total_limit = await budget.get_total_limit()
    debts = await get_debts()
    debt_info = "; ".join(f"{d['name']}: {format_amount(d['current_amount'])} "
                          f"(ставка {d['interest_rate'] or 0}%)" for d in debts) or "нет"
    return (
        f"Потрачено в этом месяце: {total_spent:.0f} руб. из лимита {total_limit:.0f}. "
        f"Траты по категориям: {spending}. Доход за месяц: {income:.0f} руб. "
        f"Остатки по кредитам: {debt_info}. "
        "Дай 2-3 короткие рекомендации: куда направить свободные деньги и на чём сэкономить."
    )


async def send_advice(message: Message, user_id: int) -> None:
    status = await message.answer("🤖 Думаю над советом...")
    recommendation = await get_recommendation(await _advice_context(user_id))
    try:
        await status.delete()
    except Exception:
        pass
    # Текст модели может содержать незакрытый Markdown — чистим, иначе Telegram отвергнет сообщение
    await message.answer(f"🤖 **Совет ИИ**\n\n{md_safe(recommendation)}")


async def send_chart(message: Message, image: bytes | None, caption: str = "") -> bool:
    """Отправляет картинку-отчёт, если её удалось нарисовать."""
    if not image:
        return False
    await message.answer_photo(BufferedInputFile(image, filename="report.png"), caption=caption or None)
    return True


async def send_month_report(message: Message, user_id: int, with_advice: bool = True) -> None:
    spending = await get_monthly_spending(user_id=user_id)
    total_spent = await get_total_spent_this_month(user_id=user_id)
    limits = await budget.get_limits()
    total_limit = await budget.get_total_limit()
    income = await get_month_income(user_id=user_id)
    text = await build_month_report(spending, total_spent, limits, total_limit)
    if income:
        text += f"\n💰 Доход за месяц: {format_amount(income)}"
    image = await charts.month_card(spending, income, total_spent, total_limit,
                                    forecast_end_of_month(total_spent), limits=limits)
    # сначала картинка для быстрого взгляда, затем текст с лимитами по категориям
    await send_chart(message, image, "📊 Расходы за месяц")
    await message.answer(text)
    if with_advice:
        await send_advice(message, user_id)


async def send_period_chart(message: Message, user_id: int, days: int) -> None:
    """Отчёт за период: текст и диаграмма с тратами по дням."""
    transactions = await get_transactions(user_id=user_id, days=days)
    if not transactions:
        await message.answer(f"📭 Трат за {days} дн. не было.")
        return
    image = await charts.period_card(by_category(transactions),
                                     sum(row["amount"] for row in transactions
                                         if row["tx_type"] == "expense"),
                                     days, charts.daily_series(transactions, days))
    await send_chart(message, image, f"📈 Траты за {days} дн.")
    await message.answer(await build_period_report(transactions, days=days))


@router.callback_query(F.data == "report_month")
async def monthly_report(callback: CallbackQuery):
    await send_month_report(callback.message, callback.from_user.id)
    await callback.answer()


@router.callback_query(F.data == "report_week")
async def week_report(callback: CallbackQuery):
    await send_period_chart(callback.message, callback.from_user.id, 7)
    await callback.answer()


@router.callback_query(F.data == "report_quarter")
async def quarter_report(callback: CallbackQuery):
    await send_period_chart(callback.message, callback.from_user.id, 90)
    await callback.answer()


@router.callback_query(F.data == "report_chart")
async def report_chart(callback: CallbackQuery):
    """Только картинка: доли категорий за месяц и остаток бюджета."""
    await callback.answer("Рисую...")
    user_id = callback.from_user.id
    spending = await get_monthly_spending(user_id=user_id)
    if not spending:
        await callback.message.answer("📭 За этот месяц трат ещё нет.")
        return
    total_spent = await get_total_spent_this_month(user_id=user_id)
    income = await get_month_income(user_id=user_id)
    total_limit = await budget.get_total_limit()
    limits = await budget.get_limits()
    image = await charts.month_card(spending, income, total_spent, total_limit,
                                    forecast_end_of_month(total_spent), limits=limits)
    if not await send_chart(callback.message, image, "📈 Диаграмма расходов"):
        await callback.message.answer("⚠️ Не получилось нарисовать диаграмму — "
                                      "покажу текстом: /report")


@router.callback_query(F.data == "report_joint")
async def joint_report(callback: CallbackQuery):
    spending_all = await get_monthly_spending(user_id=None)
    total_all = await get_total_spent_this_month(user_id=None)
    income_all = await get_month_income(user_id=None)
    total_limit = await budget.get_total_limit()

    text = "👥 **Совместный отчёт за месяц**\n\n"
    text += f"💸 Всего потрачено: **{format_amount(total_all)}**\n"
    if income_all:
        text += f"💰 Общий доход: {format_amount(income_all)}\n"
    text += f"🎯 Общий лимит: {format_amount(total_limit)}\n\n"

    if spending_all:
        text += "**По категориям:**\n"
        for category, amount in sorted(spending_all.items(), key=lambda x: -x[1]):
            share = int(amount / total_all * 100) if total_all else 0
            text += f"• {category}: {format_amount(amount)} ({share}%)\n"

    if len(USERS) > 1:
        text += "\n**Вклад каждого:**\n"
        for user_id, info in USERS.items():
            spent = await get_total_spent_this_month(user_id=user_id)
            text += f"• {info['name']}: {format_amount(spent)}\n"

    debts = await get_debts()
    if debts:
        text += "\n**Долги:**\n"
        for debt in debts:
            text += f"• {DEBT_NAMES.get(debt['id'], debt['name'])}: {format_amount(debt['current_amount'])}\n"

    forecast = forecast_end_of_month(total_all)
    if forecast:
        text += f"\n📈 Прогноз на конец месяца: **{format_amount(forecast)}**"

    await callback.message.answer(text, reply_markup=get_report_kb())
    await callback.answer()


@router.callback_query(F.data == "report_advice")
async def ai_advice(callback: CallbackQuery):
    await callback.answer()
    await send_advice(callback.message, callback.from_user.id)


@router.message(Command("report"))
async def cmd_report(message: Message):
    await send_month_report(message, message.from_user.id)
