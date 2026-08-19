package clickhouse.home.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.clickhouse.ClickHouseContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class ClickHouseIntegrationTest {

    // Singleton container pattern: started once via static initializer and shared across all IT
    // classes for the life of the JVM (Testcontainers' Ryuk reaper cleans it up on exit), rather
    // than letting @Testcontainers stop/restart it between classes, which would leave a stale
    // mapped port in any cached Spring context.
    static final ClickHouseContainer CLICKHOUSE =
            new ClickHouseContainer("clickhouse/clickhouse-server:24.8");

    static {
        CLICKHOUSE.start();
    }

    @DynamicPropertySource
    static void clickHouseProperties(DynamicPropertyRegistry registry) {
        registry.add("clickhouse.url", () -> "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        registry.add("clickhouse.username", CLICKHOUSE::getUsername);
        registry.add("clickhouse.password", CLICKHOUSE::getPassword);
        registry.add("clickhouse.database", CLICKHOUSE::getDatabaseName);
    }
}
