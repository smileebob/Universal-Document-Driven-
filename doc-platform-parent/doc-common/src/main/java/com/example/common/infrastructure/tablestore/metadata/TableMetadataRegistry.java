package com.example.common.infrastructure.tablestore.metadata;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 表元数据注册表：写入侧分区键校验的事实来源。
 *
 * <p>由 TablestoreBootstrap 加载 JSON 定义文件时注册，
 * 测试环境由 InMemoryTablestore.ensureTable 注册。</p>
 */
public final class TableMetadataRegistry {

    private final ConcurrentMap<String, TableDefinition> tables = new ConcurrentHashMap<>();

    /**
     * 注册表定义（重复注册以首次为准，幂等）。
     *
     * @param definition 表定义
     */
    public void register(TableDefinition definition) {
        tables.putIfAbsent(definition.getName(), definition);
    }

    /**
     * 查询表定义。
     *
     * @param tableName 表名
     * @return 定义（未注册返回 empty）
     */
    public Optional<TableDefinition> get(String tableName) {
        return Optional.ofNullable(tables.get(tableName));
    }

    public boolean contains(String tableName) {
        return tables.containsKey(tableName);
    }

    public int size() {
        return tables.size();
    }
}
