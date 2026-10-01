# Дополнение к плану Finance SaaS v2

Версия: 1.1, 2026-10-01. Нормативное дополнение к `../PLAN.md`.

## Основание и приоритет

Пользователь поручил продолжить два чата «План перехода на удаленку», затем
клонировать GitHub-репозиторий в текущий проект и продолжить по плану.
Архитектура и полный rewrite согласованы ранее. Повторное согласование всего
плана не требуется. Это дополнение восстанавливает требования последующих
сообщений: Android, заменяемые AI-провайдеры и будущий Vertex AI.

При конфликте действует это дополнение. Оставшиеся предложения, явно требующие
решения в исходном плане, не считаются автоматически утверждёнными.

Исходный план: чат `01a0f5a7-9830-7e23-8d4a-a7b9b3032263`, файл
`outputs/finance-bot-rewrite-migration-plan.md`, скопирован без изменения содержания.
Базовый снимок v1: `cfaa013e1c977db24d7ab81944d0113ca79daea9`, ветка `main`.
Дополнительный источник мобильных сценариев: ветка `origin/feat/android-fastapi`.
Её функции нужно учитывать отдельно; 85 файлов и F01–F58 описывают только main.

## A. Авторизация — принято пользователем 2026-10-01

ADR-007: **Keycloak + OIDC**. Пользователь выбрал этот вариант в текущем чате.
Вход по логину/паролю и восстановление выполняются через Keycloak.
Java хранит membership и бизнес-права, Keycloak — identity и учётные данные.
Browser использует BFF/session согласно плану; Android — системный браузер,
Authorization Code + PKCE и отдельный public client без client secret.
Токены не записываются в логи. Android хранит refresh credentials с защитой
Android Keystore; logout/revoke очищает локальную сессию. Приложение обрабатывает
истечение токена, отмену входа, потерю сети и отзыв membership.

Приёмка: два tenant, смена/отзыв membership, повторный вход, refresh и logout;
неверные issuer/audience/expired tokens отклоняются; callback/state/PKCE проверены.
Версия Keycloak и библиотек фиксируется после проверки совместимости в E0.

## B. Android — обязательная часть продукта

`apps/android/`: Kotlin + Jetpack Compose, RU/EN, финтех-интерфейс.
Клиент обращается к тому же Java API, что React. Бизнес-правила, тарифные лимиты
и финансовые вычисления принадлежат Java. FastAPI/Flutter не становятся backend
или целевым клиентом v2; их сценарии служат дополнительным источником parity.

Экраны: вход, onboarding, dashboard, счета, операции и история, чеки и редактор
позиций, импорт/preview/confirm/undo, бюджеты, долги, отчёты, товары/цены, покупки,
аналитика, цели, AI assistant, семья, экспорт, настройки и billing.
Операторская инфраструктура `/ops` остаётся web-интерфейсом; все пользовательские
сценарии должны быть доступны на Android. Фото через камеру/выбор файла; PDF
через системный picker; состояния отказа permission, отмены и повторной отправки.

В registry для каждого Fxx фиксируется Android acceptance либо обоснованное N/A
для Telegram protocol/desktop-specific действия. Функциональный эквивалент обязателен.
Все пользовательские строки и форматирование проверяются в RU и EN.

Этапы дополняются без исключения существующих gates:

- E0: инвентаризация мобильной ветки и контрактов, матрица parity Android.
- E2: вход и первый Android create/list сценарий через настоящий Java API.
- E3–E7: Android экраны добавляются вместе с соответствующими backend функциями.
- E8: UAT на эмуляторе и сверка мигрированных данных через Android.
- E9–E10: сборка, установка и smoke релизного кандидата.

Android DoD: unit/UI/instrumentation проверки, воспроизводимая сборка APK,
SHA-256 артефакта, `adb install`, запуск на эмуляторе, RU/EN и сквозные сценарии
с Java/PostgreSQL. Наличие исходников или запущенного CI не означает доставку APK.
Офлайн-редактирование с синхронизацией не вводится без отдельного требования.

## C. AI Gateway и будущий Vertex AI

Путь: Java jobs/API — Python intelligence gateway — provider adapter.
React, Android и Telegram не получают credentials провайдеров.
Текущие модели и детерминированные алгоритмы сохраняются как поведенческий
baseline; production-код v2 следует правилу rewrite из исходного плана.

Capabilities: `text`, `structured_output`, `vision`, `embeddings`, `tool_calling`,
опционально `streaming`. Провайдер объявляет только реально поддержанные
возможности конкретной модели. Принудительного общего API, эмулирующего всё,
нет; unsupported capability возвращает явную ошибку.

Запрос содержит task kind, input/schema version, deadline, cancellation,
policy (local-only/cloud opt-in), correlation/job ID и требуемые capabilities.
Ответ содержит validated result, provider/model/prompt version, usage,
latency, fallback reason и provenance. Unknown cost обозначается unknown.
Ошибки разделяются на timeout, unavailable, quota, invalid output,
unsupported capability и policy denied; retries ограничены deadline.

Адаптеры текущего этапа: Ollama text/vision, отдельный Tesseract OCR adapter,
детерминированный fallback. Tesseract не объявляется LLM с embeddings или tools.
Маршрутизация выбирает разрешённый provider по capabilities и task policy.
В `local-only` запрещён cloud-вызов даже при ошибке локальной модели.
Remote Ollama также считается внешней обработкой и требует явной policy.
Redaction/minimization выполняются до внешней передачи. Повтор/fallback
не создаёт новую финансовую запись и не списывает повторную пользовательскую quota.
Model tool call — только предложение из allowlist; денежные mutations проходят
Java validation и предусмотренное подтверждение пользователя.

