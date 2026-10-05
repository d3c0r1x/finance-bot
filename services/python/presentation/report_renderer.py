"""Render the finance report DTO without recalculating business totals."""

from __future__ import annotations

import re
from collections.abc import Callable, Mapping
from datetime import date, datetime, timedelta
from decimal import Decimal, InvalidOperation
from io import BytesIO


MONEY_PATTERN = re.compile(r"^\d+\.\d{2}$")
SIGNED_MONEY_PATTERN = re.compile(r"^-?\d+\.\d{2}$")
PNG_SIZE = (960, 640)
PRODUCT_PNG_WIDTH = 960


class RendererUnavailable(RuntimeError):
    """Raised when the optional image renderer cannot be loaded."""


def _money(value: object, field: str) -> Decimal:
    if not isinstance(value, str) or not MONEY_PATTERN.fullmatch(value):
        raise ValueError(f"{field} must be a decimal string with two places")
    try:
        amount = Decimal(value)
    except InvalidOperation as error:
        raise ValueError(f"{field} is not a valid amount") from error
    if not amount.is_finite() or amount < 0:
        raise ValueError(f"{field} must be finite and non-negative")
    return amount


def _signed_money(value: object, field: str) -> Decimal:
    if not isinstance(value, str) or not SIGNED_MONEY_PATTERN.fullmatch(value):
        raise ValueError(f"{field} must be a signed decimal string with two places")
    try:
        amount = Decimal(value)
    except InvalidOperation as error:
        raise ValueError(f"{field} is not a valid amount") from error
    if not amount.is_finite():
        raise ValueError(f"{field} must be finite")
    return amount


def _validated_report(report: Mapping[str, object]) -> tuple[
    date, date, dict[str, Decimal], dict[str, Decimal], tuple[Decimal, Decimal] | None,
    Mapping[str, object] | None,
]:
    try:
        from_date = date.fromisoformat(_required_text(report, "fromDate"))
        to_date = date.fromisoformat(_required_text(report, "toDate"))
    except ValueError as error:
        raise ValueError("Report dates must be ISO local dates") from error
    if from_date > to_date or (to_date - from_date).days > 365:
        raise ValueError("Report date range must be between one and 366 days")

    for field in ("incomeTotal", "expenseTotal", "debtPaymentTotal", "refundTotal"):
        _money(report.get(field), field)
    _required_text(report, "currency")
    _required_text(report, "timezone")
    if not isinstance(report.get("transactionCount"), int) or report["transactionCount"] < 0:
        raise ValueError("transactionCount must be a non-negative integer")

    raw_categories = report.get("expenseByCategory")
    raw_days = report.get("expenseByDay")
    if not isinstance(raw_categories, Mapping) or not isinstance(raw_days, Mapping):
        raise ValueError("Expense chart data must be objects")
    categories: dict[str, Decimal] = {}
    for key, value in raw_categories.items():
        if not isinstance(key, str) or not key or len(key) > 64:
            raise ValueError("Category labels must be non-empty strings up to 64 characters")
        categories[key] = _money(value, f"expenseByCategory.{key}")

    days: dict[str, Decimal] = {}
    for key, value in raw_days.items():
        if not isinstance(key, str):
            raise ValueError("expenseByDay keys must be ISO local dates")
        try:
            parsed = date.fromisoformat(key)
        except ValueError as error:
            raise ValueError("expenseByDay keys must be ISO local dates") from error
        if parsed < from_date or parsed > to_date or parsed.isoformat() != key:
            raise ValueError("expenseByDay dates must be canonical and inside the report window")
        days[key] = _money(value, f"expenseByDay.{key}")
    expected_days = {(from_date + timedelta(days=offset)).isoformat()
                     for offset in range((to_date - from_date).days + 1)}
    if set(days) != expected_days:
        raise ValueError("expenseByDay must include every report date, including zero values")
    raw_limit = report.get("monthlyBudgetLimit")
    raw_remaining = report.get("monthlyBudgetRemaining")
    budget = None
    if raw_limit is not None or raw_remaining is not None:
        if raw_limit is None or raw_remaining is None:
            raise ValueError("Monthly budget limit and remaining must both be provided")
        budget = (_money(raw_limit, "monthlyBudgetLimit"),
                  _signed_money(raw_remaining, "monthlyBudgetRemaining"))
    food_status = _validated_rolling_food_status(report.get("rolling7FoodStatus"))
    return from_date, to_date, categories, days, budget, food_status


