package clickhouse.home.metric;

import clickhouse.home.event.Event;
import clickhouse.home.event.EventRepository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Not a correctness test: a manual data-loading and exploration tool, same spirit as {@code
 * EventLoadDataIT}, but for {@link MetricRepository}'s native Map/JSON columns. Disabled by
 * default - enable it locally against a running {@code docker-compose up -d} server to load
 * sample data and see native Map/JSON access in action.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Disabled("Manual data-generation tool; run explicitly against a running ClickHouse (docker-compose up -d)")
class MetricLoadDataIT {

    private static final List<String> METRIC_NAMES = List.of("cpu_usage", "memory_usage", "latency_ms", "error_rate");
    private static final List<String> REGIONS = List.of("us-east", "us-west", "eu-west");
    private static final List<String> ENVS = List.of("prod", "staging", "dev");
    private static final List<String> STATUSES = List.of("ok", "degraded", "down");
    private static final int BATCH_SIZE = 500;

    @Autowired
    private MetricRepository repository;

    @Autowired
    private EventRepository eventRepository;

    @Test
    void generatesRandomMetrics() {
        generateRandomMetrics(10_000);
    }

    /**
     * {@code tags} is a native {@code Map(String, String)} column, so {@code tags['env'] = 'x'}
     * is evaluated by ClickHouse's Map subcolumn machinery directly against the binary-encoded
     * column - no text parsing at query time. Contrast with {@code JSONExtractString(properties,
     * key)} on the {@code events.properties} String column, which has to parse the JSON text of
     * every row on every query (see the "distinct property keys" query in
     * interesting_queries.sql). Both are fast at this table size; the difference in approach
     * shows up as data volume and JSON payload size grow.
     */
    @Test
    void nativeMapAccessVsJsonExtractOnPropertiesString() {
        generateRandomMetrics(5_000);

        long mapStart = System.nanoTime();
        List<Metric> prodMetrics = repository.findByTag("env", "prod");
        long mapMillis = (System.nanoTime() - mapStart) / 1_000_000;
        System.out.printf("native Map access (tags['env'] = 'prod'): %dms, %d rows%n", mapMillis, prodMetrics.size());

        long jsonExtractStart = System.nanoTime();
        List<Event> pageViews = eventRepository.find("page_view", null, null, null, 1_000_000, 0);
        long jsonExtractMillis = (System.nanoTime() - jsonExtractStart) / 1_000_000;
        System.out.printf("events.find (no JSON filter, for scale reference): %dms, %d rows%n",
                jsonExtractMillis, pageViews.size());
    }

    /**
     * {@code attributes} is a native JSON column. {@code attributes.status = 'ok'} reads only the
     * {@code status} subcolumn ClickHouse inferred at insert time, rather than parsing the whole
     * JSON blob per row the way {@code JSONExtractString(properties, 'status')} would against a
     * String column.
     */
    @Test
    void nativeJsonSubcolumnAccess() {
        generateRandomMetrics(2_000);

        long start = System.nanoTime();
        List<Metric> okMetrics = repository.findByAttribute("status", "ok");
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("native JSON subcolumn access (attributes.status = 'ok'): %dms, %d rows%n",
                millis, okMetrics.size());
        if (!okMetrics.isEmpty()) {
            Metric sample = okMetrics.get(0);
            System.out.println("sample attributes: " + sample.attributes() + ", tags: " + sample.tags());
        }
    }

    /** Distribution of metrics per env value, entirely via native Map access - no arrayJoin/JSON functions. */
    @Test
    void mapValueDistribution() {
        generateRandomMetrics(3_000);

        List<MetricRepository.TagCount> byEnv = repository.countByTagValue("env");
        System.out.println("metrics per env:");
        for (MetricRepository.TagCount tagCount : byEnv) {
            System.out.printf("  %-10s %d%n", tagCount.tagValue(), tagCount.count());
        }
    }

    private void generateRandomMetrics(int count) {
        List<Metric> batch = new ArrayList<>(BATCH_SIZE);
        for (int i = 0; i < count; i++) {
            batch.add(randomMetric());
            if (batch.size() == BATCH_SIZE) {
                repository.insertAll(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            repository.insertAll(batch);
        }
    }

    private Metric randomMetric() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Instant recordedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                .minusSeconds(random.nextLong(0, 60 * 60 * 24 * 30));
        String metricName = METRIC_NAMES.get(random.nextInt(METRIC_NAMES.size()));
        String deviceId = "device-" + random.nextInt(1, 500);

        Map<String, String> tags = Map.of(
                "region", REGIONS.get(random.nextInt(REGIONS.size())),
                "env", ENVS.get(random.nextInt(ENVS.size()))
        );
        Map<String, Object> attributes = Map.of(
                "status", STATUSES.get(random.nextInt(STATUSES.size())),
                "latency_ms", random.nextInt(1, 5_000)
        );

        return new Metric(UUID.randomUUID(), deviceId, metricName, tags, attributes, recordedAt);
    }
}
