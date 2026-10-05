"""Deterministic parser for the supported T-Bank account statement PDF."""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, time
from decimal import Decimal, InvalidOperation
from io import BytesIO
from pathlib import Path

from pypdf import PdfReader
from pypdf.errors import PdfReadError

PARSE_VERSION = "tbank-pdf.v1"
RECONCILIATION_TOLERANCE = Decimal("1.00")
MAX_MONEY = Decimal("1000000000000000000.00")

_DATE = r"\d{2}\.\d{2}\.\d{4}"
_TIME = r"\d{2}:\d{2}"
_OPERATION = re.compile(
    rf"(?P<operation_date>{_DATE})\s*\n\s*(?P<operation_time>{_TIME})\s*\n\s*"
    rf"(?P<posting_date>{_DATE})\s*\n\s*(?P<posting_time>{_TIME})\s*\n\s*"
    r"(?P<account_amount>[+-][\d \u00a0\u202f]+\.\d{2})\s*₽\s*"
    r"(?P<settled_amount>[+-][\d \u00a0\u202f]+\.\d{2})\s*₽\s*"
    rf"(?P<body>.*?)(?=\d{{2}}\.\d{{2}}\.\d{{4}}\s*\n\s*\d{{2}}:\d{{2}}\s*\n|\Z)",
    re.DOTALL,
)
_TOTAL_AMOUNT = r"(\d[\d \u00a0\u202f]*,\d{2})\s*₽"
_CITY_LINE = re.compile(r"\s+[A-ZА-ЯЁ][a-zа-яё]{2,}\s+RUS$", re.IGNORECASE)
_MERCHANT_PREFIXES = ("YM*", "Y.M*", "OZON*", "T-Bank.", "Т-Банк.", "PLATON*", "MTS*")


