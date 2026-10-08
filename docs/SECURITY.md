# Security and privacy

Finance Bot processes receipts, income, debts, and account data. Deployment must protect both service credentials and user financial data. This repository contains legacy local-app code and a SaaS rewrite; their storage and network boundaries differ.

## Current boundaries

- The legacy Python app stores its SQLite database and receipt files locally. Telegram messages and photos pass through Telegram when that integration is used. Ollama cloud is an external processor and must be explicitly configured.
- The SaaS target uses Java/Core as the business-data writer, PostgreSQL with tenant isolation, and service-to-service credentials. Keycloak provides OIDC identity. The exact deployed topology is not yet shipped as a complete Compose or production stack.
- The Python intelligence service defaults to `FINANCE_AI_POLICY=local-only`. Remote Ollama requires explicit `cloud-opt-in`; credentials remain server-side.
- Receipt object storage is private. `storage_key` is an internal reference, not a public URL. Deployments must use authenticated object access and malware scanning.

## Secrets

- Keep bot tokens, OIDC client secrets, database passwords, service tokens, signing keys, and storage credentials in Infisical or another secret manager.
- `.env.example` contains safe local values and `replace-me` placeholders only. Local `.env*` files other than the example are ignored by Git and Docker build contexts.
- Do not put credentials in source code, container build arguments, image layers, URLs, logs, screenshots, or test fixtures.
- Rotate any credential that entered Git history. Removing the file does not remove the secret from history.
- Use separate credentials for each service and environment. Grant only the permissions each service needs.

## Build and data protection

`.dockerignore` excludes local environment files, private keys, user data, temporary files, and Git metadata from build contexts. Dockerfiles should copy only explicit source paths. Review context exclusions whenever adding a new local-data directory or secret format.

Git ignores local databases, receipt data, runtime files, environment files, private keys, and generated outputs. Tests must use synthetic fixtures. Never stage `.android-user/`, `data/`, or private samples to make a test pass.

## SaaS controls

- Enforce tenant and member authorization in Java/Core for every financial read and write. Do not trust tenant or owner IDs from an untrusted client.
- Validate issuer, audience, expiry, state, and PKCE for OIDC flows. Keep browser sessions server-side and Android refresh credentials in protected platform storage.
- Use TLS for external traffic and protected transport for service credentials. Do not expose PostgreSQL, Redis, Kafka, or ClickHouse to public networks.
- Keep outbox consumers idempotent. Do not use analytics projections as the source of truth for money.
- Limit receipt upload size and type, scan uploaded objects, and use short-lived authorization for private object access.
- Configure backups, retention, restore tests, deletion workflows, and monitoring before production use.

## Incident response

Revoke leaked credentials first, then replace them in the secret manager and affected services. Preserve relevant audit logs without copying receipt contents or tokens into incident reports. Assess affected tenants and data before restoring service.
