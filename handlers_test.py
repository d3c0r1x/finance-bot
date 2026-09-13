"""Интеграционный тест обработчиков без Telegram.

Прогоняет настоящие апдейты через диспетчер (та же цепочка роутеров, что в bot.py),
подменяя только сетевую сессию Telegram — так проверяются меню, кнопки, карточки
и то, что разметка сообщений не сломает отправку.

Запуск:  venv\\Scripts\\python.exe handlers_test.py
"""
import asyncio
import os
import re
import sys
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ["FINANCE_DB"] = "test_handlers.db"
os.environ["USER_ID_1"] = "1111"
os.environ["USER_ID_2"] = "2222"

_TEST_DB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "test_handlers.db")

from config import MONTHLY_LIMITS  # noqa: E402
from samples import first_photo  # noqa: E402
from services.profile import LEGACY_DISPLAY_NAMES  # noqa: E402
from aiogram import Bot, Dispatcher  # noqa: E402
from aiogram.client.default import DefaultBotProperties  # noqa: E402
from aiogram.client.session.base import BaseSession  # noqa: E402
from aiogram.enums import ParseMode  # noqa: E402
from aiogram.methods import EditMessageText, GetFile, SendMessage  # noqa: E402
from aiogram.types import (CallbackQuery, Chat, File, Message, PhotoSize,  # noqa: E402
                           ReplyKeyboardMarkup, Update, User)

def receipt_photo_bytes() -> bytes:
    """Фото реального чека из локальных образцов, если оно есть, иначе синтетический чек.

    Личные фото в репозиторий не попадают: путь берётся из receipt_samples.json.
    """
    photo = first_photo()
    if photo:
        with open(photo, "rb") as file:
            return file.read()
    from io import BytesIO
    from PIL import Image, ImageDraw, ImageFont
    lines = ["ООО ПЯТЁРОЧКА", "КАССОВЫЙ ЧЕК", "МОЛОКО 1Л      89.90", "СЫР 200Г     350.00",
             "ЧИПСЫ 120Г   149.90", "ИТОГ         589.80", "09.09.26 19:14"]
    font = ImageFont.truetype(r"C:\Windows\Fonts\consola.ttf", 32)
    image = Image.new("L", (620, 60 * len(lines) + 60), color=255)
    draw = ImageDraw.Draw(image)
    for index, line in enumerate(lines):
        draw.text((24, 30 + index * 60), line, fill=0, font=font)
    buffer = BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()

USER = User(id=1111, is_bot=False, first_name="Тест", last_name="Пользователь", username="test_user")
CHAT = Chat(id=1111, type="private")


class FakeSession(BaseSession):
    """Запоминает исходящие вызовы Telegram API и отвечает заглушками."""

    def __init__(self):
        super().__init__()
        self.calls = []

    async def close(self):
        return None

    async def stream_content(self, url, headers=None, timeout=30, chunk_size=65536,
                             raise_for_status=True):
        yield receipt_photo_bytes()

    async def make_request(self, bot, method, timeout=None):
        self.calls.append(method)
        if isinstance(method, GetFile):
            return File(file_id=method.file_id, file_unique_id="uniq", file_path="receipt.png",
                        file_size=len(receipt_photo_bytes()))
        if isinstance(method, (SendMessage, EditMessageText)):
            chat_id = method.chat_id
            chat = chat_id if isinstance(chat_id, Chat) else Chat(id=int(chat_id), type="private")
            message = Message(message_id=len(self.calls), date=datetime.now(), chat=chat,
                              text=getattr(method, "text", "") or "")
            return message.as_(bot)  # как в реальном API: у сообщения есть бот
        return True

    # вспомогательное для тестов
    def texts(self):
        return [c.text for c in self.calls if isinstance(c, (SendMessage, EditMessageText)) and c.text]

    def markup(self):
        for call in reversed(self.calls):
            if isinstance(call, (SendMessage, EditMessageText)) and call.reply_markup is not None:
                return call.reply_markup
        return None


def markup_problems(text: str) -> list[str]:
    """Грубая проверка разметки: непарные ** или подчёркивания вне кода ломают отправку."""
    problems = []
    if text.count("**") % 2:
        problems.append("непарные **")
    if text.count("`") % 2:
        problems.append("непарные `")
    without_code = re.sub(r"`[^`]*`", "", text)
    if without_code.count("_") % 2:
        problems.append("непарные _")
    return problems


