import os
import re
from collections import deque

import aiosqlite
from datetime import datetime, timedelta
from config import DB_PATH, USERS
from database.models import CREATE_TABLES, INITIAL_DEBTS, MIGRATIONS


def _now_iso() -> str:
    """Локальное время в ISO — чтобы сравнения с datetime.now() были корректны
    (SQLite CURRENT_TIMESTAMP хранит UTC, мы сравниваем с локальным временем)."""
    return datetime.now().isoformat(sep=" ")


async def _migrate(db) -> None:
    """Дописывает колонки, появившиеся после первых выпусков базы.

    Проверяется именно наличие колонки, а не версия схемы: база у каждого своя и создавалась
    в разное время, а ALTER TABLE по существующей колонке роняет запуск бота.
    """
    for table, column, definition in MIGRATIONS:
        cursor = await db.execute(f"PRAGMA table_info({table})")
        columns = {row[1] for row in await cursor.fetchall()}
        if column not in columns:
            await db.execute(f"ALTER TABLE {table} ADD COLUMN {column} {definition}")


async def init_db():
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    async with aiosqlite.connect(DB_PATH) as db:
        await db.executescript(CREATE_TABLES)
        await _migrate(db)
        # Инициализация долгов, если таблица пустая
        cursor = await db.execute("SELECT COUNT(*) FROM debts")
        count = (await cursor.fetchone())[0]
        if count == 0:
            await db.executemany(
                "INSERT OR IGNORE INTO debts (id, name, initial_amount, current_amount, interest_rate, min_payment) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                INITIAL_DEBTS,
            )
        await db.commit()


async def ensure_user(telegram_id: int, display_name: str | None = None):
    """Регистрирует пользователя и синхронизирует имя из Telegram для панели."""
    info = USERS.get(telegram_id)
    if not info:
        return
    name = (display_name or info.get("name") or "Пользователь").strip()
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute(
            "INSERT OR IGNORE INTO users (telegram_id, name, role) VALUES (?, ?, ?)",
            (telegram_id, name, info["role"]),
        )
        if display_name:
            await db.execute("UPDATE users SET name = ? WHERE telegram_id = ?", (name, telegram_id))
        await db.commit()


async def add_transaction(user_id, amount, category, subcategory=None, description=None,
                          tx_type="expense", debt_target=None, source="text",
                          created_at: str | None = None) -> int:
    """Добавляет транзакцию и возвращает её id. Для платежа по долгу уменьшает остаток
    и закрывает долг при полном погашении. created_at — ISO-строка для истории
    из банковской выписки; None означает «сейчас»."""
    ids = await add_transactions_bulk(
        user_id, [(amount, category, subcategory, description, tx_type, debt_target, source, created_at)],
    )
    return ids[0]


