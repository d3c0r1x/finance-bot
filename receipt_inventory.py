"""Инвентарь чеков: прогоняет все локальные фото и показывает, где разбор врёт.

Смысл — видеть качество чтения на всём наборе, а не на одном удачном чеке. Скрипт
ничего не пишет в базу и не меняет фото: только читает и считает.

Запуск:
    venv\\Scripts\\python.exe receipt_inventory.py            # только Tesseract, быстро
    venv\\Scripts\\python.exe receipt_inventory.py --vision   # с моделью зрения (медленно)
    venv\\Scripts\\python.exe receipt_inventory.py --json     # машинный вывод
    venv\\Scripts\\python.exe receipt_inventory.py --strict   # выход 1 при грубых дефектах

Фото берутся из data/receipts (папка владельца, в .gitignore), плюс из локального
receipt_samples.json, плюс из аргументов командной строки.
"""
import argparse
import asyncio
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from samples import load_samples, sample_photos  # noqa: E402

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DIR = os.path.join(BASE_DIR, "data", "receipts")
PHOTO_SUFFIXES = (".jpg", ".jpeg", ".png", ".webp", ".bmp")


def collect_photos(extra: list[str]) -> list[str]:
    """Фото для прогона: аргументы → data/receipts → образцы из receipt_samples.json."""
    photos: list[str] = []
    for path in extra:
        if os.path.isfile(path):
            photos.append(os.path.abspath(path))
    if os.path.isdir(DEFAULT_DIR):
        for name in sorted(os.listdir(DEFAULT_DIR)):
            path = os.path.join(DEFAULT_DIR, name)
            if os.path.isfile(path) and name.lower().endswith(PHOTO_SUFFIXES):
                photos.append(path)
    for entry in load_samples().values():
        if isinstance(entry, dict):
            photos.extend(sample_photos(entry))
    unique: list[str] = []
    for path in photos:
        if path not in unique:
            unique.append(path)
    return unique


def ocr_only(path: str) -> dict:
    """Быстрый путь: только Tesseract, без моделей и без сети."""
    from ai.ocr import _extract_receipt_data_sync, is_product_name
    from ai.receipts import canonical_store

    raw = _extract_receipt_data_sync(path)
    items = [item for item in raw.get("items") or [] if item.get("name")]
    items_sum = round(sum(item.get("sum") or 0 for item in items), 2)
    total = raw.get("total")
    return {
        "total": total,
        "items_sum": items_sum,
        "positions": len(items),
        "store": canonical_store(raw.get("store")) if raw.get("store") else None,
        "bad_names": [item["name"] for item in items if not is_product_name(item["name"])],
        "reader": "ocr",
        "seconds": None,
    }


async def with_vision(path: str) -> dict:
    """Полный путь бота: модель зрения + Tesseract и сведение по арифметике."""
    from ai.receipts import parse_receipt

    result = await parse_receipt(path)
    items = result.get("items") or []
    return {
        "total": result.get("total"),
        "items_sum": result.get("items_total"),
        "positions": len(items),
        "store": result.get("store"),
        "bad_names": [item["name"] for item in items
                      if not (item.get("verified") or item.get("corroborated"))],
        "reader": result.get("reader") or result.get("parsed_by"),
        "total_estimated": result.get("total_estimated", False),
        "over_total": result.get("over_total", False),
        "mismatch": result.get("items_mismatch", False),
        "seconds": None,
    }


def gaps_of(row: dict) -> dict:
    """Что не так с разбором: без итога, без позиций, дороже чека, расхождение, мусорные имена."""
    total, items_sum = row.get("total"), row.get("items_sum") or 0
    tolerance = max(3.0, (total or 0) * 0.03)
    difference = round(items_sum - (total or 0), 2)
    return {
        "нет итога": total is None,
        "нет позиций": row.get("positions") == 0,
        "итог по позициям": bool(row.get("total_estimated")),
        "превышение": bool(row.get("over_total")) or (bool(total) and difference > tolerance),
        "расхождение": bool(row.get("mismatch")) or (bool(total) and abs(difference) > tolerance),
        "не-товары": bool(row.get("bad_names")),
        "разница": difference,
    }