async def main():
    from database.db import get_monthly_spending, init_db
    from handlers import debts, expenses, main_menu, onboarding, reports, settings
    from services import profile as profile_service

    await init_db()

    bot = Bot(token="42:TEST",
              default=DefaultBotProperties(parse_mode=ParseMode.MARKDOWN),
              session=FakeSession())
    session = bot.session

    dp = Dispatcher()
    for router in (expenses.router, reports.router, debts.router, settings.router,
                   onboarding.router, main_menu.router):
        dp.include_router(router)

    update_id = 1000
    failures = []

    async def run(text=None, callback=None, label="", photo=False):
        nonlocal update_id
        update_id += 1
        session.calls.clear()
        if callback:
            update = Update(update_id=update_id, callback_query=CallbackQuery(
                id=str(update_id), from_user=USER, chat_instance="test",
                message=Message(message_id=1, date=datetime.now(), chat=CHAT, text="карточка"),
                data=callback))
        elif photo:
            update = Update(update_id=update_id, message=Message(
                message_id=1, date=datetime.now(), chat=CHAT, from_user=USER,
                photo=[PhotoSize(file_id="photo1", file_unique_id="uniq-photo",
                                 width=960, height=1280)]))
        else:
            update = Update(update_id=update_id, message=Message(
                message_id=1, date=datetime.now(), chat=CHAT, from_user=USER, text=text))
        try:
            await dp.feed_update(bot, update)
        except Exception as error:  # noqa: BLE001
            failures.append(f"{label}: исключение {type(error).__name__}: {error}")
            return []
        sent = session.texts()
        for text_item in sent:
            for problem in markup_problems(text_item):
                failures.append(f"{label}: {problem} → {text_item[:80]!r}")
        print(f"  • {label}: {[t.replace(chr(10), ' | ')[:70] for t in sent] or 'нет ответа'}")
        return sent

    def check(label, condition, detail=""):
        if condition:
            print(f"✅ {label}")
        else:
            failures.append(f"{label}: {detail}")
            print(f"❌ {label}: {detail}")

    print("— Приветственная настройка нового пользователя —")
    sent = await run(text="/start", label="/start нового пользователя")
    check("приветствие использует имя Telegram", any("Тест Пользователь" in t for t in sent),
          f"ответы: {sent}")
    # список старых ролевых обращений живёт в одном месте — в сервисе профиля
    check("старые ролевые обращения удалены", not any(
        marker in " ".join(sent).casefold() for marker in LEGACY_DISPLAY_NAMES),
          f"ответы: {sent}")
    check("новый пользователь видит приветствие", any("Привет" in t for t in sent), f"ответы: {sent}")
    kb = session.markup()
    buttons = {b.callback_data for row in kb.inline_keyboard for b in row} if kb else set()
    check("в настройке есть шаги и пропуск", {"onb:1", "onb:skip"} <= buttons, str(buttons))
    sent = await run(callback="onb:1", label="шаг «Как пользоваться»")
    check("второй шаг настройки", any("Как этим пользоваться" in t for t in sent), f"ответы: {sent}")
    sent = await run(callback="onb:2", label="шаг «Имя»")
    check("настройка спрашивает имя", any("Как тебя называть" in t for t in sent), f"ответы: {sent}")
    sent = await run(text="Никита", label="ввод имени")
    check("имя принято", any("Никита" in t for t in sent), f"ответы: {sent}")
    sent = await run(text="150000", label="ввод плана дохода")
    check("план дохода принят", any("150 000" in t for t in sent), f"ответы: {sent}")
    sent = await run(callback="onb:skip", label="пропуск настройки")
    check("настройка завершается экраном готовности", any("Готово" in t for t in sent),
          f"ответы: {sent}")
    check("пользователь помечен как настроенный", await profile_service.is_onboarded(1111))
    check("имя сохранено в профиле", await profile_service.display_name(1111) == "Никита",
          await profile_service.display_name(1111))

    print("— Свободный ввод траты (главный сценарий) —")
    sent = await run(text="Заправка 2000", label="текст «Заправка 2000»")
    card = next((t for t in sent if "Проверь запись" in t), "")
    check("трата разобрана с первого сообщения", "Проверь запись" in card and "2 000" in card,
          f"ответы: {sent}")
    kb = session.markup()
    check("на карточке есть кнопки подтверждения",
          kb is not None and any(b.callback_data == "confirm_expense"
                                 for row in kb.inline_keyboard for b in row), str(kb))

    sent = await run(callback="confirm_expense", label="кнопка «Записать»")
    check("трата записана", any("Записал" in t for t in sent), f"ответы: {sent}")
    spending = await get_monthly_spending(user_id=1111)
    check("трата в базе", spending.get("транспорт") == 2000, str(spending))
    saved = [c for c in session.calls
             if isinstance(c, SendMessage) and c.text and "Записал" in c.text]
    check("после записи вернулось меню",
          bool(saved) and isinstance(saved[-1].reply_markup, ReplyKeyboardMarkup),
          f"reply_markup={saved[-1].reply_markup if saved else None}")

    print("— Прочие способы ввода —")
    sent = await run(text="Купил чай в автомате за 130", label="чай в автомате")
    check("категория «работа»", any("работа" in t for t in sent), f"ответы: {sent}")
    sent = await run(text="💰 Доход", label="кнопка «Доход»")
    check("подсказка по доходу", any("Доход" in t for t in sent), f"ответы: {sent}")
    sent = await run(text="Зарплата 150000", label="доход текстом")
    check("доход распознан", any("доход" in t.lower() for t in sent), f"ответы: {sent}")
    sent = await run(text="📷 Скан чека", label="кнопка «Скан чека»")
    check("подсказка по чеку", any("фото чека" in t for t in sent), f"ответы: {sent}")

    print("— Меню и кнопки —")
    sent = await run(text="/start", label="/start")
    check("/start показывает сводку и меню", any("Привет" in t for t in sent) and len(sent) >= 2,
          f"ответы: {sent}")
    sent = await run(text="/menu", label="/menu")
    check("/menu отвечает", any("Меню" in t for t in sent), f"ответы: {sent}")
    sent = await run(text="/help", label="/help")
    check("/help отвечает", any("Как пользоваться" in t for t in sent), f"ответы: {sent}")
    sent = await run(text="📊 Отчёт", label="кнопка «Отчёт»")
    check("меню отчётов", any("Выбери отчёт" in t for t in sent), f"ответы: {sent}")
    sent = await run(text="💳 Долги", label="кнопка «Долги»")
    check("сводка долгов", any("Текущие долги" in t for t in sent), f"ответы: {sent}")
    sent = await run(text="🕘 История", label="кнопка «История»")
    check("история показывает последние записи", any("Последние записи" in t for t in sent), f"ответы: {sent}")
    sent = await run(callback="menu_history", label="инлайн-история")
    check("инлайн-история отвечает", any("Последние записи" in t for t in sent), f"ответы: {sent}")
    sent = await run(text="⚙️ Настройки", label="кнопка «Настройки»")
    check("настройки с бюджетом", any("Настройки" in t and "Бюджет на месяц" in t for t in sent),
          f"ответы: {sent}")

    print("— Бюджет: правка лимитов в самом боте —")
    sent = await run(callback="budget_menu", label="экран бюджета")
    check("бюджет открылся", any("Бюджет на месяц" in t for t in sent), f"ответы: {sent}")
    sent = await run(callback="budget_set:еда", label="категория «еда» в бюджете")
    check("предложен ввод лимита", any("Сейчас:" in t for t in sent), f"ответы: {sent}")
    sent = await run(text="33000", label="свой лимит «еда» = 33 000")
    check("свой лимит сохранён", any("33 000" in t for t in sent), f"ответы: {sent}")

    from services import budget as budget_service
    limits = await budget_service.get_limits()
    check("лимит «еда» в базе", limits.get("еда") == 33000, str(limits))

    sent = await run(callback="budget_set:total", label="общий лимит")
    sent = await run(callback="budget_value:100000", label="быстрый общий лимит 100 000")
    check("общий лимит сохранён", await budget_service.get_total_limit() == 100_000,
          str(await budget_service.get_total_limit()))
    sent = await run(callback="budget_reset", label="сброс бюджета")
    limits = await budget_service.get_limits()
    check("лимиты сброшены к стартовым", limits.get("еда") == MONTHLY_LIMITS["еда"], str(limits))
    sent = await run(callback="budget_ai", label="ИИ-предложение бюджета")
    check("ИИ отвечает про бюджет", any("бюджет" in t.lower() or "днев" in t for t in sent),
          f"ответы: {sent}")

    print("— Инлайн-действия —")
    for callback, expected, label in (
        ("menu_help", "Как пользоваться", "быстрое действие «Помощь»"),
        ("menu_debts", "Текущие долги", "быстрое действие «Долги»"),
        ("menu_report", "Выбери отчёт", "быстрое действие «Отчёт»"),
        ("report_month", "Отчёт за текущий месяц", "отчёт за месяц"),
        ("report_week", "Отчёт за неделю", "отчёт за неделю"),
        ("report_quarter", "Отчёт за 90 дней", "отчёт за 90 дней"),
        ("report_joint", "Совместный отчёт", "совместный отчёт"),
        ("debt_tbank", "Т-Банк", "карточка долга"),
        ("debt_forecast", "Прогноз", "прогноз погашения"),
        ("health_check", "Статус сервисов", "статус сервисов"),
        ("pay_sber", "Платёж по", "начало платежа по долгу"),
        ("cancel", "Отменил", "отмена"),
    ):
        sent = await run(callback=callback, label=label)
        check(label, any(expected in t for t in sent), f"ответы: {sent}")

    print("— Платёж по кредитке —")
    from database.db import get_debt
    await run(text="💳 Долги", label="кнопка «Долги»")
    await run(callback="debt_sber", label="карточка Сбербанка")
    before_debt = (await get_debt("sber"))["current_amount"]
    # платим часть остатка: тест не должен зависеть от размеров демо-долгов в models.py
    payment = max(1, int(before_debt // 2))
    sent = await run(callback="pay_sber", label="кнопка «Внести платёж»")
    sent = await run(text=str(payment), label=f"сумма платежа {payment}")
    check("платёж записан", any("записан по" in t for t in sent), f"ответы: {sent}")
    kb = session.markup()
    check("после платежа вернулось нижнее меню",
          kb is not None and isinstance(kb, ReplyKeyboardMarkup)
          and any(button.text == "💸 Добавить трату" for row in kb.keyboard for button in row),
          str(kb))
    debt = await get_debt("sber")
    check("остаток долга уменьшился", bool(debt) and debt["current_amount"] == before_debt - payment,
          str(debt))

    print("— Отмена платежа возвращает меню —")
    sent = await run(callback="pay_tbank", label="начало платежа по Т-Банку")
    sent = await run(callback="cancel", label="отмена платежа")
    kb = session.markup()
    check("после отмены вернулось нижнее меню",
          kb is not None and isinstance(kb, ReplyKeyboardMarkup)
          and any(button.text == "💸 Добавить трату" for row in kb.keyboard for button in row),
          str(kb))

    print("— Фото чека целиком —")
    sent = await run(photo=True, label="фото чека")
    total_text = " ".join(sent)
    check("чек распознан", any("Проверь запись" in t for t in sent), f"ответы: {sent}")
    check("категория определена",
          any(word in total_text for word in ("техника", "еда")), f"ответы: {sent}")
    kb = session.markup()
    buttons = {b.callback_data for row in kb.inline_keyboard for b in row} if kb else set()
    check("карточка чека с кнопками позиций",
          {"confirm_expense", "receipt_items"} <= buttons, str(buttons))

    check("в карточке видно, чем прочитан чек",
          any("Прочитано моделью зрения" in t or "Читал Tesseract" in t for t in sent),
          f"ответы: {sent}")

    sent = await run(callback="receipt_items", label="кнопка «Все позиции»")
    check("позиции показаны", any("Позиции чека" in t or "Позиции" in t for t in sent),
          f"ответы: {sent}")

    sent = await run(callback="report_chart", label="кнопка «Диаграмма»")
    check("диаграмма рисуется без ошибок",
          not any("Не получилось" in t for t in sent), f"ответы: {sent}")

    before = len(await get_monthly_spending(user_id=1111))
    sent = await run(callback="confirm_expense", label="запись чека")
    check("чек записан", any("Записал" in t for t in sent), f"ответы: {sent}")
    saved = [c for c in session.calls
             if isinstance(c, SendMessage) and c.text and "Записал" in c.text]
    check("после чека вернулось меню",
          bool(saved) and isinstance(saved[-1].reply_markup, ReplyKeyboardMarkup), "нет меню")

    from database.db import get_receipt_items, get_transactions
    receipts = [t for t in await get_transactions(user_id=1111, days=1) if t["source"] == "photo"]
    check("чек попал в базу с позициями",
          bool(receipts) and len(await get_receipt_items(receipts[0]["id"])) >= 2,
          f"чеков: {len(receipts)}")

    print("— Панель подтверждения платежа —")
    sent = await run(text="Платёж Т-Банк 3000", label="платёж по долгу текстом")
    check("платёж распознан как долг", any("Платёж по" in t for t in sent), f"ответы: {sent}")
    sent = await run(callback="edit_expense", label="кнопка «Изменить»")
    check("меню изменения открылось", any("Что изменить" in t for t in sent), f"ответы: {sent}")
    sent = await run(callback="edit_amount:3000", label="быстрая сумма 3000")
    check("сумма изменена", any("3 000" in t for t in sent), f"ответы: {sent}")
    sent = await run(callback="edit_cat_menu", label="меню категорий")
    check("список категорий", session.markup() is not None, f"ответы: {sent}")
    sent = await run(callback="edit_cat:еда", label="категория «еда»")
    check("подкатегории предложены", any("подкатегорию" in t for t in sent), f"ответы: {sent}")
    sent = await run(callback="edit_sub:продукты", label="подкатегория «продукты»")
    check("подкатегория применена", any("продукты" in t for t in sent), f"ответы: {sent}")
    sent = await run(callback="edit_ok", label="кнопка «Записать» после правок")
    check("исправленная трата записана", any("Записал" in t for t in sent), f"ответы: {sent}")

    await bot.session.close()

    if os.path.exists(_TEST_DB):
        os.remove(_TEST_DB)
    if failures:
        print("\n❌ Проблемы:")
        for failure in failures:
            print("   -", failure)
        raise SystemExit(1)
    print("\n🎉 Все сценарии интерфейса прошли без ошибок")


if __name__ == "__main__":
    asyncio.run(main())
