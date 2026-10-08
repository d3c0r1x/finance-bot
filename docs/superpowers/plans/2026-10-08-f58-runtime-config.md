# F58 runtime configuration and developer entrypoints

## Goal

Complete the F58 global-plan slice without claiming that the not-yet-created Compose profiles are runnable. Make local secret handling safe for Docker builds, provide non-secret SaaS environment defaults, replace stale launch instructions with honest Windows/Linux guidance, and preserve the MIT notice.

## Acceptance

- `.env.example` documents the planned core/async/python settings with only safe local values and explicit secret placeholders.
- Git ignores local environment files, signing material, runtime data, and private receipt data while tracking `.env.example`.
- Docker build contexts exclude local environment files, credentials, user data, and VCS metadata.
- Windows and Linux entrypoints give clear preflight guidance and never print secret values. Legacy bot/panel entrypoints continue to work through the documented secret-store method.
- README, DEVELOPMENT, and SECURITY describe the current multi-service repository accurately and distinguish implemented, planned, and externally configured deployment behavior.
- MIT license text and copyright notice remain intact.

## Sequence

1. Add focused acceptance tests for env template, ignore rules, Docker context, launchers, and MIT notice; run to observe RED.
2. Implement minimal env/ignore protections and cross-platform development entrypoints.
3. Rewrite stale developer/security/README instructions with verified commands and current limitations.
4. Run focused tests, relevant existing regression suites, static diff checks, and available shell/Docker validation.
5. Update global and execution progress with evidence, commit, push, and inspect CI.

## Scope boundary

`PLAN.md` explicitly says Compose files do not exist yet. This F58 slice will not advertise `docker compose up` as functional or create a partial production stack. Compose profiles remain a plan-level delivery item until the service set and runtime integration are implemented and verified.
