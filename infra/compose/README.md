# Local Core runtime

This profile is for local development only. Core and Keycloak currently use an
internal Docker issuer; the host ports bind to `127.0.0.1`, so a phone or a
public HTTPS tunnel cannot use this configuration yet.

1. From the repository root, copy `.env.example` to `.env.dev`.
2. Replace `replace-me` for `POSTGRES_PASSWORD`, `DATABASE_PASSWORD`,
   `DATABASE_MIGRATION_PASSWORD`, `KEYCLOAK_ADMIN_USERNAME`, and
   `KEYCLOAK_ADMIN_PASSWORD` with local values. Keep `.env.dev` private and
   uncommitted.
3. Start the local profile:

   ```powershell
   docker compose --env-file .env.dev -f infra/compose/compose.yml --profile core up --build -d
   ```

4. Check service health:

   ```powershell
   docker compose --env-file .env.dev -f infra/compose/compose.yml --profile core ps
   ```

Postgres data, receipt files, and Keycloak's local dev data use named volumes.
`docker compose down` stops the services and preserves these volumes. Do not
use `down --volumes` unless intentionally deleting local data.

Keycloak runs with `start-dev`; this profile is not production-safe. The realm
requires email verification, but no email sender is configured, so signup and
password reset cannot complete until a real sender is provisioned. Do not
publish these services before the canonical public HTTPS issuer, TLS route,
OIDC and tenant-isolation checks are configured and verified.
