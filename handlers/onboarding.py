"""Приветственная настройка: знакомство, имя, доход и бюджет за минуту.

Новый пользователь попадает сюда вместо пустого главного экрана: по шагам он узнаёт,
что бот умеет, как к нему обращаться, какой доход ожидает и какой поставить бюджет.
Пройти настройку можно заново из ⚙️ Настройки — данные при этом не трогаются.

Роутер подключается до main_menu: свободный ввод траты там ловит любые сообщения,
поэтому состояния настройки должны перехватываться раньше.
"""
from aiogram import F, Router
from aiogram.fsm.context import FSMContext
from aiogram.fsm.state import State, StatesGroup
from aiogram.types import CallbackQuery, InlineKeyboardButton, InlineKeyboardMarkup, Message

from keyboards.main_menu_kb import get_onboarding_kb
from services import budget, profile
from utils.filters import AccessFilter
from utils.formatting import format_amount, get_category_emoji

router = Router()
router.message.filter(AccessFilter())
router.callback_query.filter(AccessFilter())

LAST_STEP = 4          # шаги 0..4, шаг 5 — финал
SKIP_HINT = "\n\n_Настройку можно пропустить — бот сразу готов к работе._"


class OnboardingStates(StatesGroup):
    waiting_for_name = State()
    waiting_for_income = State()


async def _step_text(step: int, user_id: int) -> tuple[str, bool]:
    """Текст шага. Второе значение — рисовать ли стандартные кнопки навигации."""
    name = await profile.display_name(user_id)
    if step <= 0:
        return (
            f"👋 Привет, **{name}**!\n\n"
            "Я помогу понять, куда уходят деньги, и держать бюджет без таблиц и Excel.\n\n"
            "• ✍️ **Траты** — просто напиши «Пятёрочка 3450» или «Заправка 2000»\n"
            "• 📷 **Чек** — пришли фото: распознаю магазин, сумму и все позиции\n"
            "• 📈 **Диаграммы** — куда ушёл месяц, видно с одного взгляда\n"
            "• 💳 **Кредитки** — остатки, платежи и срок погашения\n\n"
            "Давай настрою под тебя — это займёт меньше минуты." + SKIP_HINT, True)
    if step == 1:
        return (
            "❓ **Как этим пользоваться**\n\n"
            "1️⃣ **Траты** — пиши словами, я сам определю сумму и категорию.\n"
            "2️⃣ **Чеки** — фото целиком и ровно: нейросеть прочитает позиции, "
            "а Tesseract проверит цифры.\n"
            "3️⃣ **Бюджет** — лимиты по категориям правятся в ⚙️ Настройки, "
            "а по накопленной истории ИИ предложит бюджет сам.\n"
            "4️⃣ **Продуктовые чеки** — разберу корзину: что взять, а от чего отказаться.\n\n"
            "Главное правило: записывай траты сразу. Через месяц данных хватит, "
            "чтобы увидеть утечки." + SKIP_HINT, True)
    if step == 2:
        return (
            f"🙋 **Как тебя называть?**\n\n"
            f"Сейчас в отчётах ты — «{name}». Напиши имя или короткое обращение "
            "(например: Никита) — или жми «Продолжить», чтобы оставить как есть.", False)
    if step == 3:
        return (
            "💰 **Ожидаемый доход в месяц**\n\n"
            "Напиши сумму числом (например: 150000) — по ней я посчитаю, "
            "сколько безопасно тратить в день и какой поставить бюджет.\n\n"
            "Это необязательно: можно пропустить и задать позже." + SKIP_HINT, False)
    # последний шаг: бюджет по доходу
    income = await profile.planned_income(user_id)
    if not income:
        return (
            "🎯 **Бюджет**\n\n"
            "Доход не указан, поэтому бюджет предложить не могу — лимиты можно "
            "задать вручную в ⚙️ Настройки → 🎯 Бюджет (0 — без лимита).\n\n"
            "Жми «Продолжить» — и я покажу главный экран." + SKIP_HINT, True)
    limits, total = budget.proposal_for_income(income, await budget.get_limits(user_id))
    lines = ["🎯 **Предлагаю такой бюджет** (70% дохода, остальное — накопления):", ""]
    for category, value in sorted(limits.items(), key=lambda pair: -pair[1]):
        lines.append(f"• {get_category_emoji(category)} {category}: {format_amount(value)}")
    lines += ["", f"Лимит на месяц: **{format_amount(total)}**",
              "", "Применить его или оставить текущие лимиты?"]
    return "\n".join(lines), True


async def send_step(message: Message, step: int, user_id: int, state: FSMContext) -> None:
    """Показывает шаг настройки (текст + кнопки навигации или поля ввода)."""
    text, with_nav = await _step_text(step, user_id)
    if with_nav:
        await state.set_state(None)
        await message.answer(text, reply_markup=get_onboarding_kb(step, _step_title(step)))
        return
    keyboard = _input_kb(step)
    await message.answer(text, reply_markup=keyboard)
    await state.set_state(OnboardingStates.waiting_for_name if step == 2
                          else OnboardingStates.waiting_for_income)


