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

from config import MONTHLY_LIMITS, TOTAL_MONTHLY_LIMIT  # noqa: E402
from samples import first_photo  # noqa: E402
from services.profile import LEGACY_DISPLAY_NAMES  # noqa: E402
from aiogram import Bot, Dispatcher  # noqa: E402
from aiogram.client.default import DefaultBotProperties  # noqa: E402
from aiogram.client.session.base import BaseSession  # noqa: E402
from aiogram.enums import ParseMode  # noqa: E402
from aiogram.methods import (EditMessageReplyMarkup, EditMessageText, GetFile,  # noqa: E402
                             SendMessage, SendPhoto)
from aiogram.types import (CallbackQuery, Chat, File, Message, PhotoSize,  # noqa: E402
                           ReplyKeyboardMarkup, Update, User)

def synthetic_receipt_bytes() -> bytes:
    """Синтетический продуктовый чек.

    Нужен там, где проверка говорит именно о продуктах: для техники правила намеренно
    не выдают «вредно» и «лишнее», и подсовывать такой чек в проверку советов было бы
    выдачей желаемого за поведение бота.
    """
    from io import BytesIO
    from PIL import Image, ImageDraw
    from utils.fonts import mono_font
    lines = ["ООО ПЯТЁРОЧКА", "КАССОВЫЙ ЧЕК", "МОЛОКО 1Л      89.90", "СЫР 200Г     350.00",
             "ЧИПСЫ 120Г   149.90", "ИТОГ         589.80", "09.09.26 19:14"]
    font = mono_font(32)
    image = Image.new("L", (620, 60 * len(lines) + 60), color=255)
    draw = ImageDraw.Draw(image)
    for index, line in enumerate(lines):
        draw.text((24, 30 + index * 60), line, fill=0, font=font)
    buffer = BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


def receipt_photo_bytes() -> bytes:
    """Фото реального чека из локальных образцов, если оно есть, иначе синтетический чек.

    Личные фото в репозиторий не попадают: путь берётся из receipt_samples.json.
    """
    photo = first_photo()
    if photo:
        with open(photo, "rb") as file:
            return file.read()
    return synthetic_receipt_bytes()

USER = User(id=1111, is_bot=False, first_name="Тест", last_name="Пользователь", username="test_user")
CHAT = Chat(id=1111, type="private")


