# GraphQL Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add GraphQL as a third transport onto the existing `EventService`, with full parity to REST/gRPC, plus a standalone GraphQL tutorial file, a companion "GraphQL Field Guide" artifact carrying the REST vs. gRPC vs. GraphQL comparison, and a minimal CLAUDE.md update.

**Architecture:** `EventGraphQlController` (Spring for GraphQL, schema-first) sits alongside `EventController` (REST) and `EventGrpcService` (gRPC), all three delegating to the same `EventService` — no business logic lives in the controller. `properties` (`Map<String,String>`) is represented in GraphQL as `[PropertyEntry!]!` since GraphQL has no map scalar.

**Tech Stack:** Spring Boot 3.5, `org.springframework.boot:spring-boot-starter-graphql` (schema-first GraphQL over `graphql-java`), `spring-graphql-test` for `HttpGraphQlTester`-based integration tests, Testcontainers ClickHouse (existing `ClickHouseIntegrationTest` base).

**Spec:** `docs/superpowers/specs/2026-08-19-graphql-layer-design.md`

## Global Constraints

- No `graphql-java-extended-scalars` or other extra scalar dependency — `properties` uses `[PropertyEntry!]!` / `[PropertyEntryInput!]`, built-in scalars only.
- `eventId` uses the built-in `ID` scalar; timestamps (`createdAt`/`updatedAt`/`from`/`to`) are ISO-8601 `String`, not a custom `DateTime` scalar.
- No Relay-style cursor pagination — `events(limit, offset)` mirrors REST/gRPC's simple limit/offset.
- No subscriptions.
- This project has no `.git` repository — every task's "commit" step is replaced with "no VCS; proceed to next task."
- CLAUDE.md edits (Task 6) must be net-minimal: add only what's needed for the new controller, and trim existing lines where possible without losing information.

---

### Task 1: GraphQL dependency, schema contract, and config

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/resources/graphql/schema.graphqls`

**Interfaces:**
- Produces: the GraphQL schema contract (`Event`, `PropertyEntry`, `PropertyEntryInput`, `EventTypeCount`, `EventStats`, `CreateEventInput`, `UpdateEventInput`, `MutateEventInput`, `Query`, `Mutation`) that Task 2's controller must implement every field of, or Spring GraphQL's startup schema-mapping inspection fails.

- [ ] **Step 1: Add the GraphQL starter and test dependency to `build.gradle`**

In the `dependencies { ... }` block, after the existing `implementation 'org.springframework.boot:spring-boot-starter-validation'` line, add:

```groovy
    implementation 'org.springframework.boot:spring-boot-starter-graphql'
```

In the same block, after `testImplementation "io.grpc:grpc-inprocess:${grpcVersion}"`, add:

```groovy
    testImplementation 'org.springframework.graphql:spring-graphql-test'
```

- [ ] **Step 2: Enable GraphiQL in `application.yml`**

Add a new top-level block (after the `grpc:` block, before `clickhouse:`):

```yaml
spring:
  graphql:
    graphiql:
      enabled: true
```

- [ ] **Step 3: Write the schema contract**

Create `src/main/resources/graphql/schema.graphqls` with exactly this content:

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

- [ ] **Step 4: Verify the module still compiles**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`. (The schema file has no resolvers yet — that's fine, `compileJava` doesn't perform GraphQL runtime wiring validation; that only happens on Spring context startup, which Task 3's test will exercise once Task 2 adds the controller.)

- [ ] **Step 5: No VCS**

This project has no `.git` repository — skip commit, proceed to Task 2.

---

### Task 2: `EventGraphQlController`

**Files:**
- Create: `src/main/java/clickhouse/home/event/EventGraphQlController.java`

**Interfaces:**
- Consumes: `EventService` — `create(CreateEventRequest)`, `createBatch(List<CreateEventRequest>)`, `getById(UUID)`, `find(String,String,Instant,Instant,int,int)`, `stats(Instant,Instant)`, `replaceVersion(UUID,UpdateEventRequest)`, `mutate(UUID,MutateEventRequest)` — all already exist in `EventService` (`src/main/java/clickhouse/home/event/EventService.java`). `Event` record: `eventId, eventType, userId, properties (Map<String,String>), createdAt, updatedAt` (all `Instant`/`UUID`/`String`/`Map<String,String>`). DTOs: `CreateEventRequest(eventType, userId, properties)`, `UpdateEventRequest(eventType, userId, properties)`, `MutateEventRequest(eventType)`, `EventStatsResponse(counts: List<EventTypeCount>)` where `EventTypeCount(eventType, count)`.
- Produces: resolves every field declared in Task 1's `schema.graphqls` — required for the Spring context to start at all (Task 3's test boots the full app).

