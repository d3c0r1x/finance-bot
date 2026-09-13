"""Картинки-графики для отчётов и главного экрана.

Текстовый отчёт читается плохо: по строкам «еда: 12 000 ₽» не видно, куда уходит месяц.
Здесь те же числа рисуются картинкой — кольцо по категориям, полосы по лимитам и полоса
остатка бюджета, в тёмной теме как в приложениях-конкурентах.

Matplotlib необязателен: если его нет, функции вернут None, а отчёт останется текстовым.
"""
import asyncio
from datetime import datetime

try:  # необязательная зависимость: без неё отчёты просто остаются текстовыми
    import matplotlib

    matplotlib.use("Agg")
    from matplotlib import pyplot as plt
    from matplotlib.patches import Rectangle

    CHARTS_AVAILABLE = True
except ImportError:  # pragma: no cover
    CHARTS_AVAILABLE = False

BACKGROUND = "#1b1d22"
PANEL = "#262930"
TEXT = "#eceef1"
MUTED = "#9aa0a6"
ACCENT = "#4fd48c"
WARN = "#ffb347"
DANGER = "#ff6b6b"
GRID = "#33373f"

# Цвета категорий: похожие оттенки не стоят рядом, чтобы доли читались
COLORS = ("#4fd48c", "#5aa9e6", "#ffb347", "#b98cf0", "#ff6b6b", "#f2d16b",
          "#4ec9c0", "#e88ac0", "#8fb3ff", "#c9c9c9")

CHART_SIZE = (9.6, 5.4)
CHART_DPI = 110


def _money(value: float) -> str:
    """«12 345 ₽» — как в сообщениях бота."""
    number = round(float(value or 0))
    return f"{number:,}".replace(",", " ") + " ₽"


def _figure(title: str):
    figure = plt.figure(figsize=CHART_SIZE, dpi=CHART_DPI, facecolor=BACKGROUND)
    figure.text(0.035, 0.925, title, color=TEXT, fontsize=16, fontweight="bold", va="center")
    return figure


def _panel(axes, *, facecolor=PANEL):
    axes.set_facecolor(facecolor)
    for spine in axes.spines.values():
        spine.set_visible(False)


def _category_colors(spending: dict) -> dict[str, str]:
    """Один цвет на категорию: кольцо и полосы должны совпадать по цвету."""
    ordered = sorted(spending.items(), key=lambda pair: -pair[1])
    return {category: COLORS[index % len(COLORS)] for index, (category, _) in enumerate(ordered)}


def _ring(axes, spending: dict, total: float, colors: dict[str, str]) -> None:
    """Кольцо долей по категориям с суммой расходов в центре.

    Подписи категорий живут на полосах справа — так кольцо остаётся читаемым, а текст
    не наезжает на нижние строки с доходом.
    """
    _panel(axes, facecolor=BACKGROUND)
    items = sorted(spending.items(), key=lambda pair: -pair[1])[:9]
    values = [amount for _, amount in items]
    if sum(values) <= 0:
        axes.axis("off")
        return
    axes.pie(values, colors=[colors[category] for category, _ in items], startangle=90,
             counterclock=False, wedgeprops=dict(width=0.40, edgecolor=BACKGROUND, linewidth=2))
    axes.text(0, 0.10, _money(total), ha="center", va="center", color=TEXT,
              fontsize=13, fontweight="bold")
    axes.text(0, -0.16, "расходы", ha="center", va="center", color=MUTED, fontsize=9)
    axes.set_aspect("equal")


def _bars(axes, spending: dict, limits: dict | None, colors: dict[str, str]) -> None:
    """Полосы по категориям; оранжевая метка — лимит категории, красная полоса — перерасход."""
    _panel(axes)
    items = sorted(spending.items(), key=lambda pair: -pair[1])[:8][::-1]
    if not items:
        axes.axis("off")
        return
    limits = limits or {}
    top = max(amount for _, amount in items)
    scale = max(top, *(limits.get(category, 0) for category, _ in items)) or 1
    axes.set_xlim(0, scale * 1.28)
    for index, (category, amount) in enumerate(items):
        limit = limits.get(category) or 0
        color = DANGER if limit and amount > limit else colors.get(category, ACCENT)
        axes.barh(index, amount, height=0.52, color=color)
        axes.text(amount + scale * 0.02, index, _money(amount), va="center",
                  color=TEXT, fontsize=9)
        if limit:
            axes.plot([limit, limit], [index - 0.32, index + 0.32], color=WARN, linewidth=2)
    axes.set_yticks(range(len(items)), [category.capitalize() for category, _ in items],
                    color=TEXT, fontsize=10)
    axes.set_xticks([])
    axes.tick_params(length=0)
    axes.grid(axis="x", color=GRID, linewidth=0.6, alpha=0.7)
    axes.set_axisbelow(True)
    if limits and any(limits.get(category) for category, _ in items):
        axes.text(scale * 1.28, len(items) - 0.35, "│ — лимит", color=WARN, fontsize=8.5,
                  ha="right", va="center")


