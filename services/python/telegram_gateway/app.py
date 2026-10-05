"""Telegram command gateway; financial state remains owned by Java Core."""

from __future__ import annotations

import asyncio
from contextlib import suppress
import os
import re
import secrets
import ssl
import uuid
from datetime import date
from decimal import Decimal, InvalidOperation
from urllib.parse import urlsplit

from aiogram import Bot, Dispatcher, F, Router
from aiogram.filters import Command, CommandObject
from aiogram.fsm.context import FSMContext
from aiogram.fsm.storage.base import BaseStorage
from aiogram.fsm.storage.memory import MemoryStorage
from aiogram.types import (
    CallbackQuery,
    BufferedInputFile,
    InlineKeyboardButton,
    InlineKeyboardMarkup,
    KeyboardButton,
    Message,
    ReplyKeyboardMarkup,
)
from aiogram.webhook.aiohttp_server import SimpleRequestHandler, setup_application
from aiohttp import web

from services.python.telegram_gateway.dedup import (
    DeduplicateUpdates,
    InMemoryUpdateDeduplicator,
    RedisUpdateDeduplicator,
    UpdateDeduplicator,
)
from services.python.telegram_gateway.core_client import TelegramCoreClient, TelegramCoreError
from services.python.telegram_gateway.digest_worker import run_notification_worker
from services.python.presentation.report_renderer import (
    render_personal_inflation,
    render_product_catalog,
    render_report,
    render_shopping_candidates,
)

MENU_BUTTON = "Меню"
QUICK_AMOUNTS = ("500.00", "1000.00", "2000.00", "5000.00")
DRAFT_EDIT_FIELDS = {"amount", "categoryCode", "subcategoryCode", "description", "occurredAt"}
MAIN_MENU = ReplyKeyboardMarkup(
    keyboard=[[KeyboardButton(text=MENU_BUTTON)]],
    resize_keyboard=True,
    is_persistent=True,
)


