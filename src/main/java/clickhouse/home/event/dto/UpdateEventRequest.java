package clickhouse.home.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Idiomatic ClickHouse "update": the service inserts a new row for the same
 * eventId with a newer updatedAt version; ReplacingMergeTree reconciles it later.
 */
public record UpdateEventRequest(
        @NotBlank String eventType,
        @NotBlank String userId,
        @NotNull Map<String, String> properties
) {
}
