package com.example.meta.infrastructure.repository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.meta.domain.txconfig.TxConfig;

/**
 * 事务步骤配置仓储实现（tx_config 单键表，TX-01，保存即生效）。
 *
 * <p>upsert 语义：整行 PutRow 覆盖写（单键配置表无并发版本竞争诉求，
 * 保存即时失效链保证读取侧一致性）。</p>
 */
@Component
public class TxConfigRepositoryImpl implements TxConfig.Repository {

    private final TablestoreTemplate template;

    public TxConfigRepositoryImpl(TablestoreTemplate template) {
        this.template = template;
    }

    @Override
    public Optional<TxConfig> findByDocCode(String docCode) {
        return template.getRow(MetaTableNames.TX_CONFIG, pk(docCode)).map(row -> {
            TxConfig config = new TxConfig();
            config.setDocCode(String.valueOf(row.getPrimaryKey().get(MetaTableNames.PK_DOC_CODE)));
            config.setTxStepsJson(row.getString(MetaTableNames.COL_TX_STEPS));
            config.setUpdateTime(row.getLong(MetaTableNames.COL_UPDATE_TIME));
            return config;
        });
    }

    @Override
    public void save(TxConfig config) {
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put(MetaTableNames.COL_TX_STEPS,
                TsColumnValue.of(config.getTxStepsJson() == null ? "[]" : config.getTxStepsJson()));
        if (config.getUpdateTime() != null) {
            columns.put(MetaTableNames.COL_UPDATE_TIME, TsColumnValue.of(config.getUpdateTime()));
        }
        template.putRow(MetaTableNames.TX_CONFIG, new TsRow(pk(config.getDocCode()), columns));
    }

    private static TsPrimaryKey pk(String docCode) {
        return TsPrimaryKey.of(MetaTableNames.PK_DOC_CODE, docCode);
    }
}
