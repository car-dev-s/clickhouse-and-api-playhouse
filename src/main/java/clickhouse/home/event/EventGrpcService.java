package clickhouse.home.event;

import clickhouse.home.event.dto.CreateEventRequest;
import clickhouse.home.grpc.event.CreateEventRequestProto;
import clickhouse.home.grpc.event.EventGrpcServiceGrpc;
import clickhouse.home.grpc.event.EventMessage;
import clickhouse.home.grpc.event.ListEventsRequestProto;
import com.google.protobuf.Timestamp;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Same domain calls as {@link EventController}, exposed over gRPC instead of REST. Delegates to
 * the same {@link EventService} so this is purely an alternate transport, not a parallel
 * implementation - see TUTORIAL.md's gRPC section for why.
 */
@GrpcService
public class EventGrpcService extends EventGrpcServiceGrpc.EventGrpcServiceImplBase {

    private final EventService service;

    public EventGrpcService(EventService service) {
        this.service = service;
    }

    @Override
    public void createEvent(CreateEventRequestProto request, StreamObserver<EventMessage> responseObserver) {
        Event event = service.create(new CreateEventRequest(
                request.getEventType(), request.getUserId(), request.getPropertiesMap()));
        responseObserver.onNext(toMessage(event));
        responseObserver.onCompleted();
    }

    @Override
    public void listEvents(ListEventsRequestProto request, StreamObserver<EventMessage> responseObserver) {
        String eventType = request.getEventType().isEmpty() ? null : request.getEventType();
        String userId = request.getUserId().isEmpty() ? null : request.getUserId();
        Instant from = request.hasFrom() ? toInstant(request.getFrom()) : null;
        Instant to = request.hasTo() ? toInstant(request.getTo()) : null;
        int limit = request.getLimit() > 0 ? request.getLimit() : 50;

        List<Event> events = service.find(eventType, userId, from, to, limit, request.getOffset());
        for (Event event : events) {
            responseObserver.onNext(toMessage(event));
        }
        responseObserver.onCompleted();
    }

    private EventMessage toMessage(Event event) {
        return EventMessage.newBuilder()
                .setEventId(event.eventId().toString())
                .setEventType(event.eventType())
                .setUserId(event.userId())
                .putAllProperties(event.properties())
                .setCreatedAt(toTimestamp(event.createdAt()))
                .setUpdatedAt(toTimestamp(event.updatedAt()))
                .build();
    }

    private Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private Instant toInstant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }
}
