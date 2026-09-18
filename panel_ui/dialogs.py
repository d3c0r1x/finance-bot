"""Диалоги панели: правка записи и окно с готовой картинкой отчёта."""
import tkinter as tk
from datetime import datetime
from tkinter import messagebox, ttk

from config import USERS
from database import panel_data as data
from database.models import CATEGORIES
from panel_ui.theme import BACKGROUND, TX_TYPE_RU


class TransactionDialog(tk.Toplevel):
    """Окно добавления и правки записи: те же поля, что у карточки в боте."""

    def __init__(self, parent, title: str, transaction=None):
        super().__init__(parent)
        self.title(title)
        self.configure(background=BACKGROUND)
        self.resizable(False, False)
        self.result: dict | None = None
        row = transaction

        def field(label: str, value: str, row_index: int, options: list[str] | None = None):
            ttk.Label(self, text=label).grid(row=row_index, column=0, sticky="w", padx=12, pady=6)
            if options:
                widget = ttk.Combobox(self, values=options, state="readonly", width=28)
                widget.set(value)
            else:
                widget = ttk.Entry(self, width=30)
                widget.insert(0, value)
            widget.grid(row=row_index, column=1, padx=12, pady=6)
            return widget

        user_ids = sorted({item["user_id"] for item in data.query(
            "SELECT DISTINCT user_id FROM transactions")} | set(USERS))
        user_choices = [f"{data.user_label(user_id)} ({user_id})" for user_id in user_ids]
        default_user = next((choice for choice in user_choices
                             if str(row["user_id"]) + ")" in choice), user_choices[0]) if row \
            else (user_choices[0] if user_choices else "")
        self.user_box = field("Пользователь", default_user, 0, user_choices) if user_choices else None
        self.amount = field("Сумма, ₽", f"{row['amount']:.2f}" if row else "", 1)
        self.type_box = field("Тип", (TX_TYPE_RU.get(row["tx_type"], "расход") if row else "расход"),
                              2, list(TX_TYPE_RU.values()))
        self.category_box = field("Категория", row["category"] if row else "прочее", 3,
                                  list(CATEGORIES))
        self.subcategory = field("Подкатегория", (row["subcategory"] or "") if row else "", 4)
        self.description = field("Описание", (row["description"] or "") if row else "", 5)
        debt_ids = [item["id"] for item in data.debts()]
        self.debt_box = field("Долг (для платежа)",
                              (row["debt_target"] or "") if row else "", 6, [""] + debt_ids)
        self.date = field("Дата (ГГГГ-ММ-ДД ЧЧ:ММ)",
                          (row["created_at"] or "")[:16] if row
                          else datetime.now().strftime("%Y-%m-%d %H:%M"), 7)

        buttons = ttk.Frame(self)
        buttons.grid(row=8, column=0, columnspan=2, pady=12)
        ttk.Button(buttons, text="💾 Сохранить", style="Accent.TButton",
                   command=self._save).pack(side="left")
        ttk.Button(buttons, text="Отмена", command=self.destroy).pack(side="left", padx=8)
        self.bind("<Return>", lambda _event: self._save())
        self.bind("<Escape>", lambda _event: self.destroy())
        self.transient(parent)
        self.grab_set()

    def _save(self) -> None:
        try:
            amount = float(self.amount.get().replace(" ", "").replace(",", "."))
            if amount <= 0:
                raise ValueError
        except ValueError:
            messagebox.showerror("Нужно число", "Сумма вводится числом, например: 3450,50")
            return
        reverse_types = {value: key for key, value in TX_TYPE_RU.items()}
        tx_type = reverse_types.get(self.type_box.get(), "expense")
        self.result = {
            "user_id": self._user_id(),
            "amount": amount,
            "category": self.category_box.get() or "прочее",
            "subcategory": self.subcategory.get().strip() or None,
            "description": self.description.get().strip() or None,
            "tx_type": tx_type,
            "debt_target": (self.debt_box.get() or None) if tx_type == "debt_payment" else None,
            "created_at": self._date(),
        }
        self.destroy()

    def _user_id(self) -> int:
        value = self.user_box.get() if self.user_box else ""
        try:
            return int(value.rsplit("(", 1)[1].rstrip(")"))
        except (ValueError, IndexError):
            return next(iter(USERS), 0)

    def _date(self) -> str:
        """Проверяет дату и возвращает её в формате базы."""
        raw = self.date.get().strip()
        for pattern in ("%Y-%m-%d %H:%M", "%Y-%m-%d", "%d.%m.%Y %H:%M", "%d.%m.%Y"):
            try:
                return datetime.strptime(raw, pattern).isoformat(sep=" ")
            except ValueError:
                continue
        messagebox.showwarning("Дата непонятна", "Оставлю текущее время")
        return datetime.now().isoformat(sep=" ")


class ChartWindow(tk.Toplevel):
    """Окно с картинкой отчёта — той же, что бот отправляет в Telegram."""

    def __init__(self, parent, path: str):
        super().__init__(parent)
        self.title("Диаграмма отчёта")
        self.configure(background=BACKGROUND)
        try:
            from PIL import Image, ImageTk

            image = Image.open(path)
            self._photo = ImageTk.PhotoImage(image)
            tk.Label(self, image=self._photo, background=BACKGROUND).pack(padx=8, pady=8)
        except Exception:  # pragma: no cover — без Pillow просто покажем путь
            ttk.Label(self, text=f"Картинка сохранена: {path}").pack(padx=12, pady=12)
        ttk.Button(self, text="Закрыть", command=self.destroy).pack(pady=(0, 10))