`VertexAIProvider` — предусмотренная граница расширения, выключенная по умолчанию.
Будущая конфигурация: project/location, ADC или service identity, ссылки на
Secret Manager, model IDs, timeouts/retries, quotas, safety settings, usage/cost.
Поддержку multimodal/structured output/tools/streaming/embeddings проверять
для выбранной модели и SDK при реализации; здесь это требования к адаптеру,
а не утверждение о текущих возможностях любой модели Vertex.
Никаких cloud credentials или платных запросов на подготовительном этапе.

До переключения: versioned golden datasets для OCR, categorization, transaction
extraction и ответов ассистента; baseline/holdout; точность, hallucinations,
latency, usage/cost. Деньги рассчитываются по фактам, не по тексту модели.
Кандидат допускается только после сравнительных evals, feature flag/canary
и проверенного rollback на прежнюю конфигурацию. Автоматическое включение
Vertex после unit tests запрещено: необходим отдельный gate активации.

## D. Проверки дополнения

- A01: Keycloak OIDC web/Android, login/password, refresh/logout и tenant denial.
- A02: Android parity, RU/EN, APK build/install и E2E на эмуляторе.
- A03: local-only fail-closed, включая cloud fallback и remote Ollama.
- A04: capabilities routing, invalid JSON, timeout/cancellation и provenance.
- A05: одинаковые golden fixtures для текущего и нового provider; честные skips.
- A06: Vertex выключен до eval/canary gate; rollback не теряет jobs или quota.

Статус всех проверок: PLANNED. Эта спецификация не является доказательством
работающего Android, нового Java backend или готового Vertex adapter.

## E. Дополнительные parity IDs

| ID | Требование | Приёмка |
|---|---|---|
| F59 | Android-клиент Kotlin/Compose, общий Java API и функциональный RU/EN паритет v1 | F01–F58 отображены на Android; каждый сценарий реализован либо имеет утверждённый platform-specific N/A; APK установлен на эмуляторе и прошёл E2E |
| F60 | Python AI Gateway, capability-based routing, текущие adapters и extension point Vertex AI | Локальный режим закрыт для внешней передачи; output/provider/prompt provenance сохранён; новые adapters проходят общие evals без изменения бизнес-кода |

`F59` и `F60` добавляют объём к исходным F01–F58 и включаются в
`contracts/parity/feature-parity.yaml` и общий parity report.

## F. Изменения к исходному ADR

ADR-007 исходного плана: решение **Keycloak + OIDC принято пользователем
2026-10-01**; контекст выбора и проверки см. в разделе A. Версия identity
provider и deployment profile проверяются в E0. Альтернатива self-hosted password
auth в Java снимается с открытого выбора.

## G. Предлагаемая матрица версий E0

Проверено по официальным источникам 2026-10-01. Runtime major/minor versions
фиксируются здесь; patch updates входят через контролируемые security updates
и повторный CI. Container tags далее должны быть immutable digest-pinned в release.

| Компонент | Версия для E0 | Основание / состояние среды |
|---|---|---|
| Java JDK | 17.0.18, Java 17 | Найден Temurin в Unity Android toolchain; `java -version` и `javac -version` успешны |
| Spring Boot | 4.1.1 | Официальный минимум Java 17, Gradle 8.14+ / 9.x |
| Gradle wrapper | 9.8 | Совместимая ветка; официальный Gradle 9.8 опубликован 2026-09-24 |
| Python | 3.12.14 | Установлен isolated venv; все v1 baseline scripts прошли |
| Node.js | 24.19.x LTS | Доступен в среде как 24.19.0; использовать package manager lockfile |
| Go | 1.27.1 | Официальный стабильный patch на дату проверки; локальный Go пока не найден |
| PostgreSQL | 18.6 | Текущая поддерживаемая minor по официальной versioning table |
| Redis OSS | 8.10.0 | Версия официальных OSS release notes |
| Apache Kafka | 4.3.1 | Последний объявленный официальный patch на дату проверки |
| ClickHouse | 26.9.5.2 stable | Официальный stable package, опубликован 2026-09-28 |
| Keycloak | 26.7.3 | Поддерживающая/security patch ветка; realm/client config остаётся в репозитории без секретов |
| Android SDK / emulator | зафиксировать в Gradle toolchain после обнаружения SDK | SDK/adb пока не обнаружены в PATH; проверить Android setup gate до F59 acceptance |
| Docker Compose | plugin version установить при доступной платформе | Docker CLI/daemon отсутствуют; Compose integration пока NOT_RUN |

Официальные основания: [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html),
[Gradle releases](https://gradle.org/releases/), [Node.js release schedule](https://nodejs.org/en/about/previous-releases),
[Go release history](https://go.dev/doc/devel/release), [PostgreSQL version policy](https://www.postgresql.org/support/versioning/),
[Redis OSS release notes](https://redis.io/docs/latest/operate/oss_and_stack/stack-with-enterprise/release-notes/redisce/),
[Apache Kafka releases](https://kafka.apache.org/blog/releases/),
[ClickHouse stable packages](https://packages.clickhouse.com/),
[Keycloak release notes](https://www.keycloak.org/docs/latest/release_notes/).

Это воспроизводимый initial matrix, а не утверждение, что все сервисы уже
установлены или протестированы локально. Каждому image digest нужен живой
pull/healthcheck в core/async Compose gates. Если tag/image окажется недоступным,
обновить матрицу только после зафиксированной проверки и сохранить историю версии.
