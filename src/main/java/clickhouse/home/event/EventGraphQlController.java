package clickhouse.home.event;

import clickhouse.home.event.dto.CreateEventRequest;
import clickhouse.home.event.dto.EventStatsResponse;
import clickhouse.home.event.dto.MutateEventRequest;
import clickhouse.home.event.dto.UpdateEventRequest;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.data.method.annotation.*;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Same domain calls as {@link EventController} and {@link EventGrpcService}, exposed over
 * GraphQL instead. Delegates to the same {@link EventService} so this is purely an alternate
 * transport, not a parallel implementation - see GRAPHQL_TUTORIAL.md for the full walkthrough.
 */
@Controller
public class EventGraphQlController {

    private static final Logger log = LoggerFactory.getLogger(EventGraphQlController.class);

    private final EventService service;

    public EventGraphQlController(EventService service) {
        this.service = service;
    }

    @QueryMapping
    public Event event(@Argument String eventId) {
        log.debug("GraphQL event eventId={}", eventId);
        return service.getById(UUID.fromString(eventId));
    }

    @QueryMapping
    public List<Event> events(@Argument String eventType, @Argument String userId,
                               @Argument String from, @Argument String to,
                               @Argument int limit, @Argument int offset) {
        log.debug("GraphQL events eventType={} userId={} from={} to={} limit={} offset={}",
                eventType, userId, from, to, limit, offset);
        return service.find(eventType, userId, toInstant(from), toInstant(to), limit, offset);
    }

    @QueryMapping
    public EventStatsResponse eventStats(@Argument String from, @Argument String to) {
        log.debug("GraphQL eventStats from={} to={}", from, to);
        return service.stats(toInstant(from), toInstant(to));
    }

    @MutationMapping
    public Event createEvent(@Argument("input") Map<String, Object> input) {
        log.debug("GraphQL createEvent eventType={} userId={}", input.get("eventType"), input.get("userId"));
        return service.create(toCreateRequest(input));
    }

    @MutationMapping
    public List<Event> createEvents(@Argument("inputs") List<Map<String, Object>> inputs) {
        log.debug("GraphQL createEvents count={}", inputs.size());
        List<CreateEventRequest> requests = inputs.stream()
                .map(this::toCreateRequest)
                .collect(Collectors.toList());
        return service.createBatch(requests);
    }

    @MutationMapping
    public Event updateEvent(@Argument String eventId, @Argument("input") Map<String, Object> input) {
        log.debug("GraphQL updateEvent eventId={}", eventId);
        UpdateEventRequest request = new UpdateEventRequest(
                (String) input.get("eventType"), (String) input.get("userId"), toPropertiesMap(input));
        return service.replaceVersion(UUID.fromString(eventId), request);
    }

    @MutationMapping
    public Event mutateEvent(@Argument String eventId, @Argument("input") Map<String, Object> input) {
        log.debug("GraphQL mutateEvent eventId={} newEventType={}", eventId, input.get("eventType"));
        return service.mutate(UUID.fromString(eventId), new MutateEventRequest((String) input.get("eventType")));
    }

    /** Maps every {@code Event.properties} field access to the schema's [PropertyEntry!]! shape. */
    @SchemaMapping(typeName = "Event")
    public List<PropertyEntry> properties(Event event) {
        return toPropertyEntries(event.properties());
    }

    @GraphQlExceptionHandler
    public GraphQLError handle(ResponseStatusException ex, DataFetchingEnvironment env) {
        ErrorType errorType = ex.getStatusCode() == HttpStatus.NOT_FOUND
                ? ErrorType.NOT_FOUND
                : ErrorType.INTERNAL_ERROR;
        return GraphqlErrorBuilder.newError(env)
                .errorType(errorType)
                .message(ex.getReason() != null ? ex.getReason() : ex.getMessage())
                .build();
    }

    private CreateEventRequest toCreateRequest(Map<String, Object> input) {
        return new CreateEventRequest(
                (String) input.get("eventType"), (String) input.get("userId"), toPropertiesMap(input));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> toPropertiesMap(Map<String, Object> input) {
        List<Map<String, String>> entries = (List<Map<String, String>>) input.get("properties");
        Map<String, String> properties = new LinkedHashMap<>();
        if (entries != null) {
            for (Map<String, String> entry : entries) {
                properties.put(entry.get("key"), entry.get("value"));
            }
        }
        return properties;
    }

    private List<PropertyEntry> toPropertyEntries(Map<String, String> properties) {
        List<PropertyEntry> entries = new ArrayList<>();
        properties.forEach((key, value) -> entries.add(new PropertyEntry(key, value)));
        return entries;
    }

    private Instant toInstant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    public record PropertyEntry(String key, String value) {
    }
}