def build_dispatcher(deduplicator: UpdateDeduplicator | None = None,
                    telegram_core: TelegramCoreClient | None = None,
                    storage: BaseStorage | None = None) -> Dispatcher:
    router = Router(name="finance-telegram-gateway")
    core = telegram_core or TelegramCoreClient(
        os.environ.get("FINANCE_CORE_INTERNAL_URL", ""),
        os.environ.get("FINANCE_TELEGRAM_SERVICE_TOKEN", ""),
    )

    async def start(message: Message, state: FSMContext) -> None:
        await show_tenants(message, state)

    async def show_tenants(message: Message, state: FSMContext) -> None:
        if message.chat.type != "private":
            await message.answer("Финансы доступны только в личном чате с ботом.", reply_markup=MAIN_MENU)
            return
        if message.from_user is None:
            await message.answer("Не удалось определить Telegram-пользователя.", reply_markup=MAIN_MENU)
            return
        try:
            telegram_name = getattr(message.from_user, "full_name", "") or getattr(message.from_user, "username", "")
            tenants = await core.list_telegram_tenants(message.from_user.id, telegram_name)
        except TelegramCoreError as error:
            text = {
                "not_found": "Сначала привяжите аккаунт Finance командой /link из личного чата.",
                "unavailable": "Пространства временно недоступны. Попробуйте позже.",
                "unauthorized": "Сессия бота устарела. Повторите /menu.",
            }.get(error.code, "Не удалось загрузить пространства. Попробуйте позже.")
            await message.answer(text, reply_markup=MAIN_MENU)
            return
        if not tenants:
            await state.clear()
            await message.answer("Для привязанного аккаунта нет активных пространств.", reply_markup=MAIN_MENU)
            return
        if len(tenants) == 1:
            tenant = tenants[0]
            try:
                actor = await core.issue_actor_context(message.from_user.id, tenant["tenantId"])
            except TelegramCoreError:
                await message.answer("Не удалось выбрать пространство. Повторите /menu.", reply_markup=MAIN_MENU)
                return
            if actor.get("tenantId") != tenant["tenantId"]:
                await message.answer("Не удалось выбрать пространство. Повторите /menu.", reply_markup=MAIN_MENU)
                return
            await _save_actor_context(state, actor)
            await _show_dashboard(message, core, actor)
            return

        revision = secrets.token_urlsafe(6)
        await state.set_data({"tenant_choice_revision": revision, "tenant_choice_options": tenants})
        keyboard = InlineKeyboardMarkup(inline_keyboard=[
            [InlineKeyboardButton(text=tenant["displayName"], callback_data=f"space:{revision}:{index}")]
            for index, tenant in enumerate(tenants)
        ])
        await message.answer("Выберите финансовое пространство:", reply_markup=keyboard)

    async def menu(message: Message, state: FSMContext) -> None:
        await show_tenants(message, state)

    async def select_tenant(callback: CallbackQuery, state: FSMContext) -> None:
        parts = (callback.data or "").split(":")
        data = await state.get_data()
        options = data.get("tenant_choice_options", [])
        if len(parts) != 3 or parts[0] != "space" or parts[1] != data.get("tenant_choice_revision"):
            await callback.answer("Список пространств обновился. Вызовите /menu.", show_alert=True)
            return
        try:
            index = int(parts[2])
            tenant = options[index]
        except (ValueError, IndexError, TypeError):
            await callback.answer("Выбор устарел. Вызовите /menu.", show_alert=True)
            return
        if callback.from_user is None:
            await callback.answer("Не удалось определить пользователя.", show_alert=True)
            return
        try:
            actor = await core.issue_actor_context(callback.from_user.id, tenant["tenantId"])
        except (TelegramCoreError, KeyError, TypeError):
            await state.clear()
            await callback.answer("Нет доступа к пространству. Обновите список командой /menu.", show_alert=True)
            return
        if actor.get("tenantId") != tenant["tenantId"]:
            await state.clear()
            await callback.answer("Core вернул другой tenant. Обновите список командой /menu.", show_alert=True)
            return
        await state.clear()
        await _save_actor_context(state, actor)
        await callback.answer()
        if isinstance(callback.message, Message):
            await _show_dashboard(callback.message, core, actor)

    async def help_command(message: Message) -> None:
        await message.answer(
            "Команды: /start, /menu, /help, /link <код>, /add <описание операции>, /history, /debts, "
            "/price [название товара], /shopping, /inflation, /budget [set <family|personal> <category|total|food_week> <amount>|reset|propose|suggest <income>], "
            "/report. /budget propose строит предложение по истории, /budget suggest <income> — по доходу. "
            "Для финансов сначала привяжите аккаунт и выберите пространство.",
            reply_markup=MAIN_MENU,
        )

    async def show_report(message: Message, command: CommandObject, state: FSMContext) -> None:
        if message.chat.type != "private":
            await message.answer("Отчёты доступны только в личном чате с ботом.", reply_markup=MAIN_MENU)
            return
        actor = (await state.get_data()).get("telegram_actor_context")
        if not isinstance(actor, dict) or not isinstance(actor.get("token"), str):
            await message.answer("Сначала выберите пространство командой /menu.", reply_markup=MAIN_MENU)
            return
        try:
            report_request = _parse_report_request(command.args or "")
        except ValueError:
            await message.answer(
                "Формат: /report [month|week|90d|YYYY-MM|YYYY-MM-DD YYYY-MM-DD] [family].",
                reply_markup=MAIN_MENU,
            )
            return
        try:
            report = await core.get_report(actor["token"], **report_request)
        except TelegramCoreError as error:
            response = {
                "unauthorized": "Сессия истекла. Выберите пространство командой /menu.",
                "forbidden": "У вашей роли нет доступа к отчёту.",
                "unavailable": "Отчёт временно недоступен. Попробуйте позже.",
            }.get(error.code, "Не удалось загрузить отчёт. Попробуйте позже.")
            if error.code == "unauthorized":
                await state.clear()
            await message.answer(response, reply_markup=MAIN_MENU)
            return
        if report.get("scope") != report_request["scope"]:
            await message.answer("Отчёт вернул неверную область. Повторите /report позже.", reply_markup=MAIN_MENU)
            return
        try:
            content_type, content = render_report(report)
        except (TypeError, ValueError, KeyError):
            await message.answer("Отчёт получен в неверном формате. Попробуйте позже.", reply_markup=MAIN_MENU)
            return
        if content_type == "image/png":
            await message.answer_photo(
                BufferedInputFile(content, filename="finance-report.png"),
                caption=_report_caption(report),
                reply_markup=MAIN_MENU,
            )
            return
        text = content.decode("utf-8")
        chunks = _telegram_text_chunks(text)
        for index, chunk in enumerate(chunks):
            await message.answer(chunk, reply_markup=MAIN_MENU if index == len(chunks) - 1 else None)

    async def show_debts(message: Message, state: FSMContext) -> None:
        if message.chat.type != "private":
            await message.answer("Долги доступны только в личном чате с ботом.", reply_markup=MAIN_MENU)
            return
        actor = (await state.get_data()).get("telegram_actor_context")
        if not isinstance(actor, dict) or not isinstance(actor.get("token"), str):
            await message.answer("Сначала выберите пространство командой /menu.", reply_markup=MAIN_MENU)
            return
        try:
            debts = await core.list_telegram_debts(actor["token"])
        except TelegramCoreError as error:
            response = {
                "unauthorized": "Сессия истекла. Выберите пространство командой /menu.",
                "forbidden": "У вашей роли нет доступа к долгам.",
                "unavailable": "Список долгов временно недоступен. Попробуйте позже.",
            }.get(error.code, "Не удалось загрузить долги. Попробуйте позже.")
            if error.code == "unauthorized":
                await state.clear()
            await message.answer(response, reply_markup=MAIN_MENU)
            return
        if any(debt.get("tenantId") != actor.get("tenantId") for debt in debts):
            await state.clear()
            await message.answer("Список долгов вернул другое пространство. Выберите его командой /menu.",
                                 reply_markup=MAIN_MENU)
            return
        try:
            text = _telegram_debts_text(debts)
        except (InvalidOperation, TypeError, ValueError, KeyError):
            await message.answer("Список долгов получен в неверном формате. Попробуйте позже.",
                                 reply_markup=MAIN_MENU)
            return
        await message.answer(text, reply_markup=MAIN_MENU)

    async def show_product_catalog(message: Message, command: CommandObject, state: FSMContext) -> None:
        if message.chat.type != "private":
            await message.answer("Цены доступны только в личном чате с ботом.", reply_markup=MAIN_MENU)
            return
        actor = (await state.get_data()).get("telegram_actor_context")
        if not isinstance(actor, dict) or not isinstance(actor.get("token"), str):
            await message.answer("Сначала выберите пространство командой /menu.", reply_markup=MAIN_MENU)
            return
        query = (command.args or "").strip()
        try:
            catalog = await core.get_product_catalog(actor["token"], query)
        except TelegramCoreError as error:
            response = {
                "unauthorized": "Сессия истекла. Выберите пространство командой /menu.",
                "forbidden": "У вашей роли нет доступа к чекам.",
                "invalid_query": "Поиск должен занимать не больше 80 символов. Пример: /price молоко.",
                "unavailable": "Цены временно недоступны. Попробуйте позже.",
            }.get(error.code, "Не удалось загрузить цены. Попробуйте позже.")
            if error.code == "unauthorized":
                await state.clear()
            await message.answer(response, reply_markup=MAIN_MENU)
            return
        expected_mode = "search" if query else "catalog"
        if catalog.get("mode") != expected_mode or catalog.get("query") != query:
            await message.answer("Каталог вернул неверные данные. Повторите /price позже.", reply_markup=MAIN_MENU)
            return
        try:
            content_type, content = render_product_catalog(catalog)
        except (TypeError, ValueError, KeyError):
            await message.answer("История цен получена в неверном формате. Попробуйте позже.", reply_markup=MAIN_MENU)
            return
        if content_type == "image/png":
            await message.answer_photo(
                BufferedInputFile(content, filename="finance-prices.png"),
                caption=_product_catalog_caption(catalog),
                reply_markup=MAIN_MENU,
            )
            return
        await message.answer(content.decode("utf-8"), reply_markup=MAIN_MENU)

    async def show_shopping(message: Message, state: FSMContext) -> None:
        if message.chat.type != "private":
            await message.answer("Список покупок доступен только в личном чате с ботом.", reply_markup=MAIN_MENU)
            return
        actor = (await state.get_data()).get("telegram_actor_context")
        if not isinstance(actor, dict) or not isinstance(actor.get("token"), str):
            await message.answer("Сначала выберите пространство командой /menu.", reply_markup=MAIN_MENU)
            return
        try:
            shopping = await core.get_shopping_candidates(actor["token"])
        except TelegramCoreError as error:
            response = {
                "unauthorized": "Сессия истекла. Выберите пространство командой /menu.",
                "forbidden": "У вашей роли нет доступа к чекам.",
                "unavailable": "Список покупок временно недоступен. Попробуйте позже.",
            }.get(error.code, "Не удалось загрузить список покупок. Попробуйте позже.")
            if error.code == "unauthorized":
                await state.clear()
            await message.answer(response, reply_markup=MAIN_MENU)
            return
        try:
            text = render_shopping_candidates(shopping)
        except (TypeError, ValueError, KeyError):
            await message.answer("Список покупок получен в неверном формате. Попробуйте позже.",
                                 reply_markup=MAIN_MENU)
            return
        revision = secrets.token_urlsafe(6)
        await state.update_data(telegram_shopping_decision={
            "revision": revision,
            "tenantId": actor["tenantId"],
            "products": {
                "bought": [item["productKey"] for item in shopping["candidates"]],
                "mute": [item["productKey"] for item in shopping["candidates"]],
                "unmute": [item["productKey"] for item in shopping["mutedCandidates"]],
            },
        })
        await message.answer(text, reply_markup=_shopping_keyboard(shopping, revision), parse_mode=None)

    async def show_personal_inflation(message: Message, state: FSMContext) -> None:
        if message.chat.type != "private":
            await message.answer("Личный индекс цен доступен только в личном чате с ботом.", reply_markup=MAIN_MENU)
            return
        actor = (await state.get_data()).get("telegram_actor_context")
        if not isinstance(actor, dict) or not isinstance(actor.get("token"), str):
            await message.answer("Сначала выберите пространство командой /menu.", reply_markup=MAIN_MENU)
            return
        try:
            inflation = await core.get_personal_inflation(actor["token"])
            text = render_personal_inflation(inflation)
        except TelegramCoreError as error:
            response = {
                "unauthorized": "Сессия истекла. Выберите пространство командой /menu.",
                "forbidden": "У вашей роли нет доступа к чекам.",
                "unavailable": "Личная динамика цен временно недоступна. Попробуйте позже.",
            }.get(error.code, "Не удалось загрузить динамику цен. Попробуйте позже.")
            if error.code == "unauthorized":
                await state.clear()
            await message.answer(response, reply_markup=MAIN_MENU)
            return
        except (TypeError, ValueError, KeyError):
            await message.answer("Динамика цен получена в неверном формате. Попробуйте позже.",
                                 reply_markup=MAIN_MENU)
            return
        await message.answer(text, reply_markup=MAIN_MENU, parse_mode=None)

    async def decide_shopping(callback: CallbackQuery, state: FSMContext) -> None:
        parts = (callback.data or "").split(":")
        if len(parts) != 4 or parts[0] != "shopping" or parts[2] not in {"bought", "mute", "unmute"}:
            await callback.answer("Действие устарело. Откройте /shopping снова.", show_alert=True)
            return
        if callback.message is None or callback.message.chat.type != "private" or callback.from_user is None:
            await callback.answer("Действие доступно только в личном чате.", show_alert=True)
            return
        data = await state.get_data()
        actor = data.get("telegram_actor_context")
        selection = data.get("telegram_shopping_decision")
        if not isinstance(actor, dict) or not isinstance(actor.get("token"), str):
            await callback.answer("Сессия истекла. Выберите пространство командой /menu.", show_alert=True)
            return
        if not isinstance(selection, dict) or selection.get("revision") != parts[1] \
                or selection.get("tenantId") != actor.get("tenantId"):
            await callback.answer("Список обновился. Откройте /shopping снова.", show_alert=True)
            return
        products = selection.get("products")
        try:
            index = int(parts[3])
            keys = products.get(parts[2]) if isinstance(products, dict) else None
            product_key = keys[index] if isinstance(keys, list) and 0 <= index < len(keys) else None
        except (ValueError, TypeError, IndexError):
            product_key = None
        if not isinstance(product_key, str):
            await callback.answer("Подсказка устарела. Откройте /shopping снова.", show_alert=True)
            return
        action = {"bought": core.mark_shopping_bought, "mute": core.mute_shopping_suggestion,
                  "unmute": core.unmute_shopping_suggestion}[parts[2]]
        try:
            shopping = await action(actor["token"], product_key)
            text = render_shopping_candidates(shopping)
        except TelegramCoreError as error:
            if error.code == "unauthorized":
                await state.clear()
                await callback.answer("Сессия истекла. Выберите пространство командой /menu.", show_alert=True)
            else:
                await callback.answer("Не удалось обновить список. Откройте /shopping позже.", show_alert=True)
            return
        except (TypeError, ValueError, KeyError):
            await callback.answer("Список покупок получен в неверном формате.", show_alert=True)
            return
        revision = secrets.token_urlsafe(6)
        await state.update_data(telegram_shopping_decision={
            "revision": revision, "tenantId": actor["tenantId"],
            "products": {
                "bought": [item["productKey"] for item in shopping["candidates"]],
                "mute": [item["productKey"] for item in shopping["candidates"]],
                "unmute": [item["productKey"] for item in shopping["mutedCandidates"]],
            },
        })
        await callback.message.edit_text(text, reply_markup=_shopping_keyboard(shopping, revision), parse_mode=None)
        await callback.answer({"bought": "Отмечено: уже куплено", "mute": "Подсказка скрыта",
                               "unmute": "Подсказка возвращена"}[parts[2]])

    async def show_budget(message: Message, command: CommandObject, state: FSMContext) -> None:
        if message.chat.type != "private":
            await message.answer("Бюджеты доступны только в личном чате с ботом.", reply_markup=MAIN_MENU)
            return
        if message.from_user is None:
            await message.answer("Не удалось определить Telegram-пользователя.", reply_markup=MAIN_MENU)
            return
        actor = (await state.get_data()).get("telegram_actor_context")
        if not isinstance(actor, dict) or not isinstance(actor.get("token"), str):
            await message.answer("Сначала выберите пространство командой /menu.", reply_markup=MAIN_MENU)
            return
        args = (command.args or "").split()
        prefix = ""
        if args and args[0].casefold() == "propose" and len(args) == 1:
            try:
                proposal = await core.propose_history_telegram_budget(
                    actor["token"], f"tg-budget-proposal-{message.from_user.id}-{message.message_id}")
                text = _budget_proposal_text(proposal)
                proposal_id = str(uuid.UUID(proposal["id"]))
            except TelegramCoreError as error:
                await _budget_error(message, state, error)
                return
            except (InvalidOperation, TypeError, ValueError, KeyError, AttributeError):
                await message.answer("Предложение лимитов получено в неверном формате. Попробуйте позже.",
                                     reply_markup=MAIN_MENU)
                return
            if proposal.get("proposalSource") != "history_ai":
                await message.answer("Core вернул неподходящее предложение. Повторите позже.", reply_markup=MAIN_MENU)
                return
            await state.update_data(telegram_budget_proposal={
                "id": proposal_id,
                "tenantId": actor.get("tenantId"),
                "telegramUserId": message.from_user.id,
            })
            await message.answer(text, reply_markup=_budget_proposal_keyboard(proposal_id))
            return
        if args and args[0].casefold() == "suggest" and len(args) == 2:
            try:
                monthly_income = _parse_budget_amount(args[1])
                if Decimal(monthly_income) <= 0:
                    raise ValueError("Monthly income must be positive")
            except (InvalidOperation, ValueError):
                await message.answer("Укажите положительный доход, например: /budget suggest 100000",
                                     reply_markup=MAIN_MENU)
                return
            try:
                proposal = await core.create_telegram_budget_proposal(
                    actor["token"], f"tg-budget-proposal-{message.from_user.id}-{message.message_id}",
                    monthly_income)
                text = _budget_proposal_text(proposal)
                proposal_id = str(uuid.UUID(proposal["id"]))
            except TelegramCoreError as error:
                await _budget_error(message, state, error)
                return
            except (InvalidOperation, TypeError, ValueError, KeyError, AttributeError):
                await message.answer("Предложение лимитов получено в неверном формате. Попробуйте позже.",
                                     reply_markup=MAIN_MENU)
                return
            if proposal.get("proposalSource") != "income":
                await message.answer("Core вернул неподходящее предложение. Повторите позже.", reply_markup=MAIN_MENU)
                return
            await state.update_data(telegram_budget_proposal={
                "id": proposal_id,
                "tenantId": actor.get("tenantId"),
                "telegramUserId": message.from_user.id,
            })
            await message.answer(text, reply_markup=_budget_proposal_keyboard(proposal_id))
            return
        if args and args[0].casefold() == "reset" and len(args) == 1:
            try:
                overview = await core.reset_telegram_budget(
                    actor["token"], f"tg-budget-reset-{message.from_user.id}-{message.message_id}")
            except TelegramCoreError as error:
                await _budget_error(message, state, error)
                return
            prefix = "Личные лимиты сброшены; снова действуют семейные значения."
        elif args and args[0].casefold() == "set" and len(args) == 4:
            scope_name, target, raw_amount = args[1:]
            scope = {"family": "family", "семейный": "family", "личный": "personal",
                     "personal": "personal"}.get(scope_name.casefold())
            target = target.casefold()
            if scope is None:
                await message.answer("Область: family или personal. Пример: /budget set personal еда 5000",
                                     reply_markup=MAIN_MENU)
                return
            try:
                amount = _parse_budget_amount(raw_amount)
            except (InvalidOperation, ValueError):
                await message.answer("Нужно неотрицательное число до двух знаков. Пример: 5000 или 1250.50",
                                     reply_markup=MAIN_MENU)
                return
            if target == "food_week":
                if scope != "personal":
                    await message.answer("Недельный лимит еды настраивается только для personal.",
                                         reply_markup=MAIN_MENU)
                    return
                budget_key, period = "еда", "rolling7"
            elif target == "total":
                budget_key, period = "__total__", "monthly"
            else:
                budget_key, period = target, "monthly"
            try:
                current = await core.get_budget_overview(actor["token"])
                version = _budget_version(current, budget_key, scope, period)
                overview = await core.update_telegram_budget(
                    actor["token"], f"tg-budget-{message.from_user.id}-{message.message_id}",
                    budget_key, scope, amount, version, period)
            except TelegramCoreError as error:
                await _budget_error(message, state, error)
                return
            except (KeyError, TypeError, ValueError):
                await message.answer("Не удалось проверить версию лимита. Повторите /budget.",
                                     reply_markup=MAIN_MENU)
                return
            prefix = f"Лимит обновлён: {amount} RUB."
        elif args:
            await message.answer(
                "Формат: /budget, /budget reset, /budget propose, /budget suggest <income> или "
                "/budget set <family|personal> <category|total|food_week> <amount>.",
                reply_markup=MAIN_MENU,
            )
            return
        else:
            try:
                overview = await core.get_budget_overview(actor["token"])
            except TelegramCoreError as error:
                await _budget_error(message, state, error)
                return
        try:
            summary = await core.get_dashboard_summary(actor["token"])
        except TelegramCoreError as error:
            if error.code == "unauthorized":
                await state.clear()
                await message.answer("Сессия истекла. Выберите пространство командой /menu.", reply_markup=MAIN_MENU)
                return
            summary = {}
        try:
            text = _telegram_budget_text(overview, summary)
        except (InvalidOperation, TypeError, ValueError, KeyError):
            await message.answer("Бюджет получен в неверном формате. Попробуйте позже.", reply_markup=MAIN_MENU)
            return
        if prefix:
            text = f"{prefix}\n\n{text}"
        chunks = _telegram_text_chunks(text)
        for index, chunk in enumerate(chunks):
            await message.answer(chunk, reply_markup=MAIN_MENU if index == len(chunks) - 1 else None)

    async def add_transaction(message: Message, command: CommandObject, state: FSMContext) -> None:
        if message.chat.type != "private":
            await message.answer("Добавление операций доступно только в личном чате с ботом.", reply_markup=MAIN_MENU)
            return
        if message.from_user is None:
            await message.answer("Не удалось определить Telegram-пользователя.", reply_markup=MAIN_MENU)
            return
        text = (command.args or "").strip()
        if not text:
            await message.answer("Напишите операцию после команды, например: /add Такси 2 тыс.", reply_markup=MAIN_MENU)
            return
        actor = (await state.get_data()).get("telegram_actor_context")
        if not isinstance(actor, dict) or not isinstance(actor.get("token"), str):
            await message.answer("Сначала выберите пространство командой /menu.", reply_markup=MAIN_MENU)
            return
        idempotency_key = f"tg-add-{message.from_user.id}-{message.message_id}"
        try:
            draft = await core.create_transaction_draft(actor["token"], idempotency_key, text)
        except TelegramCoreError as error:
            response = {
                "unauthorized": "Сессия истекла. Выберите пространство командой /menu.",
                "forbidden": "У вашей роли нет права добавлять операции.",
                "manual_review": "Не удалось разобрать операцию. Уточните описание и попробуйте снова.",
                "invalid_code": "Описание операции недействительно.",
                "unavailable": "Создание черновика временно недоступно. Попробуйте позже.",
            }.get(error.code, "Не удалось создать черновик. Попробуйте позже.")
            if error.code == "unauthorized":
                await state.clear()
            await message.answer(response, reply_markup=MAIN_MENU)
            return
        if draft.get("tenantId") != actor.get("tenantId"):
            await state.clear()
            await message.answer("Пространство изменилось. Обновите его командой /menu.", reply_markup=MAIN_MENU)
            return
        labels = {"expense": "Расход", "income": "Доход", "debt_payment": "Платёж по долгу"}
        await message.answer(_draft_review_text(draft), reply_markup=_draft_keyboard(draft))

    async def show_history(message: Message, state: FSMContext) -> None:
        if message.chat.type != "private":
            await message.answer("История доступна только в личном чате с ботом.", reply_markup=MAIN_MENU)
            return
        actor = (await state.get_data()).get("telegram_actor_context")
        if not isinstance(actor, dict) or not isinstance(actor.get("token"), str):
            await message.answer("Сначала выберите пространство командой /menu.", reply_markup=MAIN_MENU)
            return
        try:
            transactions = await core.list_telegram_transactions(actor["token"], 8)
        except TelegramCoreError as error:
            response = {
                "unauthorized": "Сессия истекла. Выберите пространство командой /menu.",
                "forbidden": "У вашей роли нет права читать историю.",
                "unavailable": "История временно недоступна. Попробуйте позже.",
            }.get(error.code, "Не удалось загрузить историю. Обновите меню командой /menu.")
            if error.code == "unauthorized":
                await state.clear()
            await message.answer(response, reply_markup=MAIN_MENU)
            return
        if any(transaction.get("tenantId") != actor.get("tenantId") for transaction in transactions):
            await state.clear()
            await message.answer("История вернула другое пространство. Обновите его командой /menu.",
                                 reply_markup=MAIN_MENU)
            return
        revision = secrets.token_urlsafe(4)
        await state.update_data(telegram_history={
            "revision": revision,
            "tenantId": actor["tenantId"],
            "transactions": transactions,
        })
        await message.answer(
            _transaction_history_text(transactions),
            reply_markup=_transaction_history_keyboard(transactions, revision),
        )

    async def decide_transaction_history(callback: CallbackQuery, state: FSMContext) -> None:
        parts = (callback.data or "").split(":")
        if len(parts) != 4 or parts[0] != "history" or parts[2] not in {"repeat", "undo"}:
            await callback.answer("Действие устарело. Откройте /history снова.", show_alert=True)
            return
        try:
            index = int(parts[3])
            if index < 0:
                raise ValueError("Invalid history index")
        except ValueError:
            await callback.answer("Запись истории недействительна.", show_alert=True)
            return
        if callback.message is None or callback.message.chat.type != "private" or callback.from_user is None:
            await callback.answer("Действие доступно только в личном чате.", show_alert=True)
            return
        data = await state.get_data()
        history = data.get("telegram_history")
        actor = data.get("telegram_actor_context")
        if not isinstance(actor, dict) or not isinstance(actor.get("token"), str):
            await callback.answer("Сессия истекла. Выберите пространство командой /menu.", show_alert=True)
            return
        if not isinstance(history, dict) or history.get("revision") != parts[1] \
                or history.get("tenantId") != actor.get("tenantId"):
            await callback.answer("История обновилась. Откройте /history снова.", show_alert=True)
            return
        transactions = history.get("transactions")
        if not isinstance(transactions, list) or index >= len(transactions):
            await callback.answer("Запись истории устарела. Откройте /history снова.", show_alert=True)
            return
        transaction = transactions[index]
        if transaction.get("status") != "posted":
            await callback.answer("Операция уже изменена. Обновите /history.", show_alert=True)
            return
        try:
            if parts[2] == "repeat":
                if transaction.get("type") != "expense":
                    await callback.answer("Повтор доступен только для расхода.", show_alert=True)
                    return
                draft = await core.repeat_telegram_transaction(
                    actor["token"], f"tg-repeat-{callback.from_user.id}-{callback.id}", transaction["id"],
                )
                if draft.get("tenantId") != actor.get("tenantId"):
                    raise TelegramCoreError("unavailable")
                if draft.get("state") != "pending":
                    await callback.answer("Повтор уже обработан. Откройте /history снова.", show_alert=True)
                    return
                await callback.answer("Проверьте повтор расхода")
                await callback.message.answer(_draft_review_text(draft), reply_markup=_draft_keyboard(draft))
                return
            if index != 0:
                await callback.answer("Отменить можно только последнюю операцию.", show_alert=True)
                return
            result = await core.void_latest_telegram_transaction(
                actor["token"], f"tg-void-{callback.from_user.id}-{callback.id}",
                transaction["id"], transaction["version"],
            )
        except TelegramCoreError as error:
            response = {
                "unauthorized": "Сессия истекла. Выберите пространство командой /menu.",
                "forbidden": "У вашей роли нет права изменять операции.",
                "not_found": "Операция недоступна в выбранном пространстве.",
                "stale": "Последняя операция изменилась. Откройте /history снова.",
                "unavailable": "Операция временно недоступна. Попробуйте позже.",
            }.get(error.code, "Не удалось выполнить действие. Обновите /history.")
            if error.code == "unauthorized":
                await state.clear()
            await callback.answer(response, show_alert=True)
            return
        await callback.answer()
        await callback.message.edit_reply_markup(reply_markup=None)
        await callback.message.answer(
            f"Операция отменена: {result['amount']} RUB.", reply_markup=MAIN_MENU,
        )

    async def decide_transaction_draft(callback: CallbackQuery, state: FSMContext) -> None:
        parts = (callback.data or "").split(":")
        valid_action = (
            len(parts) == 4 and parts[3] in {"confirm", "cancel", "debts"}
            or len(parts) == 5 and parts[3] == "amount"
            or len(parts) == 5 and parts[3] == "edit" and parts[4] in DRAFT_EDIT_FIELDS
            or len(parts) == 6 and parts[3] == "debt"
        )
        if parts[0] != "draft" or not valid_action:
            await callback.answer("Действие устарело. Создайте новый черновик.", show_alert=True)
            return
        try:
            draft_id = str(uuid.UUID(parts[1]))
            version = int(parts[2])
            if version < 1:
                raise ValueError("Invalid draft version")
            amount_index = int(parts[4]) if len(parts) == 5 and parts[3] == "amount" else None
            if parts[3] == "amount" and (amount_index is None or not 0 <= amount_index < len(QUICK_AMOUNTS)):
                raise ValueError("Invalid quick amount")
            debt_index = int(parts[5]) if len(parts) == 6 and parts[3] == "debt" else None
            if parts[3] == "debt" and (debt_index is None or debt_index < 0):
                raise ValueError("Invalid debt choice")
        except ValueError:
            await callback.answer("Черновик недействителен. Создайте новый.", show_alert=True)
            return
        if callback.message is None or callback.message.chat.type != "private" or callback.from_user is None:
            await callback.answer("Действие доступно только в личном чате.", show_alert=True)
            return
        actor = (await state.get_data()).get("telegram_actor_context")
        if not isinstance(actor, dict) or not isinstance(actor.get("token"), str):
            await callback.answer("Сессия истекла. Выберите пространство командой /menu.", show_alert=True)
            return
        try:
            if parts[3] == "debts":
                draft = await core.get_transaction_draft(actor["token"], draft_id)
                if draft.get("tenantId") != actor.get("tenantId") or draft.get("version") != version \
                        or draft.get("type") != "debt_payment" or draft.get("state") != "pending":
                    raise TelegramCoreError("stale")
                debts = await core.list_telegram_debts(actor["token"])
                open_debts = [debt for debt in debts if debt.get("status") == "open"]
                if not open_debts:
                    await callback.answer()
                    await callback.message.answer("Нет открытых долгов для выбора. Создайте долг в Finance.",
                                                  reply_markup=MAIN_MENU)
                    return
                revision = secrets.token_urlsafe(4)
                await state.update_data(telegram_debt_selection={
                    "draftId": draft_id,
                    "version": version,
                    "tenantId": actor["tenantId"],
                    "revision": revision,
                    "options": open_debts,
                })
                keyboard = InlineKeyboardMarkup(inline_keyboard=[
                    [InlineKeyboardButton(
                        text=f"{debt['name']} · {debt['currentBalance']} RUB",
                        callback_data=f"draft:{draft_id}:{version}:debt:{revision}:{index}",
                    )]
                    for index, debt in enumerate(open_debts)
                ])
                await callback.answer()
                await callback.message.answer("Выберите долг для платежа:", reply_markup=keyboard)
                return
            if parts[3] == "debt":
                selection = (await state.get_data()).get("telegram_debt_selection")
                if not isinstance(selection, dict) or selection.get("draftId") != draft_id \
                        or selection.get("version") != version or selection.get("revision") != parts[4] \
                        or selection.get("tenantId") != actor.get("tenantId"):
                    raise TelegramCoreError("stale")
                options = selection.get("options")
                if not isinstance(options, list) or debt_index >= len(options):
                    raise TelegramCoreError("stale")
                debt = options[debt_index]
                draft = await core.get_transaction_draft(actor["token"], draft_id)
                if draft.get("tenantId") != actor.get("tenantId") or draft.get("version") != version \
                        or draft.get("type") != "debt_payment" or draft.get("state") != "pending" \
                        or debt.get("tenantId") != actor.get("tenantId") or debt.get("status") != "open":
                    raise TelegramCoreError("stale")
                draft["debtId"] = debt["id"]
                updated = await core.update_transaction_draft(actor["token"], draft_id, version, draft)
                if updated.get("tenantId") != actor.get("tenantId") or updated.get("id") != draft_id:
                    raise TelegramCoreError("unavailable")
                await state.update_data(telegram_debt_selection=None, telegram_pending_draft=updated)
                await callback.answer()
                await callback.message.edit_text(_draft_review_text(updated, debt.get("name")),
                                                 reply_markup=_draft_keyboard(updated))
                return
            if parts[3] == "edit":
                draft = await core.get_transaction_draft(actor["token"], draft_id)
                if draft.get("tenantId") != actor.get("tenantId") or draft.get("id") != draft_id \
                        or draft.get("version") != version or draft.get("state") != "pending":
                    raise TelegramCoreError("stale")
                await state.update_data(telegram_draft_edit={
                    "draftId": draft_id,
                    "version": version,
                    "field": parts[4],
                    "draft": draft,
                })
                prompt = {
                    "amount": "Введите новую сумму (например, 1250.50).",
                    "categoryCode": "Введите код категории (например, food или transport).",
                    "subcategoryCode": "Введите подкатегорию или «нет», чтобы убрать её.",
                    "description": "Введите новое описание операции.",
                    "occurredAt": "Введите дату и время ISO-8601 с часовым поясом.",
                }[parts[4]]
                await callback.answer()
                await callback.message.answer(prompt, reply_markup=MAIN_MENU)
                return
            if parts[3] == "amount":
                updated = await core.update_transaction_draft_amount(
                    actor["token"], draft_id, version, QUICK_AMOUNTS[amount_index],
                )
                if updated.get("tenantId") != actor.get("tenantId") or updated.get("id") != draft_id:
                    raise TelegramCoreError("unavailable")
                await callback.answer()
                await callback.message.edit_text(_draft_review_text(updated),
                                                 reply_markup=_draft_keyboard(updated))
                return
            if parts[3] == "confirm":
                result = await core.confirm_transaction_draft(
                    actor["token"], draft_id, f"tg-confirm-{draft_id}", version,
                )
                type_label = {"expense": "расход", "income": "доход", "debt_payment": "платёж по долгу"}.get(
                    result["type"], result["type"],
                )
                response = f"Операция добавлена: {result['amount']} {type_label}."
                alerts = result.get("budgetAlerts", [])
                if alerts:
                    response += "\n\n" + "\n".join(_budget_alert_text(alert) for alert in alerts)
            else:
                await core.cancel_transaction_draft(actor["token"], draft_id, version)
                response = "Черновик отменён."
        except TelegramCoreError as error:
            response = {
                "unauthorized": "Сессия истекла. Выберите пространство командой /menu.",
                "forbidden": "У вашей роли нет права изменять операции.",
                "not_found": "Черновик не найден в выбранном пространстве. Проверьте /menu.",
                "stale": "Черновик уже изменился. Создайте новый через /add.",
                "already_linked": "Черновик уже обработан. Обновите меню командой /menu.",
                "manual_review": "Для платежа по долгу сначала выберите долг в Finance.",
                "unavailable": "Операция временно недоступна. Попробуйте позже.",
            }.get(error.code, "Не удалось обработать черновик. Обновите меню командой /menu.")
            if error.code == "unauthorized":
                await state.clear()
            await callback.answer(response, show_alert=True)
            return
        await callback.answer()
        await callback.message.edit_reply_markup(reply_markup=None)
        await callback.message.answer(response, reply_markup=MAIN_MENU)

    async def receive_transaction_draft_edit(message: Message, state: FSMContext) -> None:
        edit = (await state.get_data()).get("telegram_draft_edit")
        if not isinstance(edit, dict):
            return
        if message.chat.type != "private" or message.from_user is None:
            await message.answer("Редактирование доступно только в личном чате с ботом.", reply_markup=MAIN_MENU)
            return
        actor = (await state.get_data()).get("telegram_actor_context")
        if not isinstance(actor, dict) or not isinstance(actor.get("token"), str):
            await state.clear()
            await message.answer("Сессия истекла. Выберите пространство командой /menu.", reply_markup=MAIN_MENU)
            return
        value = (message.text or "").strip()
        if not value:
            await message.answer("Значение не должно быть пустым. Повторите ввод или нажмите /menu.",
                                 reply_markup=MAIN_MENU)
            return
        draft = dict(edit.get("draft", {}))
        field = edit.get("field")
        if field not in DRAFT_EDIT_FIELDS or not isinstance(edit.get("draftId"), str):
            await state.update_data(telegram_draft_edit=None)
            await message.answer("Черновик редактирования устарел. Создайте новый через /add.",
                                 reply_markup=MAIN_MENU)
            return
        if field == "subcategoryCode" and value.casefold() in {"нет", "none", "-"}:
            draft[field] = None
        else:
            draft[field] = value
        try:
            updated = await core.update_transaction_draft(
                actor["token"], edit["draftId"], edit["version"], draft,
            )
        except TelegramCoreError as error:
            response = {
                "unauthorized": "Сессия истекла. Выберите пространство командой /menu.",
                "forbidden": "У вашей роли нет права изменять операции.",
                "not_found": "Черновик не найден в выбранном пространстве.",
                "stale": "Черновик уже изменился. Создайте новый через /add.",
                "invalid_code": "Значение не принято. Проверьте сумму, категорию или дату.",
                "manual_review": "Значение требует ручной проверки.",
                "unavailable": "Редактирование временно недоступно. Попробуйте позже.",
            }.get(error.code, "Не удалось изменить черновик. Обновите меню командой /menu.")
            if error.code == "unauthorized":
                await state.clear()
            else:
                await state.update_data(telegram_draft_edit=None)
            await message.answer(response, reply_markup=MAIN_MENU)
            return
        if updated.get("id") != edit["draftId"] or updated.get("tenantId") != actor.get("tenantId"):
            await state.clear()
            await message.answer("Пространство изменилось. Выберите его командой /menu.", reply_markup=MAIN_MENU)
            return
        await state.update_data(telegram_draft_edit=None, telegram_pending_draft=updated)
        await message.answer(_draft_review_text(updated), reply_markup=_draft_keyboard(updated))

    async def decide_budget_proposal(callback: CallbackQuery, state: FSMContext) -> None:
        parts = (callback.data or "").split(":")
        if len(parts) != 3 or parts[:2] != ["budget_proposal", "apply"]:
            await callback.answer("Предложение недействительно. Создайте новое через /budget propose.",
                                  show_alert=True)
            return
        try:
            proposal_id = str(uuid.UUID(parts[2]))
        except (ValueError, TypeError, AttributeError):
            await callback.answer("Предложение недействительно. Создайте новое через /budget propose.",
                                  show_alert=True)
            return
        if callback.message is None or callback.message.chat.type != "private" or callback.from_user is None:
            await callback.answer("Действие доступно только в личном чате.", show_alert=True)
            return
        data = await state.get_data()
        actor = data.get("telegram_actor_context")
        pending = data.get("telegram_budget_proposal")
        if not isinstance(actor, dict) or not isinstance(actor.get("token"), str):
            await callback.answer("Сессия истекла. Выберите пространство командой /menu.", show_alert=True)
            return
        if not isinstance(pending, dict) or pending.get("id") != proposal_id \
                or pending.get("tenantId") != actor.get("tenantId") \
                or pending.get("telegramUserId") != callback.from_user.id:
            await callback.answer("Предложение устарело или создано в другом пространстве.", show_alert=True)
            return
        try:
            overview = await core.apply_telegram_budget_proposal(
                actor["token"], f"tg-budget-proposal-apply-{callback.from_user.id}-{callback.id}", proposal_id)
        except TelegramCoreError as error:
            response = {
                "unauthorized": "Сессия истекла. Выберите пространство командой /menu.",
                "forbidden": "У вашей роли нет права применять лимиты.",
                "stale": "Лимиты изменились после предложения. Создайте новое командой /budget propose.",
                "not_found": "Предложение истекло. Создайте новое командой /budget propose.",
                "unavailable": "Не удалось применить предложение. Попробуйте позже.",
            }.get(error.code, "Не удалось применить предложение. Попробуйте позже.")
            if error.code == "unauthorized":
                await state.clear()
            elif error.code in {"forbidden", "stale", "not_found"}:
                await state.update_data(telegram_budget_proposal=None)
            await callback.answer(response, show_alert=True)
            return
        except (TypeError, ValueError, KeyError):
            await callback.answer("Не удалось применить предложение. Попробуйте позже.", show_alert=True)
            return
        if overview.get("month") is not None and not isinstance(overview.get("month"), str):
            await callback.answer("Core вернул неверный бюджет. Обновите его командой /budget.", show_alert=True)
            return
        await state.update_data(telegram_budget_proposal=None)
        await callback.answer()
        await callback.message.edit_text(
            f"Лимиты применены за {overview.get('month', 'текущий месяц')}.", reply_markup=None)

    async def link_account(message: Message, command: CommandObject) -> None:
        if message.chat.type != "private":
            await message.answer("Привязка доступна только в личном чате с ботом.", reply_markup=MAIN_MENU)
            return
        if message.from_user is None:
            await message.answer("Не удалось определить Telegram-пользователя.", reply_markup=MAIN_MENU)
            return
        arguments = (command.args or "").strip().split()
        if len(arguments) != 1:
            await message.answer("Использование: /link <код из профиля Finance>.", reply_markup=MAIN_MENU)
            return
        try:
            telegram_name = getattr(message.from_user, "full_name", "") or getattr(message.from_user, "username", "")
            result = await core.redeem_link_code(arguments[0], message.from_user.id, telegram_name)
        except TelegramCoreError as error:
            response = {
                "invalid_code": "Код недействителен или истёк. Создайте новый код в профиле Finance.",
                "already_linked": "Этот Telegram-аккаунт уже привязан или не подходит для связи.",
                "rate_limited": "Слишком много попыток. Повторите позже.",
                "unavailable": "Не удалось привязать аккаунт. Попробуйте позже.",
            }.get(error.code, "Не удалось привязать аккаунт. Попробуйте позже.")
            await message.answer(response, reply_markup=MAIN_MENU)
            return
        if result != "linked":
            await message.answer("Не удалось привязать аккаунт. Создайте новый код в профиле Finance.",
                                 reply_markup=MAIN_MENU)
            return
        await message.answer("Аккаунт Finance привязан. Не передавайте код другим.", reply_markup=MAIN_MENU)

    router.message.register(start, Command("start"))
    router.message.register(menu, Command("menu"))
    router.message.register(help_command, Command("help"))
    router.message.register(show_report, Command("report"))
    router.message.register(show_product_catalog, Command("price"))
    router.message.register(show_shopping, Command("shopping"))
    router.message.register(show_personal_inflation, Command("inflation"))
    router.message.register(show_debts, Command("debts"))
    router.message.register(show_budget, Command("budget"))
    router.message.register(link_account, Command("link"))
    router.message.register(add_transaction, Command("add"))
    router.message.register(show_history, Command("history"))
    router.message.register(menu, F.text.casefold() == MENU_BUTTON.casefold())
    router.message.register(receive_transaction_draft_edit, F.text)
    router.callback_query.register(decide_transaction_draft, F.data.startswith("draft:"))
    router.callback_query.register(decide_shopping, F.data.startswith("shopping:"))
    router.callback_query.register(decide_transaction_history, F.data.startswith("history:"))
    router.callback_query.register(decide_budget_proposal, F.data.startswith("budget_proposal:"))

    dispatcher = Dispatcher(storage=storage or MemoryStorage())
    dispatcher.include_router(router)
    dispatcher.update.outer_middleware(DeduplicateUpdates(deduplicator or InMemoryUpdateDeduplicator()))
    router.callback_query.register(select_tenant, F.data.startswith("space:"))
    return dispatcher


