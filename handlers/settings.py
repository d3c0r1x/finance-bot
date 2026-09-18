"""Настройки: статус сервисов, бюджет и лимиты (правятся прямо в боте)."""
from aiogram import F, Router
from aiogram.exceptions import TelegramAPIError
from aiogram.fsm.context import FSMContext
from aiogram.fsm.state import State, StatesGroup
from aiogram.types import CallbackQuery, Message

from ai.llm import resolve_model, suggest_budget
from database.models import CATEGORIES
from keyboards.budget_kb import get_budget_amount_kb, get_budget_kb, get_budget_proposal_kb
from keyboards.main_menu_kb import get_health_kb, get_settings_kb
from services import budget
from services.forecast import set_weekly_food_limit, weekly_food_limit
from services.health import check_ollama, check_tesseract, check_vision
from utils.filters import AccessFilter
from utils.formatting import format_amount, get_category_emoji, md_code

router = Router()
router.message.filter(AccessFilter())
router.callback_query.filter(AccessFilter())

VERSION = "1.2.0"


class BudgetStates(StatesGroup):
    waiting_for_limit = State()


# ─── Экран настроек ──────────────────────────────────────────────────────

async def build_settings_text(user_id: int | None = None) -> str:
    limits = await budget.get_limits(user_id)
    total_limit = await budget.get_total_limit(user_id)
    own = await budget.own_limits(user_id)

    lines = ["⚙️ **Настройки**", "",
             f"🎯 **Бюджет на месяц** ({'свой' if own else 'семейный'}):",
             f"• Всего: {format_amount(total_limit)}"]
    for category in CATEGORIES:
        limit = limits.get(category, 0)
        if limit and category != "долги":
            lines.append(f"• {get_category_emoji(category)} {category}: {format_amount(limit)}")
    food_week = await weekly_food_limit(user_id) if user_id else 0
    if food_week:
        lines.append(f"🍎 Продукты в неделю: {format_amount(food_week)}")
    lines += ["",
              "Лимиты меняются кнопкой ниже — бот сразу начнёт считать по новым."]
    if not own and user_id:
        lines.append("Пока это семейные лимиты: как только поправишь свой, они станут личными.")
    lines += ["",
              "💡 Напиши трату словами или пришли фото чека — остальное сделаю сам.",
              f"Версия бота: {VERSION}"]
    return "\n".join(lines)


async def send_settings(message: Message) -> None:
    user_id = message.from_user.id if message.from_user else None
    await message.answer(await build_settings_text(user_id), reply_markup=get_settings_kb())


@router.message(F.text == "⚙️ Настройки")
async def settings_menu(message: Message):
    await send_settings(message)


@router.callback_query(F.data == "menu_settings")
async def settings_callback(callback: CallbackQuery):
    await callback.message.answer(await build_settings_text(callback.from_user.id),
                                  reply_markup=get_settings_kb())
    await callback.answer()


# ─── Бюджет ──────────────────────────────────────────────────────────────

async def build_budget_text(user_id: int | None = None) -> str:
    limits = await budget.get_limits(user_id)
    total_limit = await budget.get_total_limit(user_id)
    limits_sum = sum(value for category, value in limits.items() if category != "долги")
    own = await budget.own_limits(user_id)

    lines = ["🎯 **Бюджет на месяц**", "",
             f"Лимит всего: **{format_amount(total_limit)}**",
             f"Сумма лимитов по категориям: {format_amount(limits_sum)}"]
    if limits_sum > total_limit:
        lines.append("⚠️ Категории в сумме больше общего лимита — поправь что-нибудь.")
    if not own and user_id:
        lines += ["", "Это семейные лимиты для всех. Меняй смело — свои значения не тронутся "
                      "у остальных."]
    food_week = await weekly_food_limit(user_id) if user_id else 0
    if food_week:
        lines.append(f"🍎 Продукты в неделю: {format_amount(food_week)}")
    lines += ["", "Нажми на категорию, чтобы изменить лимит (0 — без лимита).",
              "Недельный лимит на продукты — отдельный: он про скользящие семь дней, "
              "а не про месяц."]
    return "\n".join(lines)


