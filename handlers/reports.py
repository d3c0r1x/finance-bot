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
                         get_receipt_price_history, get_receipt_verdicts,
                         get_total_spent_this_month, get_transactions,
                         update_receipt_verdict)
from keyboards.report_kb import (get_bans_kb, get_digest_kb, get_goal_kb, get_recurring_kb,
                                 get_report_kb, get_shopping_kb)
from services import advice, budget, charts, mutelist, recurring
from services.analytics import (build_month_report, build_period_report, by_category,
                               forecast_end_of_month)
from services.digest import weekly_digest_text
from services.inflation import inflation_text, personal_inflation
from services.purchase_history import (card_text, catalog_text, parse_date, product_groups,
                                      search_products)
from services.shopping import (bought_marks, due_items, hide_blocked, hide_bought, mark_bought,
                               shopping_text)
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
    total_limit = await budget.get_total_limit(user_id)
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


async def recurring_screen(user_id: int) -> tuple[str, list[dict], list[dict]]:
    """(текст, активные серии, отключённые) за полгода истории пользователя."""
    rows = [dict(row) for row in await get_transactions(user_id=user_id, days=200)]
    found = recurring.find_recurring(rows)
    visible, muted = mutelist.split(found,
                                    await mutelist.muted_keys(user_id, mutelist.RECURRING))
    return recurring.recurring_text(visible, muted), visible, muted


async def send_chart(message: Message, image: bytes | None, caption: str = "") -> bool:
    """Отправляет картинку-отчёт, если её удалось нарисовать."""
    if not image:
        return False
    await message.answer_photo(BufferedInputFile(image, filename="report.png"), caption=caption or None)
    return True


async def send_month_report(message: Message, user_id: int, with_advice: bool = True) -> None:
    spending = await get_monthly_spending(user_id=user_id)
    total_spent = await get_total_spent_this_month(user_id=user_id)
    # Отчёт личный: и траты, и лимит — этого пользователя
    limits = await budget.get_limits(user_id)
    total_limit = await budget.get_total_limit(user_id)
    income = await get_month_income(user_id=user_id)
    text = await build_month_report(spending, total_spent, limits, total_limit)
    if income:
        text += f"\n💰 Доход за месяц: {format_amount(income)}"
    # Цель — здесь же: счёт «сдержано N из M» иначе виден только тому, кто сам откроет
    # её экран, а месяц — ровно тот период, в котором цель вообще существует.
    goal_line = await _goal_report_line(user_id)
    if goal_line:
        text += f"\n\n{goal_line}"
    image = await charts.month_card(spending, income, total_spent, total_limit,
                                    forecast_end_of_month(total_spent), limits=limits)
    # сначала картинка для быстрого взгляда, затем текст с лимитами по категориям
    await send_chart(message, image, "📊 Расходы за месяц")
    await message.answer(text)
    if with_advice:
        await send_advice(message, user_id)


async def _goal_report_line(user_id: int) -> str:
    """Одна строка о цели для месячного отчёта. Формулировку выбирает советник, не отчёт."""
    entries = await advice.goal_history(user_id)
    goal = await advice.stored_goal(user_id)
    if not goal and not entries:
        return ""   # у человека не было целей — отчёту нечего о них говорить
    progress = advice.goal_progress(goal, await get_receipt_price_history(user_id))
    return advice.goal_report_line(goal, progress, entries)


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
    total_limit = await budget.get_total_limit(user_id)
    limits = await budget.get_limits(user_id)
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
    # Совместный отчёт — про семью целиком, поэтому и лимит семейный (значение по умолчанию),
    # а не чей-то личный: у каждого своя трата, а бюджет дома один.
    total_limit = await budget.get_total_limit()

    text = "👥 **Совместный отчёт за месяц**\n\n"
    text += f"💸 Всего потрачено: **{format_amount(total_all)}**\n"
    if income_all:
        text += f"💰 Общий доход: {format_amount(income_all)}\n"
    text += f"🎯 Семейный лимит: {format_amount(total_limit)}\n\n"

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


@router.callback_query(F.data == "report_recurring")
async def report_recurring(callback: CallbackQuery):
    """Показывает найденные подписки: их не видно в отчётах по категориям."""
    await callback.answer("Ищу повторы в истории...")
    await _show_recurring(callback.message, callback.from_user.id)