def _step_title(step: int) -> str:
    titles = {0: "Приветствие", 1: "Как пользоваться", 3: "Доход", 4: "Бюджет"}
    return titles.get(step, "Настройка")


def _input_kb(step: int) -> InlineKeyboardMarkup:
    """Кнопка «пропустить» для шагов, где ждём текст от пользователя."""
    return InlineKeyboardMarkup(inline_keyboard=[
        [InlineKeyboardButton(text="Пропустить шаг", callback_data=f"onb:{step + 1}")],
    ])


async def start(message: Message, user_id: int, state: FSMContext) -> None:
    """Запускает настройку с первого шага."""
    await state.clear()
    await send_step(message, 0, user_id, state)


# ─── Кнопки настройки ────────────────────────────────────────────────────

@router.callback_query(F.data.startswith("onb:"))
async def onboarding_nav(callback: CallbackQuery, state: FSMContext):
    action = callback.data.split(":", 1)[1]
    user_id = callback.from_user.id

    if action == "noop":
        await callback.answer()
        return
    if action == "skip":
        await finish(callback.message, user_id, state, skipped=True)
        await callback.answer("Настройку можно пройти позже в ⚙️ Настройки")
        return
    if action == "budget_apply":
        income = await profile.planned_income(user_id)
        if income:
            limits, total = budget.proposal_for_income(income, await budget.get_limits(user_id))
            await budget.apply_limits(limits, total, user_id)
        await callback.answer("Бюджет применён")
        await finish(callback.message, user_id, state)
        return

    try:
        step = int(action)
    except ValueError:
        await callback.answer()
        return
    if step > LAST_STEP:
        await finish(callback.message, user_id, state)
        await callback.answer("Готово")
        return

    text, with_nav = await _step_text(step, user_id)
    await callback.answer()
    if with_nav:
        rows = []
        if step == LAST_STEP:
            rows.append([InlineKeyboardButton(text="✅ Применить бюджет",
                                              callback_data="onb:budget_apply")])
        rows.append([InlineKeyboardButton(text="Продолжить ➡️", callback_data=f"onb:{step + 1}")])
        rows.append([InlineKeyboardButton(text="Пропустить настройку", callback_data="onb:skip")])
        await state.set_state(None)
        await callback.message.edit_text(text, reply_markup=InlineKeyboardMarkup(inline_keyboard=rows))
        return
    await state.set_state(OnboardingStates.waiting_for_name if step == 2
                          else OnboardingStates.waiting_for_income)
    await callback.message.edit_text(text, reply_markup=_input_kb(step))


# ─── Ввод имени и дохода ─────────────────────────────────────────────────

@router.message(OnboardingStates.waiting_for_name, F.text)
async def step_name(message: Message, state: FSMContext):
    name = await profile.set_name(message.from_user.id, message.text or "")
    await state.set_state(None)
    if not name:
        await message.answer("Не разобрал имя — оставлю прежнее.")
    else:
        await message.answer(f"Приятно познакомиться, **{name}**! 👋")
    await send_step(message, 3, message.from_user.id, state)


@router.message(OnboardingStates.waiting_for_income, F.text)
async def step_income(message: Message, state: FSMContext):
    raw = (message.text or "").replace(" ", "").replace(",", ".").replace("₽", "").strip()
    try:
        income = float(raw)
        if income <= 0:
            raise ValueError
    except ValueError:
        await message.answer("⚠️ Нужно число, например: 150000. Или нажми «Пропустить шаг».")
        return
    await profile.set_planned_income(message.from_user.id, income)
    await state.set_state(None)
    await message.answer(f"✅ Доход: **{format_amount(income)}** в месяц.\n"
                         "Буду считать, сколько безопасно тратить в день.")
    await send_step(message, 4, message.from_user.id, state)


# ─── Финал ───────────────────────────────────────────────────────────────

async def finish(message: Message, user_id: int, state: FSMContext, skipped: bool = False) -> None:
    """Закрывает настройку и показывает главный экран с меню."""
    from handlers.main_menu import build_dashboard
    from keyboards.main_menu_kb import get_main_menu_kb

    await profile.mark_onboarded(user_id)
    await state.clear()
    name = await profile.display_name(user_id)
    limits = await budget.get_limits(user_id)
    lines = ["🏁 **Готово, всё настроено.**", ""]
    if skipped:
        lines.append("Настройку можно пройти позже: ⚙️ Настройки → 🚀 Пройти настройку заново.")
    else:
        with_limits = ", ".join(f"{category}: {format_amount(value)}"
                                for category, value in limits.items() if value and category != "долги")
        lines.append(f"🎯 Лимиты: {with_limits}" if with_limits else "🎯 Лимиты можно задать в настройках.")
    lines += ["", "Попробуй записать первую трату — просто напиши, например, "
                  "«кофе 250» или пришли фото чека."]
    await message.answer("\n".join(lines), reply_markup=get_main_menu_kb())
    await message.answer(await build_dashboard(name, user_id))
