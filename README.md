# Finance Bot

Finance Bot is a personal-finance product being migrated from a local Telegram/Desktop application to a multi-service SaaS architecture. The repository contains the legacy Python app and newer Java/Core, React/Web, Android, Python, and Go components. The full SaaS deployment is still in development.

## Current status

- Legacy Telegram bot and desktop panel remain available for local use.
- SaaS features are implemented incrementally under the approved `PLAN.md` and tracked in `.agent/PROGRESS.md`.
- Android is a native Kotlin/Compose client; Web is under `apps/web`; the Java business API is under `services/core`.
- This repository does not yet include the planned all-service Docker Compose stack or a production deployment. A successful local test or Cloudflare build does not mean the SaaS is live.

## Start the legacy app

See [Development setup](docs/DEVELOPMENT.md) for Windows and Linux requirements, secret setup, service checks, and known runtime limits.

Windows:

```powershell
.\scripts\dev.ps1 legacy-bot
.\scripts\dev.ps1 legacy-panel
```

Linux:

```sh
./scripts/dev.sh legacy-bot
./scripts/dev.sh legacy-panel
```

Entrypoints: `scripts/dev.ps1` and `scripts/dev.sh`.

The legacy app needs its own Telegram token and user configuration. Infisical is supported. Never put real credentials in tracked files.

## Repository map

| Path | Purpose |
|---|---|
| `services/core` | Java business API and persistence |
| `services/analytics-go` | Go analytics and projections |
| `services/python` | Telegram, AI, and import services |
| `apps/web` | React Web client |
| `apps/android` | Kotlin Android client |
| `contracts` | API and event contracts |
| `PLAN.md` | Approved feature and delivery plan |
| `.agent/PROGRESS.md` | Execution status and validation evidence |

## Configuration and privacy

`.env.example` contains safe local defaults and placeholders. The legacy Python app reads ignored `.env`; `.env.dev` is reserved for the planned Compose stack. Production credentials belong in a secret manager. See [Security and privacy](docs/SECURITY.md).

MIT licensed. See [LICENSE](LICENSE).
