package clickhouse.home.metric;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record Metric(
        UUID metricId,
        String deviceId,
        String metricName,
        Map<String, String> tags,
        Map<String, Object> attributes,
        Instant recordedAt
) {
}
