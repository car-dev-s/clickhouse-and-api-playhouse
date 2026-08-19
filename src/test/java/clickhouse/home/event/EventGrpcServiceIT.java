package clickhouse.home.event;

import clickhouse.home.grpc.event.CreateEventRequestProto;
import clickhouse.home.grpc.event.EventGrpcServiceGrpc;
import clickhouse.home.grpc.event.EventMessage;
import clickhouse.home.grpc.event.ListEventsRequestProto;
import clickhouse.home.support.ClickHouseIntegrationTest;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives EventGrpcService over an in-process gRPC channel - same idea as EventControllerIT
 * driving EventController over real HTTP, but no TCP port needed for the test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "grpc.server.in-process-name=test-events",
        "grpc.server.port=-1",
        "grpc.client.event-service.address=in-process:test-events"
})
class EventGrpcServiceIT extends ClickHouseIntegrationTest {

    @GrpcClient("event-service")
    private EventGrpcServiceGrpc.EventGrpcServiceBlockingStub stub;

    @Test
    void createsAnEventAndListsItBackOverGrpc() {
        EventMessage created = stub.createEvent(CreateEventRequestProto.newBuilder()
                .setEventType("purchase")
                .setUserId("user-grpc-1")
                .putProperties("sku", "abc-123")
                .build());

        assertThat(UUID.fromString(created.getEventId())).isNotNull();
        assertThat(created.getUserId()).isEqualTo("user-grpc-1");
        assertThat(created.getPropertiesMap()).containsEntry("sku", "abc-123");

        Iterator<EventMessage> listed = stub.listEvents(ListEventsRequestProto.newBuilder()
                .setEventType("purchase")
                .setUserId("user-grpc-1")
                .setLimit(10)
                .build());

        List<EventMessage> results = new java.util.ArrayList<>();
        listed.forEachRemaining(results::add);
        assertThat(results).extracting(EventMessage::getEventId).contains(created.getEventId());
    }
}
