package clickhouse.home.metric;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Companion to {@code EventRepository}, backed by ClickHouse's *native* Map and JSON column
 * types instead of the JSON-string-column pattern used for {@code events.properties}. {@code
 * tags} round-trips as a real {@code Map(String, String)} - client-v2 decodes it straight into a
 * {@link Map}. {@code attributes} is a native JSON column, pushed down server-side for filtering
 * (see {@link #findByAttribute}) but read back as text and parsed with Jackson at the boundary,
 * same shape as {@code EventRepository.readJson}, so the two tables can be compared directly.
 */
@Repository
public class MetricRepository {

    private static final DateTimeFormatter CLICKHOUSE_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final Client client;
    private final ObjectMapper objectMapper;

    public MetricRepository(Client client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    public void insert(Metric metric) {
        insertAll(List.of(metric));
    }

    public void insertAll(List<Metric> metrics) {
        String values = metrics.stream().map(this::toValuesTuple).collect(Collectors.joining(", "));
        String sql = "INSERT INTO device_metrics (metric_id, device_id, metric_name, tags, attributes, recorded_at) VALUES "
                + values;
        client.queryAll(sql);
    }

    public Optional<Metric> findById(UUID metricId) {
        String sql = selectColumns() + " WHERE metric_id = '" + metricId + "' LIMIT 1";
        List<GenericRecord> rows = client.queryAll(sql);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toMetric(rows.get(0)));
    }

    /** Native Map access: {@code tags['key'] = value}, pushed down server-side - no JSON parsing involved. */
    public List<Metric> findByTag(String tagKey, String tagValue) {
        String sql = selectColumns()
                + " WHERE tags['" + escape(tagKey) + "'] = '" + escape(tagValue) + "'"
                + " ORDER BY recorded_at DESC";
        return client.queryAll(sql).stream().map(this::toMetric).collect(Collectors.toList());
    }

    /**
     * Native JSON subcolumn access: {@code attributes.status} reads that field straight off disk
     * without parsing the whole blob, unlike {@code JSONExtractString(properties, 'status')} on
     * the events table's string column.
     */
    public List<Metric> findByAttribute(String jsonPath, String value) {
        requireSafeIdentifierPath(jsonPath);
        String sql = selectColumns()
                + " WHERE attributes." + jsonPath + " = '" + escape(value) + "'"
                + " ORDER BY recorded_at DESC";
        return client.queryAll(sql).stream().map(this::toMetric).collect(Collectors.toList());
    }

    /** Distinct tag values for a key, via native Map access - no JSON functions needed. */
    public List<TagCount> countByTagValue(String tagKey) {
        String sql = "SELECT tags['" + escape(tagKey) + "'] AS tag_value, count() AS cnt "
                + "FROM device_metrics GROUP BY tag_value ORDER BY cnt DESC";
        List<GenericRecord> rows = client.queryAll(sql);
        List<TagCount> result = new ArrayList<>();
        for (GenericRecord row : rows) {
            result.add(new TagCount(row.getString("tag_value"), row.getLong("cnt")));
        }
        return result;
    }

    private String selectColumns() {
        return "SELECT metric_id, device_id, metric_name, tags, CAST(attributes AS String) AS attributes, recorded_at "
                + "FROM device_metrics";
    }

    private String toValuesTuple(Metric metric) {
        return "('" + metric.metricId() + "', '"
                + escape(metric.deviceId()) + "', '"
                + escape(metric.metricName()) + "', "
                + toMapLiteral(metric.tags()) + ", '"
                + escape(writeJson(metric.attributes())) + "', '"
                + CLICKHOUSE_DATETIME.format(metric.recordedAt()) + "')";
    }

    private String toMapLiteral(Map<String, String> tags) {
        if (tags.isEmpty()) {
            return "map()";
        }
        String entries = tags.entrySet().stream()
                .map(e -> "'" + escape(e.getKey()) + "', '" + escape(e.getValue()) + "'")
                .collect(Collectors.joining(", "));
        return "map(" + entries + ")";
    }

    @SuppressWarnings("unchecked")
    private Metric toMetric(GenericRecord row) {
        return new Metric(
                row.getUUID("metric_id"),
                row.getString("device_id"),
                row.getString("metric_name"),
                (Map<String, String>) row.getObject("tags"),
                readJson(row.getString("attributes")),
                row.getInstant("recorded_at")
        );
    }

    private String writeJson(Map<String, Object> attributes) {
        try {
            return objectMapper.writeValueAsString(attributes);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize metric attributes", e);
        }
    }

    private Map<String, Object> readJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Unable to deserialize metric attributes: " + json, e);
        }
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    /**
     * {@code jsonPath} is interpolated as a raw SQL subcolumn path (e.g. {@code status} or
     * {@code nested.field}), not a quoted literal, so it can't be escaped the way string values
     * are - restrict it to an identifier-safe character set instead.
     */
    private void requireSafeIdentifierPath(String jsonPath) {
        if (!jsonPath.matches("[A-Za-z0-9_.]+")) {
            throw new IllegalArgumentException("Unsafe JSON path: " + jsonPath);
        }
    }

    public record TagCount(String tagValue, long count) {
    }
}
