"""Точка входа: инициализация БД, регистрация роутеров, планировщик, polling."""
import asyncio
import os
import sys

# Чтобы эмодзи в консоли Windows (cp1251) не роняли бот
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

from aiogram import Bot, Dispatcher
from aiogram.client.default import DefaultBotProperties
from aiogram.client.session.aiohttp import AiohttpSession
from aiogram.enums import ParseMode
from aiogram.exceptions import TelegramAPIError, TelegramNetworkError
from aiogram.types import BotCommand, ErrorEvent, MenuButtonCommands

from ai.llm import list_installed_models, resolve_model
from ai.vision import resolve_vision_model
from config import (BOT_TOKEN, DATA_DIR, HIDE_MENU_BUTTON, OLLAMA_MODEL,
                    RECEIPTS_DIR, USERS, VISION_ENABLED, VISION_MODEL)
from database.db import init_db
from services.scheduler import register_scheduler
from handlers import main_menu, expenses, reports, debts, settings, onboarding, bank_import  # noqa: F401

class RetryingSession(AiohttpSession):
    """Повторяет запросы к Telegram при сетевых сбоях.

    Через системный прокси/VPN соединение иногда отваливается
    (WinError 121 «Превышен таймаут семафора») — без повтора ответ теряется.
    """

    ATTEMPTS = 3

    async def make_request(self, bot, method, timeout=None):
        for attempt in range(self.ATTEMPTS):
            try:
                return await super().make_request(bot, method, timeout)
            except TelegramNetworkError:
                if attempt == self.ATTEMPTS - 1:
                    raise
                await asyncio.sleep(1.5 * (attempt + 1))


BOT_COMMANDS = [
    BotCommand(command="start", description="🏠 Главное меню"),
    BotCommand(command="menu", description="🧭 Кнопки и быстрые действия"),
    BotCommand(command="report", description="📊 Отчёт за месяц"),
    BotCommand(command="help", description="❓ Как пользоваться"),
]


async def setup_telegram_ui(bot: Bot) -> None:
    """Убирает «плашку» веб-приложения старого бота (Mini App «Умный Шоппер»)
    и настраивает список команд.

    Кнопку, заданную в @BotFather, можно перекрыть только персонально для чата,
    поэтому обходим всех пользователей из .env.
    """
    for attempt in range(3):
        try:
            await bot.set_my_commands(BOT_COMMANDS)
            break
        except TelegramAPIError as error:
            if attempt == 2:
                print(f"⚠️ Не удалось обновить список команд: {error}")
            else:
                await asyncio.sleep(3)  # сеть/прокси могли не подняться мгновенно

    if not HIDE_MENU_BUTTON:
        return

    hidden = 0
    for user_id in USERS:
        try:
            await bot.set_chat_menu_button(chat_id=user_id, menu_button=MenuButtonCommands())
            hidden += 1
        except TelegramAPIError:
            # пользователь ещё не писал боту — применим настройку при его /start
            continue
    if hidden:
        print(f"🧹 Плашка старого бота убрана в {hidden} чат(ах) — вместо неё меню команд Telegram")


async def log_ai_status() -> None:
    """Пишет в консоль, какие ИИ-модели реально используются: текст и зрение."""
    models = await list_installed_models()
    if not models:
        print(f"⚠️ Ollama не отвечает. Траты будут разбираться правилами. "
              f"Запусти Ollama и скачай модель: ollama pull {OLLAMA_MODEL}")
        return
    model = await resolve_model(force_refresh=True)
    if model == OLLAMA_MODEL or model.split(":")[0] == OLLAMA_MODEL.split(":")[0]:
        print(f"🧠 ИИ-модель: {model}")
    else:
        print(f"🧠 ИИ-модель: {model} (модель {OLLAMA_MODEL} не найдена. "
              f"Для лучшего разбора трат: ollama pull {OLLAMA_MODEL})")

    # Модель зрения читает фото чеков — основная ветка разбора чека
    vision_model = await resolve_vision_model()
    if vision_model:
        print(f"👁 Модель зрения (чеки): {vision_model}")
    elif VISION_ENABLED:
        print(f"👁 Модель зрения не найдена. Чеки читает Tesseract. "
              f"Для чтения чеков нейросетью: ollama pull {VISION_MODEL}")
    else:
        print("👁 Чтение чеков моделью зрения выключено (VISION_ENABLED=0) — читает Tesseract")


async def main():
    if not BOT_TOKEN:
        raise SystemExit(
            "❌ BOT_TOKEN не задан. Скопируй .env.example в .env и впиши токен от @BotFather."
        )
    if not USERS:
        raise SystemExit(
            "❌ Не задан ни один USER_ID в .env. Узнай свой ID через @userinfobot и впиши в .env."
        )

    # Создаём папки для данных
    os.makedirs(DATA_DIR, exist_ok=True)
    os.makedirs(RECEIPTS_DIR, exist_ok=True)

    # Инициализация базы
    await init_db()

    # Создаём бота
    bot = Bot(
        token=BOT_TOKEN,
        default=DefaultBotProperties(parse_mode=ParseMode.MARKDOWN),
        session=RetryingSession(),
    )
    dp = Dispatcher()

    # Подключаем роутеры (порядок важен!)
    dp.include_router(expenses.router)   # FSM-состояния трат — раньше меню
    dp.include_router(reports.router)    # колбэки отчётов и диаграмм
    dp.include_router(debts.router)      # колбэки долгов
    dp.include_router(settings.router)
    dp.include_router(bank_import.router)  # импорт PDF-выписки: до свободного ввода
    dp.include_router(onboarding.router) # шаги приветственной настройки — до свободного ввода
    dp.include_router(main_menu.router)  # /start, кнопки меню и свободный ввод — последними

    # Интерфейс Telegram + статус ИИ
    await setup_telegram_ui(bot)
    await log_ai_status()

    # Планировщик: ежедневная сводка
    scheduler = register_scheduler(bot)
    scheduler.start()

    # Ошибку не пробрасываем в лог и не оставляем пользователя без ответа
    @dp.error()
    async def on_error(event: ErrorEvent) -> bool:
        error = event.exception
        print(f"❌ Ошибка при обработке апдейта: {type(error).__name__}: {error}")
        update = event.update
        target = update.message or (update.callback_query.message if update.callback_query else None)
        if target is not None:
            try:
                await target.answer("⚠️ Не получилось показать ответ. Попробуй ещё раз.", parse_mode=None)
            except Exception:
                pass
        return True

    print("✅ Бот запущен. Ожидание сообщений...")
    try:
        await dp.start_polling(bot)
    finally:
        scheduler.shutdown(wait=False)


if __name__ == "__main__":
    asyncio.run(main())
