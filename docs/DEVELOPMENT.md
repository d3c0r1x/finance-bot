# Установка, секреты и структура — Finance Bot

Как поднять бота с нуля: зависимости (Python, Ollama, модель зрения, Tesseract),
доставка секретов через Infisical, автозапуск 24/7 и карта репозитория.

## Установка (Windows 11)

### 1. Python 3.11/3.12
Скачай с [python.org](https://www.python.org/downloads/), при установке отметь **Add Python to PATH**.

### 2. Ollama и модель
Скачай с [ollama.com](https://ollama.com/download), установи, затем в консоли:
```bash
ollama pull qwen2.5:7b-instruct     # ~4.7 ГБ — основная модель, точный разбор трат
```
Если 7B тяжело для ноутбука — хватит и меньшей (`qwen2.5:3b-instruct`). Бот сам подхватит лучшую из установленных моделей: сначала `OLLAMA_MODEL` из `.env`, затем любую из внутреннего списка предпочтений. Активную модель видно в **⚙️ Настройки → 🩺 Статус сервисов** и в консоли при запуске.

Без установки Ollama можно работать через облако: `OLLAMA_HOST=https://ollama.com` + `OLLAMA_API_KEY` (ключ с [ollama.com/settings/keys](https://ollama.com/settings/keys)).

### 3. Модель зрения для чеков
```bash
ollama pull qwen3-vl:8b-instruct    # ~6 ГБ — лучшее качество чтения чеков
# или, если карта слабая / мало VRAM:
ollama pull qwen3-vl:4b-instruct    # ~3.3 ГБ, быстрее
```
Бот сам найдёт скачанную модель зрения из списка предпочтений и покажет её в **⚙️ Настройки → 🩺 Статус сервисов**. Чтение можно выключить (`VISION_ENABLED=0` в `.env`) — тогда чеки читает только Tesseract.

### 4. Tesseract OCR (проверка цифр)
Установщик: [UB-Mannheim/tesseract](https://github.com/UB-Mannheim/tesseract/wiki) (`tesseract-ocr-w64-setup-*.exe`, ~50 МБ). Путь `C:\Program Files\Tesseract-OCR` бот находит сам, иначе укажи `TESSERACT_CMD` в `.env`.

Нужен русский язык: положи `rus.traineddata` (и по желанию улучшенный `eng.traineddata`) из [tessdata_best](https://github.com/tesseract-ocr/tessdata_best) в `C:\Program Files\Tesseract-OCR\tessdata\`. Проверка: `tesseract --list-langs` должен показать `rus`.

### 5. Проект
```bash
cd finance_bot
python -m venv venv
venv\Scripts\activate
pip install -r requirements.txt
```

### 6. Секреты (Infisical)
Секреты больше не лежат в `.env` на диске: они хранятся в [Infisical](https://infisical.com) и подставляются в процесс при запуске. Приложение читает те же переменные окружения (`config.py` → `os.getenv`), поэтому код не менялся — меняется только способ доставки.

Настройка — один раз на машину и на команду:

1. Заведи аккаунт на [app.infisical.com](https://app.infisical.com) и создай проект (например, `finance-bot`). В каждом проекте сразу есть окружения Development, Staging и Production.
2. Перенеси секреты: на странице **Secrets Overview** можно просто перетащить файл `.env` — Infisical разберёт его и покажет найденные пары. Проверь их и загрузи в Development. Больше файл на диске не нужен.
3. Поставь CLI: `winget install infisical` (macOS: `brew install infisical/get-cli/infisical`). Другие системы — [docs/cli/overview](https://infisical.com/docs/cli/overview).
4. Войди: `infisical login`. Если браузера нет (WSL 2, Codespaces, удалённый SSH) — `infisical login -i`.
5. Свяжи проект с кодом — из папки `finance_bot`: `infisical init`. Команда создаст `.infisical.json` — это локальные настройки проекта, секретов там нет, и коммитить его можно.

Переменные, которые читает приложение (значения живут только в Infisical):

| Переменная | Назначение |
| --- | --- |
| `BOT_TOKEN` | токен бота от @BotFather |
| `USER_ID_1`, `USER_ID_2` | Telegram ID владельца и второго пользователя |
| `OLLAMA_HOST`, `OLLAMA_API_KEY`, `OLLAMA_MODEL` | адрес, ключ и модель Ollama (ключ нужен только для облака) |
| `TESSERACT_CMD` | путь к tesseract (обычно находится сам) |
| `VISION_ENABLED`, `VISION_MODEL`, `VISION_SCALE`, `VISION_TIMEOUT`, `VISION_UNLOAD_CHAT`, `VISION_KEEP_ALIVE`, `VISION_SECOND_PASS` | чтение чеков моделью зрения |
| `AI_PARSE_TIMEOUT`, `AI_ADVICE_TIMEOUT` | таймауты обращений к модели |
| `DAILY_REPORT_HOUR`, `WEEKLY_REPORT_WEEKDAY`, `WEEKLY_REPORT_HOUR` | время сводки и дайджеста |
| `HIDE_MENU_BUTTON` | убрать «плашку» старого веб-приложения |
| `FINANCE_DB` | имя файла базы |

Настоящие секреты из списка — `BOT_TOKEN`, `OLLAMA_API_KEY` и `USER_ID_1`/`USER_ID_2`; остальное — настройки, и их переносить необязательно. Несекретные значения (`MONTHLY_LIMITS`, `TOTAL_MONTHLY_LIMIT`, `BUDGET_MIN_DAYS`, `OCR_WORKERS`) вообще заданы кодом и в окружении не нужны.

### 7. Запуск
```bash
infisical run --env=dev -- python bot.py
```
`start_bot.bat` и `start_panel.bat` уже обёрнуты в `infisical run` — запускай их, как раньше. Панель вручную:
```bash
infisical run --env=dev -- python panel.py
```

Локальный `.env` для запуска больше не нужен. `config.py` всё ещё вызывает `load_dotenv()`, так что оставшийся файл бот подхватит, но при запуске через `infisical run` значения из Infisical перекрывают его.

Проверка, что секреты идут из Infisical, а не с диска: переименуй `.env` в `.env.backup`, запусти бота через `infisical run` — он должен работать как обычно. `.env.backup` уже в `.gitignore`, но это временный файл: убедившись, что всё работает, удали его.

#### Если в файле секрета нет, а проверить нужно
Никогда не печатай значения на экран — сравнивай, например, длину: `infisical secrets get BOT_TOKEN --plain | wc -c`.

#### CI/CD, Kubernetes и прод
Там интерактивный `infisical login` не подходит: браузера и человека в контейнере нет. Вместо него заводят **machine identity** с Universal Auth ([machine-identities](https://infisical.com/docs/documentation/platform/identities/machine-identities), [universal-auth](https://infisical.com/docs/documentation/platform/identities/universal-auth)) — у неё есть client ID и client secret, из которых CLI берёт короткоживущий токен:
```bash
export INFISICAL_TOKEN=$(infisical login --method=universal-auth \
  --client-id=<client-id> --client-secret=<client-secret> --silent --plain)
infisical run --projectId=<project-id> --env=prod -- python bot.py
```
client ID и client secret кладут в секреты самой платформы (Secrets в GitHub Actions, Kubernetes Secret и т. п.), а identity ограничивают одним проектом и одним окружением — минимумом, который ей нужен. Для прод-окружений стоит также выставить `INFISICAL_DISABLE_UPDATE_CHECK=true`.

#### Если секреты уже попадали в git
История git не забывает ничего: значения из неё нужно считать скомпрометированными и **перевыпустить**, а не просто удалить строки. Перевыпусти `BOT_TOKEN` у @BotFather, отзови ключ Ollama, а `USER_ID_*` замени, если публичность ID нежелательна. Проверить репозиторий на утечки: `infisical scan` ([docs/cli/scanning-overview](https://infisical.com/docs/cli/scanning-overview)).

## Автозапуск 24/7
1. `start_bot.bat` уже в проекте — запускает бота, при первом запуске сам создаёт venv и подтягивает секреты через `infisical run` (нужны установленный CLI и пройденные один раз `infisical login` и `infisical init`).
2. Панель управления → Электропитание → сон — **Никогда** (отключение дисплея можно через 10 минут).
3. `Win + R` → `shell:startup` → положи ярлык на `start_bot.bat`. Ollama добавляет себя в автозагрузку сама.

## Структура
```
finance_bot/
├── bot.py            # точка входа, меню Telegram, повторные отправки при сбоях сети
├── panel.py          # панель управления на Tkinter: база, чеки, бюджет, пользователи, CSV
├── config.py         # токен, ID, демо-лимиты, пути, настройки моделей (текст + зрение)
├── samples.py        # локальные образцы чеков для тестов (receipt_samples.json)
├── database/         # models.py — схема SQLite; db.py — CRUD бота (aiosqlite)
│                     # panel_data.py — запросы и правки для панели (sqlite3), в т.ч. товары
├── ai/               # llm.py — разбор трат, названия позиций, разбор корзины, бюджет, советы
│                     # vision.py — модель зрения: подготовка фото, чтение, второй проход, добор
│                     # ocr.py — Tesseract: предобработка фото и колоночный разбор таблицы
│                     # receipts.py — сведение двух чтений, добор позиций, карточка, разбор корзины
├── handlers/         # start/меню, onboarding.py — приветственная настройка, траты и чеки,
│                     # долги, отчёты (с диаграммами), настройки, бюджет, bank_import.py — выписки
├── keyboards/        # reply- и inline-клавиатуры (включая бюджет и шаги настройки)
├── services/         # бюджет (личные и семейные лимиты), регулярные платежи, история цен
│                     # (обычная цена и каталог товаров), список покупок по ритму чеков,
│                     # mutelist.py — отключённые напоминания для всех подсказок,
│                     # digest.py — недельный дайджест (цены + закупка + подписки вместе),
│                     # forecast.py — обычный недельный темп на продукты и его отклонения,
│                     # inflation.py — личная инфляция по своей корзине товаров,
│                     # goals.py — цели на месяц (товарные и категорийные),
│                     # bank_statement.py — разбор PDF-выписки банка со сверкой итогов,
│                     # профиль, аналитика, диаграммы, алерты, CSV, планировщик, health
├── utils/            # форматирование (в т.ч. безопасный Markdown), фильтр доступа, шрифты (fonts.py)
├── .github/          # workflows/tests.yml — прогон трёх наборов тестов на каждый пуш
└── data/             # finance.db + чеки (создаётся автоматически, не коммитится)
```

