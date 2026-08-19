package clickhouse.home.event;

import clickhouse.home.event.dto.*;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);

    private final EventService service;

    public EventController(EventService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventResponse create(@Valid @RequestBody CreateEventRequest request) {
        log.debug("REST createEvent eventType={} userId={}", request.eventType(), request.userId());
        return EventResponse.from(service.create(request));
    }

    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public List<EventResponse> createBatch(@Valid @RequestBody BatchCreateEventsRequest request) {
        log.debug("REST createBatch count={}", request.events().size());
        return service.createBatch(request.events()).stream().map(EventResponse::from).collect(Collectors.toList());
    }

    @GetMapping("/{eventId}")
    public EventResponse getById(@PathVariable UUID eventId) {
        log.debug("REST getById eventId={}", eventId);
        return EventResponse.from(service.getById(eventId));
    }

    @GetMapping
    public List<EventResponse> find(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        log.debug("REST find eventType={} userId={} from={} to={} limit={} offset={}",
                eventType, userId, from, to, limit, offset);
        return service.find(eventType, userId, from, to, limit, offset).stream()
                .map(EventResponse::from)
                .collect(Collectors.toList());
    }

    @GetMapping("/stats")
    public EventStatsResponse stats(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to
    ) {
        log.debug("REST stats from={} to={}", from, to);
        return service.stats(from, to);
    }

    @PutMapping("/{eventId}")
    public EventResponse update(@PathVariable UUID eventId, @Valid @RequestBody UpdateEventRequest request) {
        log.debug("REST update eventId={}", eventId);
        return EventResponse.from(service.replaceVersion(eventId, request));
    }

    @PatchMapping("/{eventId}/mutate")
    public ResponseEntity<EventResponse> mutate(@PathVariable UUID eventId, @Valid @RequestBody MutateEventRequest request) {
        log.debug("REST mutate eventId={} newEventType={}", eventId, request.eventType());
        return ResponseEntity.ok(EventResponse.from(service.mutate(eventId, request)));
    }
}