async def _show_recurring(message: Message, user_id: int) -> None:
    text, visible, muted = await recurring_screen(user_id)
    await message.answer(text, reply_markup=get_recurring_kb(visible, muted))


async def _recurring_action(callback: CallbackQuery, action: str) -> None:
    """Отключает или возвращает серию по короткому хешу из кнопки."""
    wanted = callback.data.split(":", 1)[1]
    rows = [dict(row) for row in await get_transactions(user_id=callback.from_user.id, days=200)]
    found = recurring.find_recurring(rows)
    muted = await mutelist.muted_keys(callback.from_user.id, mutelist.RECURRING)
    target = next((item for item in found
                   if mutelist.digest(item["key"]) == wanted), None)
    if target is None:
        await callback.answer("Эта серия уже не находится — открой экран заново", show_alert=True)
        return
    if action == "mute":
        await mutelist.mute(callback.from_user.id, mutelist.RECURRING, target["key"])
        await callback.answer(f"Больше не напоминаю про «{target['name']}»")
    else:
        await mutelist.unmute(callback.from_user.id, mutelist.RECURRING, target["key"])
        await callback.answer(f"Снова напоминаю про «{target['name']}»")
    await _show_recurring(callback.message, callback.from_user.id)


@router.callback_query(F.data.startswith("recurring_mute:"))
async def recurring_mute(callback: CallbackQuery):
    await _recurring_action(callback, "mute")


@router.callback_query(F.data.startswith("recurring_unmute:"))
async def recurring_unmute(callback: CallbackQuery):
    await _recurring_action(callback, "unmute")


@router.callback_query(F.data == "report_digest")
async def report_digest(callback: CallbackQuery):
    """Недельный дайджест: траты недели, подорожания, закупка и списания вместе."""
    await callback.answer("Собираю неделю...")
    await callback.message.answer(await weekly_digest_text(callback.from_user.id),
                                  reply_markup=get_digest_kb())


async def ban_screen(user_id: int) -> tuple[str, list[dict], list[dict], list[dict], int]:
    """(текст, товар в списке «не брать», разрешённые, догадки модели, сколько ждёт пересчёта).

    Возвращаемые берутся без порога привычки: разрешить товар можно и с первого раза —
    кнопкой «Не согласен» на разборе чека, — и отменить это человек должен там же, иначе
    поправка окажется необратимой. Догадки — отдельным списком: они не прячут товар
    из списка покупок, пока человек не подтвердил.
    """
    rows = [dict(row) for row in await get_receipt_verdicts(user_id)]
    allowed = await advice.allowed_keys(user_id)
    confirmed = await advice.confirmed_keys(user_id)
    entries = advice.banned(rows, allowed, confirmed=confirmed)
    returned = advice.corrected_positions(rows, allowed)
    guesses = advice.guesses(rows, allowed, confirmed=confirmed)
    # План пересчёта считается заранее: кнопка пересчёта обещает результат, а не действие.
    plan = advice.recalculate_old_verdicts(rows, allowed, confirmed)
    ready = len(plan["updated"])
    return (advice.ban_text(entries, returned, guesses), entries, returned, guesses, ready)


async def _show_bans(message: Message, user_id: int) -> None:
    text, entries, returned, guesses, ready = await ban_screen(user_id)
    await message.answer(text, reply_markup=get_bans_kb(entries, returned, guesses, ready))


@router.callback_query(F.data == "recalc_verdicts")
async def recalc_verdicts(callback: CallbackQuery):
    """Пересчёт старых разборов по нынешним правилам — только по решению человека.

    Молчаливая миграция переписала бы историю чеков: бот не должен сам менять то, что
    человек уже прочитал в разборе. Поэтому пересчёт — кнопка, и он же говорит, что именно
    изменилось и что из-за этого уходит из «не брать».
    """
    await callback.answer("Пересчитываю по правилам...")
    user_id = callback.from_user.id
    rows = [dict(row) for row in await get_receipt_verdicts(user_id)]
    result = advice.recalculate_old_verdicts(rows, await advice.allowed_keys(user_id),
                                             await advice.confirmed_keys(user_id))
    for entry in result["updated"]:
        await update_receipt_verdict(entry["item_id"], entry["verdict"], entry["advice"], "rule")
    await advice.set_recalc_record(user_id, result)
    await callback.message.answer(advice.recalc_text(result))
    await _show_bans(callback.message, user_id)


