"""Доступ к базе для панели управления: синхронный sqlite3.

Бот пишет в ту же базу через aiosqlite (`database/db.py`) и о GUI знать не должен.
Здесь живут запросы, нужные только панели: агрегаты по пользователям, выборки для таблиц,
правка и удаление записей. Настройки и лимиты читаются теми же ключами, что и в боте
(`limit:{категория}`, `limit:total`, `profile:{id}:{поле}`), поэтому панель и Telegram
всегда показывают одно и то же.
"""
import sqlite3
from datetime import datetime, timedelta

from config import DB_PATH, USERS
from database.models import CATEGORIES

DEBT_PAYMENT = "debt_payment"


def _now_iso() -> str:
    return datetime.now().isoformat(sep=" ")


def _month_start() -> str:
    return datetime.now().replace(day=1, hour=0, minute=0, second=0, microsecond=0).isoformat(sep=" ")


def connect() -> sqlite3.Connection:
    """Соединение с базой (row_factory — доступ по именам колонок).

    busy_timeout нужен потому, что бот пишет в ту же базу: панель подождёт блокировку,
    а не упадёт с «database is locked» прямо во время сохранения траты в Telegram.
    """
    connection = sqlite3.connect(DB_PATH)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA busy_timeout = 3000")
    return connection


def query(sql: str, params: tuple | list = ()) -> list[sqlite3.Row]:
    with connect() as db:
        return db.execute(sql, tuple(params)).fetchall()


def one(sql: str, params: tuple | list = ()):
    with connect() as db:
        return db.execute(sql, tuple(params)).fetchone()


def execute(sql: str, params: tuple | list = ()) -> None:
    with connect() as db:
        db.execute(sql, tuple(params))


# ─── Транзакции ──────────────────────────────────────────────────────────

def transactions(user_id: int | None = None, days: int | None = 30, tx_type: str | None = None,
                 search: str = "", limit: int = 1000) -> list[sqlite3.Row]:
    """Транзакции с фильтрами: пользователь, период, тип, поиск по описанию и категории."""
    conditions, params = [], []
    if days:
        conditions.append("created_at > ?")
        params.append((datetime.now() - timedelta(days=days)).isoformat(sep=" "))
    if user_id:
        conditions.append("user_id = ?")
        params.append(user_id)
    if tx_type and tx_type != "все":
        conditions.append("tx_type = ?")
        params.append(tx_type)
    if search:
        conditions.append("(description LIKE ? OR category LIKE ? OR subcategory LIKE ?)")
        params += [f"%{search}%"] * 3
    where = f" WHERE {' AND '.join(conditions)}" if conditions else ""
    return query(f"SELECT * FROM transactions{where} ORDER BY created_at DESC LIMIT ?",
                 params + [limit])


def transaction(tx_id: int):
    return one("SELECT * FROM transactions WHERE id = ?", (tx_id,))


EDITABLE_FIELDS = ("amount", "category", "subcategory", "description", "tx_type", "debt_target",
                   "created_at", "source")


def update_transaction(tx_id: int, **fields) -> None:
    """Правит запись (только известные поля — значения подставляются параметрами)."""
    changes = {key: value for key, value in fields.items() if key in EDITABLE_FIELDS}
    if not changes:
        return
    columns = ", ".join(f"{key} = ?" for key in changes)
    execute(f"UPDATE transactions SET {columns} WHERE id = ?",
            list(changes.values()) + [tx_id])


def delete_transactions(tx_ids: list[int]) -> int:
    """Удаляет записи и позиции чеков. Платёж по долгу возвращает долг обратно."""
    if not tx_ids:
        return 0
    marks = ",".join("?" * len(tx_ids))
    with connect() as db:
        for row in db.execute(
                f"SELECT * FROM transactions WHERE id IN ({marks})", tuple(tx_ids)).fetchall():
            if row["tx_type"] == DEBT_PAYMENT and row["debt_target"]:
                db.execute("UPDATE debts SET current_amount = current_amount + ?, "
                           "status = 'active' WHERE id = ?", (row["amount"], row["debt_target"]))
        db.execute(f"DELETE FROM receipt_items WHERE transaction_id IN ({marks})", tuple(tx_ids))
        cursor = db.execute(f"DELETE FROM transactions WHERE id IN ({marks})", tuple(tx_ids))
        return cursor.rowcount


