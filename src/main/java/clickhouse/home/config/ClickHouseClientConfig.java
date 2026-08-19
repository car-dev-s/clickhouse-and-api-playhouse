package clickhouse.home.config;

import com.clickhouse.client.api.Client;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClickHouseClientConfig {

    @Bean(destroyMethod = "close")
    public Client clickHouseClient(ClickHouseProperties properties) {
        return new Client.Builder()
                .addEndpoint(properties.url())
                .setUsername(properties.username())
                .setPassword(properties.password() == null ? "" : properties.password())
                .setDefaultDatabase(properties.database())
                // Native JSON column type (used by device_metrics.attributes) is still
                // experimental as of ClickHouse 24.8; this sends the setting with every
                // request from this client so both DDL and queries can use it.
                .serverSetting("allow_experimental_json_type", "1")
                .build();
    }
}