- [ ] **Step 1: Write the controller**

```java
package clickhouse.home.event;

import clickhouse.home.event.dto.CreateEventRequest;
import clickhouse.home.event.dto.EventStatsResponse;
import clickhouse.home.event.dto.MutateEventRequest;
import clickhouse.home.event.dto.UpdateEventRequest;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Same domain calls as {@link EventController} and {@link EventGrpcService}, exposed over
 * GraphQL instead. Delegates to the same {@link EventService} so this is purely an alternate
 * transport, not a parallel implementation - see GRAPHQL_TUTORIAL.md for the full walkthrough.
 */
@Controller
public class EventGraphQlController {

    private final EventService service;

    public EventGraphQlController(EventService service) {
        this.service = service;
    }

    @QueryMapping
    public Event event(@Argument String eventId) {
        return service.getById(UUID.fromString(eventId));
    }

    @QueryMapping
    public List<Event> events(@Argument String eventType, @Argument String userId,
                               @Argument String from, @Argument String to,
                               @Argument int limit, @Argument int offset) {
        return service.find(eventType, userId, toInstant(from), toInstant(to), limit, offset);
    }

    @QueryMapping
    public EventStatsResponse eventStats(@Argument String from, @Argument String to) {
        return service.stats(toInstant(from), toInstant(to));
    }

    @MutationMapping
    public Event createEvent(@Argument("input") Map<String, Object> input) {
        return service.create(toCreateRequest(input));
    }

    @MutationMapping
    public List<Event> createEvents(@Argument("inputs") List<Map<String, Object>> inputs) {
        List<CreateEventRequest> requests = inputs.stream()
                .map(this::toCreateRequest)
                .collect(Collectors.toList());
        return service.createBatch(requests);
    }

    @MutationMapping
    public Event updateEvent(@Argument String eventId, @Argument("input") Map<String, Object> input) {
        UpdateEventRequest request = new UpdateEventRequest(
                (String) input.get("eventType"), (String) input.get("userId"), toPropertiesMap(input));
        return service.replaceVersion(UUID.fromString(eventId), request);
    }

    @MutationMapping
    public Event mutateEvent(@Argument String eventId, @Argument("input") Map<String, Object> input) {
        return service.mutate(UUID.fromString(eventId), new MutateEventRequest((String) input.get("eventType")));
    }

    /** Maps every {@code Event.properties} field access to the schema's [PropertyEntry!]! shape. */
    public List<PropertyEntry> properties(Event event) {
        return toPropertyEntries(event.properties());
    }

    @GraphQlExceptionHandler
    public GraphQLError handle(ResponseStatusException ex, DataFetchingEnvironment env) {
        return GraphqlErrorBuilder.newError(env)
                .message(ex.getReason())
                .build();
    }

    private CreateEventRequest toCreateRequest(Map<String, Object> input) {
        return new CreateEventRequest(
                (String) input.get("eventType"), (String) input.get("userId"), toPropertiesMap(input));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> toPropertiesMap(Map<String, Object> input) {
        List<Map<String, String>> entries = (List<Map<String, String>>) input.get("properties");
        Map<String, String> properties = new HashMap<>();
        if (entries != null) {
            for (Map<String, String> entry : entries) {
                properties.put(entry.get("key"), entry.get("value"));
            }
        }
        return properties;
    }

    private List<PropertyEntry> toPropertyEntries(Map<String, String> properties) {
        List<PropertyEntry> entries = new ArrayList<>();
        properties.forEach((key, value) -> entries.add(new PropertyEntry(key, value)));
        return entries;
    }

    private Instant toInstant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    public record PropertyEntry(String key, String value) {
    }
}
```

- [ ] **Step 2: Verify the module compiles**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: No VCS**

This project has no `.git` repository — skip commit, proceed to Task 3.

---

### Task 3: `EventGraphQlControllerIT`

**Files:**
- Create: `src/test/java/clickhouse/home/event/EventGraphQlControllerIT.java`

