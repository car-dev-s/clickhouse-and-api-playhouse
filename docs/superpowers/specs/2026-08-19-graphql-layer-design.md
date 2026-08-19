# GraphQL Layer Design

## Goal

Add GraphQL as a third transport onto the existing `EventService`, mirroring the
precedent set by `EventGrpcService` (REST and gRPC already sit side-by-side over
the same service; GraphQL becomes a third parallel transport, not a parallel
implementation). Ship it with teaching material: a standalone GraphQL tutorial
file, a companion artifact (in the style of the existing gRPC Field Guide) that
carries the REST vs. gRPC vs. GraphQL comparison, and a minimal CLAUDE.md update.

## Scope

Full parity with REST/gRPC: create, batch create, get by id, list (simple
limit/offset, no Relay-style cursors), stats, `replaceVersion` (PUT-equivalent),
and `mutate` (PATCH-equivalent) — all exposed as GraphQL queries/mutations.

## Dependencies

Add to `build.gradle`:
- `implementation 'org.springframework.boot:spring-boot-starter-graphql'`
- `testImplementation 'org.springframework.graphql:spring-graphql-test'`

No extra scalar library (e.g. `graphql-java-extended-scalars`) — `properties`
is represented as a list of key/value pairs using only built-in GraphQL scalars
(see Schema below).

## Schema (`src/main/resources/graphql/schema.graphqls`)

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

Notes:
- `eventId` uses the built-in `ID` scalar; the controller parses it to `UUID`.
- `from`/`to` and `createdAt`/`updatedAt` are ISO-8601 strings, parsed/formatted
  with `Instant.parse` / `Instant.toString()` — same representation REST/gRPC
  already use at their boundaries, just as plain strings instead of a
  dedicated `DateTime` scalar (keeps the schema dependency-free).
- `properties` round-trips as `[PropertyEntry!]!` / `[PropertyEntryInput!]`
  instead of a native map, since GraphQL has no map scalar.

## `EventGraphQlController`

New class in `event/`, same package as `EventController` and `EventGrpcService`.
`@Controller` (Spring GraphQL, not `@RestController`), constructor-injects
`EventService`. One `@QueryMapping`/`@MutationMapping` method per schema field
above, each a thin adapter: convert GraphQL args to the existing DTOs
(`CreateEventRequest`, `UpdateEventRequest`, `MutateEventRequest`) or `UUID`,
call the matching `EventService` method, convert the returned `Event`/
`EventStatsResponse` back to schema shape. No business logic lives here — same
rule `EventGrpcService` already follows.

Property list <-> `Map<String,String>` conversion is two small private helpers
(`toPropertyEntries`, `toPropertiesMap`), analogous to `EventGrpcService`'s
`toTimestamp`/`toInstant` helpers.

## Error handling

`EventService.getById` throws `ResponseStatusException(HttpStatus.NOT_FOUND)`.
Spring GraphQL doesn't translate that automatically, so `EventGraphQlController`
gets one `@GraphQlExceptionHandler` method that maps `ResponseStatusException`
to a `GraphQLError` carrying the status/message, so a missing event surfaces as
a proper GraphQL error entry instead of an opaque 500.

## Config

`application.yml`: add `spring.graphql.graphiql.enabled: true` so GraphiQL is
available for local exploration at `/graphiql`. Endpoint stays at the Spring
GraphQL default, `/graphql`.

## Testing

New `EventGraphQlControllerIT`, extending the shared
`ClickHouseIntegrationTest` base (same as `EventControllerIT`/
`EventGrpcServiceIT`), using Spring GraphQL's `HttpGraphQlTester` against the
real embedded server. Covers: create, get by id (found + not-found error),
list with filters, stats, update (replaceVersion), mutate.

## Documentation deliverables

1. **New tutorial file**, `GRAPHQL_TUTORIAL.md` (sibling to `TUTORIAL.md`, not
   a section appended to it) — walks through schema-first GraphQL, query vs.
   mutation, how the schema above maps to `EventService`, and how to exercise
   it (GraphiQL, `curl` POST to `/graphql`). Written in the same voice/level as
   `TUTORIAL.md`, cross-linked from it with one line.
2. **Companion artifact**, published in the style of the existing gRPC Field
   Guide artifact (interactive HTML, sections, diagrams) — a "GraphQL Field
   Guide" covering schema mechanics, the query/mutation shapes used here, and
   crucially a **REST vs. gRPC vs. GraphQL comparison** (the three transports
   now sitting side-by-side in this project) grounded in the actual
   `EventController` / `EventGrpcService` / `EventGraphQlController` code.
3. **CLAUDE.md**: minimal addition only — one bullet under Architecture for
   `EventGraphQlController` (same treatment as the existing gRPC bullet), plus
   a `GRAPHQL_TUTORIAL.md` pointer next to the existing `TUTORIAL.md` reference
   in the intro paragraph. Explicitly keep the rest of the file untouched;
   this task also net-trims CLAUDE.md wherever a line can be shortened without
   losing information, per the user's "keep the whole file to minimum" ask.

## Out of scope

- Relay-style cursor pagination.
- A `JSON` custom scalar for `properties`.
- Subscriptions (no client/server-streaming GraphQL equivalent requested).
