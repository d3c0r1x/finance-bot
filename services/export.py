"""Экспорт транзакций в CSV (Excel-совместимый, разделитель «;», кодировка UTF-8 BOM)."""
import io
from datetime import datetime

import pandas as pd


async def export_csv(df: pd.DataFrame) -> tuple[str, bytes]:
    """Возвращает (имя_файла, содержимое) для отправки документом."""
    if "created_at" in df.columns:
        df = df.copy()
        df["created_at"] = pd.to_datetime(df["created_at"]).dt.strftime("%Y-%m-%d %H:%M")
    df = df.drop(columns=["id"], errors="ignore")

    columns = {
        "created_at": "Дата",
        "amount": "Сумма",
        "category": "Категория",
        "subcategory": "Подкатегория",
        "description": "Описание",
        "tx_type": "Тип",
        "debt_target": "Долг",
        "source": "Источник",
        "user_id": "Telegram ID",
    }
    df = df.rename(columns=columns)
    ordered = [c for c in columns.values() if c in df.columns]
    df = df[ordered]

    buffer = io.StringIO()
    df.to_csv(buffer, index=False, sep=";", encoding="utf-8-sig")
    filename = f"finance_export_{datetime.now().strftime('%Y%m%d_%H%M')}.csv"
    return filename, buffer.getvalue().encode("utf-8-sig")
