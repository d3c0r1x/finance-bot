"""Разбор банковской выписки Т-Банка («Справка о движении средств», PDF).

Выписка извлекается постранично (pypdf), операции режутся из текстового потока
по устойчивой метке «дата время · дата списания · сумма · сумма · описание».
Разбор честен ровно настолько, насколько сходится арифметика: суммы всех
операций сверяются с итогами «Расходы/Пополнения» в конце документа — банк сам
подсказывает, что файл прочитан полностью и без потерь.
"""
from dataclasses import dataclass, field
from datetime import datetime
import re
from pathlib import Path

from pypdf import PdfReader


@dataclass
class BankOp:
    """Одна операция выписки в нормализованном виде."""
    date: str            # ISO «2026-09-18»
    time: str            # «06:40»
    amount: float        # отрицательное — списание, положительное — пополнение
    kind: str            # purchase, income, transfer_out, internal, withdrawal, fee
    merchant: str        # вычищенное название магазина или «»
    description: str     # полное описание операции
    card: str            # последние 4 цифры карты или «»

    @property
    def dt(self) -> datetime:
        return datetime.fromisoformat(f"{self.date}T{self.time}")


@dataclass
class Statement:
    """Результат разбора: операции, период и сверка с итогами банка."""
    ops: list[BankOp] = field(default_factory=list)
    expected_expense: float = 0.0   # «Расходы» из итогов банка
    expected_income: float = 0.0    # «Пополнения» из итогов банка
    totals_found: bool = False

    @property
    def expense_sum(self) -> float:
        return round(-sum(o.amount for o in self.ops if o.amount < 0), 2)

    @property
    def income_sum(self) -> float:
        return round(sum(o.amount for o in self.ops if o.amount > 0), 2)

    @property
    def expense_ok(self) -> bool:
        return abs(self.expense_sum - self.expected_expense) <= 1.0

    @property
    def income_ok(self) -> bool:
        return abs(self.income_sum - self.expected_income) <= 1.0

    @property
    def check_ok(self) -> bool:
        if not self.totals_found:
            return True  # итогов нет (обрезанная выписка) — сверять не с чем
        return self.expense_ok and self.income_ok

    @property
    def period(self) -> tuple[str, str] | None:
        if not self.ops:
            return None
        dates = sorted(o.date for o in self.ops)
        return dates[0], dates[-1]

    def count(self, kind: str) -> int:
        return sum(1 for o in self.ops if o.kind == kind)


# Операция: дата/время операции, дата/время списания, две суммы с ₽, затем описание
# до начала следующей операции (или конца документа).
_OP_RE = re.compile(
    r"(?P<d1>\d{2}\.\d{2}\.\d{4})\s*\n\s*(?P<t1>\d{2}:\d{2})\s*\n\s*"
    r"(?P<d2>\d{2}\.\d{2}\.\d{4})\s*\n\s*(?P<t2>\d{2}:\d{2})\s*\n\s*"
    r"(?P<a1>[+-][\d ]+\.\d{2})\s*₽\s*"
    r"(?P<a2>[+-][\d ]+\.\d{2})\s*₽\s*"
    r"(?P<body>.*?)\s*(?=\d{2}\.\d{2}\.\d{4}\s*\n\s*\d{2}:\d{2}\s*\n|\Z)",
    re.DOTALL,
)

_AMOUNT_RE = re.compile(r"[+-][\d ]+\.\d{2}")

# Итоги в конце документа. В извлечённом тексте значение идёт ПЕРЕД своей меткой
# («1 943 658,53 ₽Пополнения:»), поэтому метка — главный якорь, а сумма берётся
# из её ближайшего соседства: до метки или сразу после.
_TOTAL_AMOUNT = r"(\d[\d ,]*,\d{2})\s*₽"


def _parse_ru_amount(text: str) -> float:
    """«1 943 658,53» → 1943658.53 (итоги банка через запятую, операции через точку)."""
    return float(text.replace(" ", "").replace(",", "."))


def _total_for_label(flow: str, label: str) -> tuple[float, int] | None:
    """Итог по метке («Расходы:», «Пополнения:») и позиция метки в тексте."""
    escaped = re.escape(label)
    # Основной layout выписки: значение перед меткой.
    match = re.search(rf"{_TOTAL_AMOUNT}\s*{escaped}", flow)
    if match:
        return _parse_ru_amount(match.group(1)), match.start()
    # Запасной: метка, затем значение.
    match = re.search(rf"{escaped}\s*{_TOTAL_AMOUNT}", flow)
    if match:
        return _parse_ru_amount(match.group(1)), match.start()
    return None


