"""Проверка состояния внешних сервисов: Ollama (ИИ), зрение (VLM) и Tesseract (OCR)."""
import os
import shutil

from ai.llm import find_model, is_local_host, list_installed_models, resolve_model
from ai.vision import vision_status
from config import OLLAMA_HOST, OLLAMA_MODEL, TESSERACT_CMD, VISION_ENABLED, VISION_MODEL


async def check_ollama() -> tuple[bool, str]:
    """Проверяет, что Ollama отвечает и есть модель для работы."""
    models = await list_installed_models(force_refresh=True)
    if not models:
        if is_local_host(OLLAMA_HOST):
            return False, (f"Ollama не отвечает на {OLLAMA_HOST} — запусти приложение Ollama. "
                           f"Модель скачивается командой `ollama pull {OLLAMA_MODEL}`")
        return False, (f"Не вижу моделей на {OLLAMA_HOST} — проверь `OLLAMA_API_KEY` и интернет. "
                       f"ИИ-модель: `{OLLAMA_MODEL}`")

    preferred = find_model(OLLAMA_MODEL, models)
    if preferred:
        return True, f"модель {preferred}"

    used = await resolve_model(force_refresh=True)
    return True, (f"используется {used} — для более точного разбора скачай "
                  f"`ollama pull {OLLAMA_MODEL}`")


def check_tesseract() -> tuple[bool, str]:
    if shutil.which("tesseract") or _cmd_exists(TESSERACT_CMD):
        return True, f"установлен ({TESSERACT_CMD})"
    return False, (f"не найден по пути {TESSERACT_CMD}. Установи с "
                   "https://github.com/UB-Mannheim/tesseract/wiki и укажи путь "
                   "в `.env` — переменная `TESSERACT_CMD`")


async def check_vision() -> tuple[bool, str]:
    """Модель зрения, которая читает чеки с фото (основной путь разбора)."""
    if not VISION_ENABLED:
        return False, "выключена (VISION_ENABLED=0 в `.env`) — чеки читает только Tesseract"
    status = await vision_status()
    if status.get("model"):
        return True, f"модель {status['model']}"
    installed = status.get("installed") or []
    if not installed:
        return False, (f"нет доступа к моделям Ollama — скачай модель зрения: "
                       f"`ollama pull {VISION_MODEL}`")
    return False, (f"`{VISION_MODEL}` не скачана. Выполни в консоли: "
                   f"`ollama pull {VISION_MODEL}` — она читает фото чеков целиком")


def _cmd_exists(path: str) -> bool:
    return bool(path) and os.path.isfile(path) and os.access(path, os.X_OK)
