package clickhouse.home.event;

import clickhouse.home.support.ClickHouseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventRepositoryIT extends ClickHouseIntegrationTest {

    @Autowired
    private EventRepository repository;

    @Test
    void insertsAndReadsBackAnEvent() {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Event event = new Event(eventId, "page_view", "user-1", Map.of("path", "/home"), now, now);

        repository.insert(event);

        Event found = repository.findById(eventId).orElseThrow();
        assertThat(found.eventType()).isEqualTo("page_view");
        assertThat(found.userId()).isEqualTo("user-1");
        assertThat(found.properties()).containsEntry("path", "/home");
    }

    @Test
    void insertsAndFiltersABatch() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        List<Event> events = List.of(
                new Event(UUID.randomUUID(), "click", "user-2", Map.of(), now, now),
                new Event(UUID.randomUUID(), "click", "user-3", Map.of(), now, now),
                new Event(UUID.randomUUID(), "page_view", "user-2", Map.of(), now, now)
        );

        repository.insertAll(events);

        List<Event> clicks = repository.find("click", null, null, null, 50, 0);
        assertThat(clicks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(clicks).allMatch(e -> e.eventType().equals("click"));
    }

    @Test
    void replacingMergeTreePatternKeepsLatestVersionOnFinalRead() {
        UUID eventId = UUID.randomUUID();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        repository.insert(new Event(eventId, "signup", "user-4", Map.of("plan", "free"), createdAt, createdAt));

        Instant updatedAt = createdAt.plusSeconds(60);
        repository.insert(new Event(eventId, "signup", "user-4", Map.of("plan", "pro"), createdAt, updatedAt));

        Event latest = repository.findById(eventId).orElseThrow();
        assertThat(latest.properties()).containsEntry("plan", "pro");
        assertThat(latest.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void alterTableUpdateMutationChangesEventTypeInPlace() {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        repository.insert(new Event(eventId, "click", "user-5", Map.of(), now, now));

        repository.mutateEventType(eventId, "click_corrected");

        Event mutated = repository.findById(eventId).orElseThrow();
        assertThat(mutated.eventType()).isEqualTo("click_corrected");
    }

    @Test
    void countsEventsByType() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String marker = UUID.randomUUID().toString();
        repository.insertAll(List.of(
                new Event(UUID.randomUUID(), marker, "user-6", Map.of(), now, now),
                new Event(UUID.randomUUID(), marker, "user-7", Map.of(), now, now)
        ));

        List<EventRepository.EventTypeCount> counts = repository.countByEventType(now.minusSeconds(5), now.plusSeconds(5));

        assertThat(counts).anySatisfy(c -> {
            assertThat(c.eventType()).isEqualTo(marker);
            assertThat(c.count()).isEqualTo(2L);
        });
    }
}
