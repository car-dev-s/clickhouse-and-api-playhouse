package clickhouse.home.event;

import clickhouse.home.support.ClickHouseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventControllerIT extends ClickHouseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void createsReadsUpdatesAndMutatesAnEventOverHttp() {
        var createRequest = Map.of(
                "eventType", "purchase",
                "userId", "user-http-1",
                "properties", Map.of("sku", "abc-123")
        );

        ResponseEntity<EventDto> created = restTemplate.postForEntity("/api/events", createRequest, EventDto.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID eventId = created.getBody().eventId();

        ResponseEntity<EventDto> fetched = restTemplate.getForEntity("/api/events/" + eventId, EventDto.class);
        assertThat(fetched.getBody().userId()).isEqualTo("user-http-1");

        var updateRequest = Map.of(
                "eventType", "purchase",
                "userId", "user-http-1",
                "properties", Map.of("sku", "abc-123", "refunded", "true")
        );
        restTemplate.put("/api/events/" + eventId, updateRequest);

        ResponseEntity<EventDto> afterUpdate = restTemplate.getForEntity("/api/events/" + eventId, EventDto.class);
        assertThat(afterUpdate.getBody().properties()).containsEntry("refunded", "true");

        var mutateRequest = Map.of("eventType", "purchase_corrected");
        restTemplate.patchForObject("/api/events/" + eventId + "/mutate", mutateRequest, EventDto.class);

        ResponseEntity<EventDto> afterMutate = restTemplate.getForEntity("/api/events/" + eventId, EventDto.class);
        assertThat(afterMutate.getBody().eventType()).isEqualTo("purchase_corrected");
    }

    @Test
    void batchInsertAndListEndpointsWork() {
        var batchRequest = Map.of("events", List.of(
                Map.of("eventType", "batch_test", "userId", "user-http-2", "properties", Map.of()),
                Map.of("eventType", "batch_test", "userId", "user-http-3", "properties", Map.of())
        ));

        ResponseEntity<EventDto[]> created = restTemplate.postForEntity("/api/events/batch", batchRequest, EventDto[].class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).hasSize(2);

        ResponseEntity<EventDto[]> listed = restTemplate.getForEntity("/api/events?eventType=batch_test", EventDto[].class);
        assertThat(listed.getBody()).hasSizeGreaterThanOrEqualTo(2);
    }

    /** Minimal shape for deserializing EventResponse JSON in tests without depending on the main DTO package directly. */
    private record EventDto(UUID eventId, String eventType, String userId, Map<String, String> properties) {
    }
}