def _validated_rolling_food_status(value: object) -> Mapping[str, object] | None:
    if value is None:
        return None
    if not isinstance(value, Mapping):
        raise ValueError("rolling7FoodStatus must be an object or null")
    for field in ("fromDate", "toDate"):
        try:
            parsed = date.fromisoformat(_required_text(value, field))
        except ValueError as error:
            raise ValueError("Rolling food dates must be ISO local dates") from error
        if parsed.isoformat() != value[field]:
            raise ValueError("Rolling food dates must be canonical ISO dates")
    if date.fromisoformat(value["fromDate"]) > date.fromisoformat(value["toDate"]):
        raise ValueError("Rolling food date range is invalid")

    limit = _money(value.get("limit"), "rolling7FoodStatus.limit")
    _money(value.get("spent"), "rolling7FoodStatus.spent")
    remaining_value = value.get("remaining")
    if remaining_value is not None:
        _signed_money(remaining_value, "rolling7FoodStatus.remaining")
    usual = value.get("usualWeeklySpend")
    if usual is not None:
        _money(usual, "rolling7FoodStatus.usualWeeklySpend")
    history_weeks = value.get("historyWeeks")
    if type(history_weeks) is not int or history_weeks < 0:
        raise ValueError("rolling7FoodStatus.historyWeeks must be a non-negative integer")
    limit_status = value.get("limitStatus")
    pace_status = value.get("paceStatus")
    if limit_status not in {"disabled", "normal", "near", "exceeded"}:
        raise ValueError("rolling7FoodStatus.limitStatus is invalid")
    if pace_status not in {"under", "normal", "over", "insufficient_history"}:
        raise ValueError("rolling7FoodStatus.paceStatus is invalid")
    if (limit == 0) != (limit_status == "disabled"):
        raise ValueError("rolling7FoodStatus limit status must match limit amount")
    if (limit == 0) != (remaining_value is None):
        raise ValueError("rolling7FoodStatus remaining must match enabled limit")
    if pace_status == "insufficient_history":
        if usual is not None:
            raise ValueError("Insufficient food history cannot include usual weekly spend")
    elif usual is None or history_weeks < 2:
        raise ValueError("Historical food pace requires at least two purchase weeks")
    return value


def _required_text(report: Mapping[str, object], field: str) -> str:
    value = report.get(field)
    if not isinstance(value, str) or not value:
        raise ValueError(f"{field} is required")
    return value


