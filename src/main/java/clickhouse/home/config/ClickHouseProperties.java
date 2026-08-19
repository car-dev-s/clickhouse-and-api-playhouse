package clickhouse.home.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "clickhouse")
public record ClickHouseProperties(String url, String username, String password, String database) {
}
