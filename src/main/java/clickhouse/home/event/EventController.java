package clickhouse.home.event;

import clickhouse.home.event.dto.BatchCreateEventsRequest;
import clickhouse.home.event.dto.CreateEventRequest;
import clickhouse.home.event.dto.EventResponse;
import clickhouse.home.event.dto.EventStatsResponse;
import clickhouse.home.event.dto.MutateEventRequest;
import clickhouse.home.event.dto.UpdateEventRequest;
import jakarta.validation.Valid;
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

    private final EventService service;

    public EventController(EventService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventResponse create(@Valid @RequestBody CreateEventRequest request) {
        return EventResponse.from(service.create(request));
    }

    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public List<EventResponse> createBatch(@Valid @RequestBody BatchCreateEventsRequest request) {
        return service.createBatch(request.events()).stream().map(EventResponse::from).collect(Collectors.toList());
    }

    @GetMapping("/{eventId}")
    public EventResponse getById(@PathVariable UUID eventId) {
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
        return service.find(eventType, userId, from, to, limit, offset).stream()
                .map(EventResponse::from)
                .collect(Collectors.toList());
    }

    @GetMapping("/stats")
    public EventStatsResponse stats(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to
    ) {
        return service.stats(from, to);
    }

    @PutMapping("/{eventId}")
    public EventResponse update(@PathVariable UUID eventId, @Valid @RequestBody UpdateEventRequest request) {
        return EventResponse.from(service.replaceVersion(eventId, request));
    }

    @PatchMapping("/{eventId}/mutate")
    public ResponseEntity<EventResponse> mutate(@PathVariable UUID eventId, @Valid @RequestBody MutateEventRequest request) {
        return ResponseEntity.ok(EventResponse.from(service.mutate(eventId, request)));
    }
}
