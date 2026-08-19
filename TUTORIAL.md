# ClickHouse + Java/Spring Boot Tutorial

This project is a hands-on playground for learning how to use ClickHouse from a Java (Spring Boot) application. It models a small event/log analytics API: events are inserted, queried, aggregated, and "updated" through a REST API backed by a real ClickHouse instance. A third transport, GraphQL, is covered separately in `GRAPHQL_TUTORIAL.md`.

## 1. What is ClickHouse, and when do you reach for it?

ClickHouse is a **column-oriented OLAP** (analytical) database, not a general-purpose OLTP database like Postgres or MySQL.

- **Row-oriented databases** (Postgres, MySQL) store a full row together on disk. They're great at "fetch/update one record by ID" workloads — the classic CRUD app.
- **Column-oriented databases** (ClickHouse) store each column separately. They're great at "scan millions/billions of rows but only read a few columns, and aggregate" workloads — dashboards, analytics, log/event pipelines.

That difference drives everything else in this tutorial: how tables are modeled, why there's no cheap `UPDATE ... WHERE id = ?`, and why inserts are meant to happen in batches.

## 2. Core concepts used in this project

### MergeTree engine family

Almost every real ClickHouse table uses a `MergeTree`-family engine. Data is written in immutable chunks called **parts**; a background process periodically merges smaller parts into larger ones. This project's `events` table uses `ReplacingMergeTree`, a MergeTree variant described below.

### `ORDER BY` is ClickHouse's index

Unlike a traditional primary key, `ORDER BY (event_id)` tells ClickHouse how to physically sort each part on disk. It acts as a sparse index: ClickHouse can skip large ranges of data when your `WHERE` clause aligns with the `ORDER BY` columns. In this project, `events` is ordered by `event_id` so per-event lookups and updates are efficient.

### Why there's no cheap row-level UPDATE/DELETE

Because data is stored in immutable, sorted parts, rewriting a single row in place would mean rewriting the whole part it lives in. ClickHouse instead offers two very different tools, both used in this project:

1. **The ReplacingMergeTree pattern (idiomatic, cheap):** "updating" a row means inserting a *new* row with the same sort key and a newer version column. `ReplacingMergeTree(updated_at)` tells ClickHouse: when merging parts, if multiple rows share the same `ORDER BY` key, keep only the one with the highest `updated_at`. This is exactly what `PUT /api/events/{id}` does in this app — see `EventService.replaceVersion`.

   The catch: merges happen **in the background**, on ClickHouse's own schedule — not immediately. A plain `SELECT * FROM events` can return duplicate/stale rows until a merge happens. Appending `FINAL` to a query (`SELECT ... FROM events FINAL`) forces ClickHouse to reconcile duplicates at query time, which this app does for all reads — at the cost of extra CPU work per query. That tradeoff (fast writes, `FINAL`'s per-query cost) is worth understanding before using this pattern at real scale.

2. **`ALTER TABLE ... UPDATE` mutations (heavyweight, rare):** ClickHouse also supports SQL that looks like a normal `UPDATE`. Under the hood this is a **mutation**: an asynchronous background job that rewrites entire affected parts. It's not instant and not cheap — by default `ALTER TABLE ... UPDATE` returns before the rewrite finishes, so a client that reads immediately afterward may not see the change. This project uses `SETTINGS mutations_sync = 1` (see `EventRepository.mutateEventType`) to force the mutation to complete before returning, purely so the demo/tests are deterministic — in production, forcing synchronous mutations blocks the caller and is normally avoided. `PATCH /api/events/{id}/mutate` demonstrates this path so you can compare it directly against the ReplacingMergeTree pattern above.

**Rule of thumb:** use the ReplacingMergeTree/versioned-insert pattern for anything that changes often; reserve `ALTER TABLE ... UPDATE` for rare, one-off corrections.

## 3. Running ClickHouse locally

```
docker-compose up -d
```

