package clickhouse.home.event.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Demonstrates a literal ALTER TABLE ... UPDATE mutation, as a heavyweight
 * alternative to the ReplacingMergeTree pattern used by UpdateEventRequest.
 */
public record MutateEventRequest(
        @NotBlank String eventType
) {
}
