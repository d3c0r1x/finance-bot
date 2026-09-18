"""Оформление панели: цвета, шрифты и общие константы.

Здесь же живёт `run` — мост из синхронного Tk в асинхронные сервисы бота: вкладки вызывают
его вместо того, чтобы каждой заводить свой цикл событий (иначе один и тот же сервис
инициализировался бы по-разному в разных вкладках).
"""
import asyncio

BACKGROUND = "#1e2128"
PANEL = "#282c34"
FIELD = "#333844"
TEXT = "#e9ebee"
MUTED = "#9aa0a6"
ACCENT = "#4fd48c"
DANGER = "#ff6b6b"
FONT = ("Segoe UI", 10)
FONT_BOLD = ("Segoe UI", 10, "bold")
FONT_TITLE = ("Segoe UI", 14, "bold")
FONT_VALUE = ("Segoe UI", 16, "bold")

PERIODS = {"7 дней": 7, "30 дней": 30, "90 дней": 90, "Год": 365, "Всё время": None}
TX_TYPES = {"расходы": "expense", "доходы": "income", "платежи по долгам": "debt_payment",
            "все": None}
TX_TYPE_RU = {"expense": "расход", "income": "доход", "debt_payment": "платёж"}
ALL_USERS = "Все пользователи"


def run(coro):
    """Выполняет корутину сервисов бота (они асинхронные) из синхронного Tk."""
    return asyncio.run(coro)


def strip_emoji(text: str) -> str:
    """В Tk нет эмодзи-шрифта: из заголовков категорий эмодзи убираем."""
    return "".join(char for char in text if ord(char) < 0x2190)
