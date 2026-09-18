"""Доступ к базе для панели управления: синхронный sqlite3.

Бот пишет в ту же базу через aiosqlite (`database/db.py`) и о GUI знать не должен.
Здесь живут запросы, нужные только панели: агрегаты по пользователям, выборки для таблиц,
правка и удаление записей. Настройки и лимиты читаются теми же ключами, что и в боте
(`limit:{категория}`, `limit:total`, `profile:{id}:{поле}`), поэтому панель и Telegram
всегда показывают одно и то же.
"""
import sqlite3
from datetime import datetime, timedelta

from config import DB_PATH, MONTHLY_LIMITS, TOTAL_MONTHLY_LIMIT, USERS
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


# ─── Товары и закупка ─────────────────────────────────────────────

def receipt_price_history(user_id: int | None = None,
                          limit: int = 2000) -> list[dict]:
    """Позиции чеков для каталога цен: синхронный аналог выборки бота.

    Панель отдаёт строки, а решение «это тот же товар» принимает сервис
    (`services/purchase_history.py`) — одна логика на бота и панель.
    """
    clause, params = "t.tx_type = 'expense'", []
    if user_id:
        clause += " AND t.user_id = ?"
        params.append(user_id)
    rows = query(f"""SELECT i.name, i.qty, i.price, i.sum, t.created_at, t.description
                      FROM receipt_items i
                      JOIN transactions t ON t.id = i.transaction_id
                      WHERE {clause}
                      ORDER BY t.created_at DESC, i.id DESC LIMIT ?""", params + [limit])
    return [dict(row) for row in rows]


def has_column(table: str, column: str) -> bool:
    """Есть ли колонка в таблице.

    Панель не запускает `init_db`, поэтому миграции может ещё не быть: до первого запуска
    бота новой версии запрос к несуществующей колонке просто не делается.
    """
    return any(row["name"] == column for row in query(f"PRAGMA table_info({table})"))


def receipt_verdicts(user_id: int | None = None, limit: int = 2000) -> list[dict]:
    """Позиции чеков с сохранённым вердиктом разбора — те же строки, что читает бот."""
    if not has_column("receipt_items", "verdict"):
        return []
    clause, params = "t.tx_type = 'expense' AND i.verdict IS NOT NULL", []
    if user_id:
        clause += " AND t.user_id = ?"
        params.append(user_id)
    # Пометка происхождения появилась позже вердиктов: без колонки панель работает как раньше,
    # и позиции честно остаются «без пометки», а не получают её задним числом.
    source = ("i.verdict_source" if has_column("receipt_items", "verdict_source")
              else "NULL AS verdict_source")
    rows = query(f"""SELECT i.name, i.sum, i.verdict, i.advice, {source}, t.created_at,
                          t.description
                      FROM receipt_items i
                      JOIN transactions t ON t.id = i.transaction_id
                      WHERE {clause}
                      ORDER BY t.created_at DESC, i.id LIMIT ?""", params + [limit])
    return [dict(row) for row in rows]


def product_catalog(user_id: int | None = None,
                    min_purchases: int = 3) -> list[dict]:
    """Товары с обычной ценой и лучшим магазином — то же, что видно в боте."""
    from services.purchase_history import product_groups
    return product_groups(receipt_price_history(user_id), min_purchases=min_purchases)


def product_receipts(user_id: int | None, name: str, limit: int = 500) -> list[dict]:
    """Где и почём покупался один товар: те же строки, что сравнивает бот.

    Сопоставление названий — общее (`same_product`), поэтому в истории товара видны те же
    покупки, из которых сложилась его обычная цена.
    """
    from services.purchase_history import parse_date, same_product

    rows = [row for row in receipt_price_history(user_id, limit=limit)
            if same_product(name, row["name"])]
    rows.sort(key=lambda row: str(row.get("created_at") or ""), reverse=True)
    return [{"date": parse_date(row.get("created_at")),
             "store": (row.get("description") or "—"),
             "price": float(row.get("price") or 0),
             "name": row.get("name") or ""} for row in rows]


def advice_allowed(user_id: int | None) -> set[str]:
    """Товары, разрешённые вопреки вердиктам: ключ настроек и разбор — у `services/advice.py`."""
    from services import mutelist
    from services.advice import ALLOWED_KEY

    if not user_id:
        return set()
    return mutelist.parse(settings_map().get(ALLOWED_KEY.format(user_id=user_id), ""))