async def add_transactions_bulk(user_id, rows: list[tuple]) -> list[int]:
    """Пакетная вставка транзакций (импорт выписки): один коммит на весь файл.

    Каждая строка: (amount, category, subcategory, description, tx_type,
    debt_target, source, created_at). Возвращает id в порядке вставки.
    """
    ids: list[int] = []
    async with aiosqlite.connect(DB_PATH) as db:
        for (amount, category, subcategory, description,
             tx_type, debt_target, source, created_at) in rows:
            cursor = await db.execute(
                """INSERT INTO transactions
                   (user_id, amount, category, subcategory, description, tx_type, debt_target, source, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (user_id, amount, category, subcategory, description, tx_type,
                 debt_target, source, created_at or _now_iso()),
            )
            ids.append(cursor.lastrowid)
            if tx_type == "debt_payment" and debt_target:
                await db.execute(
                    "UPDATE debts SET current_amount = MAX(0, current_amount - ?) WHERE id = ?",
                    (amount, debt_target),
                )
                cursor = await db.execute("SELECT current_amount FROM debts WHERE id = ?", (debt_target,))
                row = await cursor.fetchone()
                if row and row[0] == 0:
                    await db.execute("UPDATE debts SET status = 'closed' WHERE id = ?", (debt_target,))
        await db.commit()
    return ids


async def get_transaction(transaction_id: int, user_id: int | None = None):
    """Возвращает запись только владельцу — используется для повторения и отмены."""
    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        query = "SELECT * FROM transactions WHERE id = ?"
        params = [transaction_id]
        if user_id is not None:
            query += " AND user_id = ?"
            params.append(user_id)
        cursor = await db.execute(query, params)
        return await cursor.fetchone()


async def get_recent_transactions(user_id: int, limit: int = 8):
    """Последние записи пользователя для быстрого контроля расходов."""
    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        cursor = await db.execute(
            "SELECT * FROM transactions WHERE user_id = ? "
            "ORDER BY created_at DESC, id DESC LIMIT ?", (user_id, limit))
        return await cursor.fetchall()


async def find_similar_transaction(user_id: int, amount: float, tx_type: str = "expense",
                                   minutes: int = 10):
    """Недавняя запись того же типа на ту же сумму — вероятный повторный ввод чека.

    Telegram может прислать одно фото дважды, и человек тоже часто отправляет чек повторно.
    Без этой проверки расход считается дважды и бюджет врёт. Ищем только свои записи и
    только за последние минуты: ту же покупку через день — это уже другая покупка.
    """
    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        since = (datetime.now() - timedelta(minutes=minutes)).isoformat(sep=" ")
        cursor = await db.execute(
            "SELECT * FROM transactions WHERE user_id = ? AND tx_type = ? "
            "AND ABS(amount - ?) < 0.01 AND created_at > ? "
            "ORDER BY created_at DESC, id DESC LIMIT 1",
            (user_id, tx_type, float(amount or 0), since),
        )
        return await cursor.fetchone()


async def delete_transactions_by_ids(user_id: int, ids: list[int],
                                     source: str | None = None) -> int:
    """Удаляет несколько записей владельца (отмена импорта выписки).

    source — страховка: удаляем только строки импорта, даже если ids испорчены.
    """
    clean = [int(i) for i in ids if i]
    if not clean:
        return 0
    removed = 0
    async with aiosqlite.connect(DB_PATH) as db:
        placeholders = ",".join("?" for _ in clean)
        query = f"DELETE FROM transactions WHERE id IN ({placeholders}) AND user_id = ?"
        params: list = list(clean) + [user_id]
        if source:
            query += " AND source = ?"
            params.append(source)
        cursor = await db.execute(query, params)
        removed = cursor.rowcount or 0
        await db.execute(
            f"DELETE FROM receipt_items WHERE transaction_id IN ({placeholders})",
            clean)
        await db.commit()
    return removed


async def delete_transaction(transaction_id: int, user_id: int) -> bool:
    """Удаляет запись владельца и её позиции; платёж по долгу возвращает остаток."""
    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        cursor = await db.execute(
            "SELECT * FROM transactions WHERE id = ? AND user_id = ?",
            (transaction_id, user_id))
        row = await cursor.fetchone()
        if not row:
            return False
        if row["tx_type"] == "debt_payment" and row["debt_target"]:
            await db.execute(
                "UPDATE debts SET current_amount = current_amount + ?, status = 'active' "
                "WHERE id = ?", (row["amount"], row["debt_target"]))
        await db.execute("DELETE FROM receipt_items WHERE transaction_id = ?", (transaction_id,))
        await db.execute("DELETE FROM transactions WHERE id = ? AND user_id = ?",
                         (transaction_id, user_id))
        await db.commit()
        return True


async def add_receipt_items(transaction_id: int, items: list[dict]) -> None:
    """Сохраняет позиции чека, чтобы потом можно было смотреть и анализировать покупки."""
    if not items:
        return
    async with aiosqlite.connect(DB_PATH) as db:
        await db.executemany(
            "INSERT INTO receipt_items (transaction_id, name, qty, price, sum) VALUES (?, ?, ?, ?, ?)",
            [(transaction_id, item.get("name") or "Позиция", item.get("qty") or 1,
              item.get("price") or 0, item.get("sum") or 0) for item in items[:100]],
        )
        await db.commit()


async def get_receipt_items(transaction_id: int):
    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        cursor = await db.execute(
            "SELECT * FROM receipt_items WHERE transaction_id = ? ORDER BY id", (transaction_id,))
        return await cursor.fetchall()


def _receipt_name_key(name) -> str:
    """Ключ позиции чека для сопоставления: регистр и разметка строки на кассе не важны."""
    return re.sub(r"\W+", "", str(name or "").lower().replace("ё", "е"))


async def save_receipt_verdicts(transaction_id: int, rows: list[tuple]) -> int:
    """Сохраняет вердикты разбора корзины в позиции чека.

    Строки сопоставляются с позициями по названию: и позиции, и вердикты описывают один чек,
    но списки могут разойтись по длине (разбор пропустил позицию) — порядковое сопоставление
    тогда уезжает: совет чипсов достаётся соседнему молоку. Одинаковые названия потребляются
    по очереди, поэтому две одинаковые строки чека получают свои вердикты. Названию позиции
    место всё равно есть: без совпадения вердикт не пишется, а не уезжает к соседней строке.
    Строка — `(название, вердикт, совет)` и, если разбор знает происхождение, ещё и источник:
    без него позиция сохранится как «без пометки», а не как правило или оценка по догадке.
    """
    if not rows:
        return 0
    queue: dict[str, deque] = {}
    for row in rows:
        queue.setdefault(_receipt_name_key(row[0]), deque()).append(row)
    async with aiosqlite.connect(DB_PATH) as db:
        cursor = await db.execute(
            "SELECT id, name FROM receipt_items WHERE transaction_id = ? ORDER BY id",
            (transaction_id,))
        items = [(row[0], row[1]) for row in await cursor.fetchall()]
        updates = []
        for item_id, name in items:
            waiting = queue.get(_receipt_name_key(name))
            if not waiting:
                continue
            row = waiting.popleft()
            updates.append((row[1], row[2], row[3] if len(row) > 3 else None, item_id))
        if not updates:
            return 0
        await db.executemany(
            "UPDATE receipt_items SET verdict = ?, advice = ?, verdict_source = ? WHERE id = ?",
            updates)
        await db.commit()
        return len(updates)


async def update_receipt_verdict(item_id: int, verdict: str, advice: str,
                                 source: str | None = None) -> None:
    """Переписывает вердикт одной позиции — при пересчёте старых разборов по нынешним правилам.

    Пишется то же, что и при разборе, и источник ставится рядом: после пересчёта это уже
    не догадка модели, а проверка по названию, и отчёт должен видеть это так же.
    """
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute(
            "UPDATE receipt_items SET verdict = ?, advice = ?, verdict_source = ? WHERE id = ?",
            (verdict, advice, source, item_id))
        await db.commit()


async def get_receipt_verdicts(user_id: int | None = None, limit: int = 2000,
                               exclude_transaction_id: int | None = None):
    """Позиции с сохранённым вердиктом: что советовал разбор корзины и сколько это стоило.

    `exclude_transaction_id` нужен в момент записи чека: без него новый чек находил бы
    сам себя в истории советов.
    """
    query = ("SELECT i.id AS item_id, i.name, i.sum, i.verdict, i.advice, i.verdict_source, "
             "t.created_at, t.description "
             "FROM receipt_items i JOIN transactions t ON t.id = i.transaction_id "
             "WHERE t.tx_type = 'expense' AND i.verdict IS NOT NULL")
    params: list = []
    if user_id:
        query += " AND t.user_id = ?"
        params.append(user_id)
    if exclude_transaction_id:
        query += " AND t.id != ?"
        params.append(exclude_transaction_id)
    # Чеки — от свежих к старым, а позиции внутри чека — в своём порядке, как на кассе:
    # иначе отчёт читался бы снизу вверх.
    query += " ORDER BY t.created_at DESC, i.id LIMIT ?"
    params.append(limit)
    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        cursor = await db.execute(query, tuple(params))
        return await cursor.fetchall()


async def get_receipt_price_history(user_id: int, limit: int = 500):
    """Позиции прошлых чеков пользователя для сравнения цен.

    История возвращается до текущего момента; новый чек ещё не сохранён, поэтому он не
    может сам повлиять на свою рекомендацию. Сопоставление названий живёт в сервисе,
    а база отвечает только за полную и изолированную выборку пользователя.
    """
    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        cursor = await db.execute(
            "SELECT i.name, i.qty, i.price, i.sum, t.created_at, t.description "
            "FROM receipt_items i JOIN transactions t ON t.id = i.transaction_id "
            "WHERE t.user_id = ? AND t.tx_type = 'expense' "
            "ORDER BY t.created_at DESC, i.id DESC LIMIT ?",
            (user_id, limit),
        )
        return await cursor.fetchall()


async def get_transactions(user_id=None, days=30, tx_type=None):
    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        since = (datetime.now() - timedelta(days=days)).isoformat(sep=" ")
        query = "SELECT * FROM transactions WHERE created_at > ?"
        params: list = [since]
        if user_id:
            query += " AND user_id = ?"
            params.append(user_id)
        if tx_type:
            query += " AND tx_type = ?"
            params.append(tx_type)
        query += " ORDER BY created_at DESC"
        cursor = await db.execute(query, params)
        return await cursor.fetchall()


async def has_transactions(user_id=None) -> bool:
    """Есть ли у пользователя хоть одна запись — по этому определяем, новый он или нет."""
    async with aiosqlite.connect(DB_PATH) as db:
        query = "SELECT 1 FROM transactions"
        params: list = []
        if user_id:
            query += " WHERE user_id = ?"
            params.append(user_id)
        cursor = await db.execute(query + " LIMIT 1", params)
        return await cursor.fetchone() is not None


async def get_monthly_spending(user_id=None):
    """Возвращает траты по категориям за текущий месяц."""
    async with aiosqlite.connect(DB_PATH) as db:
        first_day = datetime.now().replace(day=1, hour=0, minute=0, second=0, microsecond=0).isoformat(sep=" ")
        query = """
            SELECT category, SUM(amount) as total
            FROM transactions
            WHERE created_at > ? AND tx_type = 'expense'
        """
        params = [first_day]
        if user_id:
            query += " AND user_id = ?"
            params.append(user_id)
        query += " GROUP BY category"
        cursor = await db.execute(query, params)
        return dict(await cursor.fetchall())


async def get_debts(include_closed=False):
    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        query = "SELECT * FROM debts"
        if not include_closed:
            query += " WHERE status = 'active'"
        query += " ORDER BY interest_rate DESC"
        cursor = await db.execute(query)
        return await cursor.fetchall()


async def get_debt(debt_id: str):
    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        cursor = await db.execute("SELECT * FROM debts WHERE id = ?", (debt_id,))
        return await cursor.fetchone()


async def get_total_spent_this_month(user_id=None):
    async with aiosqlite.connect(DB_PATH) as db:
        first_day = datetime.now().replace(day=1, hour=0, minute=0, second=0, microsecond=0).isoformat(sep=" ")
        query = "SELECT SUM(amount) FROM transactions WHERE created_at > ? AND tx_type = 'expense'"
        params = [first_day]
        if user_id:
            query += " AND user_id = ?"
            params.append(user_id)
        cursor = await db.execute(query, params)
        row = await cursor.fetchone()
        return row[0] or 0


async def get_month_income(user_id=None):
    async with aiosqlite.connect(DB_PATH) as db:
        first_day = datetime.now().replace(day=1, hour=0, minute=0, second=0, microsecond=0).isoformat(sep=" ")
        query = "SELECT SUM(amount) FROM transactions WHERE created_at > ? AND tx_type = 'income'"
        params = [first_day]
        if user_id:
            query += " AND user_id = ?"
            params.append(user_id)
        cursor = await db.execute(query, params)
        row = await cursor.fetchone()
        return row[0] or 0


async def set_setting(key: str, value: str):
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute(
            "INSERT INTO settings (key, value) VALUES (?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            (key, value),
        )
        await db.commit()


async def delete_setting(key: str) -> None:
    """Удаляет настройку — так личный лимит возвращается к семейному значению."""
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute("DELETE FROM settings WHERE key = ?", (key,))
        await db.commit()


async def get_setting(key: str, default=None):
    async with aiosqlite.connect(DB_PATH) as db:
        cursor = await db.execute("SELECT value FROM settings WHERE key = ?", (key,))
        row = await cursor.fetchone()
        return row[0] if row else default


async def get_all_settings() -> dict:
    """Все настройки одной выборкой (бюджет читается на каждое сообщение)."""
    async with aiosqlite.connect(DB_PATH) as db:
        cursor = await db.execute("SELECT key, value FROM settings")
        return {key: value for key, value in await cursor.fetchall()}