def _draft_review_text(draft: dict, debt_name: str | None = None) -> str:
    labels = {"expense": "Расход", "income": "Доход", "debt_payment": "Платёж по долгу"}
    is_income = draft["type"] == "income"
    detail_label = "Источник" if is_income else "Описание"
    category = "" if is_income else (
        f"Категория: {draft['categoryCode']}\nПодкатегория: {draft.get('subcategoryCode') or '—'}\n"
    )
    return (
        f"Проверьте операцию\n{labels.get(draft['type'], draft['type'])}: "
        f"{draft['amount']} {draft['currency']}\n{category}"
        f"{detail_label}: {draft['description']}\n"
        f"Дата: {draft['occurredAt']}\n"
        f"Долг: {debt_name or ('выбран' if draft.get('debtId') else 'не выбран')}\n"
        f"AI: {draft['provider']} / {draft['modelVersion']}"
    )


def _transaction_history_text(transactions: list[dict]) -> str:
    if not transactions:
        return "История операций пока пуста. Добавьте первую запись командой /add."
    lines = ["Последние операции:"]
    for transaction in transactions:
        label = {"expense": "Расход", "income": "Доход", "debt_payment": "Платёж по долгу",
                 "refund": "Возврат", "transfer": "Перевод"}.get(
                     transaction["type"], transaction["type"],
                 )
        description = transaction.get("description") or transaction.get("categoryCode") or label
        day = transaction.get("occurredAt", "")[:10]
        lines.append(f"{day} · {label} · {transaction['amount']} {transaction['currency']} · {description}")
    return "\n".join(lines)