@router.callback_query(F.data == "report_bans")
async def report_bans(callback: CallbackQuery):
    """Личный список «не брать»: что бот уже дважды называл необязательным."""
    await callback.answer("Собираю список...")
    await _show_bans(callback.message, callback.from_user.id)


async def _ban_action(callback: CallbackQuery, allowed: bool, confirm: bool = False) -> None:
    """Кнопка адресует товар отпечатком ключа: список пересчитывается на каждом экране.

    «Всё равно напоминать» снимает и подтверждение: человек сказал, что возьмёт товар,
    и прятать его из списка покупок после этого уже нечем.
    """
    wanted = callback.data.split(":", 1)[1]
    user_id = callback.from_user.id
    text, entries, returned, guesses, _ready = await ban_screen(user_id)
    target = next((entry for entry in entries + returned + guesses
                   if mutelist.digest(entry["key"]) == wanted), None)
    if target is None:
        await callback.answer("Товара больше нет в списке — открой его заново", show_alert=True)
        return
    await advice.set_allowed(user_id, target["key"], allowed)
    if allowed:
        await advice.set_confirmed(user_id, target["key"], False)
    if confirm:
        await advice.set_confirmed(user_id, target["key"], True)
    await callback.answer(f"«{target['name']}» — " + (
        "верну в список покупок" if allowed
        else "подтвердил: не брать" if confirm else "убрал из списка покупок"))
    await _show_bans(callback.message, user_id)


@router.callback_query(F.data.startswith("ban_confirm:"))
async def ban_confirm(callback: CallbackQuery):
    """Человек подтверждает догадку модели — только тогда товар становится запретом."""
    await _ban_action(callback, False, confirm=True)


@router.callback_query(F.data.startswith("ban_allow:"))
async def ban_allow(callback: CallbackQuery):
    await _ban_action(callback, True)


@router.callback_query(F.data.startswith("ban_block:"))
async def ban_block(callback: CallbackQuery):
    await _ban_action(callback, False)


async def _goal_screen(user_id: int) -> dict:
    """Экран цели одним расчётом: текст, кнопки-кандидаты, действующая цель и единица счёта.

    Кандидаты считаются из тех же вердиктов и той же истории чеков, что «не брать» и каталог
    цен: цель ставится по тому же товару, а не по похожему названию из своей выборки.
    """
    rows = [dict(row) for row in await get_receipt_verdicts(user_id)]
    # День окончания цели отмечается здесь же: человек может открыть экран раньше, чем
    # сработает планировщик, и без этого итог попал бы в историю с задержкой.
    await advice.close_goal_if_finished(user_id)
    history = await get_receipt_price_history(user_id)
    goal = await advice.stored_goal(user_id)
    entries = await advice.goal_history(user_id)
    unit = await advice.goal_unit(user_id)
    allowed, confirmed = await advice.allowed_keys(user_id), await advice.confirmed_keys(user_id)
    candidates, skipped = advice.goal_candidates(rows, history, allowed,
                                                 confirmed=confirmed, unit=unit)
    # Категорийные кандидаты — рядом с товарными, по той же истории и тем же требованиям.
    # Группа, целиком состоящая из уже предложенных товаров, нового выбора не даёт: у
    # человека и так есть кнопка на каждый товар, и отдельная цель на всю группу была бы
    # тем же обещанием, названным шире.
    product_names = {item["name"] for item in candidates}
    category = [item for item in advice.category_candidates(rows, history, allowed,
                                                            confirmed=confirmed)
                if item["key"] not in {c["key"] for c in candidates}
                and not product_names.issuperset(item["members"])]
    # Переключать единицу есть смысл только если в другой единице тоже есть что предложить:
    # кнопка, ведущая к пустому экрану, — хуже её отсутствия.
    other = advice.GOAL_SUM if unit == advice.GOAL_COUNT else advice.GOAL_COUNT
    other_candidates, _ = advice.goal_candidates(rows, history, allowed, confirmed=confirmed,
                                                unit=other, limit=1)
    can_switch = bool(other_candidates)
    progress = advice.goal_progress(goal, history)
    # Словарь собирается здесь целиком, а не дописывается по ходу: по нему сразу видно,
    # что экран может вернуть три разных состояния — без цели, с активной, с закончившейся.
    screen = {"goal": goal, "unit": unit, "can_switch": can_switch,
              "candidates": candidates + category, "skipped": skipped}
    text = advice.goal_text(goal, progress, entries=entries)
    if not text:
        # История на экране без активной цели: «сколько сдержано» — ответ на вопрос, который
        # иначе негде задать: последнюю цель человек уже убрал, и от неё не осталось следа.
        parts = [advice.goal_proposals_text(screen["candidates"], unit, skipped=skipped)]
        if entries:
            parts.append(advice.goal_history_text(entries))
        screen["text"] = "\n\n".join(parts)
        return screen
    # Итог и следующее предложение — на одном экране: пока цель закончилась, но не убрана,
    # предлагать было нечего, и человек должен был сначала снять её вручную. Кнопки при этом
    # показываются только там, где предложение видно в тексте, — иначе они ведут к тому,
    # чего на экране нет.
    if progress and progress["finished"]:
        text = f"{text}\n\n{advice.goal_followup_text(screen['candidates'])}"
    else:
        # Активная цель: ни кандидатов, ни переключателя единицы — менять пока нечего, а кнопка
        # без видимого следствия хуже её отсутствия. Своя единица цели при этом сохраняется.
        screen["candidates"] = []
        screen["can_switch"] = False
    screen["text"] = text
    return screen


