"""Ежедневная сводка по расписанию (APScheduler)."""
import random

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from aiogram import Bot
from aiogram.exceptions import TelegramAPIError

from config import USERS, DAILY_REPORT_HOUR
from database.db import get_monthly_spending, get_total_spent_this_month, get_debts
from utils.formatting import format_amount


async def build_daily_summary_text() -> str:
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
    return "\n".join(lines)


def register_scheduler(bot: Bot) -> AsyncIOScheduler:
    scheduler = AsyncIOScheduler()
    user_ids = list(USERS.keys())

    async def send_daily():
        text = await build_daily_summary_text()
        for user_id in user_ids:
            try:
                await bot.send_message(user_id, text)
            except TelegramAPIError:
                pass  # пользователь мог заблокировать бота
            except Exception:
                pass  # сетевые проблемы — не роняем планировщик

    hour = max(0, min(23, DAILY_REPORT_HOUR))
    scheduler.add_job(
        send_daily,
        trigger="cron",
        hour=hour,
        minute=random.randint(0, 59),
        id="daily_report",
        replace_existing=True,
    )
    return scheduler