def advice_confirmed(user_id: int | None) -> set[str]:
    """Товары, которые человек подтвердил как «не брать»: ключ настроек — у советника."""
    from services import mutelist
    from services.advice import CONFIRMED_KEY

    if not user_id:
        return set()
    return mutelist.parse(settings_map().get(CONFIRMED_KEY.format(user_id=user_id), ""))


def banned_products(user_id: int | None = None) -> list[dict]:
    """Личный список «не брать» по сохранённым вердиктам — тот же расчёт, что в боте."""
    from services.advice import banned

    return banned(receipt_verdicts(user_id), advice_allowed(user_id),
                  confirmed=advice_confirmed(user_id))


def goal_history_entries(user_id: int | None) -> list[dict]:
    """Итоги закончившихся целей так, как их видит панель: тот же парсер, что у бота."""
    from services.advice import GOAL_HISTORY_KEY, parse_goal_history

    if user_id is None:
        return []
    return parse_goal_history(settings_map().get(GOAL_HISTORY_KEY.format(user_id=user_id), ""))


def goal_record(user_id: int | None) -> dict | None:
    """Цель пользователя: разбор записи и её срок — у советника, панель только читает ключ."""
    from services.advice import GOAL_KEY, parse_goal

    if not user_id:
        return None
    return parse_goal(settings_map().get(GOAL_KEY.format(user_id=user_id), ""))


def recalc_record(user_id: int | None) -> dict | None:
    """Последний пересчёт разборов: разбор записи делает советник, панель только читает ключ."""
    from services.advice import RECALC_KEY, parse_recalc

    if not user_id:
        return None
    return parse_recalc(settings_map().get(RECALC_KEY.format(user_id=user_id), ""))


def banned_guesses(user_id: int | None = None) -> list[dict]:
    """Товары, которые необязательными называла только модель: в «не брать» они не попадают."""
    from services.advice import guesses

    return guesses(receipt_verdicts(user_id), advice_allowed(user_id),
                   confirmed=advice_confirmed(user_id))


def shopping_list(user_id: int | None = None) -> list[dict]:
    """Что пора купить по ритму чеков: та же функция, что считает подсказку в Telegram.

    Товары из личного списка «не брать» сюда не попадают — как и в боте: панель не должна
    предлагать то, что человек сам признал лишним.
    """
    from services.shopping import due_items, hide_blocked

    blocked = {entry["key"] for entry in banned_products(user_id)}
    visible, _hidden = hide_blocked(due_items(receipt_price_history(user_id)), blocked)
    return visible


# ─── Недельный лимит на продукты ──────────────────────

def food_week_overview() -> list[dict]:
    """Недельные лимиты на продукты: у каждого пользователя свой (⚙️ Настройки → 🎯 Бюджет).

    И сумму за семь дней, и состояние лимита считают функции бота (`weekly_spend` и
    `limit_status`), а панель лишь добавляет к ним пользователя. Своих порогов «почти» и
    «превышен» здесь нет специально: иначе панель однажды пометила бы строку иначе, чем бот.
    Показываются те, у кого лимит задан или есть траты на еду за неделю.
    """
    from services.forecast import WEEK_DAYS, food_limit_key, limit_status, weekly_spend

    stored = settings_map()
    rows = []
    for user_id in user_ids():
        limit = _number(stored.get(food_limit_key(user_id)), 0)
        spent = weekly_spend([dict(row) for row in transactions(user_id=user_id, days=WEEK_DAYS)])
        if not limit and not spent:
            continue
        status = limit_status(spent, limit) or {"limit": 0, "current": spent, "ratio": 0,
                                               "left": 0, "over": False, "near": False}
        rows.append({"user_id": user_id, **status})
    return rows


def set_food_week_limit(user_id: int, value: float) -> None:
    """Правит недельный лимит пользователя (0 — отключить): ключ тот же, что пишет бот."""
    from services.forecast import food_limit_key

    set_setting(food_limit_key(user_id), str(round(float(value), 2)))


# ─── Аналитика бота ──────────────────────────────────────────────────────

