package com.example.common.infrastructure.tablestore.bootstrap;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.metadata.SearchIndexDef;
import com.example.common.infrastructure.tablestore.metadata.TableDefinition;

/**
 * Tablestore 启动引导（TS-06：定义文件驱动、幂等）。
 *
 * <p>执行顺序：加载 classpath 下全部 {@code tablestore/V*.json} →
 * 逐表 {@code ensureTable}（注册元数据 + 幂等建表）→ 逐索引 {@code ensureSearchIndex}。
 * 由 TablestoreAutoConfiguration 在 {@code doc.tablestore.bootstrap.auto-create=true}
 * 时以 ApplicationRunner 触发（dev/test）；生产流水线可显式调用本类。</p>
 */
public final class TablestoreBootstrap {

    private static final Logger log = LoggerFactory.getLogger(TablestoreBootstrap.class);

    private final TablestoreTemplate template;
    private final TablestoreDefinitionLoader loader;

    public TablestoreBootstrap(TablestoreTemplate template) {
        this(template, new TablestoreDefinitionLoader());
    }

    public TablestoreBootstrap(TablestoreTemplate template, TablestoreDefinitionLoader loader) {
        this.template = template;
        this.loader = loader;
    }

    /**
     * 执行引导（幂等，可重复调用）。
     *
     * @return 本次加载的表定义清单
     */
    public List<TableDefinition> run() {
        List<TableDefinition> definitions = loader.loadAll();
        log.info("[tablestore][bootstrap] 加载定义文件完成，共 {} 张表", definitions.size());
        for (TableDefinition definition : definitions) {
            template.ensureTable(definition);
            for (SearchIndexDef index : definition.getSearchIndexes()) {
                template.ensureSearchIndex(definition.getName(), index);
            }
        }
        log.info("[tablestore][bootstrap] 引导完成");
        return definitions;
    }
}
