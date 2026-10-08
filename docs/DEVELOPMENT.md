# Development setup

Finance Bot is a multi-service monorepo. The legacy Python bot and desktop panel remain runnable. SaaS Compose profiles are specified in `PLAN.md`, but their Compose files do not exist yet. Do not use this checkout as a production deployment recipe.

## Requirements

- Python 3.11 or 3.12 for legacy bot and Python service tests.
- Docker Desktop with WSL2 on Windows, or Docker Engine and Compose v2 on Linux, for future container profiles. No Compose stack is available yet.
- Infisical CLI is optional for legacy runs. It supplies secrets from the configured `dev` project. Never commit credentials.

## Windows: legacy bot or panel

Install Python dependencies once:

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

Run with Infisical after `infisical login` and project setup:

```powershell
.\scripts\dev.ps1 legacy-bot
.\scripts\dev.ps1 legacy-panel
```

Entrypoints: `scripts/dev.ps1` and `scripts/dev.sh`. The existing `start_bot.bat` and `start_panel.bat` call the PowerShell entrypoint. Without Infisical, provide required values through the process environment or ignored local `.env` (the legacy app loads `.env`); do not place real values in `.env.example`.

## Linux: legacy bot or panel

```sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements.txt
chmod +x scripts/dev.sh
./scripts/dev.sh legacy-bot
./scripts/dev.sh legacy-panel
```

Infisical is optional. When installed and configured, the script runs the selected entrypoint through `infisical run --env=dev`. Otherwise it uses process environment and application dotenv loading.

## SaaS services and tests

The active SaaS code lives under `services/core`, `services/python`, `services/analytics-go`, and `apps/web` / `apps/android`. Use each service's build and test instructions while developing that service. `PLAN.md` section 17 defines intended Compose profiles and commands. Those commands are targets only until `infra/compose/compose.yml` is added and validated.

Create a local template copy only when needed:

```powershell
Copy-Item .env.example .env.dev
```

```sh
cp .env.example .env.dev
```

`.env.dev` is intended for the planned Compose stack. `.env` is loaded by the legacy Python app. Both local files are ignored by Git and Docker build contexts. Replace all `replace-me` values locally. The example uses loopback service endpoints, local-only AI policy, and synthetic service names. It does not provision PostgreSQL, Redis, Keycloak, Kafka, ClickHouse, or object storage.

## Runtime status

- Legacy Python bot/panel can run locally with their existing configuration.
- SaaS API, Web, Android, Go, and Python slices have independent local checks documented by their owning code and plan goals.
- No complete all-service local stack or production deployment is currently provided by this repository.
- `start_bot.bat` and `start_panel.bat` run foreground processes. They do not configure Windows service recovery or 24/7 hosting.

## Secret handling

Keep secrets in Infisical or another secret manager. Local `.env.dev`, private keys, database files, receipts, logs, and build outputs are excluded from Git or Docker context. Do not pass secret values as command-line arguments or print them in diagnostics. If a credential entered Git history, revoke and replace it.
