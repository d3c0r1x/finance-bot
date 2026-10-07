from decimal import Decimal

from PIL import Image, ImageDraw

from services.python.presentation import report_renderer
from utils.formatting import format_amount, md_safe, plural_ru
import utils.fonts as fonts
from utils.fonts import mono_font


REPORT = {
    "fromDate": "2026-10-01",
    "toDate": "2026-10-01",
    "currency": "RUB",
    "timezone": "Europe/Moscow",
    "incomeTotal": "1234.50",
    "expenseTotal": "100.00",
    "debtPaymentTotal": "0.00",
    "refundTotal": "0.00",
    "transactionCount": 1,
    "expenseByCategory": {"Продукты_и_[дом]": "100.00"},
    "expenseByDay": {"2026-10-01": "100.00"},
}


def test_amount_formatting_uses_decimal_strings_without_float_loss():
    assert format_amount("9007199254740993") == "9 007 199 254 740 993 ₽"
    assert format_amount(Decimal("1234.50")) == "1 234,50 ₽"
    assert format_amount(Decimal("-12.30")) == "-12,30 ₽"
    assert format_amount("123456789012345678901234567890.50") == "123 456 789 012 345 678 901 234 567 890,50 ₽"


def test_telegram_dynamic_text_cannot_open_markdown_or_link_markup():
    unsafe = "Товар_* [click](https://invalid) `code`"
    safe = md_safe(unsafe)
    assert "*" not in safe and "_" not in safe and "`" not in safe and "[" not in safe
    assert "Товар" in safe and "https://invalid" in safe


def test_russian_plural_boundaries_cover_teens_and_negative_counts():
    expected = {1: "трата", 2: "траты", 4: "траты", 5: "трат", 11: "трат",
                21: "трата", 24: "траты", -22: "траты"}
    assert {count: plural_ru(count, "трата", "траты", "трат") for count in expected} == expected


def test_report_text_fallback_is_localized_and_keeps_user_labels():
    text = report_renderer.report_text(REPORT, language="ru")
    assert "Финансовый отчёт" in text
    assert "Доходы: 1 234,50 ₽" in text
    assert "Продукты_и_[дом]: 100,00 ₽" in text


def test_png_report_uses_localized_labels_and_cyrillic_capable_font(monkeypatch):
    drawn = []
    original_text = ImageDraw.ImageDraw.text

    def record_text(self, xy, text, *args, **kwargs):
        drawn.append(str(text))
        return original_text(self, xy, text, *args, **kwargs)

    monkeypatch.setattr(ImageDraw.ImageDraw, "text", record_text)
    payload = report_renderer.render_report_png(REPORT, language="ru")
    assert payload.startswith(b"\x89PNG\r\n\x1a\n")
    assert "Финансовый отчёт" in drawn
    assert any("Продукты" in label for label in drawn)


def test_report_falls_back_to_localized_utf8_when_png_renderer_is_unavailable():
    def unavailable(_report):
        raise report_renderer.RendererUnavailable("synthetic missing renderer")

    content_type, payload = report_renderer.render_report(REPORT, language="ru", png_renderer=unavailable)
    assert content_type == "text/plain; charset=utf-8"
    assert "Финансовый отчёт" in payload.decode("utf-8")


def test_font_resolution_has_sans_and_monospace_fallbacks(monkeypatch):
    monkeypatch.setattr("utils.fonts.SANS_CANDIDATES", (), raising=False)
    monkeypatch.setattr("utils.fonts.MONO_CANDIDATES", ())
    assert fonts.sans_font(16) is not None
    assert mono_font(16) is not None
    assert mono_font(16, fallback=False) is None
