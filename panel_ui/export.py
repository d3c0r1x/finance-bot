"""Вкладка «Экспорт CSV»: выгрузка транзакций файлом на компьютер.

Формат собирает `services/export.py` — тот же, который раньше отправлял файл в Telegram:
один владелец формата на оба интерфейса.
"""
import os
from datetime import datetime
from tkinter import filedialog, messagebox, ttk

import pandas as pd

from database import panel_data as data
from panel_ui.base import Tab
from panel_ui.theme import run
from services.export import export_csv


class ExportTab(Tab):
    """Выбор пользователя и периода, затем сохранение CSV через системный диалог."""

    title = "Экспорт CSV"

    def build(self) -> None:
        ttk.Label(self.frame, text="Выгрузка транзакций в CSV (Excel-совместимый, "
                                   "разделитель «;»)", style="Muted.TLabel").pack(anchor="w")
        ttk.Label(self.frame, text="Раньше эта кнопка была в Telegram — файлы удобнее хранить "
                                   "на компьютере.", style="Muted.TLabel").pack(anchor="w",
                                                                              pady=(2, 12))

        filters = ttk.Frame(self.frame)
        filters.pack(fill="x")
        self.user_box = self.user_filter(filters)
        self.period_box = self.period_filter(filters, initial="Год")

        ttk.Button(self.frame, text="💾 Сохранить CSV...", style="Accent.TButton",
                   command=self.save_file).pack(anchor="w", pady=14)
        self.info = ttk.Label(self.frame, text="", style="Muted.TLabel")
        self.info.pack(anchor="w")

    def save_file(self) -> None:
        user_id = self.selected_user(self.user_box)
        days = self.selected_period(self.period_box)
        rows = data.transactions(user_id=user_id, days=days, tx_type=None, limit=100_000)
        if not rows:
            messagebox.showinfo("Пусто", "За выбранный период записей нет")
            return
        default = f"finance_{datetime.now().strftime('%Y%m%d_%H%M')}.csv"
        path = filedialog.asksaveasfilename(title="Сохранить CSV", defaultextension=".csv",
                                            initialfile=default,
                                            filetypes=[("CSV", "*.csv")])
        if not path:
            return
        # Формат файла собирает сервис бота: один владелец формата на панель и бота
        _filename, content = run(export_csv(pd.DataFrame([dict(row) for row in rows])))
        with open(path, "wb") as file:
            file.write(content)
        self.info.configure(text=f"✅ Сохранено {len(rows)} записей: {path}")
        self.status(f"CSV выгружен: {path}")
        if hasattr(os, "startfile"):
            os.startfile(os.path.dirname(path))  # Windows: показать файл в проводнике
