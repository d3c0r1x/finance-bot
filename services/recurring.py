"""Регулярные платежи: подписки и повторяющиеся списания, найденные в истории.

Смысл — предупредить о списании, которое легко забыть: подписки, взносы, коммунальные
платежи. Это не прогноз и не «ИИ угадал»: серия находится по самой истории, а правила
консервативные — лучше промолчать, чем назвать подпиской случайные походы в магазин.
Поэтому нужны минимум три списания, ровный интервал и похожая сумма.
"""
import re
from datetime import datetime, timedelta

from services.purchase_history import STOP_WORDS
from utils.formatting import format_amount

# Сколько раз должно повториться списание, чтобы это была серия, а не совпадение
MIN_OCCURRENCES = 3
# Разброс сумм внутри серии: у подписок он почти нулевой, у коммуналки бывает больше
AMOUNT_TOLERANCE = 0.25
# Разброс интервалов и доля «ровных» интервалов среди всех
INTERVAL_TOLERANCE = 0.25
INTERVAL_SHARE = 0.6
# Циклы, которые считаем регулярными: (от, до, как называть в тексте)
PERIODS = ((6, 8, "неделю"), (25, 35, "месяц"))
# На сколько дней вперёд показывать «скоро спишется»
UPCOMING_DAYS = 3


def _moment(value) -> datetime | None:
    try:
        return datetime.fromisoformat(value)
    except (TypeError, ValueError):
        return None


def _key(row: dict) -> str:
    """Ключ серии: одинаковое описание с точностью до порядка слов и служебных слов.

    «Netflix» и «NETFLIX 999» дают один ключ, а «Пятёрочка» и «Магнит» — разные, поэтому
    разные магазины никогда не сливаются в одну «подписку».
    """
    text = f"{row.get('description') or ''} {row.get('category') or ''}".lower().replace("ё", "е")
    words = [word for word in re.findall(r"[a-zа-я0-9]+", text)
             if len(word) >= 3 and word not in STOP_WORDS]
    return " ".join(sorted(set(words)))


def _period_label(median_days: int) -> str | None:
    for low, high, label in PERIODS:
        if low <= median_days <= high:
            return label
    return None


def _is_regular(deltas: list[int], median: int) -> bool:
    if median <= 0:
        return False
    even = sum(1 for delta in deltas if abs(delta - median) <= median * INTERVAL_TOLERANCE)
    return even >= len(deltas) * INTERVAL_SHARE