async def _show_goal(message: Message, user_id: int) -> None:
    screen = await _goal_screen(user_id)
    await message.answer(screen["text"], reply_markup=get_goal_kb(
        screen["candidates"], bool(screen["goal"]), unit=screen["unit"],
        can_switch=screen["can_switch"]))


@router.callback_query(F.data == "report_goal")
async def report_goal(callback: CallbackQuery):
    """Цель на месяц: одна привычка, один измеримый шаг и проверка в конце месяца."""
    await callback.answer("Считаю, что предложить...")
    await _show_goal(callback.message, callback.from_user.id)


@router.callback_query(F.data.startswith("goal_take:"))
async def goal_take(callback: CallbackQuery):
    """Человек берёт цель — бот только предлагает, обещание даёт он сам."""
    wanted = callback.data.split(":", 1)[1]
    user_id = callback.from_user.id
    screen = await _goal_screen(user_id)
    target = next((item for item in screen["candidates"]
                   if mutelist.digest(item["key"]) == wanted), None)
    if target is None:
        await callback.answer("Предложение устарело — открой цель заново", show_alert=True)
        return
    await advice.set_goal(user_id, target)
    # Подтверждение говорит в той же единице, в которой цель и будет считаться.
    if target.get("unit") == advice.GOAL_SUM:
        await callback.answer(f"Цель: не больше {int(target['limit'])} ₽ в месяц")
    else:
        await callback.answer(f"Цель: не чаще {target['target']} раз в месяц")
    await _show_goal(callback.message, user_id)


@router.callback_query(F.data.startswith("goal_unit:"))
async def goal_unit_switch(callback: CallbackQuery):
    """В какой единице считать шаг — выбор человека, а не свойство товара.

    Переключение меняет только то, как бот предлагает и считает шаги. Уже поставленная цель
    продолжает считаться в своей единице: обещание давалось в ней, и переписывать его задним
    числом было бы подменой.
    """
    wanted = callback.data.split(":", 1)[1]
    if wanted not in advice.GOAL_UNITS:
        await callback.answer("Не понял единицу счёта", show_alert=True)
        return
    await advice.set_goal_unit(callback.from_user.id, wanted)
    await callback.answer("Считаю в деньгах" if wanted == advice.GOAL_SUM else "Считаю в разах")
    await _show_goal(callback.message, callback.from_user.id)


@router.callback_query(F.data == "goal_drop")
async def goal_drop(callback: CallbackQuery):
    """Убрать цель: это ориентир, а не обязательство перед ботом."""
    await advice.set_goal(callback.from_user.id, None)
    await callback.answer("Цель убрана")
    await _show_goal(callback.message, callback.from_user.id)