This starts a single ClickHouse node (`clickhouse/clickhouse-server`) with:
- HTTP interface on `localhost:8123` (used by the app's Java client and by `curl`)
- Native protocol on `localhost:9000` (used by the `clickhouse-client` CLI)

Poke at it directly:

```
curl "http://localhost:8123/?query=SELECT+1"

docker exec -it clickhouse-playground clickhouse-client --database=playground
```

The app itself creates the `playground` database and `events` table on startup (`SchemaInitializer`), so you don't need a separate init script — the same code path runs identically here and in the Testcontainers-backed tests.

## 4. How the app talks to ClickHouse

The app uses ClickHouse's official Java client, `com.clickhouse:client-v2` (`com.clickhouse.client.api.Client`), configured in `ClickHouseClientConfig` from `clickhouse.*` properties in `application.yml`.

- **Inserts** (`EventRepository.insert` / `insertAll`) build a plain SQL `INSERT ... VALUES (...)` statement and execute it via `client.queryAll(sql)`. Batch insert sends multiple value tuples in a single statement — this is the cheap way to write to ClickHouse; avoid issuing one `INSERT` per row in a loop, since each insert creates a new part that then has to be merged.
- **Reads** use `client.queryAll(sql)`, which returns `List<GenericRecord>` — each `GenericRecord` exposes typed getters (`getString`, `getUUID`, `getLong`, `getInstant`, ...) used to map rows back into the `Event` record.
- The `properties` column is stored as a JSON string and (de)serialized with Jackson at the repository boundary, rather than relying on ClickHouse's native `Map` type — simpler to reason about for a learning project.

## 5. REST API walkthrough

Base URL: `http://localhost:8080/api/events`

**Insert one event**
```
curl -X POST localhost:8080/api/events \
  -H 'Content-Type: application/json' \
  -d '{"eventType":"page_view","userId":"user-1","properties":{"path":"/home"}}'
```

**Batch insert**
```
curl -X POST localhost:8080/api/events/batch \
  -H 'Content-Type: application/json' \
  -d '{"events":[
        {"eventType":"click","userId":"user-2","properties":{}},
        {"eventType":"click","userId":"user-3","properties":{}}
      ]}'
```

**List with filters + pagination**
```
curl "localhost:8080/api/events?eventType=click&limit=10&offset=0"
```

**Get one event (latest version)**
```
curl localhost:8080/api/events/<event-id>
```

**Aggregation: counts by event type**
```
curl "localhost:8080/api/events/stats?from=2026-01-01T00:00:00Z&to=2026-12-31T00:00:00Z"
```

**Idiomatic "update" (ReplacingMergeTree pattern)**
```
curl -X PUT localhost:8080/api/events/<event-id> \
  -H 'Content-Type: application/json' \
  -d '{"eventType":"page_view","userId":"user-1","properties":{"path":"/home","edited":"true"}}'
```

**Literal mutation update (`ALTER TABLE ... UPDATE`)**
```
curl -X PATCH localhost:8080/api/events/<event-id>/mutate \
  -H 'Content-Type: application/json' \
  -d '{"eventType":"page_view_corrected"}'
```

## 6. Testing strategy

Integration tests use [Testcontainers](https://testcontainers.com/)' official `ClickHouseContainer` (`org.testcontainers:clickhouse`) to spin up a real, disposable ClickHouse instance per test run — no mocking of ClickHouse behavior, since things like `FINAL` deduplication and mutation timing are exactly what's worth testing for real.

- `ClickHouseIntegrationTest` (test support base class) starts the container and wires `clickhouse.*` Spring properties to it via `@DynamicPropertySource`.
- `EventRepositoryIT` exercises the repository directly: insert, batch insert, filtered queries, the ReplacingMergeTree update pattern, the mutation update path, and aggregation.
- `EventControllerIT` exercises the same flows over real HTTP with `TestRestTemplate`, against the actual Spring MVC controllers and the containerized ClickHouse.

Run everything with:

```
./gradlew test
```

(Requires Docker running locally, since Testcontainers needs it to launch the ClickHouse container.)

## 7. gRPC: a second transport onto the same domain code

Everything in §5 goes through Spring MVC and JSON over HTTP/1.1. This project also exposes a subset of the same event flows over [gRPC](https://grpc.io/), as a self-contained tutorial on gRPC itself — not because ClickHouse needs it. The point of putting it in this repo is that **the transport is swappable while the domain logic isn't**: `EventGrpcService` calls the exact same `EventService` that `EventController` calls, so this section is really about what gRPC changes and what it doesn't.

### What's actually different from REST

- **Contract-first, typed schema.** `src/main/proto/event.proto` defines the service and message shapes with [Protocol Buffers](https://protobuf.dev/). Running `./gradlew generateProto` compiles it into Java classes (`EventGrpcServiceGrpc`, `EventMessage`, `CreateEventRequestProto`, `ListEventsRequestProto`) under `build/generated/source/proto` — there's no hand-written DTO or JSON schema to keep in sync; the `.proto` file *is* the contract, for every language a client might be written in.
- **Binary wire format over HTTP/2.** Where REST here serializes `EventResponse` to JSON text, gRPC serializes `EventMessage` to protobuf's binary encoding and multiplexes calls over a single HTTP/2 connection. Smaller payloads, no per-request TCP/TLS handshake once a channel is open — the tradeoff is that a protobuf payload isn't human-readable in transit the way `curl`'d JSON is.
- **Streaming as a first-class thing.** `CreateEvent` is a **unary** RPC (one request, one response), directly analogous to `POST /api/events`. `ListEvents` is a **server-streaming** RPC: the server pushes `EventMessage`s to the client one at a time as ClickHouse's result rows come back, instead of buffering the whole `List<EventResponse>` into one JSON array first. This is the capability REST/JSON has no real equivalent for — it's the main reason to reach for gRPC over a plain REST endpoint.
- **No dynamic JSON `properties`.** The REST DTOs happily accept an arbitrary `Map<String, String>` because JSON has no fixed schema. Protobuf has no `Object`/`any` type by default, so `event.proto` models `properties` as an explicit `map<string, string>` field — you have to *decide* the value type up front. (`google.protobuf.Struct` is the escape hatch for genuinely dynamic JSON-shaped data, at the cost of losing most of protobuf's type safety — not used here, but worth knowing about.)

### Where the pieces live

- `src/main/proto/event.proto` — the service (`EventGrpcService`) and message definitions (`EventMessage`, `CreateEventRequestProto`, `ListEventsRequestProto`).
- `EventGrpcService` (`@GrpcService`, from `net.devh:grpc-spring-boot-starter`) — implements the generated `EventGrpcServiceGrpc.EventGrpcServiceImplBase`, converts between protobuf messages and the domain `Event`/`CreateEventRequest` types, and delegates to `EventService`. `net.devh`'s starter is what makes this feel like Spring MVC: `@GrpcService` beans get auto-registered on a gRPC server the same way `@RestController` beans get registered with Tomcat.
- `application.yml`'s `grpc.server.port: 9090` — the app now listens for gRPC traffic on 9090 alongside REST on 8080. `grpc.server.reflection-service-enabled: true` turns on [server reflection](https://grpc.io/docs/guides/reflection/), which is what lets a generic client discover the service's shape at runtime (see `grpcurl` below) instead of needing the `.proto` file in hand.

### Trying it

With the app running (`./gradlew bootRun`), you need a gRPC-aware client — plain `curl` can't speak HTTP/2 + protobuf. [`grpcurl`](https://github.com/fullstorydev/grpcurl) is the closest thing to `curl` for gRPC and works here because reflection is enabled:

```
grpcurl -plaintext localhost:9090 list

grpcurl -plaintext -d '{"eventType":"page_view","userId":"user-1","properties":{"path":"/home"}}' \
  localhost:9090 clickhouse.home.event.EventGrpcService/CreateEvent

grpcurl -plaintext -d '{"eventType":"page_view","limit":10}' \
  localhost:9090 clickhouse.home.event.EventGrpcService/ListEvents
```

The `ListEvents` call is the one worth watching closely: `grpcurl` prints each `EventMessage` as it arrives rather than waiting for a single combined response, which is the server-streaming behavior in practice.

### Testing

`EventGrpcServiceIT` mirrors `EventControllerIT` (§6) but drives the gRPC service over an **in-process channel** instead of a real socket — `net.devh:grpc-client-spring-boot-starter`'s `@GrpcClient` annotation injects a blocking stub wired to `grpc.server.in-process-name`, so the test never binds a TCP port at all. It hits the same Testcontainers-backed ClickHouse as every other IT in this project; only the transport in front of `EventService` differs.

## 8. Going further: indices and other MergeTree features

This tutorial keeps the schema minimal on purpose. Once you're comfortable with the basics above, see [`TUTORIAL_INDICES.md`](TUTORIAL_INDICES.md) for how the sparse primary index actually works, skip indices, partitioning, TTL, projections, materialized views, and column codecs — with concrete suggestions for how each would apply to the `events` table.