def render_report_png(report: Mapping[str, object]) -> bytes:
    from_date, to_date, categories, days, budget, _food_status = _validated_report(report)
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError as error:
        raise RendererUnavailable("Pillow is unavailable") from error

    image = Image.new("RGB", PNG_SIZE, "#f7f9fc")
    draw = ImageDraw.Draw(image)
    font = ImageFont.load_default(size=17)
    title_font = ImageFont.load_default(size=30)
    subtitle_font = ImageFont.load_default(size=15)
    draw.text((36, 28), "Finance report", fill="#172338", font=title_font)
    draw.text((38, 70), f"{from_date.isoformat()} - {to_date.isoformat()} · {_required_text(report, 'timezone')}",
              fill="#68778e", font=subtitle_font)

    currency = _required_text(report, "currency")
    summary = [
        ("Income", str(report["incomeTotal"])),
        ("Expenses", str(report["expenseTotal"])),
        ("Debt payments", str(report["debtPaymentTotal"])),
        ("Refunds", str(report["refundTotal"])),
    ]
    for index, (label, amount) in enumerate(summary):
        x = 36 + index * 225
        draw.rounded_rectangle((x, 106, x + 208, 164), radius=10, fill="white", outline="#e2e8f0")
        draw.text((x + 12, 116), label, fill="#68778e", font=font)
        draw.text((x + 12, 138), f"{amount} {currency}", fill="#172338", font=font)

    if budget is not None and budget[0] > 0:
        limit, remaining = budget
        spent = max(Decimal("0.00"), limit - remaining)
        draw.text((38, 184), "Monthly budget usage", fill="#25354b", font=font)
        draw.rounded_rectangle((250, 188, 520, 202), radius=7, fill="#e8edf4")
        width = round(270 * min(Decimal("1"), spent / limit))
        if width:
            draw.rounded_rectangle((250, 188, 250 + width, 202), radius=7, fill="#78aee6")
        draw.text((540, 181), f"{spent:.2f} / {limit:.2f} {currency}", fill="#25354b", font=subtitle_font)
        draw.text((540, 199), f"Remaining: {remaining:.2f} {currency}", fill="#68778e", font=subtitle_font)
    elif budget is not None:
        draw.text((38, 184), f"Monthly budget disabled: 0.00 {currency}; remaining: {budget[1]:.2f} {currency}",
                  fill="#68778e", font=subtitle_font)
    draw.text((38, 228), "Expenses by category", fill="#25354b", font=font)
    sorted_categories = sorted(categories.items(), key=lambda pair: (-pair[1], pair[0]))[:10]
    category_max = max((amount for _, amount in sorted_categories), default=Decimal("0"))
    if not sorted_categories:
        draw.text((38, 252), "No category expenses", fill="#8290a5", font=font)
    for index, (label, amount) in enumerate(sorted_categories):
        y = 252 + index * 28
        draw.text((38, y), label[:18], fill="#526177", font=font)
        draw.rounded_rectangle((190, y + 6, 436, y + 17), radius=6, fill="#e8edf4")
        width = int(246 * amount / category_max) if category_max > 0 else 0
        if width:
            draw.rounded_rectangle((190, y + 6, 190 + width, y + 17), radius=6, fill="#78aee6")
        draw.text((444, y), f"{amount:.2f}", fill="#25354b", font=font)

    draw.text((38, 530), "Daily expenses", fill="#25354b", font=font)
    graph = (518, 220, 920, 500)
    left, top, right, bottom = graph
    for gridline in range(4):
        y = top + gridline * (bottom - top) // 3
        draw.line((left, y, right, y), fill="#e2e8f0", width=1)
    values = [days[key] for key in sorted(days)]
    max_day = max(values, default=Decimal("0"))
    points = []
    for index, value in enumerate(values):
        x = left + (right - left) * index / max(1, len(values) - 1)
        ratio = float(value / max_day) if max_day > 0 else 0.0
        y = bottom - ratio * (bottom - top - 12)
        points.append((round(x), round(y)))
    if len(points) > 1:
        draw.line(points, fill="#5d9c54", width=4, joint="curve")
    for x, y in points:
        draw.ellipse((x - 4, y - 4, x + 4, y + 4), fill="#5d9c54")
    draw.text((left, 506), from_date.isoformat(), fill="#68778e", font=subtitle_font)
    end_label = to_date.isoformat()
    draw.text((right - 94, 506), end_label, fill="#68778e", font=subtitle_font)

    output = BytesIO()
    image.save(output, format="PNG", optimize=True)
    return output.getvalue()


