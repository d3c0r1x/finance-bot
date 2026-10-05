from io import BytesIO

import pytest
from PIL import Image, ImageDraw

from services.python.presentation.report_renderer import render_product_catalog


def test_product_catalog_png_draws_price_history_only_when_two_real_points_exist(monkeypatch):
    calls = []
    original_draw = ImageDraw.Draw

    class DrawSpy:
        def __init__(self, image):
            self.delegate = original_draw(image)

        def line(self, *args, **kwargs):
            calls.append(args)
            return self.delegate.line(*args, **kwargs)

        def __getattr__(self, name):
            return getattr(self.delegate, name)

    monkeypatch.setattr(ImageDraw, "Draw", DrawSpy)
    one_purchase = product(purchase_count=1, history=[point("2026-09-01", "100.000000")])
    content_type, payload = render_product_catalog(response(one_purchase))
    assert content_type == "image/png"
    image = Image.open(BytesIO(payload))
    assert image.format == "PNG"
    assert image.width == 960
    assert calls == []

    two_purchases = product(purchase_count=2, history=[
        point("2026-09-01", "100.000000"), point("2026-09-10", "120.000000")])
    _, payload = render_product_catalog(response(two_purchases))
    Image.open(BytesIO(payload)).verify()
    assert len(calls) == 1, "renderer must chart only the two actual receipt prices"


def test_product_catalog_renderer_rejects_fabricated_baseline_without_prior_purchase():
    item = product(purchase_count=1, history=[point("2026-09-01", "100.000000")])
    item.update(hasBaseline=True, baselineUnitPrice="0.000000", priorPurchases=0, change=None, relative=None)
    with pytest.raises(ValueError, match="baseline"):
        render_product_catalog(response(item))


def test_empty_product_results_use_real_text_fallback():
    content_type, payload = render_product_catalog({"mode": "search", "query": "milk", "products": []})
    assert content_type.startswith("text/plain")
    assert payload.decode("utf-8") == "Подходящие покупки не найдены."


def response(item):
    return {"mode": "search", "query": "tea", "products": [item]}


def product(purchase_count, history):
    has_baseline = purchase_count >= 2
    return {
        "productName": "Tea Green 500g", "purchaseCount": purchase_count,
        "usualUnitPrice": "110.000000" if has_baseline else "100.000000",
        "hasBaseline": has_baseline, "baselineUnitPrice": "100.000000" if has_baseline else None,
        "lastUnitPrice": history[-1]["unitPrice"], "lastPurchasedAt": history[-1]["purchasedAt"],
        "lastMerchant": "Market B", "cheapestUnitPrice": "100.000000", "cheapestMerchant": "Market A",
        "totalSpent": "220.00" if has_baseline else "100.00", "change": "20.000000" if has_baseline else None,
        "relative": "0.200000" if has_baseline else None, "signal": True if has_baseline else False,
        "direction": "up" if has_baseline else None, "priorPurchases": purchase_count - 1,
        "chartAvailable": has_baseline, "history": history,
    }


def point(day, unit_price):
    return {
        "receiptId": f"receipt-{day}", "itemId": f"item-{day}",
        "purchasedAt": f"{day}T10:00:00Z", "merchant": "Market", "name": "Tea Green 500g",
        "unitPrice": unit_price, "current": False,
    }
