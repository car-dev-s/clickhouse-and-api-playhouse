package clickhouse.home.schema;

import clickhouse.home.config.ClickHouseProperties;
import com.clickhouse.client.api.Client;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the playground database/table on startup so the same code path provisions schema
 * identically for local dev (docker-compose) and tests (Testcontainers) - no separate init script.
 */
@Component
public class SchemaInitializer implements ApplicationRunner {

    private final Client client;
    private final ClickHouseProperties properties;

    public SchemaInitializer(Client client, ClickHouseProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        client.queryAll("CREATE DATABASE IF NOT EXISTS " + properties.database());
        client.queryAll("""
                CREATE TABLE IF NOT EXISTS events
                (
                    event_id   UUID,
                    event_type String,
                    user_id    String,
                    properties String,
                    created_at DateTime64(3),
                    updated_at DateTime64(3)
                )
                ENGINE = ReplacingMergeTree(updated_at)
                ORDER BY (event_id)
                """);

        // Companion table to `events`, using ClickHouse's *native* Map and JSON column types
        // instead of the JSON-string-column pattern above - see MetricRepository for the
        // contrast (native Map/JSON subcolumn access vs Jackson-at-the-boundary + JSONExtract).
        client.queryAll("""
                CREATE TABLE IF NOT EXISTS device_metrics
                (
                    metric_id   UUID,
                    device_id   String,
                    metric_name String,
                    tags        Map(String, String),
                    attributes  JSON,
                    recorded_at DateTime64(3)
                )
                ENGINE = MergeTree
                ORDER BY (metric_name, recorded_at)
                """);
    }
}
