# FinPulse local AI worker: Bonsai 2 + llama.cpp

## Цель

Сделать локальную модель дешёвым worker-слоем для разработки FinPulse.
Сильная модель остаётся менеджером: ставит задачу, режет её на шаги, проверяет
результат и принимает решение. Локальная Bonsai 2 через llama.cpp помогает с
черновой работой: сводки файлов, первичное ревью, поиск рисков, план патча,
компактные подсказки по местам изменений.

## Текущая архитектура

```text
Codex / GPT manager
  ├─ читает задачу и репозиторий
  ├─ вызывает локальный worker CLI
  ├─ проверяет ответ worker
  ├─ сам вносит финальные изменения
  └─ запускает тесты и сборки

agentic/local_worker.py
  └─ agentic/local_llm.py
       └─ llama.cpp OpenAI-compatible API
            └─ Bonsai 2 model
```

Локальный worker не меняет файлы. Это сделано специально: правки остаются под
контролем основного агента и обычных тестов.

## Настройки

По умолчанию ожидается llama.cpp server с OpenAI-compatible API:

```text
http://127.0.0.1:8080/v1
```

Переменные:

```text
FINPULSE_LOCAL_LLM_BASE_URL=http://127.0.0.1:8080/v1
FINPULSE_LOCAL_LLM_MODEL=bonsai-2
FINPULSE_LOCAL_LLM_API_KEY=
FINPULSE_LOCAL_LLM_TIMEOUT=120
```

Если llama.cpp запущен на другом порту, меняется только
`FINPULSE_LOCAL_LLM_BASE_URL`.
Если llama.cpp запущен с API key, укажи тот же ключ в
`FINPULSE_LOCAL_LLM_API_KEY`.

## Команды

Проверка связи:

```powershell
.\.venv\Scripts\python.exe -m agentic.local_worker --check
```

Сводка файлов:

```powershell
.\.venv\Scripts\python.exe -m agentic.local_worker `
  --mode summarize `
  --task "Сжать контекст backend для следующего шага" `
  --files backend/app.py backend/finance.py
```

Ревью:

```powershell
.\.venv\Scripts\python.exe -m agentic.local_worker `
  --mode review `
  --task "Проверить API-слой на ошибки интеграции" `
  --files backend/app.py backend/auth.py backend/test_api.py
```

План патча:

```powershell
.\.venv\Scripts\python.exe -m agentic.local_worker `
  --mode patch-hints `
  --task "Где лучше добавить импорт SMS/push для T-Bank" `
  --files backend/app.py backend/tbank.py mobile_flutter/lib/main.dart
```

## Режимы

- `summarize` — короткая сводка контекста.
- `review` — поиск ошибок, рисков, дыр в тестах.
- `plan` — план реализации без раздувания scope.
- `patch-hints` — подсказки по точкам изменения.

## Формат ответа

Worker должен вернуть JSON:

```json
{
  "summary": "short result",
  "relevant_files": [{"path": "...", "reason": "..."}],
  "findings": [{"severity": "low|medium|high", "path": "...", "problem": "...", "fix": "..."}],
  "recommended_actions": ["..."]
}
```

Если модель вернула обычный текст, CLI заворачивает его в безопасный JSON.

## Дальше

1. Добавить режим `code-map`: индекс репозитория и быстрый поиск нужных файлов.
2. Добавить режим `test-failure`: сжатие логов тестов и гипотезы причины.
3. Добавить очередь локальных задач с сохранением ответов в `artifacts/agentic/`.
4. Добавить backend-интерфейс для runtime AI в самом FinPulse отдельно от
   dev-worker.