HISTORY_DAYS = 200   # как в дайджесте: подпискам и темпу продуктов нужны недели истории


def analytics(user_id: int | None = None, today: datetime | None = None,
              income: float | None = None, limit: float | None = None) -> dict:
    """Что бот знает про пользователя: личная инфляция, продуктовая неделя и подписки.

    Ни один расчёт здесь не повторяется: инфляцию считает `services/inflation.py`, темп
    продуктов и лимит — `services/forecast.py`, серии — `services/recurring.py`. Панель
    только приносит им историю из базы. Иначе получился бы второй набор цифр, который со
    временем разошёлся бы с тем, что человек видит в Telegram.

    `income` и `limit` — для доли потолка экономии. Если их не передали, берётся доход месяца
    и семейный лимит из самой панели; вкладка передаёт значения бота (`budget.get_total_limit`
    и доход месяца), когда у пользователя могут быть личные лимиты.
    """
    from services import mutelist
    from services.advice import (advice_effects, banned, corrected_positions, goal_history_text,
                                goal_progress, goal_text, guesses, saving_forecast,
                                waste_summary, waste_trend)
    from services.forecast import food_limit_key, grocery_forecast, limit_status, weekly_spend
    from services.inflation import personal_inflation
    from services.recurring import find_recurring, monthly_total

    rows = [dict(row) for row in transactions(user_id=user_id, days=HISTORY_DAYS)]
    verdicts = receipt_verdicts(user_id)
    # История чеков нужна сразу трём разделам — читается один раз, а не по запросу на каждый.
    history = receipt_price_history(user_id)
    goal = goal_record(user_id)
    goal_entries = goal_history_entries(user_id)
    month_income = totals(user_id)["income"] if income is None else income
    month_limit = limits().get("total", 0) if limit is None else limit
    limit = _number(settings_map().get(food_limit_key(user_id)), 0) if user_id else 0
    spent = weekly_spend(rows, today=today)
    found = find_recurring(rows, today=today)
    # Отключённые серии остаются в базе, но бот про них не напоминает — панель это показывает.
    visible, muted = mutelist.split(found, muted_keys(user_id, mutelist.RECURRING))
    return {
        "inflation": personal_inflation(history, today=today),
        "grocery": grocery_forecast(rows, today=today),
        "grocery_status": limit_status(spent, limit),
        "weekly_spend": spent,
        "recurring": visible,
        "muted": muted,
        # В месяц — по всем сериям: отключена только подсказка, деньги уходят по-прежнему.
        "recurring_month": monthly_total(found),
        # Необязательные покупки, их динамика и личный список «не брать» — по одним и тем же
        # сохранённым вердиктам: строки читаются один раз, а не по запросу на каждый экран.
        "waste": waste_summary(verdicts, allowed=advice_allowed(user_id)),
        # Цель на месяц и её ход: считается советником по той же истории чеков, что и цены.
        "goal": goal_text(goal, goal_progress(goal, history, today=today), today=today,
                          entries=goal_entries),
        # История итогов — тем же парсером, что у бота: иначе панельное «сдержано N из M»
        # разошлось бы с тем, что человек видит в Telegram.
        "goal_history": goal_history_text(goal_entries),
        "waste_trend": waste_trend(verdicts),
        # Когда пересчитывали разборы: движение доли могло прийти от правки, а не от покупок.
        "recalc": recalc_record(user_id),
        "banned": banned(verdicts, advice_allowed(user_id), confirmed=advice_confirmed(user_id)),
        # Догадки модели: в «не брать» они не попали и из списка покупок не убраны.
        "banned_guesses": guesses(verdicts, advice_allowed(user_id),
                                  confirmed=advice_confirmed(user_id)),
        # Что человек поправил в разборе: из необязательного убрано, но видно и в панели.
        "waste_corrected": corrected_positions(verdicts, advice_allowed(user_id)),
        # Потолок экономии в месяц — по тому же порогу привычки, тому же списку разрешённых
        # и с той же оговоркой о масштабе (лимит месяца и доход), что в боте.
        "saving": saving_forecast(verdicts, advice_allowed(user_id),
                                  income=month_income, limit=month_limit),
        # Эффект советов: частота тех же товаров по всей истории чеков, а не по вердиктам.
        "effects": advice_effects(verdicts, history, today=today),
    }


