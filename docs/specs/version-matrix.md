# Матрица версий разработки

Проверена 2026-10-01. Поддерживаемые значения и официальные основания описаны в
[дополнении к плану](CONTINUATION.md#предлагаемая-матрица-версий-e0).

| Runtime / service | Pinned baseline | Local verification |
|---|---|---|
| Java | Temurin 17.0.18 | `java` and `javac` both returned 17.0.18 |
| Spring Boot | 4.1.1 | Not installed; system requirements permit Java 17 |
| Gradle | Wrapper 9.8.0 | Wrapper verified with Java 17; distribution SHA-256 pinned |
| Python | 3.12.14 | v1 requirements installed into ignored `.venv` |
| Node.js | 24.19.x LTS | `node --version` returned v24.19.0; npm/corepack not found |
| Go | 1.27.1 | Not installed |
| PostgreSQL | 18.6 | EDB binaries in isolated temp; real migration and RLS integration passed |
| Psycopg | 3.3.6 | Installed in ignored `.venv`; used for PostgreSQL integration tests |
| Redis | OSS 8.10.0 | Not installed |
| Kafka | 4.3.1 | Not installed |
| ClickHouse | 26.9.5.2 stable | Not installed |
| Keycloak | 26.7.3 | Not installed |
| Docker Compose | Must be version-pinned | Docker CLI and daemon not found |
| Android SDK/emulator | Pin after local SDK discovery | `adb` not found on PATH; Unity JDK only is present |

Patch revisions may advance for security fixes. Update the matrix and lock/image
digests in one change, then run the corresponding compatibility and integration
gates. Never use a floating `latest` image in committed Compose configuration.
