# Finance Bot

> **Интересный личный проект, над которым я работал длительное время.** Это local-first Telegram-ассистент для учёта личных финансов, в котором я отдельно прорабатывал чтение чеков, валидацию данных, бюджеты, долги, регулярные платежи и общий сервисный слой для Telegram и desktop UI.
>
> **Status:** active portfolio project.

## Идея

Пользователь отправляет фотографию чека.

Вместо одного вызова модели проект использует независимую цепочку:

```
Telegram photo
     ↓
local Vision model
     ↓
Tesseract OCR
     ↓
arithmetic validation
     ↓
agreed receipt
     ↓
expense / category / reports
```

Если два независимых чтения расходятся по суммам, результат помечается как сомнительный, а не молча исправляется.

## Возможности

- добавление трат;
- чтение чеков;
- категории;
- бюджеты;
- долги;
- подписки / регулярные платежи;
- отчёты;
- графики;
- импорт банковских выписок;
- плановые уведомления;
- desktop panel на Tkinter;
- локальная AI-обработка.

## Local-first

После того как Telegram передал фотографию боту, core receipt pipeline не требует стороннего AI API.

Используются:

- Ollama Vision;
- Tesseract;
- OpenCV/Pillow;
- детерминированная арифметическая проверка.

Облачная Ollama также возможна, но это отдельный режим.

## Структура

```
bot.py                  # Telegram entrypoint
panel.py                # Tkinter control panel
config.py               # configuration

ai/
  llm.py                # parsing / advice
  vision.py             # Vision model
  ocr.py                # Tesseract
  receipts.py           # reconciliation of two readings

database/
  models.py             # SQLite schema
  db.py                 # bot CRUD
  panel_data.py         # panel queries

handlers/
  ...                   # Telegram scenarios

services/
  ...                   # budgets, recurring payments,
                         # price history, shopping list, etc.

samples.py              # local sample receipt data

docs/
  DEVELOPMENT.md        # full installation / secrets / autostart

tests/
  ...                   # receipt and business-logic tests
```

## Быстрый запуск

### Требования

- Python 3.11/3.12;
- Telegram Bot Token;
- Ollama — для local Vision/LLM;
- Tesseract OCR — для независимой проверки.

### Установка

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

Windows:

```bat
python -m venv venv
venv\Scripts\activate
pip install -r requirements.txt
```

### Ollama

Основная модель:

```bash
ollama pull qwen2.5:7b-instruct
```

Vision:

```bash
ollama pull qwen3-vl:8b-instruct
```

Для слабой машины можно использовать меньшие варианты, например `qwen2.5:3b-instruct` и `qwen3-vl:4b-instruct`.

### Tesseract

На Windows нужен Tesseract OCR и русский language data.

Проверка:

```bash
tesseract --list-langs
```

В выводе должен присутствовать:

```
rus
```

## Секреты

Рекомендуемый способ из документации проекта — Infisical.

Основной Telegram token и другие чувствительные переменные не должны коммититься в Git.

Базовые переменные:

```
BOT_TOKEN=...
USER_ID_1=...
OLLAMA_HOST=http://127.0.0.1:11434
OLLAMA_MODEL=...
VISION_ENABLED=1
VISION_MODEL=...
```

Полная инструкция по Infisical, Windows, CI/CD и автозапуску находится в [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Запуск бота

Прямой запуск:

```bash
python bot.py
```

Если используется Infisical:

```bash
infisical run --env=dev -- python bot.py
```

Desktop panel:

```bash
infisical run --env=dev -- python panel.py
```

В репозитории также есть Windows start scripts.

## Примеры использования

Типичный сценарий:

1. отправить боту фото чека;
2. дождаться распознавания;
3. проверить итоговую сумму / позиции;
4. сохранить расход;
5. открыть отчёт.

Текстовые сценарии зависят от текущего меню бота; основная обработка находится в `handlers/`.

## Бизнес-логика

Проект не ограничивается OCR.

Например:

- бюджет хранится отдельно от списка расходов;
- регулярные платежи имеют собственную service logic;
- суммы и агрегаты рассчитываются детерминированно;
- рекомендации строятся на уже записанных данных.

Это делает проект ближе к прикладному сервису, чем к простой демонстрации LLM.

## Тесты

```bash
pytest -q
```

CI специально отделяет детерминированную часть от зависимости на Ollama и реальные пользовательские фото.

## Ограничения

- плохое фото чека может привести к неопределённому OCR;
- локальная Vision inference может быть медленной;
- проект рассчитан на личное / семейное использование, а не на multi-tenant SaaS;
- рекомендации не гарантируют фактической экономии.

## AI-assisted development

AI использовался для черновой реализации, рутинных handlers, тестовых сценариев и работы с незнакомыми форматами входных данных.

Архитектура, decomposition, интеграции, debugging, validation и итоговое поведение оставались моей задачей.

## Лицензия

MIT.
