# XLSX → ClickHouse Importer  (v3)

Spring Boot 3.5 / java 17 multi-module Gradle project.

---

## Modules

| Module | Description |
|--------|-------------|
| `app` | Main application — Spring Boot REST API |
| `integration-tests` | Separate IT module — Testcontainers (requires Docker) |

---

## What's fixed in v3

| Problem | Fix |
|---------|-----|
| `Parameter 4 of constructor … required a single bean, but 2 were found` | `ImportService` uses an **explicit constructor** with `@Qualifier` directly on `JdbcTemplate` parameters. Lombok's `@RequiredArgsConstructor` cannot forward field-level `@Qualifier` to generated constructor parameters — see Javadoc in `ImportService.java` |
| DDL executed only on Node 1 | `CREATE TABLE IF NOT EXISTS` now runs on **both nodes** |
| INSERT executed on both nodes | `batchUpdate` runs on **Node 1 only** — `ReplicatedMergeTree` propagates to Node 2 |
| Swagger broken (Spring Boot 3.5) | Upgraded to `springdoc-openapi 2.8.8` — first version compatible with Spring MVC 6.2's `PathPatternParser` |
| `_str` companion type ignored `Nullable` | `Nullable(Date)` → `Nullable(String)`; `Date` → `String` |
| Integration tests mixed with unit tests | Separate `integration-tests` Gradle module with its own `docker-compose-it.yml` and `application-it.yml` |

---

## Project structure

```
xlsx-importer/
├── app/                                    ← Main Spring Boot application
│   ├── build.gradle
│   └── src/
│       ├── main/java/com/example/xlsximporter/
│       │   ├── XlsxImporterApplication.java
│       │   ├── config/
│       │   │   ├── DataSourceConfig.java   ← multi-datasource, fixed @Qualifier
│       │   │   ├── ClickHouseProperties.java
│       │   │   └── OpenApiConfig.java
│       │   ├── service/
│       │   │   ├── ImportService.java      ← explicit constructor, DDL both / INSERT Node1
│       │   │   ├── ClickHouseScriptBuilder.java  ← _str Nullable, engine switch
│       │   │   └── XlsxParserService.java
│       │   ├── controller/ImportController.java
│       │   ├── validation/{XlsxValidator,ClickHouseTypeRegistry,DateParser}.java
│       │   ├── dto/{ParsedSheet,ImportResponse,ErrorResponse}.java
│       │   ├── exception/{ValidationException,ImportException,GlobalExceptionHandler}.java
│       │   ├── model/ImportLog.java
│       │   └── repository/ImportLogRepository.java
│       └── test/                           ← Unit tests (no Docker)
│
├── integration-tests/                      ← Separate IT module
│   ├── build.gradle
│   ├── docker-compose-it.yml               ← Reference docker-compose for IT containers
│   └── src/test/
│       ├── java/com/example/xlsximporter/it/
│       │   ├── AbstractIntegrationTest.java  ← Testcontainers base
│       │   ├── XlsxTestHelper.java           ← builds in-memory xlsx
│       │   ├── ImportServiceIntegrationTest.java
│       │   └── ImportControllerIntegrationTest.java
│       └── resources/application-it.yml
│
├── clickhouse-config/                      ← ClickHouse cluster XML configs
├── postgres-init/01_init.sql
├── docker-compose.yml                      ← Production-like local cluster
├── build.gradle                            ← Root (shared versions)
├── settings.gradle                         ← include 'app', 'integration-tests'
├── gradlew / gradlew.bat
└── gradle/wrapper/gradle-wrapper.properties
```

---

## Quick Start

### 0. First-time setup — get `gradle-wrapper.jar`

The `gradle-wrapper.jar` binary is not included in the archive (it is never committed to version
control). You need it once, then `./gradlew` works forever.

**Option A — run the provided setup script (recommended)**

```powershell
# Windows PowerShell
PowerShell -ExecutionPolicy Bypass -File setup.ps1
```

```bash
# Linux / macOS
chmod +x setup.sh && ./setup.sh
```

