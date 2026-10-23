package com.example.common.infrastructure.tablestore.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Tablestore 装配模式校验：生产禁止仿真与自动建表（ENV-02）。 */
class TablestoreModeValidatorTest {

    private static MockEnvironment env(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    private static TablestoreProperties simulation() {
        TablestoreProperties properties = new TablestoreProperties();
        properties.setMode(TablestoreProperties.Mode.SIMULATION);
        return properties;
    }

    private static TablestoreProperties ots() {
        TablestoreProperties properties = new TablestoreProperties();
        properties.setMode(TablestoreProperties.Mode.OTS);
        properties.setEndpoint("https://example.invalid");
        properties.setInstance("test-instance");
        return properties;
    }

    @Test
    @DisplayName("dev + simulation + 自动建表允许")
    void allowsDevSimulation() {
        TablestoreProperties properties = simulation();
        properties.getBootstrap().setAutoCreate(true);
        assertThatCode(() -> new TablestoreModeValidator(properties, env("dev")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prod + simulation 拒绝")
    void rejectsProdSimulation() {
        assertThatThrownBy(() -> new TablestoreModeValidator(simulation(), env("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulation");
    }

    @Test
    @DisplayName("prod + 自动建表拒绝")
    void rejectsProdAutoCreate() {
        TablestoreProperties properties = ots();
        properties.getBootstrap().setAutoCreate(true);
        assertThatThrownBy(() -> new TablestoreModeValidator(properties, env("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auto-create");
    }

    @Test
    @DisplayName("prod + 真实 OTS 且不自动建表允许")
    void allowsProdOtsWithoutAutoCreate() {
        assertThatCode(() -> new TablestoreModeValidator(ots(), env("prod")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("真实 OTS 缺少 endpoint/instance 时 fail-fast")
    void rejectsUnconfiguredOts() {
        TablestoreProperties properties = new TablestoreProperties();
        assertThatThrownBy(() -> new TablestoreModeValidator(properties, env("dev")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("endpoint");
    }
}
