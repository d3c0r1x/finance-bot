import os
import shutil

from dotenv import load_dotenv

load_dotenv()

BOT_TOKEN = os.getenv("BOT_TOKEN")

# Telegram ID пользователей (узнать через @userinfobot)
USERS = {}
_user_id_1 = int(os.getenv("USER_ID_1", "0") or 0)
_user_id_2 = int(os.getenv("USER_ID_2", "0") or 0)
if _user_id_1:
    # Имя берём из Telegram при /start, а не из статического конфига.
    USERS[_user_id_1] = {"name": "", "role": "owner"}
if _user_id_2:
    USERS[_user_id_2] = {"name": "", "role": "partner"}

# Лимиты бюджета (в рублях) — демонстрационные стартовые значения.
# Свои лимиты ставятся в боте: ⚙️ Настройки → 🎯 Бюджет, либо здесь до первого запуска.
# После первого изменения лимиты живут в БД, и config тут больше не важен.
MONTHLY_LIMITS = {
    "еда": 20_000,
    "транспорт": 5_000,    # бензин
    "жилье": 0,
    "досуг": 5_000,
    "одежда": 10_000,
    "здоровье": 0,
    "работа": 0,
    "техника": 10_000,     # электроника и комплектующие (ДНС, М.Видео...)
    "долги": 0,            # платежи по кредитам считаются отдельно
    "прочее": 5_000,
}

TOTAL_MONTHLY_LIMIT = 55_000

# Сколько месяцев истории нужно, чтобы ИИ предложил бюджет
BUDGET_MIN_DAYS = 30

# ─── Настройки ИИ (Ollama) ───────────────────────────────────────────────
# Хост Ollama: локальная (http://localhost:11434), машина в сети (http://192.168.1.50:11434)
# или облако Ollama (https://ollama.com).
OLLAMA_HOST = os.getenv("OLLAMA_HOST", "http://localhost:11434").rstrip("/")

# Ключ для Ollama Cloud (https://ollama.com/settings/keys) — только для облачного хоста.
OLLAMA_API_KEY = os.getenv("OLLAMA_API_KEY", "")

# Основная модель. 7b заметно точнее в разборе трат, чем 1.5b (нужно ~5 ГБ ОЗУ/VRAM).
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "qwen2.5:7b-instruct")

# Если основная модель не скачана, бот сам выберет лучшую из установленных
# (список — от самой умной к самой слабой).
MODEL_PREFERENCE = [
    "qwen3:8b",
    "qwen2.5:7b-instruct",
    "qwen2.5:7b",
    "qwen3:4b",
    "gemma3:4b",
    "qwen2.5:3b-instruct",
    "llama3.2:3b",
    "qwen2.5:1.5b-instruct-q4_K_M",
]

# Таймауты обращений к модели, секунды
AI_PARSE_TIMEOUT = int(os.getenv("AI_PARSE_TIMEOUT", "90"))
AI_ADVICE_TIMEOUT = int(os.getenv("AI_ADVICE_TIMEOUT", "180"))

# ─── Чтение чеков моделью зрения (VLM) ───────────────────────────────────
# Локальная Vision-Language модель читает фото целиком: строки, колонки, блёклый текст.
# Отдельно от Tesseract: тот даёт точные цифры для арифметической проверки.
# 1 — читать чеки моделью зрения, 0 — только Tesseract.
VISION_ENABLED = os.getenv("VISION_ENABLED", "1").lower() not in {"0", "false", "no"}

# Модель зрения. 8b — лучшее качество (нужно ~6 ГБ VRAM), 4b-instruct — быстрее (3.3 ГБ).
#   ollama pull qwen3-vl:8b-instruct   |   ollama pull qwen3-vl:4b-instruct
VISION_MODEL = os.getenv("VISION_MODEL", "qwen3-vl:8b-instruct")

# Список запасных моделей 
VISION_PREFERENCE = [
    "qwen3-vl:8b-instruct",
    "qwen3-vl:4b-instruct",
    "qwen2.5vl:7b",
    "qwen2.5vl:3b",
    "minicpm-v:8b",
]

# Фото увеличиваем перед отправкой модели: мелкий шрифт чека читается точнее.
VISION_SCALE = float(os.getenv("VISION_SCALE", "2"))
# Сколько секунд модель зрения читает чек
VISION_TIMEOUT = int(os.getenv("VISION_TIMEOUT", "180"))
# Освобождать VRAM под модель зрения, выгружая текстовую модель (у 12 ГБ карт иначе не влезает)
VISION_UNLOAD_CHAT = os.getenv("VISION_UNLOAD_CHAT", "1").lower() not in {"0", "false", "no"}
# Сколько секунд после чтения чека модель зрения остаётся в памяти
VISION_KEEP_ALIVE = os.getenv("VISION_KEEP_ALIVE", "2m")
# Второй проход: если сумма позиций не сошлась с итогом чека — перечитать фото
# другим вариантом подготовки и с промптом, нацеленным на таблицу позиций.
# Стоит примерно одно чтение чека (десятки секунд) и заметно дотягивает потерянные строки.
VISION_SECOND_PASS = os.getenv("VISION_SECOND_PASS", "1").lower() not in {"0", "false", "no"}

# Убрать «плашку» веб-приложения слева от поля ввода (осталась от старого бота).
# 1 — заменяем её на штатное меню команд Telegram, 0 — не трогать.
HIDE_MENU_BUTTON = os.getenv("HIDE_MENU_BUTTON", "1").lower() not in {"0", "false", "no"}



def _find_tesseract() -> str:
    """Ищет tesseract.exe: переменная окружения → PATH → типичные папки установки."""
    from_env = os.getenv("TESSERACT_CMD", "").strip()
    candidates = [
        from_env,
        shutil.which("tesseract") or "",
        r"C:\Program Files\Tesseract-OCR\tesseract.exe",
        r"C:\Program Files (x86)\Tesseract-OCR\tesseract.exe",
        os.path.expandvars(r"%LOCALAPPDATA%\Programs\Tesseract-OCR\tesseract.exe"),
        "/usr/bin/tesseract",
        "/usr/local/bin/tesseract",
    ]
    for path in candidates:
        if path and os.path.isfile(path):
            return path
    return from_env or r"C:\Program Files\Tesseract-OCR\tesseract.exe"


TESSERACT_CMD = _find_tesseract()

# Час ежедневной сводки (локальное время)
DAILY_REPORT_HOUR = int(os.getenv("DAILY_REPORT_HOUR", "21"))

# Недельный дайджест: воскресенье вечером (день недели 0 — понедельник, 6 — воскресенье)
WEEKLY_REPORT_WEEKDAY = int(os.getenv("WEEKLY_REPORT_WEEKDAY", "6"))
WEEKLY_REPORT_HOUR = int(os.getenv("WEEKLY_REPORT_HOUR", "19"))

# Пути
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(BASE_DIR, "data")
DB_PATH = os.path.join(DATA_DIR, os.getenv("FINANCE_DB", "finance.db"))
RECEIPTS_DIR = os.path.join(DATA_DIR, "receipts")