async def send_budget(target_message: Message, edit: bool = False,
                      user_id: int | None = None) -> None:
    text = await build_budget_text(user_id)
    keyboard = get_budget_kb(await budget.get_limits(user_id),
                             await budget.get_total_limit(user_id), CATEGORIES,
                             food_week=await weekly_food_limit(user_id) if user_id else 0)
    if edit:
        try:
            await target_message.edit_text(text, reply_markup=keyboard)
            return
        except TelegramAPIError:
            pass
    await target_message.answer(text, reply_markup=keyboard)


@router.callback_query(F.data == "budget_menu")
async def budget_menu(callback: CallbackQuery):
    await send_budget(callback.message, edit=True, user_id=callback.from_user.id)
    await callback.answer()


@router.callback_query(F.data.startswith("budget_set:"))
async def budget_choose_category(callback: CallbackQuery, state: FSMContext):
    target = callback.data.split(":", 1)[1]
    limits = await budget.get_limits(callback.from_user.id)
    total_limit = await budget.get_total_limit(callback.from_user.id)

    await state.set_state(BudgetStates.waiting_for_limit)
    await state.update_data(budget_target=target)

    if target == "total":
        title = "🎯 **Лимит на месяц целиком**"
        current = format_amount(total_limit)
    elif target == "food_week":
        title = "🍎 **Недельный лимит на продукты**"
        limit = await weekly_food_limit(callback.from_user.id)
        current = format_amount(limit) if limit else "не задан"
        await callback.message.edit_text(
            f"{title}\n\nСейчас: {current}\n\n"
            "Сколько можно тратить на еду за семь дней? Напиши число (например: 4000) — "
            "или выбери из быстрых. Продукты считаю по скользящим семи дням, а не по "
            "календарной неделе.",
            reply_markup=get_budget_amount_kb(target),
        )
        await callback.answer()
        return
    else:
        title = f"{get_category_emoji(target)} **Лимит: {target}**"
        current = format_amount(limits.get(target, 0)) if limits.get(target) else "не задан"

    await callback.message.edit_text(
        f"{title}\n\nСейчас: {current}\n\n"
        "Напиши новое значение числом (например: 5000) — или выбери из быстрых.",
        reply_markup=get_budget_amount_kb(target),
    )
    await callback.answer()


@router.callback_query(F.data.startswith("budget_value:"))
async def budget_quick_value(callback: CallbackQuery, state: FSMContext):
    value = float(callback.data.split(":", 1)[1])
    await callback.answer(f"Лимит: {format_amount(value)}" if value else "Лимит отключён")
    await _apply_limit(callback.from_user.id, callback.message, state, value)


@router.message(BudgetStates.waiting_for_limit, F.text)
async def budget_custom_value(message: Message, state: FSMContext):
    raw = (message.text or "").replace(" ", "").replace(",", ".").replace("₽", "").strip()
    try:
        value = float(raw)
        if value < 0:
            raise ValueError
    except ValueError:
        await message.answer("⚠️ Нужно число, например: 5000 (0 — без лимита)")
        return
    await _apply_limit(message.from_user.id if message.from_user else None, message, state, value)


async def _apply_limit(user_id: int | None, message: Message, state: FSMContext,
                       value: float) -> None:
    """Сохраняет новый лимит пользователя и показывает обновлённый бюджет.

    `user_id` передаётся явно, а не берётся из `message.from_user`: при нажатии кнопки
    сообщение принадлежит боту, и владельцем лимита оказался бы бот (`None` — только
    для правок из панели, которые меняют семейное значение).
    """
    data = await state.get_data()
    target = data.get("budget_target")
    await state.clear()
    if not target:
        return

    if target == "food_week":
        # Лимит личный: продукты — самая частая трата, и у каждого своя. В панель он не едет,
        # потому что панель правит семейные месячные лимиты.
        await set_weekly_food_limit(user_id or 0, value)
        if value:
            await message.answer(f"✅ Недельный лимит на продукты: **{format_amount(value)}**\n"
                                 "Скажу, когда останется 10% и если лимит будет превышен.")
        else:
            await message.answer("✅ Недельный лимит на продукты отключён.")
        await send_budget(message, user_id=user_id)
        return
    if target == "total":
        await budget.set_total_limit(value, user_id)
        label = "Лимит на месяц"
    else:
        await budget.set_limit(target, value, user_id)
        label = f"Лимит «{target}»"

    if value:
        await message.answer(f"✅ {label}: **{format_amount(value)}**\n"
                             "Это твой личный лимит — у остальных он не меняется.")
    else:
        await message.answer(f"✅ {label} отключён — траты этой категории не ограничиваем.")
    await send_budget(message, user_id=user_id)


