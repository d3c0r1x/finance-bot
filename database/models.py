CREATE_TABLES = """
CREATE TABLE IF NOT EXISTS users (
    telegram_id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'user',
    registered_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    amount REAL NOT NULL,
    category TEXT NOT NULL DEFAULT 'прочее',
    subcategory TEXT,
    description TEXT,
    tx_type TEXT NOT NULL DEFAULT 'expense',  -- expense, income, debt_payment
    debt_target TEXT,                         -- sber, tbank, yandex, main
    source TEXT DEFAULT 'text',               -- text, photo, manual
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(telegram_id)
);

CREATE TABLE IF NOT EXISTS debts (
    id TEXT PRIMARY KEY,           -- sber, tbank, yandex, main
    name TEXT NOT NULL,
    initial_amount REAL NOT NULL,
    current_amount REAL NOT NULL,
    interest_rate REAL,
    min_payment REAL,
    status TEXT DEFAULT 'active'   -- active, closed
);

CREATE TABLE IF NOT EXISTS receipt_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    transaction_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    qty REAL DEFAULT 1,
    price REAL DEFAULT 0,
    sum REAL DEFAULT 0,
    FOREIGN KEY (transaction_id) REFERENCES transactions(id)
);

CREATE INDEX IF NOT EXISTS idx_receipt_items_transaction
    ON receipt_items (transaction_id);

CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value TEXT
);
"""

# Категории, которые понимает бот (используются в бюджете и отчётах)
CATEGORIES = ("еда", "транспорт", "жилье", "досуг", "одежда", "здоровье", "работа",
              "техника", "долги", "прочее")

# Стартовые долги — демонстрационные, по 1 000 ₽ каждый: завести свои можно
# в боте (💳 Долги → изменить остаток) или в панели управления.
INITIAL_DEBTS = [
    ("sber", "Сбербанк кредитка", 1000, 1000, 24.0, 1000),
    ("tbank", "Т-Банк кредитка", 1000, 1000, 24.0, 1000),
    ("yandex", "Яндекс кредит", 1000, 1000, None, 1000),
    ("main", "Основной кредит", 1000, 1000, 18.0, 1000),
]