class StatementParseError(ValueError):
    """A safe, user-displayable parser failure with a stable machine code."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class BankOperation:
    operation_date: str
    operation_time: str
    signed_amount: str
    kind: str
    merchant: str | None
    description: str
    card_last4: str | None

    def to_payload(self) -> dict[str, str | None]:
        return {
            "operationDate": self.operation_date,
            "operationTime": self.operation_time,
            "signedAmount": self.signed_amount,
            "currency": "RUB",
            "kind": self.kind,
            "merchant": self.merchant,
            "description": self.description,
            "cardLast4": self.card_last4,
        }


@dataclass(frozen=True)
class ParserResult:
    operations: tuple[BankOperation, ...]
    parsed_expense_total: str
    parsed_income_total: str
    expected_expense_total: str | None
    expected_income_total: str | None
    quality: str
    period_start: str
    period_end: str
    parse_version: str = PARSE_VERSION

    def to_payload(self) -> dict:
        return {
            "operations": [operation.to_payload() for operation in self.operations],
            "parsedExpenseTotal": self.parsed_expense_total,
            "parsedIncomeTotal": self.parsed_income_total,
            "expectedExpenseTotal": self.expected_expense_total,
            "expectedIncomeTotal": self.expected_income_total,
            "quality": self.quality,
            "periodStart": self.period_start,
            "periodEnd": self.period_end,
            "parseVersion": self.parse_version,
        }


def _normalize_space(value: str) -> str:
    return value.replace("\u00a0", " ").replace("\u202f", " ")


def _parse_ru_total(value: str) -> Decimal:
    try:
        amount = Decimal(_normalize_space(value).replace(" ", "").replace(",", "."))
    except InvalidOperation as error:
        raise StatementParseError("invalid_totals", "Итоги выписки имеют неверный формат") from error
    if not amount.is_finite() or amount < 0 or amount >= MAX_MONEY:
        raise StatementParseError("invalid_totals", "Итоги выписки выходят за допустимый диапазон")
    return amount


def _total_for_label(text: str, label: str) -> tuple[Decimal, int] | None:
    label_pattern = re.escape(label[:-1]) + r":?"
    for pattern in (
        rf"{_TOTAL_AMOUNT}\s*{label_pattern}",
        rf"{label_pattern}\s*{_TOTAL_AMOUNT}",
    ):
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            return _parse_ru_total(match.group(1)), match.start()
    return None


def _parse_signed_amount(value: str) -> Decimal:
    normalized = _normalize_space(value).replace(" ", "")
    try:
        amount = Decimal(normalized)
    except InvalidOperation as error:
        raise StatementParseError("invalid_operation", "Сумма операции имеет неверный формат") from error
    if not amount.is_finite() or abs(amount) >= MAX_MONEY:
        raise StatementParseError("invalid_operation", "Сумма операции выходит за допустимый диапазон")
    return amount


def _to_iso_date(value: str) -> str:
    day, month, year = value.split(".")
    try:
        return date(int(year), int(month), int(day)).isoformat()
    except ValueError as error:
        raise StatementParseError("invalid_operation", "Дата операции имеет неверный формат") from error


def _validate_time(value: str) -> str:
    try:
        return time.fromisoformat(value).isoformat(timespec="minutes")
    except ValueError as error:
        raise StatementParseError("invalid_operation", "Время операции имеет неверный формат") from error


def _classify(description: str, amount: Decimal) -> str:
    lowered = description.casefold()
    if any(marker in lowered for marker in (
        "внутренний перевод", "перевод между своими", "перевод на свою", "перевод себе",
    )):
        return "internal"
    if "снятие наличных" in lowered or lowered.startswith("снятие"):
        return "withdrawal"
    if "возврат" in lowered and amount > 0:
        return "refund"
    if amount > 0:
        return "income"
    if any(marker in lowered for marker in ("оплата в ", "оплата услуг", "оплата заказа")):
        return "purchase"
    if any(marker in lowered for marker in ("комиссия", "обслуживание", "процент", "страхов")):
        return "fee"
    return "transfer_out"


def _card_last4(body: str) -> str | None:
    lines = [line.strip() for line in body.splitlines() if line.strip()]
    if lines and re.fullmatch(r"\d{4}|—", lines[-1]):
        return None if lines[-1] == "—" else lines[-1]
    return None


def _clean_description(body: str, card_last4: str | None) -> str:
    lines = []
    for line in body.splitlines():
        line = line.strip()
        if not line or line in {card_last4, "—"}:
            continue
        if lines and lines[-1].endswith("-"):
            lines[-1] += line
        else:
            lines.append(line)
    return " ".join(lines)


def _merchant(description: str) -> str | None:
    first = description.split("\n", 1)[0].strip()
    for prefix in ("Оплата в ", "Оплата услуг ", "Оплата заказа "):
        if first.startswith(prefix):
            first = first[len(prefix):]
            break
    for prefix in _MERCHANT_PREFIXES:
        if first.startswith(prefix):
            first = first[len(prefix):]
            break
    first = _CITY_LINE.sub("", first)
    first = re.sub(r"\s+RUS$", "", first, flags=re.IGNORECASE)
    first = re.sub(r"\s+\d{4,6}$", "", first)
    cleaned = first.strip(" .,*—")
    return cleaned or None


def _money(value: Decimal) -> str:
    if not value.is_finite() or abs(value) >= MAX_MONEY:
        raise StatementParseError("invalid_totals", "Сумма операций выходит за допустимый диапазон")
    try:
        return format(value.quantize(Decimal("0.01")), ".2f")
    except InvalidOperation as error:
        raise StatementParseError("invalid_totals", "Сумма операций имеет неверный формат") from error


def parse_tbank_pdf(path: str | Path) -> ParserResult:
    """Parse the supported statement; never label missing bank totals as valid."""
    try:
        reader = PdfReader(str(path))
    except (OSError, PdfReadError, ValueError, TypeError) as error:
        raise StatementParseError("invalid_pdf", "Не удалось прочитать PDF-выписку") from error
    return _parse_reader(reader)


def parse_tbank_pdf_bytes(contents: bytes) -> ParserResult:
    """Parse bounded in-memory PDF content for authenticated synchronous imports."""
    if not isinstance(contents, bytes) or not contents:
        raise StatementParseError("invalid_pdf", "Не удалось прочитать PDF-выписку")
    try:
        reader = PdfReader(BytesIO(contents))
    except (OSError, PdfReadError, ValueError, TypeError) as error:
        raise StatementParseError("invalid_pdf", "Не удалось прочитать PDF-выписку") from error
    return _parse_reader(reader)


def _parse_reader(reader: PdfReader) -> ParserResult:
    try:
        pages = [page.extract_text() for page in reader.pages]
    except (OSError, PdfReadError, ValueError, TypeError) as error:
        raise StatementParseError("invalid_pdf", "Не удалось прочитать PDF-выписку") from error
    flow = _normalize_space("\n".join(page or "" for page in pages))
    if not flow.strip():
        raise StatementParseError("no_text", "В PDF нет извлекаемого текста; скан нужно распознать отдельно")
    normalized = flow.casefold()
    if "движении средств" not in normalized and "движения средств" not in normalized:
        raise StatementParseError("invalid_format", "Это не выписка Т-Банка «О движении средств»")

    expected_expense = _total_for_label(flow, "Расходы:")
    expected_income = _total_for_label(flow, "Пополнения:")
    cutoff_positions = [entry[1] for entry in (expected_expense, expected_income) if entry is not None]
    operation_text = flow[:min(cutoff_positions)] if cutoff_positions else flow

    operations = []
    for match in _OPERATION.finditer(operation_text):
        amount = _parse_signed_amount(match.group("account_amount"))
        card = _card_last4(match.group("body"))
        description = _clean_description(match.group("body"), card)
        if not description:
            raise StatementParseError("invalid_operation", "У операции отсутствует описание")
        date = _to_iso_date(match.group("operation_date"))
        operation_time = _validate_time(match.group("operation_time"))
        operations.append(BankOperation(
            operation_date=date,
            operation_time=operation_time,
            signed_amount=_money(amount),
            kind=_classify(description, amount),
            merchant=_merchant(match.group("body")) if amount < 0 else None,
            description=description,
            card_last4=card,
        ))
    if not operations:
        raise StatementParseError("no_operations", "В выписке не найдено операций с распознаваемой структурой")

    parsed_expense = sum((-Decimal(operation.signed_amount) for operation in operations
                          if Decimal(operation.signed_amount) < 0), Decimal("0.00"))
    parsed_income = sum((Decimal(operation.signed_amount) for operation in operations
                         if Decimal(operation.signed_amount) > 0), Decimal("0.00"))
    if expected_expense is None or expected_income is None:
        quality = "unverifiable"
    elif (abs(parsed_expense - expected_expense[0]) <= RECONCILIATION_TOLERANCE
          and abs(parsed_income - expected_income[0]) <= RECONCILIATION_TOLERANCE):
        quality = "valid"
    else:
        quality = "mismatch"
    dates = [operation.operation_date for operation in operations]
    return ParserResult(
        operations=tuple(operations),
        parsed_expense_total=_money(parsed_expense),
        parsed_income_total=_money(parsed_income),
        expected_expense_total=_money(expected_expense[0]) if expected_expense is not None else None,
        expected_income_total=_money(expected_income[0]) if expected_income is not None else None,
        quality=quality,
        period_start=min(dates),
        period_end=max(dates),
    )
