package clickhouse.home.event;

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
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Talks to ClickHouse with plain SQL text over the client-v2 {@link Client}, rather than
 * POJO/row-binary marshalling, so the statements stay readable as ClickHouse-learning material.
 * `properties` is stored as a JSON string column and (de)serialized here at the boundary.
 */
@Repository
public class EventRepository {

    private static final DateTimeFormatter CLICKHOUSE_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final Client client;
    private final ObjectMapper objectMapper;

    public EventRepository(Client client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    public void insert(Event event) {
        insertAll(List.of(event));
    }

    public void insertAll(List<Event> events) {
        String values = events.stream().map(this::toValuesTuple).collect(Collectors.joining(", "));
        String sql = "INSERT INTO events (event_id, event_type, user_id, properties, created_at, updated_at) VALUES "
                + values;
        client.queryAll(sql);
    }

    public java.util.Optional<Event> findById(UUID eventId) {
        String sql = "SELECT event_id, event_type, user_id, properties, created_at, updated_at "
                + "FROM events FINAL WHERE event_id = '" + eventId + "' LIMIT 1";
        List<GenericRecord> rows = client.queryAll(sql);
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(toEvent(rows.get(0)));
    }

    public List<Event> find(String eventType, String userId, Instant from, Instant to, int limit, int offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT event_id, event_type, user_id, properties, created_at, updated_at FROM events FINAL WHERE 1 = 1");
        if (eventType != null) {
            sql.append(" AND event_type = '").append(escape(eventType)).append('\'');
        }
        if (userId != null) {
            sql.append(" AND user_id = '").append(escape(userId)).append('\'');
        }
        if (from != null) {
            sql.append(" AND created_at >= '").append(CLICKHOUSE_DATETIME.format(from)).append('\'');
        }
        if (to != null) {
            sql.append(" AND created_at <= '").append(CLICKHOUSE_DATETIME.format(to)).append('\'');
        }
        sql.append(" ORDER BY created_at DESC LIMIT ").append(limit).append(" OFFSET ").append(offset);

        return client.queryAll(sql.toString()).stream().map(this::toEvent).collect(Collectors.toList());
    }

    public List<EventTypeCount> countByEventType(Instant from, Instant to) {
        StringBuilder sql = new StringBuilder("SELECT event_type, count() AS cnt FROM events FINAL WHERE 1 = 1");
        if (from != null) {
            sql.append(" AND created_at >= '").append(CLICKHOUSE_DATETIME.format(from)).append('\'');
        }
        if (to != null) {
            sql.append(" AND created_at <= '").append(CLICKHOUSE_DATETIME.format(to)).append('\'');
        }
        sql.append(" GROUP BY event_type ORDER BY cnt DESC");

        List<GenericRecord> rows = client.queryAll(sql.toString());
        List<EventTypeCount> result = new ArrayList<>();
        for (GenericRecord row : rows) {
            result.add(new EventTypeCount(row.getString("event_type"), row.getLong("cnt")));
        }
        return result;
    }

    /** ALTER TABLE ... UPDATE mutation, run synchronously for demo/test determinism. */
    public void mutateEventType(UUID eventId, String newEventType) {
        String sql = "ALTER TABLE events UPDATE event_type = '" + escape(newEventType) + "' "
                + "WHERE event_id = '" + eventId + "' SETTINGS mutations_sync = 1";
        client.queryAll(sql);
    }

    private String toValuesTuple(Event event) {
        String propertiesJson = writeJson(event.properties());
        return "('" + event.eventId() + "', '"
                + escape(event.eventType()) + "', '"
                + escape(event.userId()) + "', '"
                + escape(propertiesJson) + "', '"
                + CLICKHOUSE_DATETIME.format(event.createdAt()) + "', '"
                + CLICKHOUSE_DATETIME.format(event.updatedAt()) + "')";
    }

    private Event toEvent(GenericRecord row) {
        return new Event(
                row.getUUID("event_id"),
                row.getString("event_type"),
                row.getString("user_id"),
                readJson(row.getString("properties")),
                row.getInstant("created_at"),
                row.getInstant("updated_at")
        );
    }

    private String writeJson(Map<String, String> properties) {
        try {
            return objectMapper.writeValueAsString(properties);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize event properties", e);
        }
    }

    private Map<String, String> readJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Unable to deserialize event properties: " + json, e);
        }
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    public record EventTypeCount(String eventType, long count) {
    }
}
