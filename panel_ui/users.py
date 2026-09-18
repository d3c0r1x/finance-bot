"""Вкладка «Пользователи»: имя, план дохода, настройка бота и переход к тратам."""
from tkinter import messagebox, simpledialog, ttk

from database import panel_data as data
from panel_ui.base import Tab
from panel_ui.theme import run
from services import profile
from utils.formatting import format_amount

COLUMNS = ("id", "name", "role", "income", "onboarded", "transactions", "spent", "income",
           "last")
TITLES = ("Telegram ID", "Имя", "Роль", "План дохода", "Настройка", "Записей",
          "Потрачено", "Доход", "Последняя запись")
WIDTHS = (100, 150, 90, 110, 90, 80, 120, 120, 140)


class UsersTab(Tab):
    """Настройки каждого пользователя в одном месте."""

    title = "Пользователи"

    def build(self) -> None:
        ttk.Label(self.frame, text="Настройки каждого пользователя: имя, план дохода, "
                                   "настройка бота", style="Muted.TLabel").pack(anchor="w")
        container = ttk.Frame(self.frame)
        container.pack(fill="both", expand=True, pady=8)
        self.tree_view = self.tree(container, COLUMNS, TITLES, WIDTHS, height=10)
        self.tree_view.bind("<Double-1>", lambda _event: self.rename_user())

        buttons = ttk.Frame(self.frame)
        buttons.pack(fill="x")
        ttk.Button(buttons, text="✏️ Имя", command=self.rename_user).pack(side="left")
        ttk.Button(buttons, text="💰 План дохода",
                   command=self.set_income_plan).pack(side="left", padx=6)
        ttk.Button(buttons, text="🚀 Пройти настройку заново",
                   command=self.reset_onboarding).pack(side="left")
        ttk.Button(buttons, text="📄 Показать транзакции",
                   command=self.show_transactions).pack(side="left", padx=6)
        self.user_note = ttk.Label(self.frame, text="", style="Muted.TLabel")
        self.user_note.pack(anchor="w", pady=8)

    def refresh(self) -> None:
        self.fill(self.tree_view, [
            (row["user_id"], row["name"], row["role"], row["income_plan"], row["onboarded"],
             row["transactions"], format_amount(row["spent"]), format_amount(row["income"]),
             row["last"])
            for row in data.users_overview()])

    def _selected_user_id(self) -> int | None:
        selection = self.tree_view.selection()
        if not selection:
            messagebox.showinfo("Нужен пользователь", "Выбери пользователя в таблице")
            return None
        return int(self.tree_view.item(selection[0], "values")[0])

    def rename_user(self) -> None:
        user_id = self._selected_user_id()
        if not user_id:
            return
        current = self.tree_view.item(self.tree_view.selection()[0], "values")[1]
        name = simpledialog.askstring("Имя пользователя", "Как обращаться в боте?",
                                      initialvalue=str(current), parent=self.app)
        if not name:
            return
        run(profile.set_name(user_id, name))
        self.status(f"Имя пользователя {user_id} обновлено")
        self.app.refresh_all()

    def set_income_plan(self) -> None:
        user_id = self._selected_user_id()
        if not user_id:
            return
        raw = simpledialog.askstring("План дохода", "Ожидаемый доход в месяц, ₽:",
                                     initialvalue="", parent=self.app)
        if not raw:
            return
        try:
            value = float(raw.replace(" ", "").replace(",", "."))
        except ValueError:
            messagebox.showerror("Нужно число", "Введи сумму числом, например: 150000")
            return
        run(profile.set_planned_income(user_id, value))
        self.status(f"План дохода {format_amount(value)} сохранён — "
                    "бот покажет «безопасно тратить»")
        self.app.refresh_all()

    def reset_onboarding(self) -> None:
        user_id = self._selected_user_id()
        if not user_id:
            return
        if not messagebox.askyesno("Настройка заново",
                                   "Пользователь пройдёт приветственную настройку при "
                                   "следующем /start?"):
            return
        run(profile.mark_onboarded(user_id, False))
        self.status("Настройка будет показана заново при следующем /start")
        self.refresh()

    def show_transactions(self) -> None:
        """Открывает вкладку «Транзакции» на этом пользователе — вкладки не знают индексов."""
        user_id = self._selected_user_id()
        if user_id:
            self.app.transactions.show_for(user_id)