def short(path: str, width: int = 30) -> str:
    name = os.path.basename(path)
    return name if len(name) <= width else name[:width - 1] + "…"


async def main() -> int:
    parser = argparse.ArgumentParser(description="Прогон всех локальных чеков через читатель")
    parser.add_argument("photos", nargs="*", help="свои фото (по умолчанию — все локальные)")
    parser.add_argument("--vision", action="store_true", help="читать моделью зрения (медленно)")
    parser.add_argument("--json", action="store_true", help="вывести результат в JSON")
    parser.add_argument("--strict", action="store_true", help="код 1, если есть грубые дефекты")
    parser.add_argument("--limit", type=int, default=0, help="ограничить число чеков")
    parser.add_argument("--debug", action="store_true",
                        help="показать конкурирующие разборы проблемных чеков")
    args = parser.parse_args()

    photos = collect_photos(args.photos)
    if args.limit:
        photos = photos[:args.limit]
    if not photos:
        print("⏭  локальных чеков нет: положи фото в data/receipts или укажи путь аргументом")
        return 0

    rows = []
    for path in photos:
        started = time.time()
        try:
            row = await with_vision(path) if args.vision else ocr_only(path)
        except Exception as error:                     # один битый чек не должен ломать прогон
            row = {"total": None, "items_sum": 0, "positions": 0, "store": None,
                   "bad_names": [], "reader": f"ошибка: {type(error).__name__}"}
        row["seconds"] = round(time.time() - started, 1)
        row["file"] = path
        row.update(gaps_of(row))
        rows.append(row)

    if args.json:
        print(json.dumps(rows, ensure_ascii=False, indent=2))
    else:
        print(f"{'№':>3}  {'файл':<30} {'итог':>10} {'позиции':>10} {'шт':>3} "
              f"{'Δ':>9}  {'чтение':<7} флаги")
        for index, row in enumerate(rows, start=1):
            total = row["total"] if row["total"] is not None else "—"
            total_text = f"{total:.2f}" if isinstance(total, float) else total
            flags = [name for name in ("нет итога", "нет позиций", "итог по позициям", "превышение",
                                       "расхождение", "не-товары") if row[name]]
            print(f"{index:>3}  {short(row['file']):<30} {total_text:>10} "
                  f"{row['items_sum']:>10.2f} {row['positions']:>3} "
                  f"{row['разница']:>9.2f}  {row['reader'] or '—':<7} {', '.join(flags) or '—'}")
            for name in row["bad_names"][:3]:
                print(f"      ↳ подозрительная позиция: {name}")

    if args.debug:
        from ai.ocr import parse_candidates

        broken = [row for row in rows
                  if row["превышение"] or row["расхождение"] or row["нет позиций"]]
        for row in broken:
            print(f"\n🔍 Разборы-кандидаты: {short(row['file'])}")
            for candidate in parse_candidates(row["file"], limit=6):
                names = ", ".join(candidate["names"]) or "—"
                print(f"   вариант {candidate['variant']} · оценка {candidate['score']:>7.2f} · "
                      f"итог {candidate['total']} · позиций {candidate['positions']} "
                      f"на {candidate['items_total']} · {names[:70]}")

    ok = [row for row in rows if not any(row[key] for key in
                                         ("нет итога", "нет позиций", "превышение", "расхождение"))]
    print(f"\nЧеков: {len(rows)} · сошлись с итогом: {len(ok)} · "
          f"с превышением: {sum(1 for row in rows if row['превышение'])} · "
          f"без итога: {sum(1 for row in rows if row['нет итога'])} · "
          f"среднее время: {sum(row['seconds'] for row in rows) / len(rows):.1f} с")

    hard = [row for row in rows if row["превышение"] or row["нет позиций"]]
    if hard:
        print("❌ Требуют внимания: " + ", ".join(short(row["file"], 24) for row in hard))
    return 1 if args.strict and hard else 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