def _budget_strip(figure, spent: float, total_limit: float) -> None:
    """Полоса остатка бюджета под графиками: сколько уже израсходовано от лимита."""
    if not total_limit:
        return
    share = min(1.0, spent / total_limit)
    color = ACCENT if share < 0.75 else (WARN if share <= 1 else DANGER)
    left, width, bottom = 0.055, 0.89, 0.085
    figure.add_artist(Rectangle((left, bottom), width, 0.028, transform=figure.transFigure,
                                facecolor=PANEL, edgecolor="none"))
    figure.add_artist(Rectangle((left, bottom), width * share, 0.028, transform=figure.transFigure,
                                facecolor=color, edgecolor="none"))
    text = (f"Израсходовано {_money(spent)} из {_money(total_limit)} "
            f"({share * 100:.0f}%)")
    if spent > total_limit:
        text = f"Перерасход {_money(spent - total_limit)} — потрачено {_money(spent)}"
    figure.text(left, bottom + 0.045, text, color=color, fontsize=10)


def _render(figure) -> bytes:
    from io import BytesIO

    buffer = BytesIO()
    figure.savefig(buffer, format="png", facecolor=BACKGROUND)
    plt.close(figure)
    return buffer.getvalue()


def _month_card_sync(spending: dict, income: float, spent: float, total_limit: float,
                     forecast: float | None, title: str, limits: dict | None) -> bytes:
    figure = _figure(title)
    colors = _category_colors(spending)
    ring_axes = figure.add_axes((0.045, 0.40, 0.33, 0.46))
    bars_axes = figure.add_axes((0.50, 0.28, 0.46, 0.58))
    _ring(ring_axes, spending, spent, colors)
    _bars(bars_axes, spending, limits, colors)
    _budget_strip(figure, spent, total_limit)

    figure.text(0.055, 0.295, f"Доход: {_money(income)}" if income else "Доход: —",
                color=TEXT, fontsize=10)
    figure.text(0.055, 0.23, f"Расходы: {_money(spent)}", color=TEXT, fontsize=10)
    figure.text(0.055, 0.175, f"Категорий с тратами: {len(spending)}", color=MUTED, fontsize=9)
    if forecast:
        caption = f"Прогноз к концу месяца: {_money(forecast)}"
        color = DANGER if total_limit and forecast > total_limit else MUTED
        figure.text(0.055, 0.028, caption, color=color, fontsize=10)
    return _render(figure)


def _period_card_sync(by_category: dict, total: float, days: int, title: str,
                      daily: list[tuple[str, float]] | None) -> bytes:
    figure = _figure(title)
    colors = _category_colors(by_category)
    ring_axes = figure.add_axes((0.045, 0.44, 0.31, 0.44))
    bars_axes = figure.add_axes((0.50, 0.30, 0.46, 0.54))
    _ring(ring_axes, by_category, total, colors)
    _bars(bars_axes, by_category, None, colors)
    figure.text(0.055, 0.145, f"Всего: {_money(total)} за {days} дн. · "
                              f"в среднем {_money(total / max(days, 1))} в день",
                color=TEXT, fontsize=10)
    if daily:
        axes = figure.add_axes((0.055, 0.045, 0.89, 0.12))
        _panel(axes, facecolor=BACKGROUND)
        labels = [day for day, _ in daily]
        values = [amount for _, amount in daily]
        axes.fill_between(range(len(values)), values, color=ACCENT, alpha=0.28)
        axes.plot(range(len(values)), values, color=ACCENT, linewidth=1.6)
        axes.set_ylim(0, max(values) * 1.25 or 1)
        axes.set_xlim(0, max(len(values) - 1, 1))
        step = max(1, len(labels) // 7)
        axes.set_xticks(range(0, len(labels), step), [labels[index] for index in range(0, len(labels), step)],
                        color=MUTED, fontsize=8)
        axes.set_yticks([])
        axes.tick_params(length=0)
        axes.grid(axis="y", color=GRID, linewidth=0.5, alpha=0.5)
    return _render(figure)


async def month_card(spending: dict, income: float, spent: float, total_limit: float,
                     forecast: float | None = None, title: str | None = None,
                     limits: dict | None = None) -> bytes | None:
    """Картинка месячного отчёта: кольцо категорий, полосы, остаток лимита."""
    if not CHARTS_AVAILABLE or not spending:
        return None
    from utils.formatting import month_name_ru

    caption = title or f"Расходы за {month_name_ru()}"
    try:
        return await asyncio.to_thread(_month_card_sync, spending, income, spent, total_limit,
                                       forecast, caption, limits)
    except Exception:  # pragma: no cover — картинка не должна ломать отчёт
        return None


async def period_card(by_category: dict, total: float, days: int,
                      daily: list[tuple[str, float]] | None = None) -> bytes | None:
    """Картинка отчёта за период: доли категорий и траты по дням."""
    if not CHARTS_AVAILABLE or not by_category:
        return None
    title = {7: "Расходы за неделю", 14: "Расходы за 2 недели",
             90: "Расходы за 90 дней"}.get(days, f"Расходы за {days} дней")
    try:
        return await asyncio.to_thread(_period_card_sync, by_category, total, days, title, daily)
    except Exception:  # pragma: no cover
        return None


def daily_series(transactions, days: int = 7) -> list[tuple[str, float]]:
    """Траты по дням за последние `days` дней: [(«05.09», 1234.5)]."""
    buckets: dict[str, float] = {}
    for row in transactions:
        if row["tx_type"] != "expense":
            continue
        try:
            day = datetime.fromisoformat(row["created_at"]).strftime("%d.%m")
        except (TypeError, ValueError):
            continue
        buckets[day] = buckets.get(day, 0) + row["amount"]
    return sorted(buckets.items(), key=lambda pair: pair[0])