def _telegram_debts_text(debts: list[dict]) -> str:
    if not debts:
        return "Долгов нет."
    total = Decimal("0.00")
    lines = ["💳 Текущие долги:", ""]
    for debt in debts:
        opening = Decimal(debt["openingBalance"])
        balance = Decimal(debt["currentBalance"])
        if not opening.is_finite() or not balance.is_finite() or opening < 0 or balance < 0:
            raise ValueError("Debt balances are invalid")
        total += balance
        paid_percent = 0 if opening == 0 else max(0, min(100, int((opening - balance) / opening * 100)))
        lines.append(f"{debt['name']} — остаток {balance:.2f} RUB · выплачено {paid_percent}%")
        details = []
        if debt.get("interestRate") is not None:
            rate = Decimal(debt["interestRate"])
            if not rate.is_finite() or rate < 0:
                raise ValueError("Debt interest rate is invalid")
            details.append(f"ставка {rate:.2f}%")
        if debt.get("minimumPayment") is not None:
            minimum = Decimal(debt["minimumPayment"])
            if not minimum.is_finite() or minimum < 0:
                raise ValueError("Debt minimum payment is invalid")
            if minimum:
                details.append(f"минимальный платёж {minimum:.2f} RUB")
        if details:
            lines.append(" · ".join(details))
    lines.extend(["", f"Итого долгов: {total:.2f} RUB", "Платёж можно оформить командой /add."])
    return "\n".join(lines)