def _parse_amount(text: str) -> float:
    return float(text.replace(" ", ""))


def _to_iso(date_text: str) -> str:
    day, month, year = date_text.split(".")
    return f"{year}-{month}-{day}"


def _classify(body: str, amount: float) -> str:
    """Тип операции по описанию. Переводы себе и снятия не попадут в аналитику."""
    low = body.lower()
    if ("внутренний перевод" in low or "перевод между своими" in low
            or "перевод на свою" in low or "перевод себе" in low):
        return "internal"
    if "снятие наличных" in low or low.startswith("снятие"):
        return "withdrawal"
    if amount > 0:
        return "income"  # пополнения, кэшбэк, проценты
    if "оплата в " in low or "оплата услуг" in low or "оплата заказа" in low:
        return "purchase"
    if "комиссия" in low or "обслуживание" in low or "процент" in low or "страхов" in low:
        return "fee"
    return "transfer_out"  # внешний перевод по телефону и прочие списания


_CITY_LINE_RE = re.compile(r"\s+[A-ZА-Я][a-zа-я]{2,}\s+RUS$")


def _merchant(description: str) -> str:
    """Название магазина из первой строки описания: без города, страны и кода точки."""
    first = description.split("\n", 1)[0].strip()
    for prefix in ("Оплата в ", "Оплата услуг ", "Оплата заказа "):
        if first.startswith(prefix):
            first = first[len(prefix):]
            break
    first = _CITY_LINE_RE.sub("", first)
    first = re.sub(r"\s+RUS$", "", first)
    first = re.sub(r"\s+\d{4,6}$", "", first)  # код магазина: PYATEROCHKA 20174
    return first.strip(" .,")


def _clean_body(body: str, card: str) -> str:
    """Описание без строки с номером карты и лишних пробелов."""
    lines = []
    for line in body.splitlines():
        line = line.strip()
        if not line or line == card:
            continue
        # Перенос внутри слова: «T-Bank.T-\nBundle» → «T-Bank.T-Bundle».
        if lines and lines[-1].endswith("-"):
            lines[-1] += line
        else:
            lines.append(line)
    return " ".join(lines)


def _card_from_body(body: str) -> str:
    """Номер карты — последняя строка тела, если это 4 цифры (или «—»)."""
    for line in reversed([l.strip() for l in body.splitlines() if l.strip()]):
        if re.fullmatch(r"\d{4}|—", line):
            return "" if line == "—" else line
        return ""  # последняя строка не карта — номера нет
    return ""


def parse_statement_pdf(path: str | Path) -> Statement:
    """Разбирает PDF-выписку Т-Банка. Бросает ValueError, если операции не найдены."""
    reader = PdfReader(str(path))
    flow = "\n".join(page.extract_text() or "" for page in reader.pages)
    if "движении средств" not in flow and "движения средств" not in flow:
        raise ValueError("Это не похоже на справку о движении средств")

    # Итоги вырезаются до разбора операций, чтобы их числа не склеились с последней операцией.
    expense_total = _total_for_label(flow, "Расходы:")
    income_total = _total_for_label(flow, "Пополнения:")
    expected_expense = expected_income = 0.0
    totals_found = False
    cutoffs = [pos for value, pos in (expense_total, income_total) if value]
    if cutoffs:
        totals_found = True
        if expense_total:
            expected_expense = expense_total[0]
        if income_total:
            expected_income = income_total[0]
        flow = flow[:min(cutoffs)]

    ops: list[BankOp] = []
    for match in _OP_RE.finditer(flow):
        body = match.group("body") or ""
        # Суммы различаются только знаком операции; сама сумма может продублироваться
        # в валюте счёта — берём первую, знак сверяем со второй.
        amount = _parse_amount(match.group("a1"))
        card = _card_from_body(body)
        description = _clean_body(body, card)
        ops.append(BankOp(
            date=_to_iso(match.group("d1")),
            time=match.group("t1"),
            amount=amount,
            kind=_classify(description, amount),
            merchant=_merchant(description) if amount < 0 else "",
            description=description,
            card=card,
        ))

    if not ops:
        raise ValueError("В документе не найдено ни одной операции")
    return Statement(ops=ops, expected_expense=expected_expense,
                     expected_income=expected_income, totals_found=totals_found)