The script downloads `gradle-wrapper.jar` from GitHub, verifies Java, and runs the first build.

**Option B — Gradle installed globally**

```bash
gradle wrapper --gradle-version 8.7
```

**Option C — download manually**

Download `gradle-wrapper.jar` from:
```
https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar
```
Save it to: `gradle/wrapper/gradle-wrapper.jar`

---

### 1. Start infrastructure (production-like cluster)

```bash
docker-compose up -d
# Verify:
curl http://localhost:8123/ping   # Node1 → OK
curl http://localhost:8124/ping   # Node2 → OK
```

### 2. Build and run

```bash
./gradlew :app:bootRun
```

### 3. Swagger UI

```
http://localhost:8080/swagger-ui.html
```

---


## IntelliJ IDEA — открытие проекта

**Правильный способ** (иначе импорты в `integration-tests` не резолвятся):

1. **File → Open** → выбрать папку `xlsx-importer-v3` (корень проекта)
2. Если IDEA спрашивает — выбрать **"Open as Gradle project"**
3. Дождаться окончания **Gradle sync** (прогресс-бар внизу)
4. После sync: **File → Invalidate Caches → Invalidate and Restart**

**Если импорты всё ещё красные после sync:**

- Убедитесь что Gradle sync завершился без ошибок (вкладка **Build**)
- В меню **Gradle** (правая панель) нажать **Reload All Gradle Projects** (кнопка с круговой стрелкой)
- Проверить **Project Structure (Ctrl+Alt+Shift+S) → Modules** — должно быть 3 модуля:
  - `xlsx-importer`
  - `xlsx-importer.app`  
  - `xlsx-importer.integration-tests`
- В модуле `xlsx-importer.integration-tests` папка `src/test/java` должна быть **синей** (Test Sources Root)

**Если модуль `integration-tests` всё ещё не видит классы из `app`:**

```
Gradle panel → Tasks → other → idea  (запустить задачу)
```
или в терминале:
```bash
./gradlew idea
```
Это регенерирует `.iml` файлы с правильными зависимостями.

## Running tests

### Unit tests (no Docker)

```bash
./gradlew :app:test
```

Covers: type registry, date parser, validator, DDL builder, `_str` Nullable logic, INSERT SQL.

### Integration tests (Docker required)

```bash
./gradlew :integration-tests:test
```

Testcontainers pulls and starts containers automatically:
- `postgres:16-alpine`
- `clickhouse/clickhouse-server:24.3` × 2

### All tests

```bash
./gradlew test
```

---

## REST API

### `POST /api/v1/import/xlsx`

```bash
curl -X POST "http://localhost:8080/api/v1/import/xlsx?tableName=employees" \
  -F "file=@sample_employees.xlsx"
```

### `GET /api/v1/import/logs`
### `GET /api/v1/import/logs/{tableName}`
### `GET /api/v1/import/health`

---

## Write strategy

```
POST /api/v1/import/xlsx
    │
    ├─ DDL (CREATE TABLE IF NOT EXISTS)
    │     ├─ Node 1  ← mandatory, failure aborts
    │     └─ Node 2  ← best-effort (warn on failure)
    │
    └─ INSERT (batchUpdate, 1000 rows/batch)
          └─ Node 1 only
             ReplicatedMergeTree replicates to Node 2 asynchronously
```

---

## _str companion column types

| Source column type | Companion type |
|-------------------|----------------|
| `Date` | `String` |
| `Nullable(Date)` | `Nullable(String)` |
| `DateTime` | `String` |
| `Nullable(DateTime)` | `Nullable(String)` |

---

## Ports

| Service | Port | Notes |
|---------|------|-------|
| App | 8080 | REST + Swagger |
| PostgreSQL | 5432 | Import logs |
| ZooKeeper | 2181 | ClickHouse replication |
| ClickHouse Node 1 | 8123 / 9000 | Primary write target |
| ClickHouse Node 2 | 8124 / 9001 | DDL replica |

---

## Requirements

- Java 17+
- Gradle 8.7 (via wrapper)
- Docker & Docker Compose (for IT tests and local cluster)