def rolling_food_lines(status: Mapping[str, object] | None, currency: str, language: str = "en") -> list[str]:
    """Format Core's rolling-food status consistently across report surfaces."""
    if language not in {"ru", "en"}:
        raise ValueError("Unsupported rolling-food language")
    food_status = _validated_rolling_food_status(status)
    if food_status is None:
        return []

    if language == "ru":
        if food_status["limitStatus"] == "disabled":
            lines = [f"Еда за 7 дней: {food_status['spent']} {currency} · лимит отключён"]
        else:
            lines = [f"Еда за 7 дней: {food_status['spent']} / {food_status['limit']} {currency}"]
            if food_status["remaining"] is not None:
                lines.append(f"Остаток лимита еды: {food_status['remaining']} {currency}")
        if food_status["paceStatus"] == "insufficient_history":
            lines.append("Исторический темп: недостаточно истории")
        else:
            lines.append(f"Обычный недельный расход: {food_status['usualWeeklySpend']} {currency} "
                         f"({food_status['historyWeeks']} недель истории)")
        return lines

    if food_status["limitStatus"] == "disabled":
        lines = [f"Rolling food, last 7 days: {food_status['spent']} {currency}; limit disabled"]
    else:
        lines = [f"Rolling food, last 7 days: {food_status['spent']} / {food_status['limit']} {currency}"]
        if food_status["remaining"] is not None:
            lines.append(f"Food limit remaining: {food_status['remaining']} {currency}")
    if food_status["paceStatus"] == "insufficient_history":
        lines.append("Historical pace: insufficient history")
    else:
        lines.append(f"Usual weekly spend: {food_status['usualWeeklySpend']} {currency} "
                     f"({food_status['historyWeeks']} weeks)")
    return lines


def report_text(report: Mapping[str, object]) -> str:
    _from_date, _to_date, categories, days, budget, food_status = _validated_report(report)
    currency = _required_text(report, "currency")
    lines = [
        f"Finance report: {_required_text(report, 'fromDate')} — {_required_text(report, 'toDate')}",
        f"Income: {report['incomeTotal']} {currency}",
        f"Expenses: {report['expenseTotal']} {currency}",
        f"Debt payments: {report['debtPaymentTotal']} {currency}",
        f"Refunds: {report['refundTotal']} {currency}",
        "Expenses by category:",
    ]
    lines.extend(f"{key}: {amount:.2f} {currency}" for key, amount in sorted(categories.items()))
    lines.append("Daily expenses: " + ", ".join(f"{key}: {amount:.2f} {currency}" for key, amount in days.items()))
    if budget is not None:
        lines.append(f"Monthly budget: {budget[0]:.2f} {currency}; remaining: {budget[1]:.2f} {currency}")
    lines.extend(rolling_food_lines(food_status, currency))
    return "\n".join(lines)


def render_report(report: Mapping[str, object],
                  png_renderer: Callable[[Mapping[str, object]], bytes] = render_report_png) -> tuple[str, bytes]:
    _validated_report(report)
    try:
        return "image/png", png_renderer(report)
    except RendererUnavailable:
        return "text/plain; charset=utf-8", report_text(report).encode("utf-8")


def _product_decimal(value: object, field: str, places: int, *, positive: bool = True) -> Decimal:
    if not isinstance(value, str):
        raise ValueError(f"{field} must be a decimal string")
    pattern = re.compile(rf"^-?\d{{1,30}}\.\d{{{places}}}$")
    if not pattern.fullmatch(value):
        raise ValueError(f"{field} has invalid decimal precision")
    try:
        amount = Decimal(value)
    except InvalidOperation as error:
        raise ValueError(f"{field} is invalid") from error
    if not amount.is_finite() or positive and amount <= 0:
        raise ValueError(f"{field} must be finite and positive")
    return amount


def _product_time(value: object, field: str) -> datetime:
    if not isinstance(value, str):
        raise ValueError(f"{field} must be an ISO timestamp")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError(f"{field} must be an ISO timestamp") from error
    if parsed.tzinfo is None:
        raise ValueError(f"{field} must include a time zone")
    return parsed


