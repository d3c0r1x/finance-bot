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
- Android-приложение на Kotlin + Jetpack Compose;
- FastAPI backend с username/password и JWT;
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

backend/
  app.py                # FastAPI API для Android
  auth.py               # username/password, JWT access/refresh
  finance.py            # bridge к существующему Python core
  schemas.py            # API validation

android/
  app/                  # Kotlin + Jetpack Compose client
  gradlew               # Android build wrapper

samples.py              # local sample receipt data

docs/
  DEVELOPMENT.md        # full installation / secrets / autostart
  MOBILE_SPEC.md        # mobile/API specification

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

## FinPulse Android + FastAPI

Mobile-слой называется **FinPulse** (`ФинПульс`) и живет рядом с существующим Telegram-ботом, не ломая его entrypoint.
Android-клиент работает через FastAPI, а backend переиспользует текущий Python core:
транзакции, чеки/OCR/AI, бюджеты, долги, аналитику, recurring, историю цен,
список покупок и импорт банковских PDF.

Новая целевая ветка продукта описана в [docs/HOME_SERVER_FLUTTER_SPEC.md](docs/HOME_SERVER_FLUTTER_SPEC.md):
текущий ПК работает как 24/7 home server, Android получает Flutter APK, iPhone
получает Flutter Web/PWA до появления Mac/Xcode или cloud iOS builder.

Для mobile-аккаунтов используется username/password. Backend выдает short-lived
JWT access token и refresh token с ротацией. Данные каждого mobile-пользователя
пишутся в отдельный SQLite-файл под `FINANCE_API_DATA`; legacy Telegram-база
остается отдельной.

Запуск backend для телефона в той же Wi-Fi/LAN сети:

```bat
start_mobile_server.bat
```

По умолчанию сервер слушает `0.0.0.0:8000` и печатает URL для телефона.
Для текущей машины это:

```text
http://192.168.3.48:8000
```

Если Windows Firewall блокирует входящие подключения, открой PowerShell от администратора:

```powershell
.\open_mobile_port_8000_admin.ps1
```

Ручной запуск backend:

```bash
pip install -r backend/requirements.txt
uvicorn backend.app:app --host 0.0.0.0 --port 8000
```

Переменные:

```bash
FINANCE_API_DATA=data/mobile
FINANCE_JWT_SECRET=change-me-to-a-long-random-secret
OLLAMA_HOST=http://127.0.0.1:11434
OLLAMA_MODEL=qwen2.5:7b-instruct
VISION_ENABLED=1
VISION_MODEL=qwen3-vl:8b-instruct
```

Android debug build:

```bash
cd android
gradlew.bat :app:assembleDebug
```

APK для эмулятора появляется здесь:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

В этой debug-сборке приложение по умолчанию смотрит на `http://192.168.3.48:8000`.
Для Android emulator поменяй адрес на экране входа или в профиле на `http://10.0.2.2:8000`.
В профиле приложения URL API можно поменять в любой момент.

Проверки mobile-слоя:

```bash
python -m pytest backend/test_api.py -q
cd android
gradlew.bat :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
gradlew.bat :app:connectedDebugAndroidTest
```

Instrumentation-тест регистрирует пользователя в эмуляторе, проходит onboarding,
добавляет расход, повторяет его, переключает RU/EN и dark/light, пересоздает
Activity и проверяет сохранение сессии.

## Flutter Android/Web client

Flutter-клиент лежит в `mobile_flutter/`. Он станет общей базой для Android APK
и iPhone Web/PWA. Текущий Kotlin-клиент пока оставлен рабочим, чтобы APK не
сломался во время миграции.

После установки Flutter SDK:

```bash
cd mobile_flutter
flutter pub get
flutter run -d chrome
flutter build apk --debug
flutter build web
```

Первый Flutter slice уже использует backend:

- login/register;
- `GET /server/config`;
- `GET /pulse/today`;
- `GET/PUT /workspace`;
- `POST /import/tbank-notification`.

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