@router.callback_query(F.data == "report_waste")
async def report_waste(callback: CallbackQuery):
    """Что советовал не брать: сумма по сохранённым вердиктам разбора корзины."""
    await callback.answer("Считаю по разборам чеков...")
    rows = [dict(row) for row in await get_receipt_verdicts(callback.from_user.id)]
    days = advice.DEFAULT_DAYS
    allowed = await advice.allowed_keys(callback.from_user.id)
    # Поправленные человеком позиции в сумму необязательного не идут: это его решение,
    # а не вывод разбора. Строка ниже говорит, сколько именно убрано, — иначе в отчёте
    # было бы непонятно, почему сумма меньше, чем в прошлый раз.
    summary = advice.waste_summary(rows, days=days, allowed=allowed)
    corrected = advice.corrected_positions(rows, allowed)
    trend = advice.waste_trend(rows)
    # Товары, которые человек разрешил брать вопреки вердиктам, в потолок экономии не идут:
    # он уже сказал, что возьмёт их, и записывать их в «освободится» было бы упрёком.
    # Лимит и доход берём из бюджета, а не из чека: советник переводит их в доли.
    saving = advice.saving_forecast(rows, allowed, days=days,
                                    income=await get_month_income(callback.from_user.id),
                                    limit=await budget.get_total_limit(callback.from_user.id))
    # Картинку рисуем только когда есть хотя бы две недели разборов: одна точка — не динамика,
    # и текст в этом случае остаётся без картинки.
    await send_chart(callback.message, await charts.waste_trend_card(trend),
                     "📉 Необязательные покупки по неделям")
    # История покупок — та же, что у каталога цен, и это важно: частота товара считается
    # по всем его покупкам, а не только по тем, что получили вердикт, — иначе товар, который
    # человек перестал брать или стал брать чаще, из замера выпадал бы.
    history = await get_receipt_price_history(callback.from_user.id)
    effects = advice.advice_effects(rows, history)
    # Пересчёт старых разборов двигает ту же долю, что и покупки: без оговорки отчёт
    # выглядел бы как изменение привычек, которого не было.
    await callback.message.answer(
        advice.waste_text(summary, days, trend=trend, saving=saving, effects=effects,
                          corrected=corrected, recalc=await advice.last_recalc(
                              callback.from_user.id)),
        reply_markup=get_digest_kb())


@router.callback_query(F.data == "report_inflation")
async def report_inflation(callback: CallbackQuery):
    """Личная инфляция: те же товары, но по нынешним ценам."""
    await callback.answer("Считаю по твоим чекам...")
    history = await get_receipt_price_history(callback.from_user.id)
    await callback.message.answer(inflation_text(personal_inflation(history)),
                                  reply_markup=get_digest_kb())


@router.callback_query(F.data == "report_prices")
async def report_prices(callback: CallbackQuery):
    """Личный каталог цен: обычная цена товара, динамика и где было дешевле."""
    await callback.answer("Смотрю историю цен...")
    history = await get_receipt_price_history(callback.from_user.id)
    await callback.message.answer(catalog_text(history), reply_markup=get_report_kb())


async def shopping_screen(user_id: int) -> tuple[str, list[dict], list[dict]]:
    """(текст, товары к сроку, отключённые) по истории чеков пользователя.

    Отметка «уже купил» здесь не удаляет ничего: она лишь скрывает товар до следующего
    обычного срока, а отключённые товары остаются видимыми строкой — чтобы их можно было
    вернуть назад, как это сделано с подписками.
    """
    history = await get_receipt_price_history(user_id)
    muted = await mutelist.muted_keys(user_id, mutelist.SHOPPING)
    visible, marked = hide_bought(due_items(history), await bought_marks(user_id))
    # Личное «не брать» убирает товар из предложения: бот не советует брать то, что человек
    # сам же признал лишним в разборе чеков.
    visible, blocked = hide_blocked(visible, await advice.blocked_keys(user_id))
    shown, muted_items = mutelist.split(visible, muted)
    return (shopping_text(shown, muted=muted_items, marked=marked, blocked=blocked),
            shown, muted_items)


async def _show_shopping(message: Message, user_id: int) -> None:
    text, visible, muted = await shopping_screen(user_id)
    await message.answer(text, reply_markup=get_shopping_kb(visible, muted))


