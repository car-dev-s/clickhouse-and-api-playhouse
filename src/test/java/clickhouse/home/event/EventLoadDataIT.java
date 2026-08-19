package clickhouse.home.event;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Not a correctness test: a manual data-loading tool disguised as a test so it can reuse the
 * Spring context and {@link EventRepository}. Points at whatever ClickHouse the app's
 * application.yml resolves to (the docker-compose instance by default) rather than a
 * Testcontainers instance, so it's disabled by default — enable it locally against a running
 * `docker-compose up -d` server when you want sample data to poke at in the UI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Disabled("Manual data-generation tool; run explicitly against a running ClickHouse (docker-compose up -d)")
class EventLoadDataIT {

    private static final List<String> EVENT_TYPES = List.of("page_view", "click", "signup", "purchase", "logout");
    private static final int BATCH_SIZE = 500;

    @Autowired
    private EventRepository repository;

    @Test
    void generatesRandomEvents() {
        generateRandomEvents(1_000_000);
    }

    /**
     * ClickHouse writes each INSERT statement as a new immutable "part" on disk, which a
     * background thread later merges into larger parts. Row-by-row inserts therefore create
     * thousands of tiny parts (expensive merge pressure, slow), while one batched INSERT with
     * many value tuples creates a single part. This is why {@link EventRepository#insertAll}
     * exists and why the app never loops calling {@link EventRepository#insert} for bulk data.
     */
    @Test
    void batchInsertIsMuchFasterThanRowByRowInserts() {
        int count = 2_000;
        List<Event> events = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            events.add(randomEvent());
        }

        long rowByRowStart = System.nanoTime();
        for (Event event : events) {
            repository.insert(event); // one INSERT statement -> one part, per row
        }
        long rowByRowMillis = (System.nanoTime() - rowByRowStart) / 1_000_000;

        List<Event> batchEvents = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            batchEvents.add(randomEvent());
        }
        long batchStart = System.nanoTime();
        repository.insertAll(batchEvents); // one INSERT statement -> one part, for all rows
        long batchMillis = (System.nanoTime() - batchStart) / 1_000_000;

        System.out.printf(
                "row-by-row insert of %d events: %dms | single batched insert of %d events: %dms%n",
                count, rowByRowMillis, count, batchMillis);
    }

    /**
     * The {@code events} table is a {@code ReplacingMergeTree(updated_at)} ordered by
     * {@code event_id}. "Updating" a row means inserting a new row with the same {@code event_id}
     * and a newer {@code updated_at} — ClickHouse resolves duplicates lazily, only when parts
     * happen to merge in the background. Immediately after inserting several versions, a plain
     * read can still see stale/duplicate rows; appending {@code FINAL} (used throughout
     * {@link EventRepository}'s reads) forces reconciliation at query time, trading extra CPU
     * per query for always-correct results without waiting on a background merge.
     */
    @Test
    void replacingMergeTreeReconcilesDuplicateVersionsOnFinalRead() {
        UUID eventId = UUID.randomUUID();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        // Insert 5 versions of the *same* event_id in quick succession, each with a later
        // updated_at. On disk these sit as duplicate rows until a background merge runs.
        for (int version = 1; version <= 5; version++) {
            Instant updatedAt = createdAt.plusSeconds(version);
            Map<String, String> properties = Map.of("version", String.valueOf(version));
            repository.insert(new Event(eventId, "signup", "user-final-demo", properties, createdAt, updatedAt));
        }

        // repository.findById uses "... FROM events FINAL ..." so it reconciles at query time
        // and returns the highest-updated_at version, regardless of whether a merge has run yet.
        Event latest = repository.findById(eventId).orElseThrow();
        System.out.println("FINAL read returned version: " + latest.properties().get("version") + " (expected 5)");
    }

    /**
     * {@link EventRepository#mutateEventType} runs a literal {@code ALTER TABLE ... UPDATE}
     * mutation with {@code SETTINGS mutations_sync = 1}, which blocks until ClickHouse has
     * rewritten every affected part. Unlike the ReplacingMergeTree insert-a-new-version pattern
     * above, this touches existing on-disk data directly, so cost scales with how much of the
     * table the mutation's WHERE clause forces ClickHouse to rewrite — heavyweight even for a
     * single-row change once real data volume is loaded, which is why this project keeps it as a
     * contrast to the idiomatic pattern rather than the default "update" path.
     */
    @Test
    void alterTableUpdateMutationCostScalesWithTableSize() {
        generateRandomEvents(5_000); // pad the table so the mutation has real parts to rewrite

        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        repository.insert(new Event(eventId, "click", "user-mutation-demo", Map.of(), now, now));

        long mutationStart = System.nanoTime();
        repository.mutateEventType(eventId, "click_corrected");
        long mutationMillis = (System.nanoTime() - mutationStart) / 1_000_000;

        Event mutated = repository.findById(eventId).orElseThrow();
        System.out.printf("ALTER TABLE ... UPDATE (mutations_sync=1) took %dms; new event_type = %s%n",
                mutationMillis, mutated.eventType());
    }

    private void generateRandomEvents(int count) {
        List<Event> batch = new ArrayList<>(BATCH_SIZE);
        for (int i = 0; i < count; i++) {
            batch.add(randomEvent());
            if (batch.size() == BATCH_SIZE) {
                repository.insertAll(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            repository.insertAll(batch);
        }
    }

    private Event randomEvent() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                .minusSeconds(random.nextLong(0, 60 * 60 * 24 * 30));
        String eventType = EVENT_TYPES.get(random.nextInt(EVENT_TYPES.size()));
        String userId = "user-" + random.nextInt(1, 1_000);
        Map<String, String> properties = Map.of(
                "session", UUID.randomUUID().toString(),
                "value", String.valueOf(random.nextInt(1, 10_000))
        );
        return new Event(UUID.randomUUID(), eventType, userId, properties, now, now);
    }
}
