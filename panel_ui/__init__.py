"""Панель управления ботом на Tkinter: файл на вкладку.

`theme.py` — цвета и шрифты, `base.py` — общий контракт вкладки, `window.py` — само окно,
остальные модули — по вкладке (`overview`, `transactions`, `receipts`, `products`,
`analytics`, `budget`, `users`, `export`). Запуск — из `panel.py`.
"""
from panel_ui.window import Panel, main

__all__ = ["Panel", "main"]