class FakeSession(BaseSession):
    """Запоминает исходящие вызовы Telegram API и отвечает заглушками."""

    def __init__(self):
        super().__init__()
        self.calls = []
        # Проверке разрешено подменить фото: часть сценариев идёт именно по продуктовому чеку.
        self.photo_override: bytes | None = None

    def photo_bytes(self) -> bytes:
        return self.photo_override or receipt_photo_bytes()

    async def close(self):
        return None

    async def stream_content(self, url, headers=None, timeout=30, chunk_size=65536,
                             raise_for_status=True):
        yield self.photo_bytes()

    async def make_request(self, bot, method, timeout=None):
        self.calls.append(method)
        if isinstance(method, GetFile):
            return File(file_id=method.file_id, file_unique_id="uniq", file_path="receipt.png",
                        file_size=len(self.photo_bytes()))
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

    def photos(self):
        """Отправленные картинки — с байтами внутри, чтобы проверить, что это правда PNG."""
        return [call.photo for call in self.calls if isinstance(call, SendPhoto)]

    def markup_edits(self):
        """Клавиатуры, присланные правкой сообщения: так видно, что кнопка снята."""
        return [call.reply_markup for call in self.calls if isinstance(call, EditMessageReplyMarkup)]


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
    limits = await budget_service.get_limits(1111)
    check("личный лимит «еда» сохранён", limits.get("еда") == 33000, str(limits))
    # Правка бюджета одним человеком не должна менять картину у второго:
    # траты считаются по каждому, значит и лимит — тоже его собственный.
    check("семейный лимит не тронут", (await budget_service.get_limits()).get("еда")
          == MONTHLY_LIMITS["еда"], str(await budget_service.get_limits()))
    check("у второго пользователя лимит остался семейным",
          (await budget_service.get_limits(2222)).get("еда") == MONTHLY_LIMITS["еда"],
          str(await budget_service.get_limits(2222)))
    check("у второго пользователя своих лимитов нет",
          not await budget_service.own_limits(2222))

    sent = await run(callback="budget_set:total", label="общий лимит")
    sent = await run(callback="budget_value:100000", label="быстрый общий лимит 100 000")
    check("личный общий лимит сохранён",
          await budget_service.get_total_limit(1111) == 100_000,
          str(await budget_service.get_total_limit(1111)))
    check("семейный общий лимит не тронут",
          await budget_service.get_total_limit() == TOTAL_MONTHLY_LIMIT,
          str(await budget_service.get_total_limit()))
    sent = await run(callback="budget_reset", label="сброс личных лимитов")
    check("после сброса пользователь снова на семейных лимитах",
          not await budget_service.own_limits(1111)
          and (await budget_service.get_total_limit(1111)) == TOTAL_MONTHLY_LIMIT,
          str(await budget_service.get_total_limit(1111)))
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
    # Пока ни один чек не разбирался, экран необязательных покупок говорит об этом прямо,
    # а не показывает нуль (нуль читался бы как «необязательного нет»).
    sent = await run(callback="report_waste", label="необязательные покупки без разборов")
    check("без разобранных чеков экран честно говорит, что считать нечего",
          any("считать нечего" in t for t in sent), f"ответы: {sent}")
    check("без разборов картинки динамики нет",
          not session.photos(), f"картинок: {len(session.photos())}")

    sent = await run(photo=True, label="фото чека")
    total_text = " ".join(sent)
    check("чек распознан", any("Проверь запись" in t for t in sent), f"ответы: {sent}")
    # Какая именно категория — зависит от фото: на чеке электроники это «техника»,
    # а на синтетическом продуктовом с чипсами срабатывает правило «снеки — это досуг».
    # Проверяем интерфейс, а не конкретный товар: категория должна быть осмысленной.
    check("категория определена",
          any(word in total_text for word in ("техника", "еда", "досуг")), f"ответы: {sent}")
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

    print("— Правка позиций чека —")
    from aiogram.fsm.storage.base import StorageKey
    key = StorageKey(bot_id=bot.id, chat_id=CHAT.id, user_id=USER.id)

    await run(photo=True, label="фото чека для правки позиций")
    sent = await run(callback="receipt_items", label="кнопка «Все позиции» с правкой")
    kb = session.markup()
    buttons = {b.callback_data for row in kb.inline_keyboard for b in row} if kb else set()
    check("в списке позиций есть удаление, правка и добавление",
          "item_add" in buttons and "receipt_sync_total" in buttons
          and any(str(data).startswith("item_del:") for data in buttons)
          and any(str(data).startswith("item_fix:") for data in buttons), str(buttons))

    before = len(((await dp.storage.get_data(key)).get("parsed") or {}).get("items") or [])
    sent = await run(callback="item_del:0", label="убрать первую позицию")
    parsed = (await dp.storage.get_data(key)).get("parsed") or {}
    check("позиция убрана из разбора", len(parsed.get("items") or []) == before - 1,
          f"было {before}, стало {len(parsed.get('items') or [])}")
    check("список перерисован с пометкой удаления", any("Убрал" in t for t in sent), f"ответы: {sent}")

    sent = await run(callback="item_fix:0", label="исправить позицию")
    check("бот спрашивает новое название и цену", any("Пришли новое название" in t for t in sent),
          f"ответы: {sent}")
    sent = await run(text="Сыр Российский 320,50", label="ввод «Сыр Российский 320,50»")
    parsed = (await dp.storage.get_data(key)).get("parsed") or {}
    first = (parsed.get("items") or [{}])[0]
    check("позиция заменена на введённую",
          first.get("name") == "Сыр Российский" and first.get("sum") == 320.5, str(first))
    check("правка видна в списке", any("Сыр Российский" in t for t in sent), f"ответы: {sent}")

    sent = await run(callback="item_add", label="добавить пропущенную позицию")
    check("бот ждёт название и цену новой позиции", any("Пропущенная позиция" in t for t in sent),
          f"ответы: {sent}")
    await run(text="Кофе 250", label="ввод «Кофе 250»")
    parsed = (await dp.storage.get_data(key)).get("parsed") or {}
    check("новая позиция добавлена",
          any(item.get("name") == "Кофе" and item.get("sum") == 250.0
              for item in parsed.get("items") or []), str(parsed.get("items")))

    items_total = round(sum(item.get("sum") or 0 for item in parsed.get("items") or []), 2)
    sent = await run(callback="receipt_sync_total", label="сумма чека = сумма позиций")
    parsed = (await dp.storage.get_data(key)).get("parsed") or {}
    check("сумма чека взята из позиций", parsed.get("amount") == items_total,
          f"{parsed.get('amount')} != {items_total}")

    sent = await run(callback="receipt_card", label="возврат к карточке")
    check("карточка помнит про ручную правку", any("Позиции исправлены вручную" in t for t in sent),
          f"ответы: {sent}")
    sent = await run(callback="confirm_expense", label="запись чека с правками")
    check("исправленный чек записан", any("Записал" in t for t in sent), f"ответы: {sent}")

    print("— Повторно присланный чек —")
    await run(photo=True, label="тот же чек прислан ещё раз")
    sent = await run(callback="confirm_expense", label="запись повторного чека")
    check("бот предупреждает о повторной записи", any("уже записан" in t for t in sent),
          f"ответы: {sent}")
    check("дубль не записан молча", not any("Записал" in t for t in sent), f"ответы: {sent}")
    kb = session.markup()
    buttons = {b.callback_data for row in kb.inline_keyboard for b in row} if kb else set()
    check("решение про дубль — кнопками", "confirm_duplicate" in buttons, str(buttons))
    sent = await run(callback="confirm_duplicate", label="«Записать ещё раз»")
    check("после подтверждения чек записан", any("Записал" in t for t in sent), f"ответы: {sent}")

    # Разбор сохранён вместе с чеком: следующая покупка того же товара должна получить
    # напоминание о совете в карточке записи — в момент покупки, а не через отчёт.
    # Чек для этой проверки — продуктовый: вердикт «вредно» у техники правила не выдают,
    # и выдумывать его в фикстуре значило бы проверять поведение, которого у бота нет.
    import aiosqlite
    from config import DB_PATH as verdict_db
    from database.db import (delete_transaction, get_receipt_items,
                             save_receipt_verdicts)

    async def receipt_ids() -> set[int]:
        async with aiosqlite.connect(verdict_db) as db:
            cursor = await db.execute(
                "SELECT DISTINCT t.id FROM transactions t JOIN receipt_items i "
                "ON i.transaction_id = t.id WHERE t.user_id = 1111")
            return {row[0] for row in await cursor.fetchall()}

    before_ids = await receipt_ids()
    session.photo_override = synthetic_receipt_bytes()
    await run(photo=True, label="продуктовый чек для напоминания")
    await run(callback="confirm_expense", label="запись продуктового чека")

    fresh_ids = (await receipt_ids()) - before_ids
    async with aiosqlite.connect(verdict_db) as db:
        db.row_factory = aiosqlite.Row
        cursor = await db.execute(
            "SELECT id, transaction_id, name FROM receipt_items WHERE transaction_id IN "
            f"({','.join('?' * len(fresh_ids))}) ORDER BY id", tuple(fresh_ids))
        items = await cursor.fetchall()
    advised_id = items[0]["transaction_id"]
    receipt_names = [row["name"] for row in items]
    # Вердикт — тот, что реально ставит разбор снекам: «вредно» со конкретным советом.
    chips = next(name for name in receipt_names if "ЧИПС" in name.upper())
    # Вердикты идут с названиями позиций: сохранение сопоставляет их по названию, а не
    # по порядку, поэтому частичный список — только чипсы из трёх позиций — адресует
    # совет именно чипсам, а не соседнему молоку.
    await save_receipt_verdicts(advised_id, [(chips, "вредно", "снек, много калорий")])

    await run(photo=True, label="чек с уже разобранным товаром")
    await run(callback="confirm_expense", label="запись чека с прошлым советом")
    sent = await run(callback="confirm_duplicate", label="«Записать ещё раз» с прошлым советом")
    check("о прошлом совете напомнили в момент покупки",
          any("Уже было" in t and "не запрет" in t for t in sent), f"ответы: {sent}")
    check("в напоминании есть название товара из чека",
          any(chips in t for t in sent), f"ответы: {sent}")

    # За собой убираем: эти чеки нужны были только проверке, а на каталог цен и отчёт
    # о необязательных покупках они бы влияли — три покупки одного товара это уже история.
    session.photo_override = None
    for receipt_id in (await receipt_ids()) - before_ids:
        await delete_transaction(receipt_id, 1111)

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

    print("— Регулярные платежи —")
    sent = await run(callback="report_recurring", label="экран регулярных платежей (пусто)")
    check("экран регулярных платежей открылся", any("Регулярные платежи" in t for t in sent),
          f"ответы: {sent}")

    # Серия подписок: четыре списания раз в 30 дней, последнее — 28 дней назад,
    # значит следующее ожидается через пару дней.
    import aiosqlite
    from datetime import timedelta as _delta
    from config import DB_PATH as _db_path
    from services.forecast import weekly_food_limit
    from services.scheduler import build_daily_summary_text

    async with aiosqlite.connect(_db_path) as db:
        for index in range(4):
            when = (datetime.now() - _delta(days=28 + 30 * index)).isoformat(sep=" ")
            await db.execute(
                "INSERT INTO transactions "
                "(user_id, amount, category, description, tx_type, source, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (1111, 999, "досуг", "Netflix", "expense", "text", when))
        await db.commit()

    sent = await run(callback="report_recurring", label="экран регулярных платежей (с подпиской)")
    check("подписка найдена в истории", any("Netflix" in t for t in sent), f"ответы: {sent}")
    check("в отчёте видно, сколько уходит в месяц",
          any("В месяц" in t for t in sent), f"ответы: {sent}")

    summary = await build_daily_summary_text(1111)
    check("ежедневная сводка предупреждает о скором списании",
          "Netflix" in summary and "Скоро спишется" in summary, summary)

    kb = session.markup()
    buttons = [button.callback_data for row in kb.inline_keyboard for button in row] if kb else []
    mute_data = next((data for data in buttons if data.startswith("recurring_mute:")), None)
    check("у серии есть кнопка «не подписка»", bool(mute_data), str(buttons))

    sent = await run(callback=mute_data, label="отключить ложную подписку")
    check("серия отключена и не пропала из истории",
          any("Отключены из напоминаний" in t for t in sent), f"ответы: {sent}")
    summary = await build_daily_summary_text(1111)
    check("отключённая подписка не напоминает в сводке", "Netflix" not in summary, summary)

    kb = session.markup()
    buttons = [button.callback_data for row in kb.inline_keyboard for button in row] if kb else []
    back_data = next((data for data in buttons if data.startswith("recurring_unmute:")), None)
    check("есть кнопка вернуть напоминание", bool(back_data), str(buttons))
    sent = await run(callback=back_data, label="вернуть напоминание")
    check("серия вернулась", any("Netflix" in t for t in sent), f"ответы: {sent}")
    summary = await build_daily_summary_text(1111)
    check("после возврата сводка снова напоминает", "Netflix" in summary, summary)

    print("— Мои цены —")
    sent = await run(callback="report_prices", label="экран «Мои цены» без истории")
    check("экран цен честно говорит, что данных мало",
          any("Мои цены" in t and "нечего сравнивать" in t for t in sent), f"ответы: {sent}")

    # Товар куплен трижды с ровным ритмом: появляется обычная цена, место, где было дешевле,
    # и обычный срок закупки — последний раз молоко брали как раз восемь дней назад.
    async with aiosqlite.connect(_db_path) as db:
        for price, store, day in ((95, "Пятёрочка", 28), (89, "К&Б", 18), (119, "Пятёрочка", 8)):
            when = (datetime.now() - _delta(days=day)).isoformat(sep=" ")
            cursor = await db.execute(
                "INSERT INTO transactions "
                "(user_id, amount, category, description, tx_type, source, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (1111, price, "еда", store, "expense", "receipt", when))
            await db.execute(
                "INSERT INTO receipt_items (transaction_id, name, qty, price, sum) "
                "VALUES (?, ?, 1, ?, ?)",
                (cursor.lastrowid, "Молоко Простоквашино 3,2% 930мл", price, price))
        await db.commit()

    sent = await run(callback="report_prices", label="экран «Мои цены» с историей")
    text = "\n".join(sent)
    check("обычная цена товара видна", "обычно" in text, text)
    check("видно, где было дешевле", "дешевле всего" in text and "К&Б" in text, text)
    check("рост цены подписан процентами и прежней ценой",
          "к прежней цене" in text and "обычно" in text, text)

    print("— Список покупок —")
    sent = await run(callback="report_shopping", label="экран списка покупок")
    check("список покупок отвечает осмысленно",
          any("не пора" in t or "пора брать" in t for t in sent), f"ответы: {sent}")

    # Хлеб берут раз в 10 дней, последний раз 8 дней назад — обычный срок как раз подошёл.
    async with aiosqlite.connect(_db_path) as db:
        for price, store, day in ((42, "Пятёрочка", 28), (44, "К&Б", 18), (46, "Пятёрочка", 8)):
            when = (datetime.now() - _delta(days=day)).isoformat(sep=" ")
            cursor = await db.execute(
                "INSERT INTO transactions "
                "(user_id, amount, category, description, tx_type, source, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (1111, price, "еда", store, "expense", "receipt", when))
            await db.execute(
                "INSERT INTO receipt_items (transaction_id, name, qty, price, sum) "
                "VALUES (?, ?, 1, ?, ?)",
                (cursor.lastrowid, "Хлеб Бородинский 400г", price, price))
        await db.commit()

    def listed(body: str, name: str) -> bool:
        """Товар именно в списке закупки, а не в строке отключённых или отмеченных."""
        return any(line.startswith(f"• **{name}") for line in body.splitlines())

    def button_for(prefix: str, name: str) -> str | None:
        """Кнопка конкретного товара: у нескольких позиций в списке кнопки одинаковых типов."""
        keyboard = session.markup()
        for row in (keyboard.inline_keyboard if keyboard else []):
            for button in row:
                if (button.callback_data or "").startswith(prefix) and name in button.text:
                    return button.callback_data
        return None

    bread = "Хлеб Бородинский 400г"
    sent = await run(callback="report_shopping", label="список покупок с товаром к сроку")
    text = "\n".join(sent)
    check("товар к сроку попал в список", listed(text, bread), text)
    check("у товара виден срок и обычная цена", "пора брать" in text and "обычно" in text, text)
    check("сумма закупки посчитана", "примерно" in text, text)

    mute_data = button_for("shopping_mute:", bread)
    check("у товара есть кнопка «не напоминать»", bool(mute_data), text)

    sent = await run(callback=mute_data, label="не напоминать про хлеб")
    text = "\n".join(sent)
    check("товар отключён и виден в строке отключённых",
          "Отключены из напоминаний" in text and bread in text, text)
    check("отключённый товар больше не в списке", not listed(text, bread), text)
    check("соседний товар отключение не задело", listed(text, "Молоко"), text)

    back_data = button_for("shopping_unmute:", bread)
    check("есть кнопка вернуть товар", bool(back_data), text)
    sent = await run(callback=back_data, label="вернуть хлеб в напоминания")
    text = "\n".join(sent)
    check("товар вернулся в список", listed(text, bread), text)

    bought_data = button_for("shopping_bought:", bread)
    check("у товара есть кнопка «уже купил»", bool(bought_data), text)
    sent = await run(callback=bought_data, label="отметить хлеб купленным")
    text = "\n".join(sent)
    check("отметка «уже купил» убрала товар из списка",
          "Уже отмечено" in text and not listed(text, bread), text)
    check("соседний товар отметка не спрятала", listed(text, "Молоко"), text)

    print("— Недельный дайджест —")
    sent = await run(callback="report_digest", label="дайджест недели")
    text = "\n".join(sent)
    check("дайджест собрался", "Недельный дайджест" in text, text)
    check("в дайджесте есть траты недели", "Потрачено" in text or "трат не записано" in text,
          text)
    # Товар, отмеченный кнопкой «уже купил», не должен возвращаться в дайджесте — иначе
    # отметка обманывает: человек уже сказал, что купил, а его снова просят.
    check("отмеченный товар не просят купить снова", "Хлеб Бородинский" not in text, text)
    # Дайджест смотрит длинную историю: подписки и прогноз продуктов требуют недель данных,
    # а не двухнедельного окна сравнения.
    check("в дайджесте видно списание подписки",
          "Спишется" in text and "Netflix" in text, text)


    print("— Карточка товара (/price) —")
    sent = await run(text="/price молоко", label="/price с одним товаром")
    text = "\n".join(sent)
    check("карточка товара открылась",
          "Обычная цена" in text and "Молоко Простоквашино" in text, text)
    check("в карточке видна история покупок",
          "История покупок" in text and "К&Б" in text, text)
    check("в карточке виден срок закупки", "Пора брать" in text, text)

    sent = await run(text="/price кола", label="/price без совпадений")
    check("неизвестный товар — честный отказ",
          any("не нашлось" in t for t in sent), f"ответы: {sent}"
    )
    sent = await run(text="/price", label="/price без названия")
    check("без названия бот подсказывает формат",
          any("/price молоко" in t for t in sent), f"ответы: {sent}")

    # Второй похожий товар: бот не угадывает, какой имел в виду человек, а показывает варианты.
    async with aiosqlite.connect(_db_path) as db:
        when = (datetime.now() - _delta(days=6)).isoformat(sep=" ")
        cursor = await db.execute(
            "INSERT INTO transactions "
            "(user_id, amount, category, description, tx_type, source, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            (1111, 80, "еда", "Магнит", "expense", "receipt", when))
        await db.execute("INSERT INTO receipt_items (transaction_id, name, qty, price, sum) "
                         "VALUES (?, ?, 1, ?, ?)",
                         (cursor.lastrowid, "Молоко Яркино 1л", 80, 80))
        await db.commit()
    sent = await run(text="/price молоко", label="/price с двумя товарами")
    text = "\n".join(sent)
    check("два товара — бот показывает варианты", "несколько товаров" in text, text)
    check("в вариантах есть оба названия",
          "Простоквашино" in text and "Яркино" in text, text)

    print("— Недельный лимит на продукты —")
    sent = await run(callback="budget_set:food_week", label="кнопка «Продукты в неделю»")
    check("предложен ввод недельного лимита",
          any("Недельный лимит на продукты" in t and "семи дням" in t for t in sent),
          f"ответы: {sent}")
    sent = await run(text="4000", label="недельный лимит 4 000")
    check("недельный лимит сохранён",
          any("4 000" in t and "лимит на продукты" in t.lower() for t in sent), f"ответы: {sent}")
    check("лимит записался пользователю", await weekly_food_limit(1111) == 4000,
          str(await weekly_food_limit(1111)))
    check("лимит личный: у второго пользователя пусто",
          await weekly_food_limit(2222) == 0, str(await weekly_food_limit(2222)))
    kb = session.markup()
    buttons = [button.text for row in kb.inline_keyboard for button in row] if kb else []
    check("в бюджете видно недельный лимит",
          any("Продукты в неделю: 4 000 ₽" in text for text in buttons), str(buttons))

    # Лимит сообщается в момент покупки, а не только в сводке и дайджесте: в них он приходит
    # через день, когда чек уже не на экране и цифра ни с чем не связывается.
    await run(text="Пятёрочка 500", label="текст «Пятёрочка 500»")
    sent = await run(callback="confirm_expense", label="запись еды с заданным лимитом")
    check("после покупки еды видно недельный лимит",
          any("Продукты" in t and "4 000" in t for t in sent), f"ответы: {sent}")
    await run(text="Такси 300", label="текст «Такси 300»")
    sent = await run(callback="confirm_expense", label="запись транспорта с заданным лимитом")
    check("после непищевой траты лимит продуктов не при чем",
          not any("Продукты" in t and "4 000" in t for t in sent), f"ответы: {sent}")
    # Перерасход виден в момент покупки и тут же подсказано, где лимит поправить.
    await run(text="Пятёрочка 9000", label="текст «Пятёрочка 9000»")
    sent = await run(callback="confirm_expense", label="перерасход недельного лимита")
    check("при перерасходе лимит назван и есть где его поправить",
          any("превышен" in t and "Поправить: ⚙️ Настройки" in t for t in sent), f"ответы: {sent}")

    from handlers.main_menu import build_dashboard
    dashboard = await build_dashboard("Тест", 1111)
    check("на главном экране видна строка про продукты с лимитом",
          "Продукты" in dashboard and "из 4 000 ₽" in dashboard, dashboard)
    empty_dashboard = await build_dashboard("Новичок", 777777)
    check("при пустой истории главный экран не печатает строку про продукты",
          "Продукты" not in empty_dashboard, empty_dashboard)

    # Дайджест, собранный уже с лимитом, обязан о нём сказать.
    sent = await run(callback="report_digest", label="дайджест с заданным лимитом")
    # Проверяется именно лимит и его сумма, а не формулировка: она зависит от состояния
    # лимита (превышен / почти / в рамках), и тест на слово ломался бы от новых данных.
    check("дайджест помнит про недельный лимит",
          any("на продукты" in t.lower() and "4 000 ₽" in t for t in sent), f"ответы: {sent}")

    print("— Личная инфляция —")
    sent = await run(callback="report_inflation", label="личная инфляция без истории")
    check("экран инфляции честно говорит, что данных мало",
          any("считать нечего" in t for t in sent), f"ответы: {sent}")

    # Три товара с прежними ценами: молоко и хлеб подорожали, сыр подешевел сильнее —
    # взвешенный по тратам индекс должен уйти в минус.
    async with aiosqlite.connect(_db_path) as db:
        old_rows = [("Молоко Простоквашино 3,2% 930мл", 90, 150), ("Молоко Простоквашино 3,2% 930мл", 90, 120),
                    ("Хлеб Бородинский 400г", 40, 150), ("Хлеб Бородинский 400г", 40, 120),
                    ("Сыр Российский 200г", 300, 150), ("Сыр Российский 200г", 300, 120)]
        for name, price, day in old_rows:
            when = (datetime.now() - _delta(days=day)).isoformat(sep=" ")
            cursor = await db.execute(
                "INSERT INTO transactions "
                "(user_id, amount, category, description, tx_type, source, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (1111, price, "еда", "Пятёрочка", "expense", "receipt", when))
            await db.execute("INSERT INTO receipt_items (transaction_id, name, qty, price, sum) "
                             "VALUES (?, ?, 1, ?, ?)", (cursor.lastrowid, name, price, price))
        when = (datetime.now() - _delta(days=4)).isoformat(sep=" ")
        cursor = await db.execute(
            "INSERT INTO transactions "
            "(user_id, amount, category, description, tx_type, source, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)", (1111, 270, "еда", "Магнит", "expense", "receipt", when))
        await db.execute("INSERT INTO receipt_items (transaction_id, name, qty, price, sum) "
                         "VALUES (?, ?, 1, ?, ?)", (cursor.lastrowid, "Сыр Российский 200г", 270, 270))
        await db.commit()

    sent = await run(callback="report_inflation", label="личная инфляция с корзиной")
    text = "\n".join(sent)
    # Точное значение индекса зависит от ранее записанных чеков — проверяем структуру
    # ответа, а арифметику индекса проверяет смоук-тест на своих числах.
    check("корзина посчитана по трём товарам", "Корзина: 3 товаров" in text, text)
    check("видно, во сколько корзина обходится сейчас",
          "По нынешним" in text and "₽" in text, text)
    check("в тексте есть подорожавший товар",
          "Подорожало сильнее всего" in text and "Хлеб Бородинский" in text, text)
    check("в тексте объяснён источник цифр", "а не официальная статистика" in text, text)

    print("— Необязательные покупки —")
    # Разбор чека уже сохранён вместе с позициями: вредная позиция и лишняя должны попасть
    # в отчёт, а совет по позиции — читаться вместе с ней.
    async with aiosqlite.connect(_db_path) as db:
        when = (datetime.now() - _delta(days=3)).isoformat(sep=" ")
        cursor = await db.execute(
            "INSERT INTO transactions "
            "(user_id, amount, category, description, tx_type, source, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            (1111, 257, "еда", "Пятёрочка", "expense", "receipt", when))
        transaction_id = cursor.lastrowid
        for name, value in (("Молоко Простоквашино 3,2% 930мл", 100), ("Чипсы Lays 120г", 150),
                            ("Пакет-майка", 7)):
            await db.execute("INSERT INTO receipt_items (transaction_id, name, qty, price, sum) "
                             "VALUES (?, ?, 1, ?, ?)", (transaction_id, name, value, value))
        # Прошлая неделя с своим разбором: без двух недель динамики не бывает.
        older = (datetime.now() - _delta(days=9)).isoformat(sep=" ")
        cursor = await db.execute(
            "INSERT INTO transactions "
            "(user_id, amount, category, description, tx_type, source, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            (1111, 300, "еда", "Магнит", "expense", "receipt", older))
        older_id = cursor.lastrowid
        # Чипсы второй раз: только привычка (два и больше разборов) даёт потолок экономии.
        for name, value in (("Сок Добрый 1л", 100), ("Сухарики Кириешки", 200),
                            ("Чипсы Lays 120г", 150)):
            await db.execute("INSERT INTO receipt_items (transaction_id, name, qty, price, sum) "
                             "VALUES (?, ?, 1, ?, ?)", (older_id, name, value, value))
        # Товар с историей до совета: куплен дважды раньше, потом совет — и больше не покупался.
        # Вердикты есть только у последней покупки: разборы начались позже, и это как раз то,
        # по чему видно, изменилась ли частота после совета.
        for days_back in (100, 90, 80):
            when_old = (datetime.now() - _delta(days=days_back)).isoformat(sep=" ")
            cursor = await db.execute(
                "INSERT INTO transactions "
                "(user_id, amount, category, description, tx_type, source, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (1111, 120, "еда", "Лента", "expense", "receipt", when_old))
            await db.execute(
                "INSERT INTO receipt_items (transaction_id, name, qty, price, sum, verdict, advice) "
                "VALUES (?, ?, 1, ?, ?, ?, ?)",
                (cursor.lastrowid, "Чипсы Pringles 140г", 120, 120,
                 "вредно" if days_back == 80 else None,
                 "заменить на овощи" if days_back == 80 else ""))
        await db.commit()
    from database.db import save_receipt_verdicts
    await save_receipt_verdicts(transaction_id, [
        ("Молоко Простоквашино 3,2% 930мл", "полезно", ""),
        ("Чипсы Lays 120г", "вредно", "заменить на овощи"),
        ("Пакет-майка", "лишнее", ""),
    ])
    await save_receipt_verdicts(older_id, [("Сок Добрый 1л", "полезно", ""),
                                           ("Сухарики Кириешки", "вредно", ""),
                                           ("Чипсы Lays 120г", "вредно", "")])
    sent = await run(callback="report_waste", label="необязательные покупки с разбором")
    text = "\n".join(sent)
    # Проверка про смысл, а не про конкретный товар: в отчёте должны быть обе группы вердиктов,
    # а в топ по сумме попадают самые дорогие разобранные позиции, и их состав зависит от чека.
    check("в отчёте видно вредное и лишнее",
          "❌ Лучше сократить" in text and "🗑 Можно было не брать" in text, text)
    check("дорогие разобранные позиции попадают в отчёт по сумме",
          "Чипсы Lays 120г" in text and "Сухарики Кириешки" in text, text)
    check("совет по позиции сохранён вместе с вердиктом", "заменить на овощи" in text, text)
    check("отчёт называет, чем он не является", "не оценка экономии" in text, text)
    check("полезные позиции в необязательные не попали", "Молоко Простоквашино" not in text, text)
    check("в отчёте видна динамика по неделям",
          "Доля необязательного по неделям" in text and "→" in text, text)
    # Динамику видно не только текстом: к отчёту приходит та же картинка, что панель открывает
    # по кнопке. По одной неделе она не рисуется — это проверено ниже, на экране без разборов.
    # Потолок экономии считается только по привычкам: чипсы пришли дважды, пакет — один раз,
    # и в прогноз должны попасть именно чипсы, 300 ₽ за 90 дней = ~100 ₽ в месяц.
    check("в отчёте есть потолок экономии",
          "Сколько это в месяц" in text and "~100 ₽" in text, text)
    check("потолок назван оговоркой, а не обещанием",
          "потолок" in text and "не обещание" in text, text)
    # Масштаб: потолок сравнивается с месячным лимитом бота — тем же, что считает бюджет.
    check("потолок показан в долях лимита",
          "Для масштаба" in text and "месячного лимита" in text, text)
    # Эффект советов: товар, который до совета брали, а после — нет, назван именно так.
    check("в отчёте видно, что стало после совета",
          "Что было с этими товарами после совета" in text and "Чипсы Pringles 140г" in text, text)
    # Эффект сравнивает частоты, а не суммы: «раньше раз в 10 дн., теперь раз в 80 дн.».
    check("эффект назван частотой, а не доказательством",
          "раньше раз в" in text and "теперь раз в" in text and "не доказательство" in text,
          text)
    check("разовая лишняя покупка в прогноз не попала", "Пакет-майка — ~" not in text, text)
    pictures = session.photos()
    check("к отчёту приложена картинка динамики", len(pictures) == 1, f"картинок: {len(pictures)}")
    check("картинка — настоящий PNG",
          bool(pictures) and bytes(pictures[0].data).startswith(b"\x89PNG"),
          f"начало: {bytes(pictures[0].data)[:8] if pictures else None}")

    print("— Личный список «не брать» —")
    # Товар берётся регулярно (значит, его подсказывает список покупок) и дважды попадал
    # в «лишнее»: бот не должен предлагать его купить, а список «не брать» — показать.
    from database.db import save_receipt_verdicts
    marked_receipts = []
    async with aiosqlite.connect(_db_path) as db:
        for days in (40, 25, 10):     # ритм 15 дней: срок подходит как раз сегодня-завтра
            when = (datetime.now() - _delta(days=days)).isoformat(sep=" ")
            cursor = await db.execute(
                "INSERT INTO transactions "
                "(user_id, amount, category, description, tx_type, source, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (1111, 149.90, "досуг", "Пятёрочка", "expense", "receipt", when))
            await db.execute("INSERT INTO receipt_items (transaction_id, name, qty, price, sum) "
                             "VALUES (?, ?, 1, 149.90, 149.90)",
                             (cursor.lastrowid, "Мороженое Эскимо"))
            marked_receipts.append(cursor.lastrowid)
        await db.commit()
    for receipt_id in marked_receipts[-2:]:      # два разбора из трёх — уже привычка
        await save_receipt_verdicts(
            receipt_id, [("Мороженое Эскимо", "лишнее", "десерт → заменить на фрукты")])

    sent = await run(callback="report_shopping", label="список покупок с личным «не брать»")
    text = "\n".join(sent)
    check("товар из «не брать» не предлагают брать",
          "• **Мороженое Эскимо**" not in text, text)
    check("в списке видно, что товар убран и почему", "Убрано из списка" in text, text)

    sent = await run(callback="report_bans", label="экран «не брать»")
    text = "\n".join(sent)
    check("в списке «не брать» видно товар, число разборов и совет",
          "Мороженое Эскимо" in text and "2 раза" in text
          and "заменить на фрукты" in text, text)
    kb = session.markup()
    buttons = {b.callback_data for row in kb.inline_keyboard for b in row} if kb else set()
    # Кнопка адресуется отпечатком ключа товара. Ищем кнопку именно мороженого, а не
    # «первую попавшуюся»: в списке есть и другие товары, и тогда тест разрешил бы не тот.
    from services import mutelist as mutelist_service
    from services.purchase_history import product_key
    allow_button = f"ban_allow:{mutelist_service.digest(product_key('Мороженое Эскимо'))}"
    check("из списка можно вернуть товар кнопкой", allow_button in buttons, str(buttons))

    sent = await run(callback=allow_button, label="«Всё равно напоминать»")
    text = "\n".join(sent)
    check("разрешённый товар назван разрешённым",
          "Разрешено брать всё равно" in text and "Мороженое Эскимо" in text, text)
    sent = await run(callback="report_shopping", label="список покупок после разрешения")
    text = "\n".join(sent)
    check("разрешённый товар вернулся в список покупок", "Мороженое Эскимо" in text, text)
    check("в списке покупок больше не сказано про «не брать»", "Убрано из списка" not in text, text)

    print("— Спор с разбором —")
    # Разбор ошибается («колбаса» как пакет), и спорить с ним человек должен там, где совет
    # на экране, — одним тапом. Правка идёт в тот же ключ «разрешено», что и список «не брать»:
    # второго механизма поправок у бота нет.
    from database.db import get_receipt_items
    from keyboards.expense_kb import REVIEW_FIX_LIMIT
    from services.advice import allowed_keys

    # Тест не должен зависеть от того, поднята ли в этот момент локальная модель: после
    # перезапуска машины Ollama лежит, разбор корзины честно возвращает None — и спорить
    # было бы не с чем. Подменяем ответ модели canned-разбором с оценкой модели, дальше
    # бот ведёт себя как с настоящим разбором: правила поверх, кнопки спора под сообщением.
    import handlers.expenses as expenses_module
    from ai.receipts import verdict_rows

    async def _canned_basket(items: list[dict], store: str = "") -> dict:
        verdicts = {index: {"verdict": "вредно", "advice": "сладкое заменить", "source": "model"}
                    for index, row in enumerate(items, start=1)
                    if (row.get("name") or "").strip().upper().startswith("ЧИПСЫ")}
        return {"items": verdicts} if verdicts else None

    real_basket = expenses_module.analyze_basket
    expenses_module.analyze_basket = _canned_basket


    session.photo_override = synthetic_receipt_bytes()
    before_fix = await receipt_ids()
    await run(photo=True, label="продуктовый чек для спора с разбором")
    sent = await run(callback="confirm_expense", label="запись чека для спора")
    expenses_module.analyze_basket = real_basket
    fix_tx = sorted(await receipt_ids())[-1]
    rows = [dict(row) for row in await get_receipt_items(fix_tx)]
    # Происхождение вердикта сохраняется вместе с позицией: без этого отчёт не смог бы
    # отличить проверку по названию от чтения кассовой строки моделью.
    check("происхождение вердикта записано в позиции чека",
          all(row["verdict_source"] in ("rule", "model", "default") for row in rows)
          and any(row["verdict_source"] == "rule" for row in rows),
          str([(row["name"], row["verdict_source"]) for row in rows]))

    text = "\n".join(sent)
    # Экран здесь — разбор корзины при подтверждении: он печатает «Откуда вердикты»
    # теми же словами, что и отчёт о необязательных покупках (его формулировки
    # «Из … необязательного: … проверка по правилам» отдельно проверены в смоук-тесте).
    check("в разборе видно происхождение необязательного",
          "Откуда вердикты" in text and "проверка по названию" in text, text)
    waste_rows = [row for row in rows if (row["verdict"] or "") in ("вредно", "лишнее")]
    check("необязательные позиции в чеке есть", bool(waste_rows),
          str([(row["name"], row["verdict"]) for row in rows]))

    # Кнопки спора ищем на последнем сообщении с инлайн-клавиатурой: правка адресуется
    # id позиции из базы, поэтому набор кнопок и есть перечень спорного.
    kb = session.markup()
    fix_buttons = sorted(str(b.callback_data) for row in (kb.inline_keyboard if kb else [])
                         for b in row if str(b.callback_data).startswith("review_ok:"))
    check("под разбором есть кнопка спора по позиции", bool(fix_buttons), str(fix_buttons))
    check("кнопок спора не больше предела", len(fix_buttons) <= REVIEW_FIX_LIMIT,
          f"кнопок: {len(fix_buttons)}")
    offered = {int(data.split(":")[2]) for data in fix_buttons}
    check("спорить предлагают только с необязательными позициями",
          offered == {row["id"] for row in waste_rows},
          f"предложено {offered}, необязательных {[row['id'] for row in waste_rows]}")
    check("полезная позиция в спор не предлагается",
          all((row["verdict"] or "") != "полезно"
              for row in rows if row["id"] in offered),
          str([(row["name"], row["verdict"]) for row in rows]))

    target = waste_rows[0]
    # Нажимаем ту самую кнопку, что бот прислал: её данные несут и страницу, и id позиции.
    press = next(data for data in fix_buttons if data.split(":")[2] == str(target["id"]))
    sent = await run(callback=press, label="«Не согласен» с разбором")
    text = "\n".join(sent)
    check("бот подтверждает правку и путь назад",
          "Учёл" in text and target["name"] in text and "🚫 Не брать" in text, text)
    fixed_key = product_key(target["name"])
    check("правка — это тот же ключ «разрешено», что в списке «не брать»",
          fixed_key in await allowed_keys(1111), str(await allowed_keys(1111)))
    # Снятая кнопка: позицию, которую человек уже поправил, второй раз не предлагают.
    edits = session.markup_edits()
    left = {str(b.callback_data) for edge in edits if edge for row in edge.inline_keyboard
            for b in row}
    check("кнопка спора снята вместе с правкой",
          not any(data.startswith(f"review_ok:{fix_tx}:{target['id']}:") for data in left),
          str(left))

    sent = await run(callback="report_waste", label="отчёт после правки")
    text = "\n".join(sent)
    check("в отчёте видно, что разбор поправлен",
          "Ты поправил разбор" in text and "разбор ошибся" in text, text)
    check("поправленный товар назван в отчёте", target["name"] in text, text)

    # Длинный список спорных позиций листается: без страниц у чека с десятком необязательных
    # позиций половина поправок была бы недоступна вовсе.
    async with aiosqlite.connect(verdict_db) as db:
        for number in range(1, 9):
            await db.execute(
                "INSERT INTO receipt_items (transaction_id, name, qty, price, sum, verdict, advice)"
                " VALUES (?, ?, 1, ?, ?, 'лишнее', '')",
                (fix_tx, f"Спорная позиция {number}", 10.0 + number, 10.0 + number))
        await db.commit()

    async def page_buttons(page: int) -> set[str]:
        await run(callback=f"review_page:{fix_tx}:{page}", label=f"страница спорных {page + 1}")
        markup = next((edge for edge in reversed(session.markup_edits()) if edge), None)
        return {str(b.callback_data) for row in markup.inline_keyboard for b in row} if markup else set()

    first = await page_buttons(0)
    check("страница спорных показывает не больше предела",
          len([data for data in first if data.startswith("review_ok:")]) == REVIEW_FIX_LIMIT,
          str(first))
    check("на первой странице есть переход дальше",
          any(data == f"review_page:{fix_tx}:1" for data in first), str(first))
    second = await page_buttons(1)
    check("вторая страница показывает остаток",
          len([data for data in second if data.startswith("review_ok:")]) == 2, str(second))
    check("поправленное не возвращается на страницах",
          not any(data.startswith(f"review_ok:{fix_tx}:{target['id']}:")
                  for data in first | second), str(first | second))

    sent = await run(callback="report_bans", label="экран «не брать» после правки")
    text = "\n".join(sent)
    check("правку можно отменить в списке «не брать»",
          "Разрешено брать всё равно" in text and target["name"] in text, text)
    kb = session.markup()
    buttons = {str(b.callback_data) for row in kb.inline_keyboard for b in row} if kb else set()
    block_button = f"ban_block:{mutelist_service.digest(fixed_key)}"
    # Правка возможна и с первого разбора, поэтому возврат адресован именно этому товару:
    # порог привычки отмену не ограничивает.
    check("возврат адресован поправленному товару", block_button in buttons, str(buttons))
    await run(callback=block_button, label="«Снова не брать» после правки")
    check("снятая правка возвращает товар в список «не брать»",
          fixed_key not in await allowed_keys(1111), str(await allowed_keys(1111)))

    # За собой убираем: чек нужен был только проверке спора.
    session.photo_override = None
    for receipt_id in (await receipt_ids()) - before_fix:
        await delete_transaction(receipt_id, 1111)

    print("— Догадки модели —")
    # Товар с ровным ритмом: список покупок его предлагает, значит видно и обратную сторону.
    # Оба разбора — вердикты модели, правило такого не говорило.
    from services.advice import (allowed_keys, blocked_keys, confirmed_keys, set_allowed,
                                set_confirmed)

    guess_product = "Кофе в зернах Lavazza 1кг"
    guess_key = product_key(guess_product)
    guess_receipts = []
    async with aiosqlite.connect(verdict_db) as db:
        for days in (40, 25, 10):     # ритм 15 дней: пора покупать как раз сейчас
            when = (datetime.now() - _delta(days=days)).isoformat(sep=" ")
            cursor = await db.execute(
                "INSERT INTO transactions "
                "(user_id, amount, category, description, tx_type, source, created_at)"
                " VALUES (?, 899.00, 'еда', 'Пятёрочка', 'expense', 'receipt', ?)", (1111, when))
            await db.execute("INSERT INTO receipt_items (transaction_id, name, qty, price, sum)"
                             " VALUES (?, ?, 1, 899.00, 899.00)", (cursor.lastrowid, guess_product))
            guess_receipts.append(cursor.lastrowid)
        await db.commit()
    for receipt_id in guess_receipts[-2:]:     # два разбора — уже привычка
        await save_receipt_verdicts(
            receipt_id, [(guess_product, "лишнее", "дорогой кофе", "model")])
    await set_confirmed(1111, guess_key, False)
    await set_allowed(1111, guess_key, False)

    sent = await run(callback="report_shopping", label="список покупок с догадкой модели")
    text = "\n".join(sent)
    check("догадка модели товар из списка покупок не убирает",
          guess_product in text, text)
    check("про «убрано» из-за догадки не написано", "Убрано из списка" not in text, text)

    sent = await run(callback="report_bans", label="экран «не брать» с догадкой модели")
    text = "\n".join(sent)
    check("догадка названа догадкой, а не запретом",
          "говорит только модель" in text and guess_product in text, text)
    check("экран говорит, что бот такие товары не прячет", "не убираю" in text, text)
    kb = session.markup()
    buttons = {str(b.callback_data) for row in kb.inline_keyboard for b in row} if kb else set()
    guess_digest = mutelist_service.digest(guess_key)
    check("догадку можно подтвердить кнопкой", f"ban_confirm:{guess_digest}" in buttons, str(buttons))
    check("догадку можно и отклонить той же кнопкой, что разрешает",
          f"ban_allow:{guess_digest}" in buttons, str(buttons))

    sent = await run(callback=f"ban_confirm:{guess_digest}", label="подтвердить догадку")
    text = "\n".join(sent)
    check("подтверждённая догадка становится «не брать»",
          guess_product in text and "2 раза" in text, text)
    check("подтверждение сохранено", guess_key in await confirmed_keys(1111),
          str(await confirmed_keys(1111)))
    check("подтверждённое блокирует предложение", guess_key in await blocked_keys(1111),
          str(await blocked_keys(1111)))
    sent = await run(callback="report_shopping", label="список покупок после подтверждения")
    text = "\n".join(sent)
    check("подтверждённое убрано из списка покупок", "Убрано из списка" in text, text)

    # После подтверждения товар перешёл в основной список: догадка больше не отдельная строка,
    # и вернуть его можно обычной кнопкой.
    sent = await run(callback="report_bans", label="экран «не брать» после подтверждения")
    text = "\n".join(sent)
    check("подтверждённое больше не числится догадкой",
          "говорит только модель" not in text and guess_product in text, text)
    kb = session.markup()
    buttons = {str(b.callback_data) for row in kb.inline_keyboard for b in row} if kb else set()
    check("подтверждённое можно вернуть", f"ban_allow:{guess_digest}" in buttons, str(buttons))
    await run(callback=f"ban_allow:{guess_digest}", label="вернуть подтверждённый товар")
    check("возврат снимает и подтверждение",
          guess_key not in await confirmed_keys(1111) and guess_key in await allowed_keys(1111),
          f"confirmed={await confirmed_keys(1111)}, allowed={await allowed_keys(1111)}")

    # За собой убираем: чеки и решения нужны были только этой проверке.
    await set_allowed(1111, guess_key, False)
    await set_confirmed(1111, guess_key, False)
    for receipt_id in guess_receipts:
        await delete_transaction(receipt_id, 1111)

    print("— Пересчёт старых разборов —")
    # Ошибка чтения из старого чека: макароны дважды были «лишними», пометки о происхождении нет.
    # Нынешние правила такую позицию отдают в «нейтрально», и пересчёт это говорит вслух.
    old_product = "К.Ц.Изд.мак.ПЕРЬЯ В/С ГР.Б 400г"
    old_key = product_key(old_product)
    old_receipts = []
    async with aiosqlite.connect(verdict_db) as db:
        for days in (40, 25, 10):
            when = (datetime.now() - _delta(days=days)).isoformat(sep=" ")
            cursor = await db.execute(
                "INSERT INTO transactions"
                " (user_id, amount, category, description, tx_type, source, created_at)"
                " VALUES (?, 10.93, 'еда', 'Пятёрочка', 'expense', 'receipt', ?)", (1111, when))
            await db.execute("INSERT INTO receipt_items (transaction_id, name, qty, price, sum)"
                             " VALUES (?, ?, 1, 10.93, 10.93)", (cursor.lastrowid, old_product))
            old_receipts.append(cursor.lastrowid)
        await db.commit()
    for receipt_id in old_receipts[-2:]:
        # Вердикт без пометки происхождения — так выглядит разбор до этой возможности.
        await save_receipt_verdicts(receipt_id, [(old_product, "лишнее", "ненужные перья")])

    sent = await run(callback="report_bans", label="экран «не брать» со старой ошибкой")
    text = "\n".join(sent)
    check("старая ошибка чтения попала в «не брать»", old_product in text, text)
    kb = session.markup()
    buttons = {str(b.callback_data) for row in kb.inline_keyboard for b in row} if kb else set()
    check("есть кнопка пересчёта старых разборов", "recalc_verdicts" in buttons, str(buttons))
    check("в списке покупок товар убран из-за старой ошибки",
          old_key in await blocked_keys(1111), str(await blocked_keys(1111)))

    sent = await run(callback="recalc_verdicts", label="пересчёт старых разборов")
    text = "\n".join(sent)
    check("пересчёт говорит, что вердикт был ошибкой чтения",
          "Пересчитал" in text and "ошибкой чтения" in text, text)
    check("пересчёт называет товар, который перестал быть необязательным",
          "Больше не считаю необязательным" in text and "ПЕРЬЯ" in text, text)
    check("пересчёт говорит, что уходит из «не брать»", "Из «не брать» уходит" in text, text)
    check("пересчёт говорит, чего он не трогал", "не трогались" in text, text)

    async with aiosqlite.connect(verdict_db) as db:
        db.row_factory = aiosqlite.Row
        cursor = await db.execute(
            "SELECT verdict, verdict_source FROM receipt_items WHERE transaction_id = ?",
            (old_receipts[-1],))
        fixed_row = dict(await cursor.fetchone())
    check("вердикт переписан и помечен правилом",
          fixed_row["verdict"] == "нейтрально" and fixed_row["verdict_source"] == "rule",
          str(fixed_row))
    check("после пересчёта товар больше не блокирует предложение",
          old_key not in await blocked_keys(1111), str(await blocked_keys(1111)))
    sent = await run(callback="report_shopping", label="список покупок после пересчёта")
    check("товар вернулся в список покупок", old_product in "\n".join(sent), "\n".join(sent))
    sent = await run(callback="report_waste", label="отчёт после пересчёта")
    text = "\n".join(sent)
    check("в отчёте больше нет макарон в необязательном", "ПЕРЬЯ" not in text, text)
    # Без этой оговорки отчёт выдавал бы правку разбора за изменение привычек.
    check("отчёт говорит, что часть движения — от пересчёта, а не от покупок",
          "Оговорка к динамике" in text and "а не твои покупки" in text, text)
    # Пересчёт — разовое действие человека: когда правилам нечего добавить, кнопка исчезает.
    await run(callback="report_bans", label="экран «не брать» после пересчёта")
    kb = session.markup()
    buttons = {str(b.callback_data) for row in kb.inline_keyboard for b in row} if kb else set()
    check("после пересчёта кнопка исчезает", "recalc_verdicts" not in buttons, str(buttons))

    # За собой убираем: чеки нужны были только этой проверке.
    for receipt_id in old_receipts:
        await delete_transaction(receipt_id, 1111)

    print("— Цель на месяц —")
    # Привычка, которую разбор называл необязательной не один раз и которая берётся регулярно:
    # только по такой цели и есть что измерять. Вердикты — от правил, а не от оценки модели.
    from aiogram.methods import AnswerCallbackQuery
    from services.advice import stored_goal

    habit_product = "Мороженое Carte D'Or 500мл"
    habit_key = product_key(habit_product)
    habit_receipts = []
    async with aiosqlite.connect(verdict_db) as db:
        for days in (2, 16, 30, 44):
            when = (datetime.now() - _delta(days=days)).isoformat(sep=" ")
            cursor = await db.execute(
                "INSERT INTO transactions"
                " (user_id, amount, category, description, tx_type, source, created_at)"
                " VALUES (?, 150.00, 'еда', 'Пятёрочка', 'expense', 'receipt', ?)", (1111, when))
            await db.execute("INSERT INTO receipt_items (transaction_id, name, qty, price, sum)"
                             " VALUES (?, ?, 1, 150.00, 150.00)",
                             (cursor.lastrowid, habit_product))
            habit_receipts.append(cursor.lastrowid)
        await db.commit()
    for receipt_id in habit_receipts[-2:]:
        await save_receipt_verdicts(receipt_id, [(habit_product, "вредно", "сладкое", "rule")])

    await run(text="📊 Отчёт", label="меню отчётов перед целью")
    kb = session.markup()
    buttons = {str(b.callback_data) for row in kb.inline_keyboard for b in row} if kb else set()
    check("цель достижима из меню отчётов, а не только из дайджеста",
          "report_goal" in buttons, str(buttons))

    sent = await run(callback="report_goal", label="экран цели с предложением")
    text = "\n".join(sent)
    check("бот предлагает цель по привычке, а не общими словами",
          "Цель на месяц" in text and habit_product in text, text)
    check("предложение называет шаг и обычную частоту", "не чаще" in text and "в месяц" in text, text)
    kb = session.markup()
    buttons = {str(b.callback_data) for row in kb.inline_keyboard for b in row} if kb else set()
    # Кнопка адресуется отпечатком ключа товара: в списке кандидатов могут быть и другие
    # товары, и номер строки указывал бы уже не на тот.
    habit_button = f"goal_take:{mutelist_service.digest(habit_key)}"
    check("цель берётся кнопкой именно этого товара", habit_button in buttons, str(buttons))

    sent = await run(callback=habit_button, label="взять цель")
    text = "\n".join(sent)
    goal_state = await stored_goal(1111)
    check("цель сохранена именно по этому товару",
          bool(goal_state) and goal_state["key"] == habit_key, str(goal_state))
    check("экран цели называет день окончания, а не «весь месяц»",
          "🎯 Цель до" in text and "обычная частота" in text, text)
    check("экран цели говорит, как она считается и что это не запрет",
          "Считаю по чекам" in text and "в этом окне потрачено" in text, text)
    kb = session.markup()
    buttons = {str(b.callback_data) for row in kb.inline_keyboard for b in row} if kb else set()
    check("поставленную цель можно убрать", "goal_drop" in buttons, str(buttons))

    # Кнопка не должна переживать сам список: отпечаток устаревшего предложения не берёт цель.
    sent = await run(callback="goal_take:deadbeefdead", label="устаревшее предложение")
    alarms = [c.text for c in session.calls
              if isinstance(c, AnswerCallbackQuery) and c.text]
    check("устаревшая кнопка не ставит чужую цель",
          any("устарел" in (item or "") for item in alarms), str(alarms))
    check("устаревшая кнопка цель не меняет",
          (await stored_goal(1111))["key"] == habit_key, str(await stored_goal(1111)))

    # Единица счёта — предпочтение человека: шаг можно задать в разах или в деньгах, и это
    # одна и та же цель, а не два разных обещания.
    from services.advice import GOAL_SUM, goal_unit

    check("по умолчанию шаг считается в разах", await goal_unit(1111) == "count",
          await goal_unit(1111))

    # Единица счёта — предпочтение человека. Смена единицы не переписывает уже данное
    # обещание: у активной цели ни кандидатов, ни переключателя — менять нечего.
    sent = await run(callback="report_goal", label="экран цели со сменой единицы")
    kb = session.markup()
    buttons = {str(b.callback_data) for row in kb.inline_keyboard for b in row} if kb else set()
    check("у активной цели нет переключателя единицы",
          not any(str(b).startswith("goal_unit:") for b in buttons), str(buttons))

    await run(callback="goal_drop", label="снять цель перед сменой единицы")
    sent = await run(callback="report_goal", label="экран предложений в разах")
    kb = session.markup()
    buttons = {str(b.callback_data) for row in kb.inline_keyboard for b in row} if kb else set()
    check("на экране предложений можно переключить единицу счёта",
          "goal_unit:sum" in buttons, str(buttons))
    check("в разах шаг сформулирован числом раз",
          "не чаще" in "\n".join(sent), "\n".join(sent))

    sent = await run(callback="goal_unit:sum", label="считать в деньгах")
    text = "\n".join(sent)
    check("в деньгах шаг сформулирован суммой",
          "не больше" in text and "₽ в месяц" in text and "не чаще" not in text, text)
    check("экран объясняет, в какой единице считает",
          "Считаю **в деньгах**" in text, text)
    check("единица сохранена", await goal_unit(1111) == GOAL_SUM, await goal_unit(1111))

    sent = await run(callback="goal_unit:чепуха", label="неизвестная единица")
    alarms = [c.text for c in session.calls
              if isinstance(c, AnswerCallbackQuery) and c.text]
    check("неизвестная единица не меняет настройку и объясняет отказ",
          await goal_unit(1111) == GOAL_SUM and any("Не понял" in (item or "")
                                                    for item in alarms), str(alarms))

    # Цель в деньгах считается по рублям, а не по числу покупок.
    await run(callback="report_goal", label="экран предложений в деньгах")
    sent = await run(callback=habit_button, label="взять цель в деньгах")
    money_goal = await stored_goal(1111)
    check("цель взята в денежной единице",
          bool(money_goal) and money_goal["unit"] == GOAL_SUM and money_goal["limit"] > 0,
          str(money_goal))
    text = "\n".join(sent)
    check("экран денежной цели говорит суммами",
          "не больше" in text and "₽ из" in text, text)
    check("экран показывает тот же шаг в других единицах",
          "Это примерно не чаще" in text, text)
    sent = await run(callback="report_month", label="месячный отчёт с денежной целью")
    check("месячный отчёт повторяет шаг в деньгах",
          "не больше" in "\n".join(sent), "\n".join(sent))
    await run(text="📊 Отчёт", label="меню перед снятием денежной цели")
    await run(callback="report_goal", label="экран цели перед снятием")
    await run(callback="goal_drop", label="убрать денежную цель")
    check("денежная цель снята", await stored_goal(1111) is None)
    await run(callback="report_goal", label="экран предложений после денежной цели")
    await run(callback="goal_unit:count", label="вернуться к разам")
    check("единицу можно вернуть", await goal_unit(1111) == "count", await goal_unit(1111))
    # Возвращаем активную цель для проверок ниже: кнопок других целей у неё быть не должно.
    await run(callback=habit_button, label="взять цель обратно в разах")

    # У активной цели нет кнопок других целей: на экране их не видно, и кнопка вела бы
    # к тому, чего человек не читал.
    sent = await run(callback="report_goal", label="экран активной цели")
    kb = session.markup()
    buttons = {str(b.callback_data) for row in kb.inline_keyboard for b in row} if kb else set()
    check("активная цель не предлагает себя заменить",
          not any(str(b).startswith("goal_take:") for b in buttons), str(buttons))

    # Закончившаяся цель: итог и следующее предложение на одном экране — без «сначала убери».
    import json as _json
    from database.db import get_setting, set_setting
    from services.advice import GOAL_KEY, goal_progress as _progress

    goal_key = GOAL_KEY.format(user_id=1111)

    async def set_goal_start(days_ago: int) -> None:
        """Подвинуть начало цели — так в тесте появляется цель, у которой окно уже прошло."""
        raw = _json.loads(await get_setting(goal_key, ""))
        raw["started_at"] = (datetime.now() - _delta(days=days_ago)).isoformat(sep=" ")
        raw.pop("announced_at", None)
        await set_setting(goal_key, _json.dumps(raw))

    await set_goal_start(40)
    sent = await run(callback="report_goal", label="экран закончившейся цели")
    text = "\n".join(sent)
    check("закончившаяся цель называет исход", "🎯 Цель (" in text, text)
    check("закончившаяся цель сразу предлагает следующую",
          "Можно взять следующую" in text, text)
    kb = session.markup()
    buttons = {str(b.callback_data) for row in kb.inline_keyboard for b in row} if kb else set()
    check("новую цель можно взять, не снимая старую",
          habit_button in buttons and "goal_drop" in buttons, str(buttons))

    sent = await run(callback=habit_button, label="взять цель поверх закончившейся")
    replaced = await stored_goal(1111)
    check("новая цель заменяет закончившуюся",
          bool(replaced) and not _progress(replaced, [], today=datetime.now())["finished"],
          str(replaced))

    # Итог в дайджесте говорится один раз: отметку ставит планировщик после удачной отправки.
    from services.advice import mark_goal_outcome_sent

    await set_goal_start(40)

    await run(text="📊 Отчёт", label="меню перед дайджестом")
    sent = await run(callback="report_digest", label="экран дайджеста с итогом")
    check("дайджест-экран говорит про исход цели",
          "🎯 Цель (" in "\n".join(sent), "\n".join(sent))
    settings_snapshot = _json.loads(await get_setting(goal_key, ""))
    check("просмотр дайджеста отметку не ставит",
          "announced_at" not in settings_snapshot, str(settings_snapshot))
    await mark_goal_outcome_sent(1111)
    check("после отправки сводки отметка стоит",
          "announced_at" in _json.loads(await get_setting(goal_key, "")),
          await get_setting(goal_key, ""))
    sent = await run(callback="report_digest", label="дайджест после отметки")
    check("сказанный один раз итог больше не повторяется",
          "🎯 Цель (" not in "\n".join(sent), "\n".join(sent))

    # Возвращаем цель в активное состояние, чтобы убрать её так, как это делает человек.
    await set_goal_start(0)

    sent = await run(callback="goal_drop", label="убрать цель")
    check("цель убрана решением человека", await stored_goal(1111) is None)
    check("без цели бот снова показывает предложение", "не чаще" in "\n".join(sent),
          "\n".join(sent))

    # История итогов: после снятия цели от неё должно остаться то, что можно посчитать.
    from services.advice import goal_history, goal_history_line

    entries = await goal_history(1111)
    check("итог закончившейся цели попал в историю при открытии экрана",
          len(entries) == 1 and entries[0]["name"] == habit_product, str(entries))
    check("история помнит исход, а не только название",
          entries and "met" in entries[0] and entries[0]["closed_at"], str(entries))
    sent = await run(callback="report_goal", label="экран без цели с историей")
    text = "\n".join(sent)
    check("экран без цели показывает счёт по истории",
          f"🏁 Сдержано {sum(1 for item in entries if item['met'])} из {len(entries)}" in text,
          text)
    check("история называет товар и исход",
          habit_product in text and "не вышло" in text, text)
    check("формулировка счёта — из советника, а не своя",
          goal_history_line(entries) in text, text)
    # Историю можно убрать только вместе с базой: она — память, а не состояние экрана.
    await run(callback="report_goal", label="повторный вход в экран цели")
    check("история не задваивается при каждом заходе",
          len(await goal_history(1111)) == 1, str(await goal_history(1111)))

    # Обычный месячный отчёт тоже говорит о целях: без этого счёт видел только тот, кто сам
    # откроет экран цели.
    await run(text="📊 Отчёт", label="меню перед месячным отчётом")
    sent = await run(callback="report_month", label="месячный отчёт без цели")
    month_text = "\n".join(sent)
    check("месячный отчёт показывает счёт по прошлым целям",
          f"🏁 Сдержано {sum(1 for item in entries if item['met'])} из {len(entries)}"
          in month_text, month_text)
    check("в месячном отчёте счёт не подменяет ход активной цели",
          "🎯 Цель до" not in month_text, month_text)

    await run(callback="report_goal", label="экран цели перед вторым отчётом")
    await run(callback=habit_button, label="взять цель для проверки отчёта")
    sent = await run(callback="report_month", label="месячный отчёт с активной целью")
    month_text = "\n".join(sent)
    check("месячный отчёт показывает ход активной цели",
          "🎯 Цель до" in month_text and habit_product in month_text, month_text)
    # С активной целью отчёт говорит о ней, а счёт по прошлым — только когда цели нет:
    # счёт по прошлым месяцам к текущему месяцу не относится и удлинял бы отчёт зря.
    check("с активной целью отчёт не добавляет счёт по прошлым",
          "🏁 Сдержано" not in month_text, month_text)
    await run(callback="report_goal", label="экран цели после отчёта")
    await run(callback="goal_drop", label="убрать цель после проверки отчёта")
    check("цель после проверки отчёта снята", await stored_goal(1111) is None)

    # За собой убираем: чеки и решения нужны были только этой проверке.
    for receipt_id in habit_receipts:
        await delete_transaction(receipt_id, 1111)

    print("— Прогноз продуктов —")
    # Две спокойные недели в прошлом и заметно более дорогая текущая: обычный темп есть,
    # а последние семь дней его превысили — в сводке должно быть предупреждение.
    async with aiosqlite.connect(_db_path) as db:
        for amount, day in ((1000, 20), (1200, 13), (20_000, 2)):
            when = (datetime.now() - _delta(days=day)).isoformat(sep=" ")
            await db.execute(
                "INSERT INTO transactions "
                "(user_id, amount, category, description, tx_type, source, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (1111, amount, "еда", "Пятёрочка", "expense", "text", when))
        await db.commit()

    summary = await build_daily_summary_text(1111)
    check("сводка предупреждает о продуктах быстрее обычного",
          "Продукты быстрее обычного" in summary, summary)
    check("в предупреждении есть обычная неделя и проценты",
          "обычной недели" in summary and "%" in summary, summary)
    check("сводка помнит про заданный недельный лимит продуктов",
          "Лимит на продукты превышен" in summary, summary)

    sent = await run(callback="report_digest", label="дайджест с прогнозом продуктов")
    check("в дайджесте есть строка про продукты",
          any("Продукты" in t for t in sent), f"ответы: {sent}")
    # Бот сам сообщает об эффекте советов, а не ждёт, пока откроют отчёт.
    digest_text = "\n".join(sent)
    check("в дайджесте есть эффект советов",
          "После советов" in digest_text and "Чипсы Pringles 140г" in digest_text, digest_text)
    check("дайджест не выдаёт частоту за доказательство",
          "не доказательство" in digest_text, digest_text)

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
