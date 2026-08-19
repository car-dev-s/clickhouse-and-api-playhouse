package clickhouse.home.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record Event(
        UUID eventId,
        String eventType,
        String userId,
        Map<String, String> properties,
        Instant createdAt,
        Instant updatedAt
) {
}
