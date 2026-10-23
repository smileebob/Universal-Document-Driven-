package com.example.common.infrastructure.tablestore.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** simulation 模式 profile 守卫：仅 dev/test/sim，prod 混合激活也必须拒绝（ENV-02）。 */
class SimulationModeGuardTest {

    private static MockEnvironment env(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    @Test
    @DisplayName("dev/test/sim 允许仿真")
    void allowsDevProfiles() {
        assertThatCode(() -> new SimulationModeGuard(env("dev"))).doesNotThrowAnyException();
        assertThatCode(() -> new SimulationModeGuard(env("test"))).doesNotThrowAnyException();
        assertThatCode(() -> new SimulationModeGuard(env("sim"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prod 或 prod+dev 混合激活均拒绝仿真")
    void rejectsProdEvenWhenMixed() {
        assertThatThrownBy(() -> new SimulationModeGuard(env("prod")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SimulationModeGuard(env("prod", "dev")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("无激活 profile 拒绝仿真")
    void rejectsNoProfile() {
        assertThatThrownBy(() -> new SimulationModeGuard(env()))
                .isInstanceOf(IllegalStateException.class);
    }
}
