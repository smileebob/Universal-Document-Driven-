package com.example.meta.infrastructure.index;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.common.infrastructure.tablestore.bootstrap.TablestoreDefinitionLoader;
import com.example.common.infrastructure.tablestore.metadata.IndexFieldDef;
import com.example.common.infrastructure.tablestore.metadata.TableDefinition;

/**
 * V1 基线索引字段读取工具（两个索引注册器共用）。
 *
 * <p>{@link EntityDataIndexRegistrar} / {@link DocDataIndexRegistrar} 的派生索引定义
 * 直接从 V1 定义文件（V1__base-tables.json）读取基线字段，再叠加动态/固定 Nested
 * 子字段，从机制上杜绝手工维护基线列导致的派生定义再次收窄（任务 #9 教训：
 * 派生基线缺 deleted 列 → runtime 强制注入 term(deleted=0) 被真实 OTS 以
 * field_name[deleted] is not existed 拒绝）。</p>
 */
final class BaselineIndexFields {

    /** tableName#indexName → 基线字段深拷贝快照（进程级缓存，定义文件只读不变） */
    private static final Map<String, List<IndexFieldDef>> CACHE = new ConcurrentHashMap<>();

    private BaselineIndexFields() {
    }

    /**
     * 取指定表多元索引的 V1 基线字段（深拷贝，注册器可安全改写 subFields）。
     *
     * @param tableName 表名（如 entity_data / doc_data）
     * @param indexName 多元索引名（如 entity_data_index / doc_data_index）
     * @return 基线字段副本列表（顺序与 V1 定义一致）
     * @throws IllegalStateException V1 定义中缺少该表或索引（fail-fast）
     */
    static List<IndexFieldDef> of(String tableName, String indexName) {
        // 每次调用返回新深拷贝：注册器会改写 Nested 容器 subFields，不得污染缓存快照
        return CACHE.computeIfAbsent(tableName + "#" + indexName,
                key -> load(tableName, indexName)).stream()
                .map(BaselineIndexFields::copy)
                .toList();
    }

    private static List<IndexFieldDef> load(String tableName, String indexName) {
        for (TableDefinition table : new TablestoreDefinitionLoader().loadAll()) {
            if (!tableName.equals(table.getName())) {
                continue;
            }
            return table.getSearchIndexes().stream()
                    .filter(index -> indexName.equals(index.getName()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "V1 定义缺少多元索引: " + tableName + "/" + indexName))
                    .getFields().stream()
                    .map(BaselineIndexFields::copy)
                    .toList();
        }
        throw new IllegalStateException("V1 定义缺少表: " + tableName);
    }

    private static IndexFieldDef copy(IndexFieldDef source) {
        IndexFieldDef copy = new IndexFieldDef(source.getName(), source.getType());
        copy.setArray(source.isArray());
        copy.setSubFields(source.getSubFields().stream()
                .map(BaselineIndexFields::copy).toList());
        copy.setComment(source.getComment());
        return copy;
    }
}