**Interfaces:**
- Consumes: `ClickHouseIntegrationTest` base class (`src/test/java/clickhouse/home/support/ClickHouseIntegrationTest.java`) — starts Testcontainers ClickHouse, wires `clickhouse.*` properties, `@SpringBootTest(webEnvironment = RANDOM_PORT)`. `org.springframework.graphql.test.tester.HttpGraphQlTester`, autoconfigured against the random port when `spring-graphql-test` + `spring-boot-starter-graphql` are both on the test classpath and a `WebTestClient`-capable server is up (Spring Boot auto-configures `HttpGraphQlTester` for `@SpringBootTest(webEnvironment = RANDOM_PORT)`).

- [ ] **Step 1: Write the test**

```java
package clickhouse.home.event;

import clickhouse.home.support.ClickHouseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.graphql.test.tester.HttpGraphQlTester;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventGraphQlControllerIT extends ClickHouseIntegrationTest {

    @Autowired
    private HttpGraphQlTester graphQlTester;

    @Test
    void createsReadsUpdatesAndMutatesAnEventOverGraphQl() {
        String createMutation = """
                mutation {
                  createEvent(input: {eventType: "purchase", userId: "user-gql-1", properties: [{key: "sku", value: "abc-123"}]}) {
                    eventId eventType userId properties { key value }
                  }
                }
                """;

        String eventId = graphQlTester.document(createMutation).execute()
                .path("createEvent.eventId").entity(String.class).get();

        graphQlTester.document(createMutation).execute()
                .path("createEvent.properties[0].key").entity(String.class).isEqualTo("sku");

        String eventQuery = """
                query($id: ID!) {
                  event(eventId: $id) { eventId userId eventType }
                }
                """;
        graphQlTester.document(eventQuery).variable("id", eventId).execute()
                .path("event.userId").entity(String.class).isEqualTo("user-gql-1");

        String updateMutation = """
                mutation($id: ID!) {
                  updateEvent(eventId: $id, input: {eventType: "purchase", userId: "user-gql-1", properties: [{key: "refunded", value: "true"}]}) {
                    properties { key value }
                  }
                }
                """;
        graphQlTester.document(updateMutation).variable("id", eventId).execute()
                .path("updateEvent.properties[0].value").entity(String.class).isEqualTo("true");

        String mutateMutation = """
                mutation($id: ID!) {
                  mutateEvent(eventId: $id, input: {eventType: "purchase_corrected"}) { eventType }
                }
                """;
        graphQlTester.document(mutateMutation).variable("id", eventId).execute()
                .path("mutateEvent.eventType").entity(String.class).isEqualTo("purchase_corrected");
    }

    @Test
    void missingEventReturnsAGraphQlError() {
        String eventQuery = """
                query($id: ID!) {
                  event(eventId: $id) { eventId }
                }
                """;

        graphQlTester.document(eventQuery)
                .variable("id", "00000000-0000-0000-0000-000000000000")
                .execute()
                .errors().expect(error -> error.getMessage().contains("Event not found"));
    }

    @Test
    void batchCreateListAndStatsWork() {
        String batchMutation = """
                mutation {
                  createEvents(inputs: [
                    {eventType: "batch_gql", userId: "user-gql-2", properties: []},
                    {eventType: "batch_gql", userId: "user-gql-3", properties: []}
                  ]) { eventId eventType }
                }
                """;
        List<String> created = graphQlTester.document(batchMutation).execute()
                .path("createEvents[*].eventType").entityList(String.class).get();
        assertThat(created).containsExactly("batch_gql", "batch_gql");

        String listQuery = """
                query {
                  events(eventType: "batch_gql", limit: 10) { userId }
                }
                """;
        List<String> users = graphQlTester.document(listQuery).execute()
                .path("events[*].userId").entityList(String.class).get();
        assertThat(users).hasSizeGreaterThanOrEqualTo(2);

        String statsQuery = """
                query {
                  eventStats { counts { eventType count } }
                }
                """;
        graphQlTester.document(statsQuery).execute()
                .path("eventStats.counts").entityList(Map.class).get();
    }
}
```

- [ ] **Step 2: Run the tests and verify they fail before this task's dependencies were in place**

(Skip if Tasks 1-2 are already applied — this step documents the TDD check for anyone replaying the plan from scratch.) Run: `./gradlew test --tests "clickhouse.home.event.EventGraphQlControllerIT"` against a tree with only Task 1 applied (no controller). Expected: Spring context fails to start with a schema-mapping error naming unresolved fields (`Query.event`, etc.).

