package clickhouse.home.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record CreateEventRequest(
        @NotBlank String eventType,
        @NotBlank String userId,
        @NotNull Map<String, String> properties
) {
}
