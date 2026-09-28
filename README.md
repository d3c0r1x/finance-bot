# Finance Bot

Local-first Telegram finance assistant for personal budgeting.

> Photos sent to Telegram are downloaded by the bot; after that, AI processing is local. The core receipt pipeline does not require a third-party AI API.

## What it does

- reads receipts with a local Vision model (Ollama);
- independently checks receipts with Tesseract OCR and arithmetic rules;
- tracks expenses, categories, budgets, debts and subscriptions;
- generates reports and scheduled digests;
- includes a desktop control panel using the same service layer.

## Why it is interesting

The receipt pipeline deliberately uses two independent readers. If the item total does not agree with the receipt total, the bot flags the discrepancy instead of inventing a value.

The same principle is used elsewhere: deterministic calculations for numbers, explicit uncertainty in reports and one source of truth for shared metrics.

## Architecture

```
Telegram
   ↓
handlers
   ↓
services ───────────────→ SQLite
   ↓
receipt pipeline
   ├─ local Vision model (Ollama)
   └─ Tesseract OCR
          ↓
     arithmetic check
          ↓
     agreed receipt
```

The desktop panel uses the same service layer as the Telegram bot, so reports and UI do not implement separate financial rules.

## Stack

Python · aiogram 3 · SQLite/aiosqlite · Ollama · Vision LLM · Tesseract · OpenCV · pandas · matplotlib · APScheduler · Tkinter · GitHub Actions

## Tests

The repository includes smoke tests, handler tests and receipt scenarios covering different receipt layouts and arithmetic checks. CI runs the deterministic test path without requiring Ollama or personal receipt images.

## Limitations

- intended for personal/family use, not a multi-tenant financial SaaS;
- poor-quality receipt photos can still produce uncertain OCR;
- local Vision inference can be slow on low-end hardware;
- recommendations are based on recorded data and do not prove actual savings.

## Local setup

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## AI-assisted development

AI was used for implementation drafts, routine handlers, test-case generation and unfamiliar input formats.

I owned the decomposition, architecture, integration decisions, debugging, validation and final product behaviour.
