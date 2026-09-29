import asyncio
import json
from datetime import datetime, timedelta

import httpx
import jwt
import pytest
from fastapi.testclient import TestClient

from backend.app import create_app
from database.context import request_database


@pytest.fixture
def client(tmp_path):
    with TestClient(create_app(tmp_path, "test-secret-that-is-at-least-32-characters")) as value:
        yield value


def register(client, name="alice"):
    response = client.post("/api/v1/auth/register", json={"username": name, "password": "correct-password", "display_name": name})
    assert response.status_code == 201, response.text
    data = response.json()
    return {"Authorization": "Bearer " + data["access_token"]}, data


def test_auth_rotation_logout_and_expiry(client):
    headers, tokens = register(client)
    assert client.post("/api/v1/auth/login", json={"username": "alice", "password": "incorrect"}).status_code == 401
    assert client.post("/api/v1/auth/register", json={"username": "ALICE", "password": "correct-password", "display_name": "A"}).status_code == 409
    refreshed = client.post("/api/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
    assert refreshed.status_code == 200
    assert client.post("/api/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]}).status_code == 401
    claims = jwt.decode(tokens["access_token"], options={"verify_signature": False})
    claims["exp"] = 1
    expired = jwt.encode(claims, client.app.state.auth.secret, algorithm="HS256")
    assert client.get("/api/v1/users/me", headers={"Authorization": "Bearer " + expired}).status_code == 401
    assert client.post("/api/v1/auth/logout", headers=headers).status_code == 200
    assert client.get("/api/v1/users/me", headers=headers).status_code == 401
    assert client.post("/api/v1/auth/refresh", json={"refresh_token": refreshed.json()["refresh_token"]}).status_code == 401


def test_crud_debt_budget_and_isolation(client):
    first, _ = register(client)
    second, _ = register(client, "bob")
    assert client.get("/api/v1/dashboard").status_code == 401
    debt = client.post("/api/v1/debts", headers=first, json={"name": "Loan", "current_amount": 1000, "interest_rate": 12, "min_payment": 100}).json()["id"]
    assert client.get("/api/v1/debts", headers=second).json() == []
    payment = client.post(f"/api/v1/debts/{debt}/payment", headers=first, json={"amount": 250}).json()["id"]
    assert client.post(f"/api/v1/debts/{debt}/payment", headers=second, json={"amount": 1}).status_code == 404
    assert client.post(f"/api/v1/debts/{debt}/payment", headers=first, json={"amount": 1000}).status_code == 422
    assert client.get("/api/v1/debts", headers=first).json()[0]["current_amount"] == 750
    assert client.delete(f"/api/v1/transactions/{payment}", headers=second).status_code == 404
    assert client.delete(f"/api/v1/transactions/{payment}", headers=first).status_code == 200
    assert client.get("/api/v1/debts", headers=first).json()[0]["current_amount"] == 1000
    tx = client.post("/api/v1/transactions", headers=first, json={"amount": 120, "category": "еда"}).json()["id"]
    assert client.put(f"/api/v1/transactions/{tx}", headers=first, json={"amount": 150, "category": "транспорт"}).status_code == 200
    assert client.post(f"/api/v1/transactions/{tx}/repeat", headers=first).status_code == 200
    assert client.get("/api/v1/reports/summary", headers=first).json()["expenses"] == 300
    assert client.get("/api/v1/transactions", headers=second).json() == []
    assert client.put("/api/v1/budgets", headers=first, json={"total": 0, "limits": {"еда": 10}, "weekly_food": 5}).status_code == 200
    assert client.get("/api/v1/budgets", headers=first).json()["total"] == 0
    assert client.get("/api/v1/budgets", headers=second).json()["total"] != 0
    assert request_database.get() is None


def test_validation_and_feature_reads(client):
    headers, _ = register(client)
    for amount in [-1, 0, "nan", "inf", 100000001]:
        assert client.post("/api/v1/transactions", headers=headers, json={"amount": amount}).status_code == 422
    for endpoint in ["dashboard", "budgets", "products", "shopping-list", "recurring", "settings", "categories", "reports/summary"]:
        response = client.get("/api/v1/" + endpoint, headers=headers)
        assert response.status_code == 200, response.text
    assert client.post("/api/v1/receipts/analyze", headers=headers, files={"file": ("bad.jpg", b"bad", "image/jpeg")}).status_code == 422
    assert client.post("/api/v1/import/bank-statement", headers=headers, files={"file": ("bad.pdf", b"bad", "application/pdf")}).status_code == 422
    assert client.post("/api/v1/demo", headers=headers).status_code == 200
    assert client.post("/api/v1/demo", headers=headers).status_code == 409


def test_parallel_accounts(client):
    one, _ = register(client)
    two, _ = register(client, "bob")

    async def run():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=client.app), base_url="http://test") as ac:
            async def write(headers, amount):
                for _ in range(5):
                    result = await ac.post("/api/v1/transactions", headers=headers, json={"amount": amount})
                    assert result.status_code == 201
            await asyncio.gather(write(one, 10), write(two, 30))
    asyncio.run(run())
    assert client.get("/api/v1/reports/summary", headers=one).json()["expenses"] == 50
    assert client.get("/api/v1/reports/summary", headers=two).json()["expenses"] == 150


