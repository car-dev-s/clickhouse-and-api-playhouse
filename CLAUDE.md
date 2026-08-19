# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project purpose

A learning playground for using ClickHouse from Java. A Spring Boot REST API inserts, queries, aggregates, and "updates" event/log-analytics data in ClickHouse. See `TUTORIAL.md` for the full concept walkthrough (why ClickHouse has no cheap row UPDATE, the ReplacingMergeTree pattern vs. `ALTER TABLE ... UPDATE` mutations, etc.) — read it before schema or repository changes, since the code demonstrates specific ClickHouse idioms rather than generic CRUD. A third transport, GraphQL, has its own `GRAPHQL_TUTORIAL.md`.

## Java toolchain

This project runs in IntelliJ, which is the source of truth for which JDK to use. At the start of a session:

1. Read `sourceCompatibility` from `build.gradle` (currently `JavaVersion.VERSION_21`).
2. Read `project-jdk-name` from `.idea/misc.xml` and confirm its major version matches `sourceCompatibility`. If they've diverged, flag it instead of silently picking one.
3. Resolve that JDK name's `homePath` from IntelliJ's `jdk.table.xml` (Windows: `%APPDATA%\JetBrains\<product><version>\options\jdk.table.xml`) — `$USER_HOME$` in that file expands to the Windows user profile dir. This file is large (mostly per-module classpath/sourcepath jar entries); don't read it in full. Instead grep for the `<name value="<jdk-name>"` line and take the `homePath` a few lines below it (e.g. `grep -A 3 '<name value="azul-21"' jdk.table.xml`), or query it with an XPath tool (`xmllint --xpath "//jdk[name/@value='azul-21']/homePath/@value" jdk.table.xml`).
4. Set `JAVA_HOME` for the session's shell commands (`gradlew`, etc.) to that resolved path, rather than relying on whatever `java` is on `PATH`.
5. This resolution is cached in project memory once found — check memory first before re-deriving it; only re-resolve if the cached path no longer exists or the JDK name in `.idea/misc.xml` has changed.

## Commands

Local ClickHouse (needed for `bootRun`; not needed for `test`, which uses Testcontainers):
```
docker-compose up -d
```

Build:
```
./gradlew build
```

Run the app (`http://localhost:8080`):
```
./gradlew bootRun
```

Run all tests (requires Docker running — tests spin up a real ClickHouse via Testcontainers):
```
./gradlew test
```

Run a single test class or method:
```
./gradlew test --tests "clickhouse.home.event.EventRepositoryIT"
./gradlew test --tests "clickhouse.home.event.EventRepositoryIT.insertsAndReadsBackAnEvent"
```

## Architecture

- **`config/`** — `ClickHouseProperties` (bound from `clickhouse.*` in `application.yml`) and `ClickHouseClientConfig`, which builds the `com.clickhouse.client.api.Client` bean used everywhere else.
- **`event/`** — the one vertical slice in this app: `EventController` (REST) → `EventService` (orchestration) → `EventRepository` (SQL against ClickHouse via `Client`), plus `event/dto` request/response records.
- **`EventGrpcService`** — a second, alternate transport onto the same `EventService`, exposed over gRPC (port 9090) instead of REST. Contract lives in `src/main/proto/event.proto`; see `TUTORIAL.md` §7 for the full walkthrough (unary vs. server-streaming RPCs, `grpcurl` usage, in-process test client).
- **`EventGraphQlController`** — a third transport onto the same `EventService`, exposed over GraphQL (`/graphql`, GraphiQL at `/graphiql`) instead of REST/gRPC. Schema lives in `src/main/resources/graphql/schema.graphqls`; see `GRAPHQL_TUTORIAL.md` for the full walkthrough (schema-first design, query vs. mutation, `[PropertyEntry!]!` in place of a map scalar).
- **`schema/SchemaInitializer`** — an `ApplicationRunner` that creates the database/table on startup, so local dev (docker-compose) and tests (Testcontainers) provision schema through the same code path, with no separate SQL init script to keep in sync.
- **`events` table** — `ReplacingMergeTree(updated_at)` ordered by `event_id`. `properties` is stored as a JSON string column (Jackson-serialized at the repository boundary), not ClickHouse's native `Map` type.
- **Two distinct "update" code paths**, both intentional (see `TUTORIAL.md` §2 for why):
  - `EventService.replaceVersion` / `PUT /api/events/{id}` — inserts a new versioned row; relies on `ReplacingMergeTree` + `FINAL` reads to reconcile. The idiomatic, cheap ClickHouse pattern.
  - `EventRepository.mutateEventType` / `PATCH /api/events/{id}/mutate` — a literal `ALTER TABLE ... UPDATE` mutation, run with `mutations_sync = 1` for demo/test determinism. Heavyweight; kept only as a contrast to the pattern above.
- **`EventRepository`** builds plain SQL text (`client.queryAll(sql)`) rather than using POJO/row-binary marshalling, so statements stay readable as teaching material. String values are manually escaped (see `escape()`) — this is a single-user playground, not a template for production SQL construction.
- Reads use `... FROM events FINAL ...` throughout to reconcile ReplacingMergeTree duplicates at query time.

## Testing

- `src/test/java/clickhouse/home/support/ClickHouseIntegrationTest` is the shared base class: starts a `ClickHouseContainer` (Testcontainers) and wires `clickhouse.*` Spring properties to it via `@DynamicPropertySource`. New integration tests should extend this rather than standing up their own container.
- `EventRepositoryIT` tests the repository layer directly; `EventControllerIT` drives the same flows over real HTTP with `TestRestTemplate`. Both hit a real ClickHouse instance — no mocking of ClickHouse-specific behavior (merge/FINAL semantics, mutation timing) since that behavior is the point.
