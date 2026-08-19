package clickhouse.home.event.dto;

import java.util.List;

public record EventStatsResponse(List<EventTypeCount> counts) {
    public record EventTypeCount(String eventType, long count) {
    }
}