def _budget_status_text(status: str) -> str:
    return {"disabled": "лимит отключён", "normal": "ниже 90%", "near": "достигнут порог 90%",
            "exceeded": "лимит достигнут"}.get(status, "статус недоступен")


def _budget_alert_text(alert: dict[str, str]) -> str:
    label = "общий лимит" if alert["budgetKey"] == "__total__" else f"категория «{alert['budgetKey']}»"
    status = "достигнут порог 90%" if alert["threshold"] == "near" else "достигнут порог 100%"
    icon = "⚠️" if alert["threshold"] == "near" else "🚨"
    return f"{icon} {status}: {label} — потрачено {alert['spent']} из {alert['limit']} RUB."


def _parse_budget_amount(value: str) -> str:
    amount = Decimal(value.replace(" ", "").replace(",", ".").replace("₽", ""))
    parts = amount.as_tuple()
    if not amount.is_finite() or amount < 0 or parts.exponent < -2 or len(parts.digits) > 20:
        raise ValueError("Budget amount is invalid")
    return format(amount.quantize(Decimal("0.01")), ".2f")


def _budget_version(overview: dict, key: str, scope: str, period: str) -> int:
    if period == "rolling7":
        return overview["familyRolling7FoodVersion"] if scope == "family" \
            else overview["personalRolling7FoodVersion"]
    if key == "__total__":
        return overview["familyTotalVersion"] if scope == "family" else overview["personalTotalVersion"]
    return overview["familyVersions" if scope == "family" else "personalVersions"].get(key, 0)


