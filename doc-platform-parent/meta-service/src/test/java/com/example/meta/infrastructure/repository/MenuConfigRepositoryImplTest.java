package com.example.meta.infrastructure.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.common.infrastructure.tablestore.bootstrap.TablestoreBootstrap;
import com.example.common.infrastructure.tablestore.inmemory.InMemoryTablestore;
import com.example.meta.domain.schema.MenuEntry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MenuConfigRepositoryImpl 仓储测试（InMemoryTablestore 仿真层）。
 */
class MenuConfigRepositoryImplTest {

    private InMemoryTablestore template;
    private MenuConfigRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        template = new InMemoryTablestore();
        new TablestoreBootstrap(template).run();
        repository = new MenuConfigRepositoryImpl(template);
    }

    private MenuEntry entry(String targetType, String targetCode, boolean enabled) {
        MenuEntry entry = new MenuEntry();
        entry.setTargetType(targetType);
        entry.setTargetCode(targetCode);
        entry.setMenuName(targetCode + "-menu");
        entry.setGroupCode("basic_data");
        entry.setSortOrder(1L);
        entry.setEnabled(enabled);
        entry.setCreateTime(1L);
        entry.setUpdateTime(2L);
        return entry;
    }

    @Test
    @DisplayName("save + findByTarget 往返一致；覆盖写幂等")
    void save_and_findByTarget() {
        repository.save(entry("entity", "customer", true));
        MenuEntry loaded = repository.findByTarget("entity", "customer").orElseThrow();
        assertThat(loaded.getMenuName()).isEqualTo("customer-menu");
        assertThat(loaded.isEnabled()).isTrue();
        assertThat(loaded.targetKey()).isEqualTo("entity_customer");

        // 覆盖写（取消挂载）
        repository.save(entry("entity", "customer", false));
        assertThat(repository.findByTarget("entity", "customer").orElseThrow().isEnabled()).isFalse();
        assertThat(repository.findByTarget("entity", "not_exist")).isEmpty();
    }

    @Test
    @DisplayName("findAll 过滤 sys_schema_version 计数行")
    void findAll_filtersSysRow() {
        repository.save(entry("entity", "customer", true));
        repository.incrementSchemaVersion(); // 建行 sys_schema_version
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("schema_version：无行返回 0；increment 建行 1 后连续自增")
    void schemaVersion_increment() {
        assertThat(repository.getSchemaVersion()).isZero();
        assertThat(repository.incrementSchemaVersion()).isEqualTo(1L);
        assertThat(repository.incrementSchemaVersion()).isEqualTo(2L);
        assertThat(repository.getSchemaVersion()).isEqualTo(2L);
    }
}
