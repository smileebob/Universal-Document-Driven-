package com.example.common.infrastructure.tablestore.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import com.alicloud.openservices.tablestore.SyncClient;

/**
 * Tablestore 健康探针（J-05 变通：轻量 ListTable 探活，无业务读写）。
 */
public class TablestoreHealthIndicator implements HealthIndicator {

    private final SyncClient client;
    private final TablestoreProperties properties;

    public TablestoreHealthIndicator(SyncClient client, TablestoreProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public Health health() {
        try {
            client.listTable();
            return Health.up()
                    .withDetail("endpoint", properties.getEndpoint())
                    .withDetail("instance", properties.getInstance())
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("endpoint", properties.getEndpoint())
                    .withDetail("instance", properties.getInstance())
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
