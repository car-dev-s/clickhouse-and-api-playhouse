package clickhouse.home.event;

import clickhouse.home.event.dto.CreateEventRequest;
import clickhouse.home.event.dto.EventStatsResponse;
import clickhouse.home.event.dto.MutateEventRequest;
import clickhouse.home.event.dto.UpdateEventRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class EventService {

    private final EventRepository repository;

    public EventService(EventRepository repository) {
        this.repository = repository;
    }

    public Event create(CreateEventRequest request) {
        Instant now = nowAtColumnPrecision();
        Event event = new Event(UUID.randomUUID(), request.eventType(), request.userId(),
                request.properties(), now, now);
        repository.insert(event);
        return event;
    }

    public List<Event> createBatch(List<CreateEventRequest> requests) {
        Instant now = nowAtColumnPrecision();
        List<Event> events = requests.stream()
                .map(r -> new Event(UUID.randomUUID(), r.eventType(), r.userId(), r.properties(), now, now))
                .collect(Collectors.toList());
        repository.insertAll(events);
        return events;
    }

    public Event getById(UUID eventId) {
        return repository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found: " + eventId));
    }

    public List<Event> find(String eventType, String userId, Instant from, Instant to, int limit, int offset) {
        return repository.find(eventType, userId, from, to, limit, offset);
    }

    public EventStatsResponse stats(Instant from, Instant to) {
        List<EventStatsResponse.EventTypeCount> counts = repository.countByEventType(from, to).stream()
                .map(c -> new EventStatsResponse.EventTypeCount(c.eventType(), c.count()))
                .collect(Collectors.toList());
        return new EventStatsResponse(counts);
    }

    /** Idiomatic ClickHouse "update": insert a new version of the row for ReplacingMergeTree to reconcile. */
    public Event replaceVersion(UUID eventId, UpdateEventRequest request) {
        Event existing = getById(eventId);
        Event updated = new Event(eventId, request.eventType(), request.userId(),
                request.properties(), existing.createdAt(), nowAtColumnPrecision());
        repository.insert(updated);
        return updated;
    }

    /** Literal ALTER TABLE ... UPDATE mutation demo. */
    public Event mutate(UUID eventId, MutateEventRequest request) {
        getById(eventId);
        repository.mutateEventType(eventId, request.eventType());
        return getById(eventId);
    }

    /** The events table is DateTime64(3); truncate here so in-memory instants match round-tripped ones. */
    private static Instant nowAtColumnPrecision() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }
}