def _validated_product_catalog(catalog: Mapping[str, object]) -> tuple[str, str, list[Mapping[str, object]]]:
    mode, query, raw_products = catalog.get("mode"), catalog.get("query"), catalog.get("products")
    if mode not in {"catalog", "search"} or not isinstance(query, str) or len(query) > 80 \
            or mode != ("catalog" if not query.strip() else "search") or not isinstance(raw_products, list):
        raise ValueError("Product catalog mode, query or products are invalid")
    limit = 10 if mode == "catalog" else 5
    threshold = 3 if mode == "catalog" else 1
    if len(raw_products) > limit:
        raise ValueError("Product catalog exceeded its result limit")
    products: list[Mapping[str, object]] = []
    for product in raw_products:
        if not isinstance(product, Mapping):
            raise ValueError("Product catalog card must be an object")
        name = product.get("productName")
        count = product.get("purchaseCount")
        if not isinstance(name, str) or not name.strip() or len(name) > 200 \
                or type(count) is not int or not threshold <= count <= 5000:
            raise ValueError("Product catalog card identity or purchase count is invalid")
        for field in ("usualUnitPrice", "lastUnitPrice", "cheapestUnitPrice"):
            _product_decimal(product.get(field), field, 6)
        _product_decimal(product.get("totalSpent"), "totalSpent", 2)
        _product_time(product.get("lastPurchasedAt"), "lastPurchasedAt")
        has_baseline = product.get("hasBaseline")
        baseline, change, relative = (product.get(field) for field in
                                      ("baselineUnitPrice", "change", "relative"))
        signal, direction = product.get("signal"), product.get("direction")
        prior = product.get("priorPurchases")
        if type(has_baseline) is not bool or type(signal) is not bool or type(prior) is not int or prior < 0:
            raise ValueError("Product catalog baseline metadata is invalid")
        if has_baseline:
            if prior != count - 1 or baseline is None or change is None or relative is None:
                raise ValueError("Product price baseline is incomplete")
            _product_decimal(baseline, "baselineUnitPrice", 6)
            _product_decimal(change, "change", 6, positive=False)
            _product_decimal(relative, "relative", 6, positive=False)
            if signal and direction not in {"up", "down"} or not signal and direction is not None:
                raise ValueError("Product price signal is invalid")
        elif baseline is not None or change is not None or relative is not None or prior != 0 or signal or direction is not None:
            raise ValueError("Product baseline cannot exist without prior purchases")
        if has_baseline and signal and direction not in {"up", "down"}:
            raise ValueError("Product price signal direction is invalid")
        if product.get("lastMerchant") is not None and (
                not isinstance(product["lastMerchant"], str) or not product["lastMerchant"].strip()):
            raise ValueError("Last merchant must be a non-empty string or null")
        if product.get("cheapestMerchant") is not None and (
                not isinstance(product["cheapestMerchant"], str) or not product["cheapestMerchant"].strip()):
            raise ValueError("Cheapest merchant must be a non-empty string or null")
        history = product.get("history")
        chart_available = product.get("chartAvailable")
        if type(chart_available) is not bool or chart_available != (count >= 2) \
                or not isinstance(history, list) or not 1 <= len(history) <= min(count, 12):
            raise ValueError("Product price history is invalid")
        last_time = None
        for point in history:
            if not isinstance(point, Mapping) or not all(
                    isinstance(point.get(field), str) and point[field].strip()
                    for field in ("receiptId", "itemId", "name")):
                raise ValueError("Product history point is incomplete")
            point_time = _product_time(point.get("purchasedAt"), "history.purchasedAt")
            _product_decimal(point.get("unitPrice"), "history.unitPrice", 6)
            if point.get("current") is not False or last_time is not None and point_time < last_time:
                raise ValueError("Product history must contain ordered real purchases only")
            if point.get("merchant") is not None and (
                    not isinstance(point["merchant"], str) or not point["merchant"].strip()):
                raise ValueError("History merchant must be a non-empty string or null")
            last_time = point_time
        if len(history) != min(count, 12):
            raise ValueError("Product history must include its bounded recent purchase points")
        products.append(product)
    return mode, query.strip(), products