@router.callback_query(F.data == "budget_reset")
async def budget_reset(callback: CallbackQuery):
    """Снимает личные лимиты: пользователь возвращается к семейным."""
    await budget.reset_limits(callback.from_user.id)
    await callback.answer("Вернул семейные лимиты")
    await send_budget(callback.message, edit=True, user_id=callback.from_user.id)


@router.callback_query(F.data == "budget_ai")
async def budget_ai(callback: CallbackQuery, state: FSMContext):
    await callback.answer("Считаю по истории трат...")
    status = await callback.message.answer("🤖 Смотрю последние месяцы и считаю бюджет...")

    context, days = await budget.history_summary(user_id=callback.from_user.id, months=2)
    if not budget.history_enough(days) or not context:
        try:
            await status.delete()
        except Exception:
            pass
        await callback.message.answer(budget.empty_budget_hint(days))
        return

    proposal = await suggest_budget(context)
    try:
        await status.delete()
    except Exception:
        pass

    if not proposal:
        await callback.message.answer("🤖 Не получилось посчитать. Проверь, что ИИ-модель работает "
                                      "(⚙️ Настройки → 🩺 Статус сервисов).")
        return

    await state.update_data(budget_proposal=proposal)
    limits = proposal["limits"]
    current_limits = await budget.get_limits(callback.from_user.id)
    lines = ["🤖 **Предлагаю такой бюджет:**", ""]
    for category, value in sorted(limits.items(), key=lambda item: -item[1]):
        current = current_limits.get(category, 0)
        marker = "" if not current or abs(current - value) < 1 else f"  (сейчас {format_amount(current)})"
        lines.append(f"• {get_category_emoji(category)} {category}: **{format_amount(value)}**{marker}")
    lines += ["", f"🎯 Лимит на месяц: **{format_amount(proposal['total'])}**"]
    if proposal.get("comment"):
        lines += ["", f"_{proposal['comment']}_"]
    lines += ["", "Применить эти лимиты?"]

    await callback.message.answer("\n".join(lines), reply_markup=get_budget_proposal_kb())


@router.callback_query(F.data == "budget_apply")
async def budget_apply(callback: CallbackQuery, state: FSMContext):
    data = await state.get_data()
    proposal = data.get("budget_proposal")
    if not proposal:
        await callback.answer("Предложение устарело, попроси новое", show_alert=True)
        return
    await budget.apply_limits(proposal["limits"], proposal.get("total"), callback.from_user.id)
    await state.update_data(budget_proposal=None)
    await callback.answer("Применил")
    await send_budget(callback.message, user_id=callback.from_user.id)


# ─── Статус сервисов ─────────────────────────────────────────────────────

@router.callback_query(F.data == "health_check")
async def health_check(callback: CallbackQuery):
    ollama_ok, ollama_msg = await check_ollama()
    vision_ok, vision_msg = await check_vision()
    tess_ok, tess_msg = check_tesseract()
    model = await resolve_model(force_refresh=True)

    lines = [
        "🩺 **Статус сервисов**",
        "",
        f"{'✅' if ollama_ok else '❌'} **ИИ-модель (Ollama):** {ollama_msg}",
        f"{'✅' if vision_ok else '❌'} **Зрение — чтение чеков:** {vision_msg}",
        f"{'✅' if tess_ok else '❌'} **Tesseract (проверка цифр):** {tess_msg}",
        "",
        f"🧠 Модель разбора трат: {md_code(model)}",
        "",
        "🖼 Чек читается двумя способами: модель зрения понимает таблицу и названия, "
        "Tesseract проверяет арифметику. Если модель зрения недоступна, чек читает Tesseract.",
    ]
    if not ollama_ok:
        lines.append("➡️ Траты будут разбираться простыми правилами, пока Ollama недоступен.")
    elif not vision_ok:
        lines.append("➡️ Чеки читает Tesseract — точно, но без разбора позиций нейросетью.")
    elif not tess_ok:
        lines.append("➡️ Чеки читаются моделью зрения, но без арифметической проверки.")

    await callback.message.edit_text("\n".join(lines), reply_markup=get_health_kb())
    await callback.answer("Проверил")