def find_recurring(rows: list[dict], today: datetime | None = None,
                   min_occurrences: int = MIN_OCCURRENCES,
                   tx_type: str = "expense") -> list[dict]:
    """Находит повторяющиеся операции: одно описание, похожая сумма, ровный интервал.

    На вход идут строки транзакций как словари (в базе они лежат как sqlite3.Row).
    Результат отсортирован по близости следующей операции. По умолчанию ищутся расходы,
    но `tx_type="income"` находит зарплату — от её даты зависит, сколько денег свободно.
    """
    today = (today or datetime.now()).replace(hour=0, minute=0, second=0, microsecond=0)
    groups: dict[str, list[dict]] = {}
    for row in rows:
        if row.get("tx_type") != tx_type:
            continue
        moment = _moment(row.get("created_at"))
        if moment is None:
            continue
        key = _key(row)
        if not key:
            continue
        groups.setdefault(key, []).append({**row, "_moment": moment})

    found: list[dict] = []
    # Ключ берём из самого словаря групп: в item он нужен, чтобы кнопка «это не подписка»
    # отключала именно эту серию, а не первую попавшуюся.
    for key, series in groups.items():
        if len(series) < min_occurrences:
            continue
        series.sort(key=lambda item: item["_moment"])
        amounts = [float(item.get("amount") or 0) for item in series]
        average = sum(amounts) / len(amounts)
        if average <= 0 or max(amounts) - min(amounts) > average * AMOUNT_TOLERANCE:
            continue  # суммы скачут — это не подписка, а обычные покупки
        deltas = [(later["_moment"] - earlier["_moment"]).days
                  for earlier, later in zip(series, series[1:]) if
                  (later["_moment"] - earlier["_moment"]).days > 0]
        if len(deltas) < min_occurrences - 1:
            continue  # несколько записей в один день — не интервалы
        median = sorted(deltas)[len(deltas) // 2]
        label = _period_label(median)
        if label is None or not _is_regular(deltas, median):
            continue
        last = series[-1]["_moment"]
        expected = last + timedelta(days=median)
        found.append({
            "key": key,
            "name": series[-1].get("description") or series[-1].get("category") or "Платёж",
            "category": series[-1].get("category"),
            "amount": round(average, 2),
            "period_days": median,
            "period_label": label,
            "occurrences": len(series),
            "last_date": last,
            "next_date": expected,
            "days_left": (expected - today).days,
        })
    # Сначала то, что спишется впереди, потом самые дорогие; просроченные серии — в конце,
    # иначе они сдвигают наверх весь список.
    found.sort(key=lambda item: (item["days_left"] < 0, item["days_left"], -item["amount"]))
    return found


def due_soon(items: list[dict], within: int = UPCOMING_DAYS) -> list[dict]:
    """Что спишется в ближайшие дни — для ежедневной сводки и предупреждения."""
    return [item for item in items if 0 <= item["days_left"] <= within]


def next_income(items: list[dict]) -> dict | None:
    """Ближайшее ожидаемое поступление (зарплата и т. п.), если его удалось найти.

    Прошлые ожидания не считаются: если деньги должны были прийти и не пришли,
    горизонт планирования по ним строить нельзя.
    """
    upcoming = [item for item in items if item["days_left"] >= 0]
    return min(upcoming, key=lambda item: item["days_left"]) if upcoming else None


def total_before(items: list[dict], days: int) -> float:
    """Сколько денег уйдёт на регулярные платежи за ближайшие `days` дней включительно.

    Последний день считается тоже: если списание и зарплата в один день, деньги уже
    обещаны, и записывать их в свободные нельзя. Это и есть главная ошибка в подсказках
    «можно тратить столько в день».
    """
    return round(sum(item["amount"] for item in items if 0 <= item["days_left"] <= days), 2)


def monthly_total(items: list[dict]) -> float:
    """Во сколько регулярные платежи обходятся в месяц.

    Месячная серия считается как одно списание в месяц независимо от того, 30 дней в ней
    или 31 день: иначе подписка «999 ₽ раз в месяц» показывалась бы как 966 ₽ и выглядела
    бы ошибкой. Недельные и прочие приводятся к 30 дням.
    """
    total = 0.0
    for item in items:
        days = max(item["period_days"], 1)
        total += item["amount"] if 25 <= days <= 35 else item["amount"] * 30 / days
    return round(total, 2)


def when_label(days_left: int) -> str:
    """«сегодня», «завтра», «через N дн.» — одна формулировка на все экраны."""
    if days_left <= 0:
        return "сегодня"
    if days_left == 1:
        return "завтра"
    return f"через {days_left} дн."


def recurring_text(items: list[dict], muted: list[dict] | None = None) -> str:
    """Текст экрана регулярных платежей: что спишется скоро и во сколько это в месяц."""
    if not items:
        text = ("🔁 **Регулярные платежи**\n\n"
                "Пока не нашёл ни одной серии: ищу списания, которые повторяются примерно "
                "раз в месяц или в неделю одинаковой суммой и минимум три раза.\n\n"
                "Записывай траты — через месяц-два здесь появится список подписок, "
                "а бот будет предупреждать о списании заранее.")
        if muted:
            text += ("\n\n🔕 Отключены из напоминаний: "
                     + ", ".join(item["name"] for item in muted) + ".")
        return text
    lines = ["🔁 **Регулярные платежи**", ""]
    upcoming = due_soon(items)
    if upcoming:
        lines.append("**Скоро спишется:**")
        for item in upcoming:
            lines.append(f"   • {item['name']} — {format_amount(item['amount'])}, "
                         f"{when_label(item['days_left'])}")
        lines.append("")
    lines.append("**Все найденные:**")
    for item in items:
        lines.append(f"   • {item['name']} — {format_amount(item['amount'])} раз в "
                     f"{item['period_label']} · последний раз {item['last_date']:%d.%m}")
    lines += ["",
              f"💰 В месяц на регулярные платежи уходит примерно "
              f"**{format_amount(monthly_total(items))}**.",
              "Я считаю их по твоей же истории, поэтому в список попадают только настоящие "
              "повторы: три раза и больше с ровным интервалом и похожей суммой."]
    lines.append("Если что-то из этого — не подписка, отключи кнопкой ниже: серия останется "
                 "в истории, но напоминать о ней я перестану.")
    return "\n".join(lines)


def upcoming_text(items: list[dict]) -> str:
    """Короткая строка для ежедневной сводки: что списывается на днях."""
    upcoming = due_soon(items)
    if not upcoming:
        return ""
    lines = ["🔁 **Скоро спишется:**"]
    for item in upcoming:
        lines.append(f"   • {item['name']} — {format_amount(item['amount'])}, "
                     f"{when_label(item['days_left'])}")
    return "\n".join(lines)
