import asyncio
import calendar
import hashlib
import json
import os
import secrets
import shutil
import tempfile
import time
import uuid
from collections import defaultdict, deque
from dataclasses import asdict
from datetime import datetime, timedelta
from pathlib import Path

import httpx
from fastapi import Depends, FastAPI, File, HTTPException, Query, Request, UploadFile
from fastapi.encoders import jsonable_encoder
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from backend.auth import AuthStore
from backend import finance
from backend.schemas import (Budget, Credentials, Debt, ImportConfirm, Mark, Payment,
                             Preferences, ReceiptConfirm, Refresh, Registration, TextInput, Transaction)
from database import db as core
from database.context import request_database
from database.models import CATEGORIES
from services import analytics, budget, forecast, purchase_history, recurring, shopping


def create_app(data_dir=None, secret=None):
    root = Path(data_dir or os.getenv("FINANCE_API_DATA", "data/mobile")).resolve()
    root.mkdir(parents=True, exist_ok=True)
    secret = secret or os.getenv("FINANCE_JWT_SECRET")
    if not secret:
        secret_file = root / ".jwt-secret"
        if not secret_file.exists():
            try:
                with secret_file.open("x") as stream:
                    stream.write(secrets.token_urlsafe(48))
                secret_file.chmod(0o600)
            except FileExistsError:
                pass
        secret = secret_file.read_text().strip()
    if len(secret) < 32:
        raise ValueError("FINANCE_JWT_SECRET must contain at least 32 characters")
    auth = AuthStore(root, secret)
    app = FastAPI(title="Finance Mobile API", version="0.1.0")
    app.state.auth = auth
    bearer = HTTPBearer(auto_error=False)
    attempts = defaultdict(deque)

    def rate_limit(request: Request):
        key = request.client.host if request.client else "local"
        queue = attempts[key]
        now = time.monotonic()
        while queue and queue[0] < now - 60:
            queue.popleft()
        if len(queue) >= 20:
            raise HTTPException(429, "too_many_attempts")
        queue.append(now)

    async def user(credentials: HTTPAuthorizationCredentials = Depends(bearer)):
        if not credentials:
            raise HTTPException(401, "authentication_required")
        account = await asyncio.to_thread(auth.authenticate, credentials.credentials)
        path = root / "accounts" / account["id"] / "finance.sqlite3"
        await finance.initialize(path, account["display_name"])
        token = request_database.set(str(path))
        try:
            yield account
        finally:
            request_database.reset(token)

    prefix = "/api/v1"

    @app.get(prefix + "/health")
    async def health():
        return {"status": "ok", "version": "0.1.0"}

    @app.post(prefix + "/auth/register", status_code=201, dependencies=[Depends(rate_limit)])
    async def register(body: Registration):
        uid = await asyncio.to_thread(auth.register, body.username, body.password, body.display_name, body.language)
        await finance.initialize(root / "accounts" / uid / "finance.sqlite3", body.display_name)
        return await asyncio.to_thread(auth.issue, uid)

    @app.post(prefix + "/auth/login", dependencies=[Depends(rate_limit)])
    async def login(body: Credentials):
        return await asyncio.to_thread(auth.login, body.username, body.password)

    @app.post(prefix + "/auth/refresh", dependencies=[Depends(rate_limit)])
    async def refresh(body: Refresh):
        return await asyncio.to_thread(auth.rotate, body.refresh_token)

    @app.post(prefix + "/auth/logout")
    async def logout(account=Depends(user)):
        await asyncio.to_thread(auth.logout, account["session_id"])
        return {"ok": True}

    @app.get(prefix + "/users/me")
    async def me(account=Depends(user)):
        return {key: account[key] for key in ("id", "username", "display_name", "language")}

    @app.get(prefix + "/settings")
    async def settings(account=Depends(user)):
        stored = await core.get_setting("mobile:preferences")
        return json.loads(stored) if stored else {"language": account["language"], "theme": "system",
                                                  "display_name": account["display_name"], "onboarded": False}

    @app.put(prefix + "/settings")
    async def update_settings(body: Preferences, account=Depends(user)):
        await core.set_setting("mobile:preferences", body.model_dump_json())
        return body

    @app.get(prefix + "/categories")
    async def categories(account=Depends(user)):
        return CATEGORIES

    @app.get(prefix + "/transactions")
    async def transactions(days: int = Query(365, ge=1, le=36500),
                           tx_type: str | None = None, account=Depends(user)):
        return [dict(row) for row in await core.get_transactions(1, days, tx_type)]

    @app.post(prefix + "/transactions", status_code=201)
    async def add_transaction(body: Transaction, account=Depends(user)):
        return await finance.create(body)

    @app.post(prefix + "/transactions/parse")
    async def parse(body: TextInput, account=Depends(user)):
        from ai.llm import parse_transaction, fallback_parse
        try:
            return await asyncio.wait_for(parse_transaction(body.text), timeout=45)
        except (TimeoutError, OSError, RuntimeError):
            return {**fallback_parse(body.text), "parsed_by": "rules"}

    @app.get(prefix + "/transactions/{txid}")
    async def transaction(txid: int, account=Depends(user)):
        return await finance.get_transaction(txid)

    @app.put(prefix + "/transactions/{txid}")
    async def edit_transaction(txid: int, body: Transaction, account=Depends(user)):
        return await finance.edit(txid, body)

    @app.delete(prefix + "/transactions/{txid}")
    async def delete_transaction(txid: int, account=Depends(user)):
        if not await core.delete_transaction(txid, 1):
            raise HTTPException(404, "transaction_not_found")
        return {"ok": True}

    @app.post(prefix + "/transactions/{txid}/repeat")
    async def repeat_transaction(txid: int, account=Depends(user)):
        row = await finance.get_transaction(txid)
        row["created_at"] = None
        return await finance.create(Transaction.model_validate(row))

    @app.get(prefix + "/budgets")
    async def budgets(account=Depends(user)):
        return {"total": await budget.get_total_limit(1), "limits": await budget.get_limits(1),
                "weekly_food": await forecast.weekly_food_limit(1)}

    @app.put(prefix + "/budgets")
    async def set_budget(body: Budget, account=Depends(user)):
        # apply_limits deliberately skips zero total in legacy; explicit setter preserves zero here.
        await budget.apply_limits(body.limits, user_id=1)
        await budget.set_total_limit(body.total, 1)
        await forecast.set_weekly_food_limit(1, body.weekly_food)
        return body

    @app.post(prefix + "/budgets/suggest")
    async def suggest(account=Depends(user)):
        from ai.llm import suggest_budget
        context, days = await budget.history_summary(1)
        if not budget.history_enough(days):
            return {"status": "insufficient_history", "days": days}
        try:
            result = await asyncio.wait_for(suggest_budget(context), 60)
        except TimeoutError:
            result = None
        return {"status": "ok" if result else "ai_unavailable", "proposal": result}

    @app.get(prefix + "/reports/summary")
    async def report(days: int = Query(30, ge=1, le=36500), account=Depends(user)):
        rows = [dict(row) for row in await core.get_transactions(1, days)]
        expenses = sum(row["amount"] for row in rows if row["tx_type"] == "expense")
        income = sum(row["amount"] for row in rows if row["tx_type"] == "income")
        debt_paid = sum(row["amount"] for row in rows if row["tx_type"] == "debt_payment")
        timeline = defaultdict(float)
        for row in rows:
            if row["tx_type"] == "expense":
                timeline[row["created_at"][:10]] += row["amount"]
        return {"expenses": round(expenses, 2), "income": round(income, 2), "debt_paid": round(debt_paid, 2),
                "balance": round(income - expenses - debt_paid, 2), "categories": analytics.by_category(rows),
                "timeline": dict(sorted(timeline.items()))}

    @app.get(prefix + "/dashboard")
    async def dashboard(account=Depends(user)):
        now = datetime.now()
        expense = await core.get_total_spent_this_month(1)
        limit = await budget.get_total_limit(1)
        remaining_days = calendar.monthrange(now.year, now.month)[1] - now.day + 1
        return {"expenses": expense, "income": await core.get_month_income(1), "limit": limit,
                "remaining": limit - expense, "daily_safe": max(0, (limit - expense) / remaining_days),
                "forecast": analytics.forecast_end_of_month(expense),
                "categories": await core.get_monthly_spending(1),
                "recent": [dict(row) for row in await core.get_recent_transactions(1, 8)],
                "food_week": await forecast.food_week_status(1),
                "total_debt": sum(row["current_amount"] for row in await core.get_debts())}

    @app.get(prefix + "/debts")
    async def debts(account=Depends(user)):
        rows = [dict(row) for row in await core.get_debts(True)]
        for row in rows:
            row["payoff_months"] = analytics.forecast_debt_payoff(row["current_amount"], row["min_payment"], row["interest_rate"])
        return rows

    @app.post(prefix + "/debts", status_code=201)
    async def add_debt(body: Debt, account=Depends(user)):
        key = uuid.uuid4().hex
        async with finance.connect() as db:
            await db.execute("INSERT INTO debts VALUES(?,?,?,?,?,?,?)", (key, body.name, body.current_amount,
                             body.current_amount, body.interest_rate, body.min_payment,
                             "active" if body.current_amount else "closed"))
            await db.commit()
        return {"id": key}

    @app.put(prefix + "/debts/{key}")
    async def edit_debt(key: str, body: Debt, account=Depends(user)):
        async with finance.connect() as db:
            cursor = await db.execute("UPDATE debts SET name=?,current_amount=?,interest_rate=?,min_payment=?,status=? WHERE id=?",
                                     (body.name, body.current_amount, body.interest_rate, body.min_payment,
                                      "active" if body.current_amount else "closed", key))
            if not cursor.rowcount:
                raise HTTPException(404, "debt_not_found")
            await db.commit()
        return {"id": key}

    @app.post(prefix + "/debts/{key}/payment")
    async def pay_debt(key: str, body: Payment, account=Depends(user)):
        return await finance.create(Transaction(amount=body.amount, category="долги", tx_type="debt_payment", debt_target=key))

    @app.get(prefix + "/recurring")
    async def recurring_payments(account=Depends(user)):
        hidden = set(json.loads(await core.get_setting("mobile:recurring_hidden", "[]")))
        rows = [dict(row) for row in await core.get_transactions(1, 730)]
        return [item for item in recurring.find_recurring(rows) if item["key"] not in hidden]

    @app.post(prefix + "/recurring/hide")
    async def hide_recurring(body: Mark, account=Depends(user)):
        hidden = set(json.loads(await core.get_setting("mobile:recurring_hidden", "[]")))
        hidden.add(body.key)
        await core.set_setting("mobile:recurring_hidden", json.dumps(sorted(hidden)))
        return {"ok": True}

    @app.get(prefix + "/products")
    async def products(q: str = Query("", max_length=200), account=Depends(user)):
        rows = await core.get_receipt_price_history(1, 5000)
        return purchase_history.search_products(rows, q) if q else purchase_history.product_groups(rows, min_purchases=1)

    @app.get(prefix + "/shopping-list")
    async def shopping_list(account=Depends(user)):
        rows = await core.get_receipt_price_history(1, 5000)
        visible, marked = shopping.hide_bought(shopping.due_items(rows), await shopping.bought_marks(1))
        return {"items": visible, "bought": marked}

    @app.post(prefix + "/shopping-list/bought")
    async def mark_bought(body: Mark, account=Depends(user)):
        await shopping.mark_bought(1, body.key)
        return {"ok": True}

    async def upload(file: UploadFile, suffix: str):
        handle = tempfile.NamedTemporaryFile(suffix=suffix, delete=False)
        path = Path(handle.name)
        total = 0
        try:
            with handle:
                while chunk := await file.read(1024 * 1024):
                    total += len(chunk)
                    if total > 15 * 1024 * 1024:
                        raise HTTPException(413, "file_too_large")
                    handle.write(chunk)
            if total == 0:
                raise HTTPException(422, "empty_file")
            return path
        except BaseException:
            path.unlink(missing_ok=True)
            raise
        finally:
            await file.close()

    @app.post(prefix + "/receipts/analyze")
    async def analyze_receipt(file: UploadFile = File(...), account=Depends(user)):
        from PIL import Image, UnidentifiedImageError
        path = await upload(file, ".jpg")
        try:
            try:
                with Image.open(path) as picture:
                    if picture.width * picture.height > 25_000_000:
                        raise HTTPException(422, "image_too_large")
                    picture.verify()
            except (UnidentifiedImageError, OSError):
                raise HTTPException(422, "invalid_image")
            from ai.receipts import parse_receipt, apply_review_rules, verdict_rows
            from ai.llm import analyze_basket
            try:
                result = await asyncio.wait_for(parse_receipt(str(path)), 180)
                analysis = await asyncio.wait_for(analyze_basket(result.get("items", []), result.get("store") or ""), 60)
                result["analysis"] = apply_review_rules(analysis, result.get("items", []))
            except (TimeoutError, OSError, RuntimeError):
                raise HTTPException(503, "receipt_service_unavailable")
            if not result.get("total") and not result.get("items"):
                raise HTTPException(422, "receipt_unreadable")
            result["confirmation_id"] = await finance.preview("receipt", jsonable_encoder(result))
            return result
        finally:
            path.unlink(missing_ok=True)

    @app.post(prefix + "/receipts/confirm")
    async def confirm_receipt(body: ReceiptConfirm, account=Depends(user)):
        if body.tx_type != "expense":
            raise HTTPException(422, "receipt_must_be_expense")
        return await finance.create(body, "photo", body.items, body.confirmation_id)

    @app.post(prefix + "/import/bank-statement")
    async def bank_statement(file: UploadFile = File(...), account=Depends(user)):
        from services.bank_statement import parse_statement_pdf
        path = await upload(file, ".pdf")
        try:
            try:
                statement = await asyncio.to_thread(parse_statement_pdf, path)
            except Exception:
                raise HTTPException(422, "invalid_bank_pdf")
            if not statement.ops:
                raise HTTPException(422, "no_bank_operations")
            operations = [asdict(op) for op in importable_bank_ops(statement.ops)]
            preview_id = await finance.preview("bank", {"ops": operations, "check_ok": statement.check_ok})
            async with finance.connect() as db:
                duplicates = 0
                for op in operations:
                    key = bank_fingerprint(op)
                    cursor = await db.execute("SELECT 1 FROM bank_fingerprints WHERE fingerprint=?", (key,))
                    duplicates += bool(await cursor.fetchone())
            return {"preview_id": preview_id, "operations": operations, "duplicates": duplicates,
                    "expenses": round(sum(-op["amount"] for op in operations if op["amount"] < 0), 2),
                    "income": round(sum(op["amount"] for op in operations if op["amount"] > 0), 2),
                    "totals_found": statement.totals_found, "check_ok": statement.check_ok}
        finally:
            path.unlink(missing_ok=True)

    def importable_bank_ops(ops):
        return [op for op in ops
                if (op.kind == "purchase" and op.amount < 0)
                or (op.kind in ("income", "refund") and op.amount > 0)]

    def bank_fingerprint(op):
        fields = {key: op[key] for key in ("date", "time", "amount", "description", "card")}
        return hashlib.sha256(json.dumps(fields, sort_keys=True, ensure_ascii=False).encode()).hexdigest()

    @app.post(prefix + "/import/confirm")
    async def confirm_import(body: ImportConfirm, account=Depends(user)):
        from ai.llm import rule_category
        async with finance.connect() as db:
            await db.execute("BEGIN IMMEDIATE")
            cursor = await db.execute("SELECT payload,result FROM previews WHERE id=? AND kind='bank'", (body.preview_id,))
            row = await cursor.fetchone()
            if not row:
                raise HTTPException(404, "preview_not_found")
            if row[1]:
                return json.loads(row[1])
            payload = json.loads(row[0])
            if not payload["check_ok"]:
                raise HTTPException(422, "bank_totals_mismatch")
            count, skipped = 0, 0
            for op in payload["ops"]:
                key = bank_fingerprint(op)
                cursor = await db.execute("SELECT 1 FROM bank_fingerprints WHERE fingerprint=?", (key,))
                if await cursor.fetchone():
                    skipped += 1
                    continue
                tx = Transaction(amount=abs(op["amount"]), category=rule_category(op["merchant"]) or "прочее",
                                 description=op["merchant"] or op["description"],
                                 tx_type="income" if op["amount"] > 0 else "expense",
                                 created_at=datetime.fromisoformat(op["date"] + "T" + op["time"]))
                txid = await finance.insert(db, tx, "bank")
                await db.execute("INSERT INTO bank_fingerprints VALUES(?,?)", (key, txid))
                count += 1
            result = {"imported": count, "skipped": skipped}
            await db.execute("UPDATE previews SET result=? WHERE id=?", (json.dumps(result), body.preview_id))
            await db.commit()
        return result

    @app.get(prefix + "/services/status")
    async def service_status(account=Depends(user)):
        from config import OLLAMA_HOST, TESSERACT_CMD
        ollama_ok = False
        try:
            async with httpx.AsyncClient(timeout=2) as client:
                ollama_ok = (await client.get(OLLAMA_HOST + "/api/tags")).is_success
        except httpx.HTTPError:
            pass
        return {"ollama": ollama_ok, "tesseract": bool(shutil.which(TESSERACT_CMD))}

    @app.post(prefix + "/demo")
    async def demo(account=Depends(user)):
        async with finance.connect() as db:
            await db.execute("BEGIN IMMEDIATE")
            cursor = await db.execute("SELECT 1 FROM transactions LIMIT 1")
            if await cursor.fetchone():
                raise HTTPException(409, "demo_requires_empty_account")
            now = datetime.now()
            await finance.insert(db, Transaction(amount=150000, tx_type="income", description="Salary", created_at=now.replace(day=1)))
            for day, amount, category, name in [(0, 1240, "еда", "Grocery"), (1, 2000, "транспорт", "Fuel"), (2, 399, "досуг", "Music")]:
                await finance.insert(db, Transaction(amount=amount, category=category, description=name, created_at=now-timedelta(days=day)))
            await db.execute("INSERT INTO debts VALUES('demo','Credit card',42000,42000,18,5000,'active')")
            await db.commit()
        return {"ok": True}

    return app


app = create_app()