- [ ] **Step 3: Run the tests against the full implementation**

Ensure Docker is running. Run: `./gradlew test --tests "clickhouse.home.event.EventGraphQlControllerIT"`
Expected: `BUILD SUCCESSFUL`, all three tests pass.

- [ ] **Step 4: Run the full test suite to confirm no regressions**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: No VCS**

This project has no `.git` repository — skip commit, proceed to Task 4.

---

### Task 4: `GRAPHQL_TUTORIAL.md`

**Files:**
- Create: `GRAPHQL_TUTORIAL.md`
- Modify: `TUTORIAL.md` (add one cross-link line)

**Interfaces:**
- Consumes: `TUTORIAL.md`'s existing voice/structure (numbered `##` sections, code-block-driven, cross-references files by name) and §7's gRPC section as the tonal model. `schema.graphqls` (Task 1) and `EventGraphQlController` (Task 2) as the source of truth for every example shown.

- [ ] **Step 1: Write `GRAPHQL_TUTORIAL.md`**

Create the file as a sibling to `TUTORIAL.md`, matching its heading style (`# GraphQL + Java/Spring Boot Tutorial`, then `## 1. ...` sections). Required sections, each grounded in this project's actual code (no generic/hypothetical examples):

1. **What GraphQL is, schema-first vs. proto-first/REST** — one query endpoint, client picks the shape of the response; contrast briefly with REST's fixed-shape-per-endpoint and gRPC's fixed proto messages.
2. **The schema contract** — walk through `src/main/resources/graphql/schema.graphqls` field by field: why `properties` is `[PropertyEntry!]!` instead of a map (GraphQL has no map scalar), why `eventId` is `ID!`, why timestamps are plain ISO-8601 `String`.
3. **Query vs. Mutation** — map `event`/`events`/`eventStats` (reads) and `createEvent`/`createEvents`/`updateEvent`/`mutateEvent` (writes) to their `EventGraphQlController` methods and, in turn, to the same `EventService` calls REST/gRPC already use — reinforce "one service, three transports."
4. **Error handling** — the `@GraphQlExceptionHandler` in `EventGraphQlController`, and how a not-found event surfaces as a GraphQL `errors[]` entry instead of an HTTP 404.
5. **Trying it out** — GraphiQL at `http://localhost:8080/graphiql` (enabled via `spring.graphql.graphiql.enabled: true`), plus a raw `curl -X POST localhost:8080/graphql -H 'Content-Type: application/json' -d '{"query": "..."}'` example using the `createEvent` mutation from Task 3's test.
6. **Testing** — `EventGraphQlControllerIT` and `HttpGraphQlTester`, one sentence, cross-referencing the existing "Testing" section pattern.

End the file with a one-line pointer: "For the REST vs. gRPC vs. GraphQL comparison, see the GraphQL Field Guide artifact." (Task 5 fills in the actual link once published — until then leave the sentence without a URL.)

- [ ] **Step 2: Cross-link from `TUTORIAL.md`**

In `TUTORIAL.md`, in the intro paragraph (currently ending "...event/log-analytics data in ClickHouse."), add one sentence: "A third transport, GraphQL, is covered separately in `GRAPHQL_TUTORIAL.md`."

- [ ] **Step 3: Proofread against the actual code**

Re-open `schema.graphqls` and `EventGraphQlController.java` side-by-side with the new tutorial; confirm every field name, argument name, and code sample matches exactly (copy-paste from the real files rather than retyping).

- [ ] **Step 4: No VCS**

This project has no `.git` repository — skip commit, proceed to Task 5.

---

### Task 5: GraphQL Field Guide artifact

**Files:**
- N/A (published via the Artifact tool, not a repo file) — working file in the scratchpad directory before publishing.

**Interfaces:**
- Consumes: the existing gRPC Field Guide artifact (already published this session — find its URL via `Artifact` `action: "list"` if not already known) as the structural/visual model. `EventController.java`, `EventGrpcService.java`, `EventGraphQlController.java` (Task 2) as the source of truth for the comparison's code snippets.

- [ ] **Step 1: Load the `artifact-design` skill**

Per the Artifact tool's requirement, invoke the `artifact-design` skill before writing the HTML, to calibrate design investment consistent with the existing gRPC Field Guide.