def add_transaction(user_id: int, amount: float, category: str = "прочее", subcategory: str = "",
                    description: str = "", tx_type: str = "expense", debt_target: str | None = None,
                    source: str = "manual", created_at: str | None = None) -> int:
    """Добавляет запись из панели (та же логика долгов, что в боте)."""
    with connect() as db:
        cursor = db.execute(
            """INSERT INTO transactions
               (user_id, amount, category, subcategory, description, tx_type, debt_target,
                source, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (user_id, amount, category, subcategory or None, description or None, tx_type,
             debt_target, source, created_at or _now_iso()))
        if tx_type == DEBT_PAYMENT and debt_target:
            db.execute("UPDATE debts SET current_amount = MAX(0, current_amount - ?) "
                       "WHERE id = ?", (amount, debt_target))
            remaining = db.execute("SELECT current_amount FROM debts WHERE id = ?",
                                   (debt_target,)).fetchone()[0]
            if not remaining:
                db.execute("UPDATE debts SET status = 'closed' WHERE id = ?", (debt_target,))
        return cursor.lastrowid


# ─── Чеки ────────────────────────────────────────────────────────────────

def receipt_transactions(limit: int = 300) -> list[sqlite3.Row]:
    """Чеки с фото: записи, у которых есть позиции, плюс число позиций и их сумма."""
    return query("""
        SELECT t.*, COUNT(i.id) AS items_count, SUM(i.sum) AS items_sum
        FROM transactions t
        JOIN receipt_items i ON i.transaction_id = t.id
        GROUP BY t.id
        ORDER BY t.created_at DESC
        LIMIT ?""", (limit,))


def receipt_items(transaction_id: int) -> list[sqlite3.Row]:
    return query("SELECT * FROM receipt_items WHERE transaction_id = ? ORDER BY id",
                 (transaction_id,))


# ─── Агрегаты ────────────────────────────────────────────────────────────

def totals(user_id: int | None = None, since: str | None = None) -> dict:
    """Расход, доход и платежи по долгам за период (по умолчанию — с начала месяца)."""
    since = since or _month_start()
    params: list = [since]
    clause = "created_at > ?"
    if user_id:
        clause += " AND user_id = ?"
        params.append(user_id)
    rows = query(f"SELECT tx_type, SUM(amount) AS total FROM transactions "
                 f"WHERE {clause} GROUP BY tx_type", params)
    result = {row["tx_type"]: row["total"] or 0 for row in rows}
    return {"spent": result.get("expense", 0), "income": result.get("income", 0),
            "debts_paid": result.get(DEBT_PAYMENT, 0)}


def period_totals(days: int | None = 30, user_id: int | None = None) -> dict:
    """То же, что totals, но с явным периодом панели: None — вся история."""
    since = None if days is None else (datetime.now() - timedelta(days=days)).isoformat(sep=" ")
    return totals(user_id=user_id, since=since)


def category_totals(days: int | None = 30, user_id: int | None = None) -> dict[str, float]:
    params: list = []
    clause = "tx_type = 'expense'"
    if days is not None:
        clause = "created_at > ? AND " + clause
        params.append((datetime.now() - timedelta(days=days)).isoformat(sep=" "))
    if user_id:
        clause += " AND user_id = ?"
        params.append(user_id)
    rows = query(f"SELECT category, SUM(amount) AS total FROM transactions WHERE {clause} "
                 f"GROUP BY category ORDER BY total DESC", params)
    return {row["category"]: row["total"] or 0 for row in rows}


def daily_totals(days: int = 30, user_id: int | None = None) -> list[tuple[str, float]]:
    """Траты по дням: [(«05.09», 1234.5)] — для графика в панели."""
    since = (datetime.now() - timedelta(days=days)).isoformat(sep=" ")
    params: list = [since]
    clause = "created_at > ? AND tx_type = 'expense'"
    if user_id:
        clause += " AND user_id = ?"
        params.append(user_id)
    rows = query(f"SELECT substr(created_at, 1, 10) AS day, SUM(amount) AS total "
                 f"FROM transactions WHERE {clause} GROUP BY day ORDER BY day", params)
    return [(f"{row['day'][8:10]}.{row['day'][5:7]}", row["total"] or 0) for row in rows]


def users_overview() -> list[dict]:
    """Строка на пользователя: имя, роль, доход-план, настройка, активность и суммы месяца."""
    settings = settings_map()
    known = {row["telegram_id"]: row for row in query("SELECT * FROM users")}
    ids = set(known) | set(USERS) | {
        row["user_id"] for row in query("SELECT DISTINCT user_id FROM transactions")}
    overview = []
    for user_id in sorted(ids):
        stats = totals(user_id)
        last = one("SELECT created_at FROM transactions WHERE user_id = ? "
                   "ORDER BY created_at DESC LIMIT 1", (user_id,))
        count = one("SELECT COUNT(*) AS n FROM transactions WHERE user_id = ?", (user_id,))["n"]
        registered = known.get(user_id)
        name = (settings.get(f"profile:{user_id}:name")
                or (registered["name"] if registered else None)
                or (USERS.get(user_id) or {}).get("name") or "—")
        overview.append({
            "user_id": user_id,
            "name": name,
            "role": (USERS.get(user_id) or {}).get("role", "user"),
            "income_plan": settings.get(f"profile:{user_id}:income", "—"),
            "onboarded": "да" if settings.get(f"profile:{user_id}:onboarded") == "1" else "нет",
            "transactions": count,
            "spent": stats["spent"],
            "income": stats["income"],
            "last": (last["created_at"][:16] if last else "—"),
        })
    return overview


# ─── Настройки, лимиты и долги ───────────────────────────────────────────

def settings_map() -> dict[str, str]:
    return {row["key"]: row["value"] for row in query("SELECT key, value FROM settings")}


def set_setting(key: str, value: str) -> None:
    execute("INSERT INTO settings (key, value) VALUES (?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value = excluded.value", (key, value))


def delete_setting(key: str) -> None:
    execute("DELETE FROM settings WHERE key = ?", (key,))


def limits() -> dict[str, float]:
    """Лимиты по категориям и общий лимит — прямо из настроек (те же ключи, что в боте)."""
    stored = settings_map()
    result: dict[str, float] = {}
    for category in CATEGORIES:
        try:
            result[category] = float(stored.get(f"limit:{category}", 0) or 0)
        except ValueError:
            result[category] = 0.0
    try:
        result["total"] = float(stored.get("limit:total", 0) or 0)
    except ValueError:
        result["total"] = 0.0
    return result


def set_limit(category: str, value: float) -> None:
    key = "limit:total" if category == "total" else f"limit:{category}"
    set_setting(key, str(round(float(value), 2)))


def debts() -> list[sqlite3.Row]:
    return query("SELECT * FROM debts ORDER BY interest_rate DESC")


def update_debt(debt_id: str, current_amount: float | None = None,
                min_payment: float | None = None) -> None:
    if current_amount is not None:
        execute("UPDATE debts SET current_amount = ?, "
                "status = CASE WHEN ? <= 0 THEN 'closed' ELSE 'active' END WHERE id = ?",
                (current_amount, current_amount, debt_id))
    if min_payment is not None:
        execute("UPDATE debts SET min_payment = ? WHERE id = ?", (min_payment, debt_id))
