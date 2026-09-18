"""Вкладка «Транзакции»: таблица записей, позиции чека и правка отдельных строк."""
from tkinter import messagebox, ttk

from database import panel_data as data
from panel_ui.base import Tab
from panel_ui.dialogs import TransactionDialog
from panel_ui.theme import TX_TYPE_RU, TX_TYPES
from utils.formatting import format_amount

COLUMNS = ("id", "date", "user", "amount", "category", "subcategory", "description", "type")
TITLES = ("ID", "Дата", "Пользователь", "Сумма", "Категория", "Подкатегория", "Описание", "Тип")
WIDTHS = (60, 130, 130, 100, 110, 120, 260, 80)


class TransactionsTab(Tab):
    """Фильтры, список записей и позиции выбранного чека под ним."""

    title = "Транзакции"

    def build(self) -> None:
        filters = ttk.Frame(self.frame)
        filters.pack(fill="x")
        self.user_box = self.user_filter(filters, width=20, on_change=self.refresh)
        self.period_box = self.period_filter(filters, width=12, on_change=self.refresh)
        ttk.Label(filters, text="Тип:").pack(side="left")
        self.type_box = ttk.Combobox(filters, state="readonly", width=18, values=list(TX_TYPES))
        self.type_box.set("расходы")
        self.type_box.pack(side="left", padx=(6, 12))
        self.type_box.bind("<<ComboboxSelected>>", lambda _event: self.refresh())
        ttk.Label(filters, text="Поиск:").pack(side="left")
        self.search_box = ttk.Entry(filters, width=22)
        self.search_box.pack(side="left", padx=6)
        self.search_box.bind("<Return>", lambda _event: self.refresh())
        ttk.Button(filters, text="Найти", command=self.refresh).pack(side="left")

        buttons = ttk.Frame(self.frame)
        buttons.pack(fill="x", pady=8)
        ttk.Button(buttons, text="➕ Добавить", command=self.add_transaction).pack(side="left")
        ttk.Button(buttons, text="✏️ Изменить",
                   command=self.edit_selected).pack(side="left", padx=6)
        ttk.Button(buttons, text="🗑 Удалить", command=self.delete_selected).pack(side="left")
        self.count_label = ttk.Label(buttons, text="", style="Muted.TLabel")
        self.count_label.pack(side="left", padx=12)

        container = ttk.Frame(self.frame)
        container.pack(fill="both", expand=True)
        self.tx_tree = self.tree(container, COLUMNS, TITLES, WIDTHS, height=16,
                                 on_double=self.edit_selected)
        self.tx_tree.bind("<<TreeviewSelect>>", lambda _event: self.show_selected_items())

        self.items_label = ttk.Label(self.frame, text="Позиции чека: выбери запись с чеком",
                                     style="Muted.TLabel")
        self.items_label.pack(anchor="w", pady=(8, 2))
        self.items_tree = self.tree(self.frame, ("name", "qty", "price", "sum"),
                                    ("Товар", "Кол-во", "Цена", "Сумма"),
                                    (420, 80, 100, 110), height=6)

    def refresh(self) -> None:
        rows = data.transactions(user_id=self.selected_user(self.user_box),
                                 days=self.selected_period(self.period_box),
                                 tx_type=TX_TYPES.get(self.type_box.get()),
                                 search=self.search_box.get().strip())
        self.fill(self.tx_tree, [
            (row["id"], (row["created_at"] or "")[:16], self.user_name(row["user_id"]),
             format_amount(row["amount"]), row["category"], row["subcategory"] or "",
             row["description"] or "", TX_TYPE_RU.get(row["tx_type"], row["tx_type"]))
            for row in rows])
        self.count_label.configure(text=f"записей: {len(rows)}")
        total = sum(row["amount"] for row in rows if row["tx_type"] == "expense")
        self.items_label.configure(text=f"Позиции чека · расход за выборку: {format_amount(total)}")
        self.fill(self.items_tree, [])

    def show_for(self, user_id: int) -> None:
        """Открывает вкладку на тратах одного пользователя — так просит вкладка «Пользователи»."""
        choice = next((value for value in self.user_box["values"]
                       if value.endswith(f"({user_id})")), None)
        if choice:
            self.user_box.set(choice)
        self.period_box.set("30 дней")
        self.type_box.set("все")
        self.app.notebook.select(self.frame)
        self.refresh()

    # ─── Действия ────────────────────────────────────────────────────────

    def _selected_id(self) -> int | None:
        selection = self.tx_tree.selection()
        if not selection:
            messagebox.showinfo("Нужна запись", "Выбери запись в таблице")
            return None
        return int(self.tx_tree.item(selection[0], "values")[0])

    def show_selected_items(self) -> None:
        selection = self.tx_tree.selection()
        if not selection:
            return
        row_id = int(self.tx_tree.item(selection[0], "values")[0])
        self.fill(self.items_tree, [
            (item["name"], f"{item['qty']:.0f}" if item["qty"] else "1",
             format_amount(item["price"]), format_amount(item["sum"]))
            for item in data.receipt_items(row_id)])

    def add_transaction(self) -> None:
        dialog = TransactionDialog(self.app, title="Новая запись")
        self.app.wait_window(dialog)
        if dialog.result:
            data.add_transaction(**dialog.result)
            self.status("Запись добавлена")
            self.app.refresh_all()

    def edit_selected(self) -> None:
        tx_id = self._selected_id()
        if not tx_id:
            return
        row = data.transaction(tx_id)
        dialog = TransactionDialog(self.app, title=f"Запись #{tx_id}", transaction=row)
        self.app.wait_window(dialog)
        if dialog.result:
            data.update_transaction(tx_id, **dialog.result)
            self.status(f"Запись #{tx_id} обновлена")
            self.app.refresh_all()

    def delete_selected(self) -> None:
        tx_id = self._selected_id()
        if not tx_id:
            return
        row = data.transaction(tx_id)
        question = (f"Удалить запись #{tx_id}: {format_amount(row['amount'])} "
                    f"({row['description'] or row['category']})?")
        note = "\nЕсли это платёж по долгу, остаток кредита вернётся." \
            if row["tx_type"] == "debt_payment" else ""
        if messagebox.askyesno("Удаление", question + note):
            data.delete_transactions([tx_id])
            self.status(f"Запись #{tx_id} удалена")
            self.app.refresh_all()