- [ ] **Step 2: Draft the HTML**

Write an HTML file to the scratchpad directory titled "GraphQL Field Guide," matching the gRPC Field Guide's section structure and visual system (same fonts/palette/diagram style for series consistency). Required content:

1. GraphQL mechanics (schema, resolvers, query/mutation) — the general concept, briefly.
2. This project's schema, anchored to `schema.graphqls` and `EventGraphQlController`, same treatment the gRPC guide gives `event.proto`/`EventGrpcService`.
3. **The REST vs. gRPC vs. GraphQL comparison** (the piece explicitly requested) — a table or card layout contrasting, using real code from all three controllers: request/response shape, endpoint style (`/api/events` vs. proto RPC vs. single `/graphql`), typing (JSON/OpenAPI vs. `.proto` vs. GraphQL SDL), over-/under-fetching, tooling (curl vs. `grpcurl` vs. GraphiQL), and when each is the right choice.
4. "Trying it out" — GraphiQL screenshot-style callout or embedded example query, consistent with the gRPC guide's `grpcurl` section.

- [ ] **Step 3: Publish**

Call `Artifact` with the drafted file, a `title` of "GraphQL Field Guide" (only if the HTML lacks its own `<title>`), a one-sentence `description`, and a `favicon` (reuse or complement the gRPC guide's emoji choice — do not change an existing artifact's favicon, this is a new artifact so pick fresh).

- [ ] **Step 4: Backfill the tutorial link**

Update the pointer sentence added at the end of `GRAPHQL_TUTORIAL.md` in Task 4 with the real published URL.

- [ ] **Step 5: No VCS**

This project has no `.git` repository — skip commit, proceed to Task 6.

---

### Task 6: Minimal CLAUDE.md update

**Files:**
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: final state of `CLAUDE.md` (read above) — Architecture section's existing gRPC bullet (`- **`EventGrpcService`**  — ...`) is the direct template for the new bullet.

- [ ] **Step 1: Add one Architecture bullet**

In the `## Architecture` section, immediately after the existing `EventGrpcService` bullet, insert:

```markdown
- **`EventGraphQlController`** — a third transport onto the same `EventService`, exposed over GraphQL (`/graphql`, GraphiQL at `/graphiql`) instead of REST/gRPC. Schema lives in `src/main/resources/graphql/schema.graphqls`; see `GRAPHQL_TUTORIAL.md` for the full walkthrough (schema-first design, query vs. mutation, `[PropertyEntry!]!` in place of a map scalar).
```

- [ ] **Step 2: Add the doc pointer to the intro paragraph**

In the "Project purpose" paragraph, the sentence currently reads: "See `TUTORIAL.md` for the full concept walkthrough (...) — read it before making schema or repository changes...". Append, in the same sentence's spirit but as a short addition: "A third transport, GraphQL, has its own `GRAPHQL_TUTORIAL.md`."

- [ ] **Step 3: Trim the file for net-minimal size**

Re-read the full file with fresh eyes and shorten anywhere meaning is preserved — collapse repeated phrasing, cut words that don't change what a reader would do differently (e.g., tighten "This is deliberate: it means local dev (docker-compose) and tests (Testcontainers) provision schema through the exact same code path, with no separate SQL init script to keep in sync." only if a shorter phrasing loses nothing). Do not cut content that changes behavior guidance (JDK resolution steps, test commands, the two-update-paths explanation) — trim wording, not information. Net file growth should be roughly one bullet + one short sentence, offset by any trims found.

- [ ] **Step 4: No VCS**

This project has no `.git` repository — skip commit. Plan complete.

---

## Self-Review Notes

- **Spec coverage:** dependencies (Task 1), schema (Task 1), controller + error handling (Task 2), config/GraphiQL (Task 1), testing (Task 3), tutorial file (Task 4), artifact with comparison (Task 5), CLAUDE.md minimal update (Task 6) — all spec sections have a task.
- **Type consistency checked:** `EventGraphQlController` method names/args (`event`, `events`, `eventStats`, `createEvent`, `createEvents`, `updateEvent`, `mutateEvent`, `PropertyEntry`) match the schema field names in Task 1 and the test's GraphQL documents in Task 3 exactly.
- **No git repo:** every task's would-be commit step is replaced with an explicit no-op note, per the environment's actual state (confirmed via `git status` returning "not a git repository").