async def _budget_error(message: Message, state: FSMContext, error: TelegramCoreError) -> None:
    response = {
        "unauthorized": "Сессия истекла. Выберите пространство командой /menu.",
        "forbidden": "У вашей роли нет права менять этот лимит.",
        "stale": "Лимит успел измениться. Повторите /budget и задайте значение снова.",
        "not_found": "Пространство или лимит не найден. Повторите /menu.",
        "unavailable": "Бюджет временно недоступен. Попробуйте позже.",
    }.get(error.code, "Не удалось выполнить действие с бюджетом. Проверьте формат и повторите.")
    if error.code == "unauthorized":
        await state.clear()
    await message.answer(response, reply_markup=MAIN_MENU)


def _budget_proposal_text(proposal: dict) -> str:
    source = proposal.get("proposalSource")
    monthly_income = Decimal(proposal["monthlyIncome"])
    total_limit = Decimal(proposal["totalLimit"])
    if not monthly_income.is_finite() or monthly_income <= 0 \
            or not total_limit.is_finite() or total_limit < 0:
        raise ValueError("Budget proposal amounts are invalid")
    if source == "history_ai":
        history_days = proposal.get("historyDays")
        if type(history_days) is not int or history_days < 30:
            raise ValueError("Budget proposal history is insufficient")
        source_text = f"Источник: AI-анализ истории за {history_days} дней."
    elif source == "income":
        source_text = "Источник: расчёт от указанного дохода (70%)."
    else:
        raise ValueError("Budget proposal source is invalid")
    lines = [
        "Предложение лимитов",
        source_text,
        f"Месячный доход: {_format_budget_proposal_money(monthly_income)} RUB",
        f"Общий лимит: {_format_budget_proposal_money(total_limit)} RUB",
    ]
    limits = proposal.get("limits")
    if not isinstance(limits, dict):
        raise ValueError("Budget proposal limits are invalid")
    if limits:
        lines.append("Категории:")
        for category, raw_amount in sorted(limits.items()):
            amount = Decimal(raw_amount)
            if not category or not amount.is_finite() or amount < 0:
                raise ValueError("Budget proposal category is invalid")
            safe_category = " ".join(category.split())[:80]
            lines.append(f"• {safe_category}: {_format_budget_proposal_money(amount)} RUB")
    lines.append("Предложение не применяется без вашего действия.")
    return "\n".join(lines)


