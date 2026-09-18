"""Вкладка «Товары»: каталог цен, история одного товара и список закупки."""
from tkinter import messagebox, ttk

from database import panel_data as data
from panel_ui.base import Tab
from utils.formatting import format_amount


class ProductsTab(Tab):
    """Обычная цена, история покупок и «пора купить» по ритму чеков."""

    title = "Товары"

    def build(self) -> None:
        ttk.Label(self.frame, text="Обычная цена — медиана прошлых покупок; товар попадает "
                                   "сюда после трёх покупок в чеках",
                  style="Muted.TLabel").pack(anchor="w")

        filters = ttk.Frame(self.frame)
        filters.pack(fill="x", pady=8)
        self.user_box = self.user_filter(filters, on_change=self.refresh)
        ttk.Button(filters, text="📋 Скопировать закупку",
                   command=self.copy_shopping).pack(side="right")

        ttk.Label(self.frame, text="Каталог товаров", style="Muted.TLabel").pack(anchor="w")
        container = ttk.Frame(self.frame)
        container.pack(fill="both", expand=True, pady=(2, 10))
        self.product_tree = self.tree(
            container,
            ("name", "usual", "last", "trend", "cheapest", "store", "count", "spent"),
            ("Товар", "Обычная цена", "Последняя покупка", "К обычной", "Дешевле всего",
             "Где дешевле", "Покупок", "Всего"),
            (280, 110, 140, 90, 110, 130, 80, 110), height=9)
        self.product_tree.bind("<<TreeviewSelect>>", lambda _event: self.show_product_history())

        self.history_note = ttk.Label(self.frame, text="История товара: выбери строку "
                                                       "в каталоге", style="Muted.TLabel")
        self.history_note.pack(anchor="w")
        history_container = ttk.Frame(self.frame)
        history_container.pack(fill="both", expand=True, pady=(2, 8))
        self.history_tree = self.tree(history_container, ("date", "store", "price", "name"),
                                      ("Когда", "Магазин", "Цена", "Как записано в чеке"),
                                      (140, 180, 110, 320), height=4)

        self.shopping_note = ttk.Label(self.frame, text="Пора купить: считается по ритму чеков",
                                       style="Muted.TLabel")
        self.shopping_note.pack(anchor="w")
        shopping_container = ttk.Frame(self.frame)
        shopping_container.pack(fill="both", expand=True, pady=(2, 0))
        self.shopping_tree = self.tree(shopping_container,
                                       ("name", "usual", "interval", "last", "due"),
                                       ("Товар", "Обычная цена", "Берёшь раз в",
                                        "Последний раз", "Ожидаемый срок"),
                                       (280, 110, 100, 140, 160), height=6)

    def refresh(self) -> None:
        """Каталог и закупка — те же функции сервисов, что и в боте, а не свой запрос в базу."""
        user_id = self.selected_user(self.user_box)
        # История товара пересчитывается заново на каждом обновлении: старые строки относились
        # к другому пользователю или к уже изменённому чеку.
        self.fill(self.history_tree, [])
        self.history_note.configure(text="История товара: выбери строку в каталоге")
        catalog = data.product_catalog(user_id)
        self.fill(self.product_tree, [
            (group["name"], format_amount(group["usual"]),
             (group["last_date"] or "")[:16].replace("T", " "),
             f"{round(group['trend'] * 100):+d}%", format_amount(group["cheapest"]),
             group["cheapest_store"] or "—", group["count"], format_amount(group["spent"]))
            for group in catalog])
        shopping = data.shopping_list(user_id)
        self.fill(self.shopping_tree, [
            (item["name"], format_amount(item["usual"]),
             f"{item['interval']} дн.", item["last_date"].strftime("%d.%m.%Y"),
             f"{item['due_date']:%d.%m.%Y}"
             + (f" (прошёл {abs(item['until'])} дн. назад)" if item["until"] < 0 else ""))
            for item in shopping])
        self._shopping_rows = shopping
        total = sum(item["usual"] for item in shopping)
        self.shopping_note.configure(
            text=(f"Пора купить: {len(shopping)} — примерно {format_amount(total)} "
                  "по прошлым ценам" if shopping else
                  "Пора купить: пока нечего — бот ждёт, когда подойдёт обычный срок"))

    def copy_shopping(self) -> None:
        """Кладёт текст списка закупки в буфер: его удобно переслать в мессенджер."""
        rows = getattr(self, "_shopping_rows", [])
        if not rows:
            messagebox.showinfo("Список пуст", "Сейчас ничего не пора покупать")
            return
        lines = [f"🛒 Закупка ({len(rows)}):"]
        lines += [f"• {item['name']} — обычно {format_amount(item['usual'])}" for item in rows]
        lines.append(f"Итого примерно {format_amount(sum(i['usual'] for i in rows))}")
        self.app.clipboard_clear()
        self.app.clipboard_append("\n".join(lines))
        self.status(f"Список закупки скопирован ({len(rows)} товаров)")

    def show_product_history(self) -> None:
        """История выбранного товара: те же покупки, из которых сложилась обычная цена."""
        selection = self.product_tree.selection()
        if not selection:
            return
        name = self.product_tree.item(selection[0], "values")[0]
        rows = data.product_receipts(self.selected_user(self.user_box), name)
        self.fill(self.history_tree, [
            (row["date"].strftime("%d.%m.%Y") if row["date"] else "—", row["store"],
             format_amount(row["price"]), row["name"]) for row in rows])
        stores = {row["store"] for row in rows if row["store"] != "—"}
        self.history_note.configure(
            text=(f"История «{name}»: покупок {len(rows)} в "
                  f"{len(stores)} магазин(ах)" if rows else
                  f"Покупок «{name}» в чеках не нашлось"))
