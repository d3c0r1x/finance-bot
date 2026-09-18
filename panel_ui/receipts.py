"""Вкладка «Чеки»: чеки с фото и позиции выбранного чека."""
from tkinter import messagebox, ttk

from database import panel_data as data
from panel_ui.base import Tab
from utils.formatting import format_amount

COLUMNS = ("id", "date", "user", "store", "amount", "category", "items")
TITLES = ("ID", "Дата", "Пользователь", "Магазин", "Итог чека", "Категория", "Позиций")


def _verdict(item) -> str:
    """Вердикт позиции: пусто, если база ещё создана до появления колонки или чек не разбирали."""
    return (item["verdict"] or "") if "verdict" in item.keys() else ""


class ReceiptsTab(Tab):
    """Список чеков, прочитанных с фото, и позиции выбранного."""

    title = "Чеки"

    def build(self) -> None:
        ttk.Label(self.frame, text="Чеки, прочитанные с фото: магазин, итог, позиции",
                  style="Muted.TLabel").pack(anchor="w")

        container = ttk.Frame(self.frame)
        container.pack(fill="both", expand=True, pady=8)
        self.receipt_tree = self.tree(container, COLUMNS, TITLES,
                                      (60, 130, 130, 240, 110, 110, 80), height=12)
        self.receipt_tree.bind("<<TreeviewSelect>>", lambda _event: self.show_receipt_items())

        buttons = ttk.Frame(self.frame)
        buttons.pack(fill="x")
        ttk.Button(buttons, text="🗑 Удалить чек", command=self.delete_receipt).pack(side="left")
        self.receipt_sum = ttk.Label(buttons, text="", style="Muted.TLabel")
        self.receipt_sum.pack(side="left", padx=12)

        # Вердикт — то, что разбор корзины сохранил вместе с позицией: в панели видно, что
        # бот советовал по этому товару, не открывая переписку в Telegram.
        self.items_tree = self.tree(self.frame, ("name", "qty", "price", "sum", "verdict"),
                                    ("Товар", "Кол-во", "Цена", "Сумма", "Вердикт"),
                                    (420, 70, 100, 110, 140), height=8)

    def refresh(self) -> None:
        rows = data.receipt_transactions()
        self.fill(self.receipt_tree, [
            (row["id"], (row["created_at"] or "")[:16], self.user_name(row["user_id"]),
             row["description"] or "—", format_amount(row["amount"]), row["category"],
             row["items_count"])
            for row in rows])
        self.fill(self.items_tree, [])
        self.receipt_sum.configure(text=f"чеков с позициями: {len(rows)}")

    def show_receipt_items(self) -> None:
        selection = self.receipt_tree.selection()
        if not selection:
            return
        row_id = int(self.receipt_tree.item(selection[0], "values")[0])
        items = data.receipt_items(row_id)
        self.fill(self.items_tree, [
            (item["name"], f"{item['qty']:.0f}" if item["qty"] else "1",
             format_amount(item["price"]), format_amount(item["sum"]),
             _verdict(item)) for item in items])
        self.receipt_sum.configure(text=f"позиций: {len(items)} · "
                                        f"сумма позиций {format_amount(sum(i['sum'] for i in items))}")

    def delete_receipt(self) -> None:
        selection = self.receipt_tree.selection()
        if not selection:
            messagebox.showinfo("Нужен чек", "Выбери чек в таблице")
            return
        row_id = int(self.receipt_tree.item(selection[0], "values")[0])
        values = self.receipt_tree.item(selection[0], "values")
        if messagebox.askyesno("Удаление чека",
                               f"Удалить чек №{row_id} ({values[3]}, {values[4]}) "
                               "вместе с позициями?"):
            data.delete_transactions([row_id])
            self.status(f"Чек №{row_id} удалён")
            self.app.refresh_all()
