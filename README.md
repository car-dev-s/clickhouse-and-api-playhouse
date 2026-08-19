# ClickHouse Playground

A learning playground for using [ClickHouse](https://clickhouse.com/) from Java. A Spring Boot service inserts, queries, aggregates, and "updates" event/log-analytics data in ClickHouse, exposed over three parallel transports: REST, gRPC, and GraphQL.

This project exists to demonstrate specific ClickHouse idioms — why there's no cheap row `UPDATE`, the `ReplacingMergeTree` pattern vs. `ALTER TABLE ... UPDATE` mutations, sparse primary indices, skip indices, etc. — rather than to be a generic CRUD template. Read **[TUTORIAL.md](TUTORIAL.md)** before making schema or repository changes.

## Docs

- **[TUTORIAL.md](TUTORIAL.md)** — the core concept walkthrough: column storage, `MergeTree` parts, `ORDER BY`, and the two "update" patterns used in this codebase.
- **[TUTORIAL_INDICES.md](TUTORIAL_INDICES.md)** — a deeper dive into primary/sparse indices, data-skipping (secondary) indices, and other `MergeTree` features not yet used in this schema.
- **[GRAPHQL_TUTORIAL.md](GRAPHQL_TUTORIAL.md)** — the GraphQL transport: schema-first design, query vs. mutation, and how it compares to the REST/gRPC transports.

## Requirements

- JDK 21
- Docker (for local ClickHouse via `docker-compose`, and for Testcontainers in tests)

## Quick start

Start a local ClickHouse instance:

```bash
docker-compose up -d
```

This also brings up a Tabix web UI for ClickHouse at `http://localhost:8124`.

Run the app:

```bash
./gradlew bootRun
```

- REST API: `http://localhost:8080/api/events`
- GraphQL endpoint: `http://localhost:8080/graphql` (GraphiQL playground at `http://localhost:8080/graphiql`)
- gRPC: `localhost:9090` (see `src/main/proto/event.proto`)

## Building and testing

```bash
./gradlew build
```

```bash
./gradlew test
```

Tests spin up a real ClickHouse instance via Testcontainers, so Docker must be running — no ClickHouse-specific behavior (merge/`FINAL` semantics, mutation timing) is mocked.

Run a single test class or method:

```bash
./gradlew test --tests "clickhouse.home.event.EventRepositoryIT"
./gradlew test --tests "clickhouse.home.event.EventRepositoryIT.insertsAndReadsBackAnEvent"
```

## Architecture

The app has one vertical slice — **events** — exposed over three transports that all delegate to the same `EventService`:

| Transport | Entry point | Port |
|---|---|---|
| REST | `EventController` | 8080 |
| gRPC | `EventGrpcService` (`src/main/proto/event.proto`) | 9090 |
| GraphQL | `EventGraphQlController` (`src/main/resources/graphql/schema.graphqls`) | 8080 (`/graphql`) |

Other key pieces:

- **`config/`** — `ClickHouseProperties` (bound from `clickhouse.*` in `application.yml`) and `ClickHouseClientConfig`, which builds the `com.clickhouse.client.api.Client` bean used everywhere else.
- **`event/`** — `EventController` → `EventService` → `EventRepository` (plain SQL against ClickHouse via `Client`), plus `event/dto` request/response records.
- **`schema/SchemaInitializer`** — an `ApplicationRunner` that creates the database/table on startup, so local dev and tests provision schema through the same code path.
- **`events` table** — `ReplacingMergeTree(updated_at)` ordered by `event_id`. `properties` is stored as a JSON string column (Jackson-serialized at the repository boundary), not ClickHouse's native `Map` type.

Two distinct "update" code paths are deliberately kept side by side (see `TUTORIAL.md` §2 for why):

- `PUT /api/events/{id}` (`EventService.replaceVersion`) — inserts a new versioned row; relies on `ReplacingMergeTree` + `FINAL` reads to reconcile. The idiomatic, cheap ClickHouse pattern.
- `PATCH /api/events/{id}/mutate` (`EventRepository.mutateEventType`) — a literal `ALTER TABLE ... UPDATE` mutation, run synchronously for demo/test determinism. Heavyweight; kept only as a contrast to the pattern above.

See `CLAUDE.md` for more implementation notes (SQL escaping conventions, why `FINAL` is used throughout, etc.).

## REST API

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/events` | Create a single event |
| `POST` | `/api/events/batch` | Create a batch of events |
| `GET` | `/api/events/{eventId}` | Fetch an event by ID |
| `GET` | `/api/events` | Search events (`eventType`, `userId`, `from`, `to`, `limit`, `offset`) |
| `GET` | `/api/events/stats` | Aggregate stats over a time range |
| `PUT` | `/api/events/{eventId}` | Replace-version update (`ReplacingMergeTree` pattern) |
| `PATCH` | `/api/events/{eventId}/mutate` | Mutation-based update (`ALTER TABLE ... UPDATE`) |