def muted_keys(user_id: int | None, section: str) -> set[str]:
    """Что пользователь отключил в боте: ключ и формат хранения берутся у `services/mutelist.py`."""
    from services import mutelist

    if not user_id:
        return set()
    return mutelist.parse(settings_map().get(
        mutelist.STORAGE_KEY.format(section=section, user_id=user_id), ""))


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


def user_ids() -> list[int]:
    """Кто вообще есть: из таблицы пользователей, конфига и записей в базе."""
    return sorted(set(USERS)
                  | {row["telegram_id"] for row in query("SELECT telegram_id FROM users")}
                  | {row["user_id"] for row in query("SELECT DISTINCT user_id FROM transactions")})


def users_overview() -> list[dict]:
    """Строка на пользователя: имя, роль, доход-план, настройка, активность и суммы месяца."""
    settings = settings_map()
    known = {row["telegram_id"]: row for row in query("SELECT * FROM users")}
    overview = []
    for user_id in user_ids():
        stats = totals(user_id)
        last = one("SELECT created_at FROM transactions WHERE user_id = ? "
                   "ORDER BY created_at DESC LIMIT 1", (user_id,))
        count = one("SELECT COUNT(*) AS n FROM transactions WHERE user_id = ?", (user_id,))["n"]
        overview.append({
            "user_id": user_id,
            "name": user_label(user_id, settings, known.get(user_id)),
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

def user_label(user_id: int, settings: dict[str, str] | None = None,
               registered: dict | None = None) -> str:
    """Как показать пользователя: имя из бота, затем из базы, затем из config, иначе id.

    Имя первого шага пишет приветственная настройка, поэтому важно брать именно его:
    в config имена могут быть пустыми, и тогда все таблицы панели теряли бы подпись строки.
    """
    settings = settings_map() if settings is None else settings
    if registered is None:
        registered = one("SELECT * FROM users WHERE telegram_id = ?", (user_id,))
    return (settings.get(f"profile:{user_id}:name")
            or (registered["name"] if registered and registered["name"] else None)
            or (USERS.get(user_id) or {}).get("name")
            or str(user_id))


def settings_map() -> dict[str, str]:
    return {row["key"]: row["value"] for row in query("SELECT key, value FROM settings")}


def set_setting(key: str, value: str) -> None:
    execute("INSERT INTO settings (key, value) VALUES (?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value = excluded.value", (key, value))


def delete_setting(key: str) -> None:
    execute("DELETE FROM settings WHERE key = ?", (key,))


def personal_limit_users() -> list[int]:
    """Кто задал свои лимиты: у этих пользователей бот считает не по семейным значениям.

    Личный лимит живёт под ключом `limit:<id>:<категория>`, семейный — под `limit:<категория>`.
    Панель показывает семейные значения, поэтому о личных она должна хотя бы сообщить.
    """
    users: set[int] = set()
    for key in settings_map():
        parts = key.split(":")
        if len(parts) == 3 and parts[0] == "limit" and parts[1].isdigit():
            users.add(int(parts[1]))
    return sorted(users)


def limits() -> dict[str, float]:
    """Семейные лимиты по категориям и общий (ключи без id пользователя).

    Если значения в базе нет, берётся стартовое из `config` — ровно как это делает бот
    (`services/budget.py`). Иначе на чистой базе панель показывала «0 — без лимита» там,
    где бот уже считал по лимиту, и цифры двух интерфейсов не сходились.
    """
    stored = settings_map()
    result: dict[str, float] = {}
    for category in CATEGORIES:
        result[category] = _number(stored.get(f"limit:{category}"),
                                   MONTHLY_LIMITS.get(category, 0))
    result["total"] = _number(stored.get("limit:total"), float(TOTAL_MONTHLY_LIMIT))
    return result


def _number(value, fallback: float) -> float:
    """Число из настроек или запасное значение (в базе может лежать мусор)."""
    if value in (None, ""):
        return float(fallback)
    try:
        return float(value)
    except ValueError:
        return float(fallback)


def set_limit(category: str, value: float) -> None:
    """Меняет семейный лимит (значение по умолчанию): личные лимиты пользователей не трогает."""
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
