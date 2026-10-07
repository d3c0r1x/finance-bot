"""Durable outbox delivery loop for scheduled Telegram reports."""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Mapping, Sequence

from aiogram.exceptions import TelegramAPIError, TelegramBadRequest, TelegramForbiddenError

from services.python.telegram_gateway.core_client import TelegramCoreClient, TelegramCoreError
from services.python.telegram_gateway.digest import render_digest

logger = logging.getLogger(__name__)


async def poll_notification_deliveries(core: TelegramCoreClient, bot, *, limit: int = 10) -> int:
    claims = await core.claim_notification_deliveries(limit)
    return await deliver_notification_claims(core, bot, claims)


async def deliver_notification_claims(core: TelegramCoreClient, bot,
                                      claims: Sequence[Mapping[str, object]]) -> int:
    """Send each claimed digest once and record its result; a lease expiry recovers crashes."""
    completed = 0
    for claim in claims:
        intent_id = claim["intentId"]
        lease_token = claim["leaseToken"]
        report = claim["report"]
        try:
            text = render_digest(report, claim["digestKind"], claim["language"], claim.get("goalOutcome"))
        except (TypeError, ValueError):
            outcome, error_code, provider_message_id = "permanent_failure", "invalid_report", None
        else:
            if text is None:
                outcome, error_code, provider_message_id = "no_data", None, None
            else:
                try:
                    sent = await bot.send_message(chat_id=claim["telegramUserId"], text=text)
                except TelegramForbiddenError:
                    outcome, error_code, provider_message_id = "permanent_failure", "telegram_forbidden", None
                except TelegramBadRequest:
                    outcome, error_code, provider_message_id = "permanent_failure", "telegram_bad_request", None
                except TelegramAPIError:
                    outcome, error_code, provider_message_id = "retryable_failure", "telegram_api_error", None
                except Exception:
                    outcome, error_code, provider_message_id = "retryable_failure", "telegram_transport_error", None
                else:
                    outcome, error_code = "delivered", None
                    message_id = getattr(sent, "message_id", None)
                    provider_message_id = str(message_id) if message_id is not None else None
        try:
            await core.acknowledge_notification_delivery(
                intent_id, lease_token, outcome, error_code=error_code,
                provider_message_id=provider_message_id)
        except TelegramCoreError:
            # Core lease expiry will retry if delivery may have succeeded before the ack was lost.
            continue
        completed += 1
    return completed


async def run_notification_worker(core: TelegramCoreClient, bot, *, poll_interval: float = 5.0) -> None:
    while True:
        try:
            await poll_notification_deliveries(core, bot)
        except TelegramCoreError as error:
            logger.warning("Notification delivery poll failed (%s)", error.code)
        except TelegramAPIError:
            logger.warning("Notification delivery request failed")
        except Exception:
            logger.exception("Notification delivery worker failed")
        await asyncio.sleep(poll_interval)
