"""Mobile persistence operations with transaction-level invariants."""
import json
import uuid
from datetime import datetime
from pathlib import Path

import aiosqlite
from fastapi import HTTPException

from database.context import request_database
from database.models import CREATE_TABLES
from database import db as core
from backend.schemas import Transaction


def connect():
    path = request_database.get()
    if not path:
        raise RuntimeError("Mobile database requires an authenticated context")
    return aiosqlite.connect(path)


async def initialize(path: Path, name: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    async with aiosqlite.connect(path) as db:
        await db.executescript(CREATE_TABLES)
        await core._migrate(db)
        await db.executescript("""
            CREATE TABLE IF NOT EXISTS previews (
                id TEXT PRIMARY KEY, kind TEXT NOT NULL, payload TEXT NOT NULL,
                created_at TEXT NOT NULL, result TEXT);
            CREATE TABLE IF NOT EXISTS bank_fingerprints (
                fingerprint TEXT PRIMARY KEY, transaction_id INTEGER NOT NULL);
        """)
        await db.execute("INSERT OR IGNORE INTO users(telegram_id,name) VALUES(1,?)", (name,))
        await db.commit()


async def get_transaction(txid):
    row = await core.get_transaction(txid, 1)
    if not row:
        raise HTTPException(404, "transaction_not_found")
    result = dict(row)
    result["items"] = [dict(item) for item in await core.get_receipt_items(txid)]
    return result


async def insert(db, tx: Transaction, source="manual", items=()):
    if tx.tx_type == "debt_payment":
        cursor = await db.execute("SELECT current_amount FROM debts WHERE id=?", (tx.debt_target,))
        debt = await cursor.fetchone()
        if not debt:
            raise HTTPException(404, "debt_not_found")
        if tx.amount > debt[0]:
            raise HTTPException(422, "payment_exceeds_balance")
        await db.execute("UPDATE debts SET current_amount=ROUND(current_amount-?,2), "
                         "status=CASE WHEN ROUND(current_amount-?,2)=0 THEN 'closed' ELSE 'active' END WHERE id=?",
                         (tx.amount, tx.amount, tx.debt_target))
    cursor = await db.execute("INSERT INTO transactions(user_id,amount,category,description,tx_type,debt_target,source,created_at) "
                             "VALUES(1,?,?,?,?,?,?,?)",
                             (round(tx.amount, 2), tx.category, tx.description, tx.tx_type,
                              tx.debt_target if tx.tx_type == "debt_payment" else None, source,
                              (tx.created_at or datetime.now()).isoformat(sep=" ")))
    txid = cursor.lastrowid
    for item in items:
        await db.execute("INSERT INTO receipt_items(transaction_id,name,qty,price,sum) VALUES(?,?,?,?,?)",
                         (txid, item.name, item.qty, item.price, item.sum))
    return txid


async def create(tx, source="manual", items=(), confirmation_id=None):
    async with connect() as db:
        await db.execute("BEGIN IMMEDIATE")
        if confirmation_id:
            cursor = await db.execute("SELECT result,payload FROM previews WHERE id=? AND kind='receipt'", (confirmation_id,))
            preview = await cursor.fetchone()
            if not preview:
                raise HTTPException(404, "preview_not_found")
            if preview[0]:
                return json.loads(preview[0])
        txid = await insert(db, tx, source, items)
        if confirmation_id:
            from ai.receipts import apply_review_rules, verdict_rows
            payload = json.loads(preview[1])
            # AI verdicts belong to the original item names. Edited items are re-evaluated by rules.
            old_items = payload.get("items") or []
            original = (payload.get("analysis") or {}).get("items") or {}
            matched = {}
            for index, item in enumerate(items, 1):
                for old_index, old_item in enumerate(old_items, 1):
                    if old_item.get("name") == item.name:
                        verdict = original.get(str(old_index)) or original.get(old_index)
                        if verdict:
                            matched[index] = verdict
                        break
            item_dicts = [item.model_dump() for item in items]
            analysis = apply_review_rules({"items": matched}, item_dicts)
            for name, verdict, advice, origin in verdict_rows(analysis, item_dicts):
                await db.execute("UPDATE receipt_items SET verdict=?,advice=?,verdict_source=? WHERE transaction_id=? AND name=?",
                                 (verdict, advice, origin, txid, name))
        result = {"id": txid}
        if confirmation_id:
            await db.execute("UPDATE previews SET result=? WHERE id=?", (json.dumps(result), confirmation_id))
        await db.commit()
    return result


async def edit(txid, tx):
    async with connect() as db:
        await db.execute("BEGIN IMMEDIATE")
        cursor = await db.execute("SELECT tx_type,debt_target,amount FROM transactions WHERE id=? AND user_id=1", (txid,))
        old = await cursor.fetchone()
        if not old:
            raise HTTPException(404, "transaction_not_found")
        if old[0] == "debt_payment":
            await db.execute("UPDATE debts SET current_amount=current_amount+?,status='active' WHERE id=?", (old[2], old[1]))
        # Apply validation and debt adjustment using the same insert path, then retain the original identity.
        temporary_id = await insert(db, tx)
        await db.execute("UPDATE transactions SET amount=?,category=?,description=?,tx_type=?,debt_target=?,created_at="
                         "COALESCE(?,created_at) WHERE id=?",
                         (round(tx.amount, 2), tx.category, tx.description, tx.tx_type,
                          tx.debt_target if tx.tx_type == "debt_payment" else None,
                          tx.created_at.isoformat(sep=" ") if tx.created_at else None, txid))
        await db.execute("DELETE FROM transactions WHERE id=?", (temporary_id,))
        await db.commit()
    return await get_transaction(txid)


async def preview(kind, payload):
    key = uuid.uuid4().hex
    async with connect() as db:
        await db.execute("INSERT INTO previews VALUES(?,?,?,?,NULL)",
                         (key, kind, json.dumps(payload, ensure_ascii=False), datetime.now().isoformat()))
        await db.commit()
    return key