@router.callback_query(F.data == "report_shopping")
async def report_shopping(callback: CallbackQuery):
    """Список покупок: что пора взять, судя по ритму чеков пользователя."""
    await callback.answer("Смотрю ритм покупок...")
    await _show_shopping(callback.message, callback.from_user.id)


async def _shopping_action(callback: CallbackQuery, action: str) -> None:
    """Кнопка адресует товар коротким отпечатком ключа, а не позицией в списке."""
    wanted = callback.data.split(":", 1)[1]
    user_id = callback.from_user.id
    history = await get_receipt_price_history(user_id)
    target = next((item for item in due_items(history)
                   if mutelist.digest(item["key"]) == wanted), None)
    if target is None:
        await callback.answer("Этого товара больше нет в списке — открой его заново", show_alert=True)
        return
    if action == "bought":
        await mark_bought(user_id, target["key"])
        await callback.answer(f"Отметил: «{target['name']}» — напомню через обычный срок")
    elif action == "mute":
        await mutelist.mute(user_id, mutelist.SHOPPING, target["key"])
        await callback.answer(f"Больше не напоминаю про «{target['name']}»")
    else:
        await mutelist.unmute(user_id, mutelist.SHOPPING, target["key"])
        await callback.answer(f"Снова напоминаю про «{target['name']}»")
    await _show_shopping(callback.message, user_id)


@router.callback_query(F.data.startswith("shopping_bought:"))
async def shopping_bought(callback: CallbackQuery):
    await _shopping_action(callback, "bought")


@router.callback_query(F.data.startswith("shopping_mute:"))
async def shopping_mute(callback: CallbackQuery):
    await _shopping_action(callback, "mute")


@router.callback_query(F.data.startswith("shopping_unmute:"))
async def shopping_unmute(callback: CallbackQuery):
    await _shopping_action(callback, "unmute")


@router.callback_query(F.data == "report_advice")
async def ai_advice(callback: CallbackQuery):
    await callback.answer()
    await send_advice(callback.message, callback.from_user.id)


@router.message(Command("price"))
async def cmd_price(message: Message):
    """«/price молоко» — карточка товара: обычная цена, история покупок и где было дешевле."""
    query = (message.text or "").partition(" ")[2].strip()
    history = await get_receipt_price_history(message.from_user.id)
    if not query:
        known = product_groups(history)
        hint = "🔍 **Карточка товара**\n\nНапиши название: `/price молоко`."
        if known:
            hint += ("\n\nВ чеках уже есть: "
                     + ", ".join(md_safe(group["name"]) for group in known[:5]))
        await message.answer(hint)
        return
    matches = search_products(history, query)
    if not matches:
        await message.answer(
            f"🔍 «{md_safe(query)}» в твоих чеках не нашлось.\n"
            "Я знаю только то, что есть в позициях чеков, — историю цен бот не выдумывает.")
        return
    if len(matches) > 1:
        # Несколько товаров — не угадываем за человека: показываем варианты и ждём уточнения.
        lines = [f"🔍 По запросу «{md_safe(query)}» подходит несколько товаров:", ""]
        for item in matches:
            lines.append(f"• {md_safe(item['name'])} — обычно "
                         f"{format_amount(item['usual'])} ({item['count']} покупок)")
        lines.append("\nУточни название — покажу карточку товара.")
        await message.answer("\n".join(lines))
        return
    group = matches[0]
    due = next((item for item in due_items(history) if item["key"] == group["key"]), None)
    # Картинка — только когда есть что рисовать: по двум и более покупкам.
    points = [(moment, entry["price"], entry["store"])
              for moment, entry in ((parse_date(entry["date"]), entry) for entry in group["entries"])
              if moment]
    image = await charts.price_card(group["name"], points, group["usual"], group["cheapest"],
                                   group["cheapest_store"])
    if image:
        await message.answer_photo(BufferedInputFile(image, filename="price.png"),
                                   caption=f"📈 **История цены:** {md_safe(group['name'])}")
    await message.answer(card_text(group, due=due), reply_markup=get_digest_kb())


@router.message(Command("report"))
async def cmd_report(message: Message):
    await send_month_report(message, message.from_user.id)