def test_bank_preview_confirm_deduplicates(client, monkeypatch):
    from services.bank_statement import BankOp, Statement
    statement = Statement(ops=[
        BankOp("2026-09-01", "12:00", -100, "purchase", "Shop", "Shop", "1234"),
        BankOp("2026-09-01", "12:10", 50, "income", "Salary", "", "1234"),
        BankOp("2026-09-01", "12:20", -200, "transfer_out", "Own account", "", "1234"),
        BankOp("2026-09-01", "12:30", 10, "refund", "Refund", "", "1234"),
    ])
    monkeypatch.setattr("services.bank_statement.parse_statement_pdf", lambda path: statement)
    headers, _ = register(client)
    def preview():
        return client.post("/api/v1/import/bank-statement", headers=headers,
                           files={"file": ("test.pdf", b"test", "application/pdf")}).json()
    first = preview()
    assert len(first["operations"]) == 3
    assert first["expenses"] == 100
    assert first["income"] == 60
    assert client.get("/api/v1/transactions", headers=headers).json() == []
    confirm = lambda key: client.post("/api/v1/import/confirm", headers=headers, json={"preview_id": key})
    assert confirm(first["preview_id"]).json()["imported"] == 3
    assert confirm(first["preview_id"]).json()["imported"] == 3
    second = preview()
    assert second["duplicates"] == 3
    assert confirm(second["preview_id"]).json() == {"imported": 0, "skipped": 3}
    transactions = client.get("/api/v1/transactions", headers=headers).json()
    assert len(transactions) == 3
    assert {row["tx_type"] for row in transactions} == {"expense", "income"}


def test_receipt_preview_confirmation_and_prices(client, monkeypatch):
    from PIL import Image
    import io
    async def parse(path):
        return {"total": 100, "store": "Store", "category": "еда", "items": [{"name": "Молоко", "qty": 1, "price": 100, "sum": 100}]}
    async def analyze(items, store):
        return {"items": {1: {"verdict": "полезно", "reason": "Test model verdict", "note": ""}}}
    monkeypatch.setattr("ai.receipts.parse_receipt", parse)
    monkeypatch.setattr("ai.llm.analyze_basket", analyze)
    headers, _ = register(client)
    second, _ = register(client, "bob")
    image = io.BytesIO()
    Image.new("RGB", (20, 20)).save(image, format="JPEG")
    response = client.post("/api/v1/receipts/analyze", headers=headers, files={"file": ("receipt.jpg", image.getvalue(), "image/jpeg")})
    assert response.status_code == 200, response.text
    preview = response.json()
    assert client.get("/api/v1/transactions", headers=headers).json() == []
    body = {"confirmation_id": preview["confirmation_id"], "amount": 100, "description": "Store", "category": "еда", "items": preview["items"]}
    assert client.post("/api/v1/receipts/confirm", headers=second, json=body).status_code == 404
    result = client.post("/api/v1/receipts/confirm", headers=headers, json=body)
    assert result.status_code == 200, result.text
    txid = result.json()["id"]
    assert client.post("/api/v1/receipts/confirm", headers=headers, json=body).json()["id"] == txid
    transaction = client.get(f"/api/v1/transactions/{txid}", headers=headers).json()
    assert transaction["items"][0]["verdict"]
    assert client.get("/api/v1/products", headers=headers).json()[0]["last"] == 100
    assert client.get("/api/v1/products", headers=second).json() == []