def _format_budget_proposal_money(amount: Decimal) -> str:
    return f"{amount:,.2f}".replace(",", " ")


def _budget_proposal_keyboard(proposal_id: str) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[[
        InlineKeyboardButton(text="Применить предложенные лимиты",
                             callback_data=f"budget_proposal:apply:{proposal_id}"),
    ]])


def _telegram_budget_text(overview: dict, summary: dict) -> str:
    currency = overview["currency"]
    lines = [f"Бюджет за {overview['month']} ({currency}):",
             f"Семейный общий лимит: {overview['familyTotalLimit']} · "
             f"{_budget_status_text(overview['totalLimitStatus'])}"]
    personal_total = overview.get("personalTotalOverride")
    personal_label = "Личный общий лимит" if personal_total is not None else "Личный общий лимит наследует семейный"
    lines.append(f"{personal_label}: {overview['effectiveTotalLimit']} · "
                 f"потрачено {overview['totalMonthlySpent']} · {_budget_status_text(overview['totalLimitStatus'])}")

    family_limits = overview.get("familyLimits", {})
    personal_limits = overview.get("personalOverrides", {})
    effective_limits = overview.get("effectiveLimits", {})
    spent = overview.get("monthlySpent", {})
    states = overview.get("limitStatus", {})
    if effective_limits:
        lines.extend(["", "Лимиты категорий:"])
        for category in sorted(effective_limits):
            family_limit = family_limits.get(category, "—")
            own_limit = personal_limits.get(category)
            override = f" · личный override {own_limit}" if own_limit is not None else ""
            category_label = category[:1].upper() + category[1:]
            lines.append(f"{category_label}: семья {family_limit}, действует {effective_limits[category]}{override}; "
                         f"потрачено {spent.get(category, '0.00')} · "
                         f"{_budget_status_text(states.get(category, 'disabled'))}")

    food = overview.get("rolling7FoodStatus")
    if food is not None:
        lines.extend(["", f"Еда за 7 дней ({food['fromDate']}–{food['toDate']}): "
                      f"лимит {food['limit']}, потрачено {food['spent']}, остаток {food.get('remaining') or '—'}; "
                      f"{_budget_status_text(food['limitStatus'])}."])
        if food.get("usualWeeklySpend") is not None:
            lines.append(f"Обычный недельный расход: {food['usualWeeklySpend']} · "
                         f"темп {food['paceStatus']} ({food.get('paceShare') or '—'}).")

    if "safeToSpend" not in summary:
        lines.extend(["", "Безопасный расход сейчас недоступен."])
    elif summary["safeToSpend"] is None:
        lines.extend(["", "Безопасный расход не рассчитан: задайте план дохода."])
    else:
        safe = summary["safeToSpend"]
        income_basis = "фактический доход" if safe["incomeBasis"] == "actual_income" else "плановый доход"
        lines.extend(["", f"Безопасно потратить до {safe['horizonDate']}: {safe['safeTotal']} {currency}; "
                      f"в день {safe['safePerDay']} {currency}.",
                      f"Основа: {income_basis} {safe['incomeBase']}; резерв {safe['reserve']}; "
                      f"обязательные списания {safe['promisedPayments']}."])
    return "\n".join(lines)


def _parse_report_request(arguments: str) -> dict[str, str | None]:
    parts = arguments.split()
    scope = "personal"
    scope_tokens = [part.casefold() for part in parts if part.casefold() in {
        "family", "семья", "семейный", "personal", "личный", "личная", "личное",
    }]
    if len(scope_tokens) > 1:
        raise ValueError("Report scope was specified more than once")
    if scope_tokens:
        scope = "family" if scope_tokens[0] in {"family", "семья", "семейный"} else "personal"
        selected = scope_tokens[0]
        parts.remove(next(part for part in parts if part.casefold() == selected))

    if not parts:
        return {"period": "month", "month": None, "from_date": None, "to_date": None, "scope": scope}
    if len(parts) == 1:
        period = parts[0].casefold()
        aliases = {"month": "month", "месяц": "month", "week": "week", "неделя": "week",
                   "90d": "90d", "90д": "90d"}
        if period in aliases:
            return {"period": aliases[period], "month": None, "from_date": None, "to_date": None,
                    "scope": scope}
        if re.fullmatch(r"\d{4}-\d{2}", parts[0]):
            try:
                date.fromisoformat(parts[0] + "-01")
            except ValueError as error:
                raise ValueError("Invalid report month") from error
            return {"period": "month", "month": parts[0], "from_date": None, "to_date": None,
                    "scope": scope}
    if len(parts) == 2:
        try:
            from_date = date.fromisoformat(parts[0])
            to_date = date.fromisoformat(parts[1])
        except ValueError as error:
            raise ValueError("Custom report dates must be ISO dates") from error
        if to_date < from_date or (to_date - from_date).days >= 366:
            raise ValueError("Custom report range must be between one and 366 days")
        return {"period": "custom", "month": None, "from_date": from_date.isoformat(),
                "to_date": to_date.isoformat(), "scope": scope}
    raise ValueError("Unsupported report arguments")


def _product_catalog_caption(catalog: dict) -> str:
    products = catalog["products"]
    lines = ["Покупки из подтверждённых чеков; цены за единицу товара."]
    for product in products[:3]:
        merchant = product.get("cheapestMerchant") or "магазин не указан"
        lines.append(f"{product['productName'][:80]}: дешевле всего {product['cheapestUnitPrice']} ₽ · {merchant[:80]}")
    return "\n".join(lines)[:1000]


def _report_caption(report: dict) -> str:
    scope = "семейный" if report["scope"] == "family" else "личный"
    lines = [f"{scope.capitalize()} отчёт: {report['fromDate']} — {report['toDate']}",
             f"Доходы: {report['incomeTotal']} {report['currency']}",
             f"Расходы: {report['expenseTotal']} {report['currency']}"]
    food = report.get("rolling7FoodStatus")
    if isinstance(food, dict):
        if food.get("limitStatus") == "disabled":
            lines.append(f"Еда за 7 дней: {food['spent']} {report['currency']} · лимит отключён")
        else:
            lines.append(f"Еда за 7 дней: {food['spent']} / {food['limit']} {report['currency']}")
            if food.get("remaining") is not None:
                lines.append(f"Остаток лимита еды: {food['remaining']} {report['currency']}")
        if food.get("paceStatus") == "insufficient_history":
            lines.append("Недостаточно истории для темпа")
        elif food.get("usualWeeklySpend") is not None:
            lines.append(f"Обычный недельный расход: {food['usualWeeklySpend']} {report['currency']} "
                         f"({food['historyWeeks']} недель)")
    return "\n".join(lines)


def _telegram_text_chunks(text: str, limit: int = 3800) -> list[str]:
    chunks: list[str] = []
    current = ""
    for line in text.splitlines():
        while len(line) > limit:
            if current:
                chunks.append(current)
                current = ""
            chunks.append(line[:limit])
            line = line[limit:]
        if current and len(current) + len(line) + 1 > limit:
            chunks.append(current)
            current = line
        else:
            current = f"{current}\n{line}" if current else line
    if current or not chunks:
        chunks.append(current)
    return chunks


def _transaction_history_keyboard(transactions: list[dict], revision: str) -> InlineKeyboardMarkup | None:
    buttons = []
    for index, transaction in enumerate(transactions):
        if transaction.get("type") != "expense" or transaction.get("status") != "posted":
            continue
        description = transaction.get("description") or transaction.get("categoryCode") or "расход"
        buttons.append([InlineKeyboardButton(
            text=f"Повторить: {description[:32]}",
            callback_data=f"history:{revision}:repeat:{index}",
        )])
    if transactions and transactions[0].get("status") == "posted":
        buttons.append([InlineKeyboardButton(
            text="Отменить последнюю",
            callback_data=f"history:{revision}:undo:0",
        )])
    return InlineKeyboardMarkup(inline_keyboard=buttons) if buttons else None


