"""Ежедневная сводка по расписанию (APScheduler)."""
import random

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from aiogram import Bot
from aiogram.exceptions import TelegramAPIError

from config import (DAILY_REPORT_HOUR, USERS, WEEKLY_REPORT_HOUR, WEEKLY_REPORT_WEEKDAY)
from database.db import get_debts, get_monthly_spending, get_total_spent_this_month, get_transactions
from services import advice, mutelist, recurring
from services.digest import weekly_digest_text
from services.forecast import (forecast_note, forecast_text, grocery_forecast, limit_status,
                               limit_text, weekly_food_limit, weekly_spend)
from utils.formatting import format_amount


async def build_daily_summary_text(user_id: int | None = None) -> str:
    """Сводка на утро. Общие цифры — по семье, но предупреждение о списании личное:
    у каждого свои подписки, и чужое списание в своей сводке видеть бессмысленно."""
    from datetime import datetime

    spending = await get_monthly_spending()
    total = await get_total_spent_this_month()
    debts = await get_debts()

    lines = [f"🌅 **Доброе утро!** {datetime.now().strftime('%d.%m.%Y')}", ""]

    if debts:
        lines.append("💳 **Остатки по кредитам:**")
        total_debt = 0
        for d in debts:
            lines.append(f"• {d['name']}: {format_amount(d['current_amount'])}")
            total_debt += d["current_amount"]
        lines.append(f"**Итого долги: {format_amount(total_debt)}**")
    else:
        lines.append("🎉 Все кредиты закрыты!")

    lines.append("")
    lines.append(f"💸 Траты за месяц (оба): **{format_amount(total)}**")
    if spending:
        top = sorted(spending.items(), key=lambda x: -x[1])[:3]
        lines.append("📊 Топ-3 категории: " + ", ".join(f"{c} {format_amount(a)}" for c, a in top))
    if user_id is not None:
        # Про подписки дешевле узнать за день до списания, чем из отчёта за месяц.
        # Отключённые серии в напоминание не попадают — это выбор пользователя.
        rows = [dict(row) for row in await get_transactions(user_id=user_id, days=200)]
        found = recurring.find_recurring(rows)
        visible, _ = mutelist.split(found,
                                    await mutelist.muted_keys(user_id, mutelist.RECURRING))
        upcoming = recurring.upcoming_text(visible)
        if upcoming:
            lines += ["", upcoming]
        # Продукты — самая частая и самая незаметная утечка: о ней стоит сказать не только
        # в недельном дайджесте, но и в тот день, когда неделя поехала вверх.
        grocery = grocery_forecast(rows)
        if grocery and grocery["over"]:
            lines += ["", forecast_text(grocery), forecast_note(grocery)]
        # Заданный лимит важнее общего темпа: человек сам сказал, сколько готов тратить,
        # и для этой проверки история не нужна — достаточно суммы за последние семь дней.
        status = limit_status(weekly_spend(rows), await weekly_food_limit(user_id))
        if status and (status["over"] or status["near"]):
            lines += ["", limit_text(status)]
    return "\n".join(lines)


def register_scheduler(bot: Bot) -> AsyncIOScheduler:
    scheduler = AsyncIOScheduler()
    user_ids = list(USERS.keys())

    async def send_daily():
        for user_id in user_ids:
            # День окончания цели отмечается здесь, а не только в недельной задаче: иначе
            # итог попал бы в историю с задержкой до недели.
            await advice.close_goal_if_finished(user_id)
            try:
                await bot.send_message(user_id, await build_daily_summary_text(user_id))
            except TelegramAPIError:
                pass  # пользователь мог заблокировать бота
            except Exception:
                pass  # сетевые проблемы — не роняем планировщик

    async def send_weekly():
        for user_id in user_ids:
            # Закрытие цели перед сводкой: без этого в истории не было бы той цели, про исход
            # которой сводка только что говорит.
            await advice.close_goal_if_finished(user_id)
            try:
                await bot.send_message(user_id, await weekly_digest_text(user_id))
            except TelegramAPIError:
                continue  # пользователь мог заблокировать бота
            except Exception:
                continue  # сбой дайджеста не должен ронять планировщик
            # Итог закончившейся цели считается сказанным только после удачной отправки:
            # отметка здесь, а не при сборке текста, поэтому сбой не съедает его молча.
            await advice.mark_goal_outcome_sent(user_id)

    hour = max(0, min(23, DAILY_REPORT_HOUR))
    scheduler.add_job(
        send_daily,
        trigger="cron",
        hour=hour,
        minute=random.randint(0, 59),
        id="daily_report",
        replace_existing=True,
    )
    # Дайджест — раз в неделю: итоги смотреть один раз, а не каждый вечер.
    scheduler.add_job(
        send_weekly,
        trigger="cron",
        day_of_week=max(0, min(6, WEEKLY_REPORT_WEEKDAY)),
        hour=max(0, min(23, WEEKLY_REPORT_HOUR)),
        minute=random.randint(0, 59),
        id="weekly_digest",
        replace_existing=True,
    )
    return scheduler