def render_product_catalog(catalog: Mapping[str, object]) -> tuple[str, bytes]:
    mode, query, products = _validated_product_catalog(catalog)
    if not products:
        text = "Подходящие покупки не найдены." if mode == "search" else "Каталог появится после трёх подтверждённых покупок."
        return "text/plain; charset=utf-8", text.encode("utf-8")
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError as error:
        raise RendererUnavailable("Pillow is unavailable") from error

    image_height = 112 + len(products) * 176
    image = Image.new("RGB", (PRODUCT_PNG_WIDTH, image_height), "#f7f9fc")
    draw = ImageDraw.Draw(image)
    try:
        title_font = ImageFont.truetype("DejaVuSans.ttf", 27)
        font = ImageFont.truetype("DejaVuSans.ttf", 16)
        small_font = ImageFont.truetype("DejaVuSans.ttf", 13)
    except OSError:
        title_font = ImageFont.load_default(size=27)
        font = ImageFont.load_default(size=16)
        small_font = ImageFont.load_default(size=13)
    title = "Каталог товаров" if mode == "catalog" else f"Поиск: {query}"
    draw.text((32, 22), title, fill="#172338", font=title_font)

    for index, product in enumerate(products):
        top = 72 + index * 176
        bottom = top + 160
        history = product["history"]
        assert isinstance(history, list)
        draw.rounded_rectangle((28, top, PRODUCT_PNG_WIDTH - 28, bottom), radius=12,
                               fill="white", outline="#e2e8f0", width=2)
        name = str(product["productName"])
        if len(name) > 48:
            name = name[:45] + "…"
        draw.text((44, top + 12), name, fill="#172338", font=font)
        count = int(product["purchaseCount"])
        draw.text((44, top + 42), f"Покупок: {count}", fill="#68778e", font=small_font)
        draw.text((44, top + 64), f"Обычная цена: {product['usualUnitPrice']} ₽", fill="#25354b", font=small_font)
        draw.text((44, top + 84), f"Последняя: {product['lastUnitPrice']} ₽ · "
                  f"{str(product['lastPurchasedAt'])[:10]}", fill="#25354b", font=small_font)
        merchant = product.get("cheapestMerchant") or "магазин не указан"
        draw.text((44, top + 104), f"Дешевле всего: {product['cheapestUnitPrice']} ₽ · {merchant}",
                  fill="#25354b", font=small_font)
        draw.text((44, top + 124), f"Всего: {product['totalSpent']} ₽", fill="#68778e", font=small_font)
        if product["hasBaseline"]:
            draw.text((44, top + 142), f"До последней покупки медиана: {product['baselineUnitPrice']} ₽",
                      fill="#68778e", font=small_font)
        elif count == 1:
            draw.text((44, top + 142), "Одна покупка · сравнения пока нет", fill="#68778e", font=small_font)
        if product["chartAvailable"] and len(history) >= 2:
            prices = [float(Decimal(str(point["unitPrice"]))) for point in history]
            low, high = min(prices), max(prices)
            points = []
            for point_index, amount in enumerate(prices):
                x = 584 + 326 * point_index / (len(prices) - 1)
                y = top + 132 - (amount - low) / (high - low or 1) * 88
                points.append((round(x), round(y)))
            draw.line(points, fill="#548f69", width=4, joint="curve")
            for x, y in points:
                draw.ellipse((x - 5, y - 5, x + 5, y + 5), fill="white", outline="#548f69", width=3)
            draw.text((584, top + 16), "История цены", fill="#68778e", font=small_font)

    output = BytesIO()
    image.save(output, format="PNG", optimize=True)
    return "image/png", output.getvalue()
