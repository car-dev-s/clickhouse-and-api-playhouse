package clickhouse.home.event.dto;

import clickhouse.home.event.Event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EventResponse(
        UUID eventId,
        String eventType,
        String userId,
        Map<String, String> properties,
        Instant createdAt,
        Instant updatedAt
) {
    public static EventResponse from(Event event) {
        return new EventResponse(
                event.eventId(),
                event.eventType(),
                event.userId(),
                event.properties(),
                event.createdAt(),
                event.updatedAt()
        );
    }
}
