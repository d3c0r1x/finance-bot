"""Подбор шрифта для картинок: синтетические чеки в тестах.

Шрифт нужен моноширинный и с кириллицей — чек рисуется колонками цифр, и на
пропорциональном шрифте OCR читает его иначе, чем настоящее фото. Поиск живёт в одном
месте: раньше он был скопирован в три теста, и каждый знал только путь своей платформы
(на Linux и macOS синтетический чек просто не рисовался, а тест молча пропускался).
"""
import os

# Моноширинные шрифты с кириллицей: Windows, Linux (DejaVu/Liberation), macOS.
MONO_CANDIDATES = (
    "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationMono-Regular.ttf",
    "/usr/share/fonts/truetype/freefont/FreeMono.ttf",
    r"C:\Windows\Fonts\consola.ttf",
    r"C:\Windows\Fonts\lucon.ttf",
    "/System/Library/Fonts/Menlo.ttc",
    "/Library/Fonts/Arial Unicode.ttf",
)


def mono_font(size: int, fallback: bool = True):
    """Моноширинный шрифт заданного размера; `fallback=False` — None, если его нет."""
    from PIL import ImageFont  # импорт только когда шрифт реально понадобился

    for path in MONO_CANDIDATES:
        if not os.path.isfile(path):
            continue
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            continue  # файл есть, но Pillow его не понимает — пробуем следующий
    return ImageFont.load_default() if fallback else None
