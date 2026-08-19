# GraphQL + Java/Spring Boot Tutorial

This project exposes a third transport onto the same `Event` domain code: [GraphQL](https://graphql.org/), alongside the REST API (`TUTORIAL.md` §5) and gRPC (`TUTORIAL.md` §7). As with gRPC, the point of including it here isn't that ClickHouse needs GraphQL — it's that the transport is swappable while the domain logic isn't. `EventGraphQlController` calls the exact same `EventService` that `EventController` and `EventGrpcService` call, so this tutorial is really about what GraphQL changes and what it doesn't.

## 1. What GraphQL is, schema-first vs. REST/gRPC

REST gives you one URL per resource shape: `GET /api/events/{id}` always returns the full `EventResponse`, whether the caller wants every field or just `eventType`. gRPC (§7 of `TUTORIAL.md`) is similar in spirit but contract-first with binary messages: `event.proto` fixes exactly what fields an `EventMessage` carries, and every client gets all of them.

GraphQL inverts this: there's a single endpoint (`POST /graphql`), and the *client* picks the shape of the response by writing a query that names only the fields it wants. Look at `EventGraphQlControllerIT`'s `eventQuery`:

```graphql
query($id: ID!) {
  event(eventId: $id) { eventId userId eventType }
}
```

This asks for three fields out of `Event`'s six — `properties`, `createdAt`, and `updatedAt` are simply not fetched or returned. A different caller could ask for all six, or just `eventId`, without the server changing at all. That flexibility is GraphQL's core trade: one endpoint, one schema, and the shape of every response is negotiated per-request instead of fixed per-endpoint (REST) or per-message (gRPC).

Like gRPC, GraphQL is schema-first: the contract lives in a schema file (`src/main/resources/graphql/schema.graphqls`) rather than being implied by DTO classes, and Spring validates every incoming query against it before your resolver code ever runs.

## 2. The schema contract

`src/main/resources/graphql/schema.graphqls` is the full contract. Walking it field by field:

```graphql
type Event {
    eventId: ID!
    eventType: String!
    userId: String!
    properties: [PropertyEntry!]!
    createdAt: String!
    updatedAt: String!
}

type PropertyEntry {
    key: String!
    value: String!
}
```

- **`eventId: ID!`** — GraphQL's `ID` scalar is serialized as a string on the wire but signals intent: this is an opaque identifier, not free text. The domain type is a `UUID` (see `Event.java`); the controller converts with `UUID.fromString(eventId)` on the way in. The trailing `!` means non-null — GraphQL types are nullable by default, so every field that this project's domain guarantees is present gets an explicit `!`.
- **`properties: [PropertyEntry!]!`** — GraphQL has no map/dictionary scalar type, only scalars, objects, lists, and enums. The domain model's `Map<String, String> properties` (`Event.java`) therefore can't be expressed directly; the schema models it as a list of `{key, value}` pairs instead. `EventGraphQlController.properties()`, a `@SchemaMapping(typeName = "Event")` method, is what performs that conversion on the way out (`toPropertyEntries`), and `toPropertiesMap` does the reverse on the way in for mutations.
- **`createdAt` / `updatedAt: String!`** — GraphQL also has no built-in date/time scalar (a custom `DateTime` scalar is possible but adds complexity this playground doesn't need). Both fields are plain ISO-8601 strings, matching how the app already serializes `Instant` over REST/JSON.

The rest of the schema:

```graphql
input PropertyEntryInput {
    key: String!
    value: String!
}

type EventTypeCount {
    eventType: String!
    count: Int!
}

type EventStats {
    counts: [EventTypeCount!]!
}

input CreateEventInput {
    eventType: String!
    userId: String!
    properties: [PropertyEntryInput!]
}

input UpdateEventInput {
    eventType: String!
    userId: String!
    properties: [PropertyEntryInput!]
}

input MutateEventInput {
    eventType: String!
}
```

Note the split between `type` (output shapes) and `input` (argument shapes) — `PropertyEntry`/`PropertyEntryInput` are structurally identical but GraphQL requires separate declarations for values you read versus values you send, since input objects can't reference output types. `properties` on `CreateEventInput`/`UpdateEventInput` is nullable (no trailing `!` on the list itself) — omitting it in a mutation is valid and simply yields an empty properties map (`toPropertiesMap` treats `null` entries as "no properties").

Finally, the root operation types:

```graphql
type Query {
    event(eventId: ID!): Event
    events(eventType: String, userId: String, from: String, to: String, limit: Int = 50, offset: Int = 0): [Event!]!
    eventStats(from: String, to: String): EventStats!
}

type Mutation {
    createEvent(input: CreateEventInput!): Event!
    createEvents(inputs: [CreateEventInput!]!): [Event!]!
    updateEvent(eventId: ID!, input: UpdateEventInput!): Event!
    mutateEvent(eventId: ID!, input: MutateEventInput!): Event!
}
```

`event(eventId: ID!): Event` (no `!` on the return type) is deliberately nullable — a missing event doesn't have to be a hard error at the schema level, though in practice this project's resolver throws instead (see §4). `events` defaults `limit` to `50` and `offset` to `0` directly in the schema, so a client can omit both and still get sane pagination.

## 3. Query vs. Mutation

GraphQL splits every operation into a `Query` (read, side-effect-free) or a `Mutation` (write). `EventGraphQlController` maps each schema field to a Java method with `@QueryMapping` or `@MutationMapping`, and every one of them delegates to the same `EventService` that `EventController` (REST) and `EventGrpcService` (gRPC) already call — reinforcing that this is one service behind three transports.

**Reads (`Query`):**

| Schema field | Controller method | `EventService` call |
|---|---|---|
| `event(eventId)` | `event(@Argument String eventId)` | `service.getById(UUID.fromString(eventId))` |
| `events(...)` | `events(@Argument String eventType, ..., @Argument int limit, @Argument int offset)` | `service.find(eventType, userId, toInstant(from), toInstant(to), limit, offset)` |
| `eventStats(from, to)` | `eventStats(@Argument String from, @Argument String to)` | `service.stats(toInstant(from), toInstant(to))` |

**Writes (`Mutation`):**

| Schema field | Controller method | `EventService` call |
|---|---|---|
| `createEvent(input)` | `createEvent(@Argument("input") Map<String, Object> input)` | `service.create(toCreateRequest(input))` |
| `createEvents(inputs)` | `createEvents(@Argument("inputs") List<Map<String, Object>> inputs)` | `service.createBatch(requests)` |
| `updateEvent(eventId, input)` | `updateEvent(@Argument String eventId, @Argument("input") Map<String, Object> input)` | `service.replaceVersion(UUID.fromString(eventId), request)` |
| `mutateEvent(eventId, input)` | `mutateEvent(@Argument String eventId, @Argument("input") Map<String, Object> input)` | `service.mutate(UUID.fromString(eventId), new MutateEventRequest(...))` |

Two things worth noticing:

- `updateEvent`/`mutateEvent` line up exactly with REST's two "update" paths from `TUTORIAL.md` §2: `updateEvent` calls `service.replaceVersion` — the idiomatic ReplacingMergeTree pattern (`PUT /api/events/{id}` in REST) — while `mutateEvent` calls `service.mutate`, the literal `ALTER TABLE ... UPDATE` mutation path (`PATCH /api/events/{id}/mutate` in REST). GraphQL doesn't change which ClickHouse pattern is in play, only how the call is made.
- Input arguments arrive as raw `Map<String, Object>` (Spring for GraphQL's default binding for `input` types) rather than typed request records; `toCreateRequest`/`toPropertiesMap` do the same manual conversion into `CreateEventRequest`/`UpdateEventRequest`/`MutateEventRequest` that the REST controller's `@RequestBody` deserialization does implicitly via Jackson.

## 4. Error handling

REST signals "not found" with an HTTP status code (`ResponseStatusException(HttpStatus.NOT_FOUND, ...)` in `EventService.getById`, which Spring MVC turns into a 404 response). GraphQL always returns HTTP 200 for a syntactically valid request — there's no per-field HTTP status to hang an error off of. Instead, errors surface in an `errors[]` array alongside (or instead of) `data` in the response body.

`EventGraphQlController` bridges the two with a `@GraphQlExceptionHandler`:

```java
@GraphQlExceptionHandler
public GraphQLError handle(ResponseStatusException ex, DataFetchingEnvironment env) {
    return GraphqlErrorBuilder.newError(env)
            .message(ex.getReason())
            .build();
}
```

Since `event()`, `updateEvent()`, and `mutateEvent()` all ultimately call `EventService.getById` (directly, or indirectly via `replaceVersion`/`mutate`), a missing event still throws the same `ResponseStatusException("Event not found: " + eventId)` the REST layer throws — but here Spring for GraphQL catches it, routes it to this handler, and turns it into a `GraphQLError` whose `message` is the exception's reason. `EventGraphQlControllerIT`'s `missingEventReturnsAGraphQlError` test asserts exactly this:

```java
graphQlTester.document(eventQuery)
        .variable("id", "00000000-0000-0000-0000-000000000000")
        .execute()
        .errors().expect(error -> error.getMessage().contains("Event not found"));
```

No HTTP 404 is involved; the failure is entirely inside the GraphQL response envelope.

## 5. Trying it out

With the app running (`./gradlew bootRun`), GraphQL is reachable two ways.

**GraphiQL** — an in-browser, schema-aware query editor — is enabled via `spring.graphql.graphiql.enabled: true` in `application.yml` and served at:

```
http://localhost:8080/graphiql
```

It autocompletes against the live schema and is the easiest way to explore `event`/`events`/`eventStats`/`createEvent`/etc. interactively.

**Raw `curl`**, since GraphQL over HTTP is just a `POST` with a JSON body containing a `query` string. Using the `createEvent` mutation from `EventGraphQlControllerIT`:

```
curl -X POST localhost:8080/graphql \
  -H 'Content-Type: application/json' \
  -d '{"query": "mutation { createEvent(input: {eventType: \"purchase\", userId: \"user-gql-1\", properties: [{key: \"sku\", value: \"abc-123\"}]}) { eventId eventType userId properties { key value } } }"}'
```

Unlike REST's `POST /api/events`, this is still the same `/graphql` endpoint every other query and mutation in this project goes through — only the `query` string in the body changes.

## 6. Testing

`EventGraphQlControllerIT` follows the same pattern as `EventControllerIT` (`TUTORIAL.md` §6): it extends `ClickHouseIntegrationTest` and runs against a real, Testcontainers-backed ClickHouse instance, no mocking of ClickHouse behavior. In place of `TestRestTemplate`, it uses Spring for GraphQL's `HttpGraphQlTester` (`@Autowired`) to send documents and assert on response paths (`.path("createEvent.eventId")`) and errors (`.errors().expect(...)`) directly, without needing to hand-parse a JSON body.

For the REST vs. gRPC vs. GraphQL comparison, see the GraphQL Field Guide artifact.
