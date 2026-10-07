# Legacy goal history importer

The importer copies `advice:goal_history:{legacyUserId}` settings from a legacy SQLite database into Core's `origin='legacy'` goal outcomes. It opens SQLite in read-only mode and does not change finance records or notification state.

Create a reviewed JSON manifest. Never infer tenant, owner, or timezone from a Telegram ID:

```json
{
  "legacyUsers": {
    "123456789": {
      "tenantId": "0199b81a-4a9c-7000-8000-000000000001",
      "ownerUserId": "0199b81a-4a9c-7000-8000-000000000002",
      "timezone": "Europe/Moscow"
    }
  }
}
```

Run a dry report first:

```powershell
python -m tools.migration.goal_history C:\path\legacy.sqlite3 .\legacy-users.json
```

Review every quarantined source key and reason before import. Quarantine output excludes goal names, amounts, and raw JSON. Missing or invalid timezone, ambiguous/nonexistent local time, malformed rows, and unmapped owners stay quarantined.

Import only over HTTPS, or HTTP on loopback for local development. The token comes from `FINANCE_MIGRATION_SERVICE_TOKEN`; the tool never prints it. If quarantine remains after review, pass `--allow-quarantine` to import only valid rows:

```powershell
$env:FINANCE_MIGRATION_SERVICE_TOKEN = '<migration credential>'
python -m tools.migration.goal_history C:\path\legacy.sqlite3 .\legacy-users.json `
  --core-url https://core.example --apply
```

Each owner batch contains at most 500 rows. Core's deterministic legacy key makes a full rerun safe after interruption; server response counts must account for every row or the tool stops. This repository has no real legacy SQLite database, so live source mapping, quarantine review, and migration rehearsal remain unverified.