def _shopping_keyboard(shopping: dict, revision: str) -> InlineKeyboardMarkup | None:
    buttons = []
    for index, candidate in enumerate(shopping["candidates"]):
        key = candidate["productKey"]
        if not isinstance(key, str):
            raise ValueError("Shopping candidate has no product key")
        buttons.append([
            InlineKeyboardButton(text=f"Уже купил · {candidate['productName'][:28]}",
                                 callback_data=f"shopping:{revision}:bought:{index}"),
            InlineKeyboardButton(text=f"Скрыть · {candidate['productName'][:28]}",
                                 callback_data=f"shopping:{revision}:mute:{index}"),
        ])
    for index, candidate in enumerate(shopping["mutedCandidates"]):
        key = candidate["productKey"]
        if not isinstance(key, str):
            raise ValueError("Muted shopping candidate has no product key")
        buttons.append([InlineKeyboardButton(text=f"Вернуть · {candidate['productName'][:32]}",
                                             callback_data=f"shopping:{revision}:unmute:{index}")])
    return InlineKeyboardMarkup(inline_keyboard=buttons) if buttons else None


def _draft_keyboard(draft: dict) -> InlineKeyboardMarkup:
    draft_id = draft["id"]
    version = draft["version"]
    needs_debt = draft.get("type") == "debt_payment" and not draft.get("debtId")
    primary_buttons = [InlineKeyboardButton(
        text="Отменить", callback_data=f"draft:{draft_id}:{version}:cancel",
    )]
    if not needs_debt:
        primary_buttons.insert(0, InlineKeyboardButton(
            text="Подтвердить", callback_data=f"draft:{draft_id}:{version}:confirm",
        ))
    return InlineKeyboardMarkup(inline_keyboard=[
        primary_buttons,
        [InlineKeyboardButton(text=amount.replace(".00", "").replace("1000", "1 000").replace("2000", "2 000")
                                    .replace("5000", "5 000"),
                              callback_data=f"draft:{draft_id}:{version}:amount:{index}")
         for index, amount in enumerate(QUICK_AMOUNTS)],
        [InlineKeyboardButton(text="Сумма", callback_data=f"draft:{draft_id}:{version}:edit:amount"),
         InlineKeyboardButton(text="Категория", callback_data=f"draft:{draft_id}:{version}:edit:categoryCode")],
        [InlineKeyboardButton(text="Подкатегория", callback_data=f"draft:{draft_id}:{version}:edit:subcategoryCode"),
         InlineKeyboardButton(text="Описание", callback_data=f"draft:{draft_id}:{version}:edit:description")],
        [InlineKeyboardButton(text="Дата", callback_data=f"draft:{draft_id}:{version}:edit:occurredAt")],
        *([[InlineKeyboardButton(text="Выбрать долг", callback_data=f"draft:{draft_id}:{version}:debts")]]
          if draft.get("type") == "debt_payment" else []),
    ])


async def _save_actor_context(state: FSMContext, actor: dict) -> None:
    await state.set_data({
        "telegram_actor_context": {
            "token": actor["token"],
            "tenantId": actor["tenantId"],
            "displayName": actor["displayName"],
            "role": actor["role"],
            "expiresAt": actor["expiresAt"],
        }
    })


async def _show_dashboard(message: Message, core: TelegramCoreClient, actor: dict) -> None:
    try:
        summary = await core.get_dashboard_summary(actor["token"])
    except (TelegramCoreError, KeyError):
        await message.answer("Пространство выбрано, но сводка сейчас недоступна. Повторите /menu позже.",
                             reply_markup=MAIN_MENU)
        return
    lines = [f"Пространство: {actor['displayName']}",
             f"Доходы за {summary['month']}: {summary['incomeTotal']} {summary['currency']}",
             f"Расходы за {summary['month']}: {summary['expenseTotal']} {summary['currency']}",
             f"Операций: {summary['transactionCount']}"]
    safe = summary.get("safeToSpend")
    if safe is not None:
        lines.append(f"Безопасно тратить до {safe['horizonDate']}: {safe['safeTotal']} {summary['currency']} "
                     f"({safe['safePerDay']} в день).")
    await message.answer("\n".join(lines), reply_markup=MAIN_MENU)


def create_webhook_app(bot: Bot, dispatcher: Dispatcher, secret_token: str) -> web.Application:
    if not secret_token.strip():
        raise ValueError("FINANCE_TELEGRAM_WEBHOOK_SECRET is required")
    app = web.Application(client_max_size=1024 * 1024)
    SimpleRequestHandler(
        dispatcher=dispatcher,
        bot=bot,
        handle_in_background=False,
        secret_token=secret_token,
    ).register(app, path="/telegram/webhook")
    setup_application(app, dispatcher, bot=bot)
    return app


def validate_webhook_url(url: str) -> str:
    parsed = urlsplit(url.strip())
    if parsed.scheme.lower() != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("FINANCE_TELEGRAM_WEBHOOK_URL must be an HTTPS URL without credentials")
    return url.strip()


def _redis_components(redis_url: str):
    if not redis_url.strip():
        raise ValueError("FINANCE_REDIS_URL is required for shared update deduplication")
    from redis.asyncio import Redis
    from aiogram.fsm.storage.redis import RedisStorage

    client = Redis.from_url(redis_url, decode_responses=True, max_connections=10)
    storage = RedisStorage(redis=client, state_ttl=900, data_ttl=900)
    return RedisUpdateDeduplicator(client), storage


async def run_polling(token: str) -> None:
    if not token.strip():
        raise ValueError("FINANCE_TELEGRAM_BOT_TOKEN is required")
    redis_url = os.environ.get("FINANCE_REDIS_URL", "")
    if redis_url:
        deduplicator, storage = _redis_components(redis_url)
    else:
        deduplicator, storage = InMemoryUpdateDeduplicator(), MemoryStorage()
    bot = Bot(token=token.strip())
    core = TelegramCoreClient(os.environ.get("FINANCE_CORE_INTERNAL_URL", ""),
                              os.environ.get("FINANCE_TELEGRAM_SERVICE_TOKEN", ""))
    worker = asyncio.create_task(run_notification_worker(core, bot)) if core.base_url and core.service_token else None
    try:
        await build_dispatcher(deduplicator, telegram_core=core, storage=storage).start_polling(bot)
    finally:
        if worker is not None:
            worker.cancel()
            with suppress(asyncio.CancelledError):
                await worker
        await bot.session.close()
        await storage.close()


async def run_webhook(token: str) -> None:
    if not token.strip():
        raise ValueError("FINANCE_TELEGRAM_BOT_TOKEN is required")
    secret = os.environ.get("FINANCE_TELEGRAM_WEBHOOK_SECRET", "")
    redis_url = os.environ.get("FINANCE_REDIS_URL", "")
    webhook_url = validate_webhook_url(os.environ.get("FINANCE_TELEGRAM_WEBHOOK_URL", ""))
    cert_file = os.environ.get("FINANCE_TELEGRAM_TLS_CERT", "")
    key_file = os.environ.get("FINANCE_TELEGRAM_TLS_KEY", "")
    if not secret.strip() or not cert_file.strip() or not key_file.strip():
        raise ValueError("Webhook mode requires a secret token and TLS certificate/key")
    tls = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
    tls.load_cert_chain(certfile=cert_file, keyfile=key_file)
    deduplicator, storage = _redis_components(redis_url)
    bot = Bot(token=token.strip())
    core = TelegramCoreClient(os.environ.get("FINANCE_CORE_INTERNAL_URL", ""),
                              os.environ.get("FINANCE_TELEGRAM_SERVICE_TOKEN", ""))
    worker = None
    runner = None
    try:
        dispatcher = build_dispatcher(deduplicator, telegram_core=core, storage=storage)
        if core.base_url and core.service_token:
            worker = asyncio.create_task(run_notification_worker(core, bot))
        app = create_webhook_app(bot, dispatcher, secret)
        runner = web.AppRunner(app)
        await runner.setup()
        site = web.TCPSite(
            runner,
            os.environ.get("FINANCE_TELEGRAM_BIND", "0.0.0.0"),
            int(os.environ.get("FINANCE_TELEGRAM_PORT", "8443")),
            ssl_context=tls,
        )
        await site.start()
        await bot.set_webhook(
            url=webhook_url,
            secret_token=secret,
            allowed_updates=dispatcher.resolve_used_update_types(),
            drop_pending_updates=False,
        )
        await asyncio.Event().wait()
    finally:
        if worker is not None:
            worker.cancel()
            with suppress(asyncio.CancelledError):
                await worker
        if runner is not None:
            await runner.cleanup()
        await bot.session.close()
        await storage.close()


def main() -> None:
    token = os.environ.get("FINANCE_TELEGRAM_BOT_TOKEN", "")
    mode = os.environ.get("FINANCE_TELEGRAM_MODE", "polling").strip().lower()
    if mode == "polling":
        asyncio.run(run_polling(token))
    elif mode == "webhook":
        asyncio.run(run_webhook(token))
    else:
        raise ValueError("FINANCE_TELEGRAM_MODE must be polling or webhook")


if __name__ == "__main__":
    main()
