"""Локальные образцы чеков для тестов.

Фото чеков и реальные суммы — личные данные владельца бота, поэтому в репозитории
их нет: тесты читают `receipt_samples.json` (он в `.gitignore`), а формат файла
повторяет `receipt_samples.example.json`.

Если файла нет, проверки на реальных чеках пропускаются — синтетические остаются.
"""
import json
import os

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
SAMPLES_FILE = os.path.join(BASE_DIR, "receipt_samples.json")


def load_samples() -> dict:
    """Читает локальные образцы чеков; пустой словарь, если файла нет или он битый."""
    try:
        with open(SAMPLES_FILE, encoding="utf-8") as file:
            data = json.load(file)
    except (OSError, ValueError):
        return {}
    return data if isinstance(data, dict) else {}


def sample_photos(sample: dict) -> list[str]:
    """Все фото образца — основное и дополнительные — абсолютными путями.

    Пути в файле образцов можно писать и относительно корня проекта, и абсолютно.
    """
    names = []
    if sample.get("photo"):
        names.append(sample["photo"])
    names.extend(sample.get("extra_photos") or [])
    photos = []
    for name in names:
        path = name if os.path.isabs(name) else os.path.join(BASE_DIR, name)
        if os.path.isfile(path):
            photos.append(path)
    return photos


def sample(name: str) -> dict | None:
    """Образец по имени, если его фото реально есть на диске. Иначе None."""
    entry = load_samples().get(name)
    if not isinstance(entry, dict) or not sample_photos(entry):
        return None
    return entry


def first_photo() -> str | None:
    """Фото любого доступного образца — когда конкретный чек не важен."""
    for name in ("dns", "grocery", "narrow"):
        entry = sample(name)
        if entry:
            return sample_photos(entry)[0]
    return None
