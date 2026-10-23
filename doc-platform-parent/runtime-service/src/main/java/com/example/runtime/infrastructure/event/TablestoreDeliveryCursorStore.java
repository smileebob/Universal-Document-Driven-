package com.example.runtime.infrastructure.event;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.common.infrastructure.tablestore.model.ColumnUpdate;
import com.example.common.infrastructure.tablestore.model.RowCondition;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.runtime.domain.event.DeliveryCursorStore;
import com.example.runtime.domain.event.DocDeliveryCursor;

/**
 * event_delivery_cursor Tablestore 实现（EV-02）。
 *
 * <p>所有推进使用列条件 CAS（last_sent_seq + lease_owner + blocked_event_seq），
 * 旧持有者/旧 checkpoint 的更新会失败，避免多实例重复推进。</p>
 */
@Component
public class TablestoreDeliveryCursorStore implements DeliveryCursorStore {

    static final String TABLE = "event_delivery_cursor";

    private final TablestoreTemplate template;

    public TablestoreDeliveryCursorStore(TablestoreTemplate template) {
        this.template = template;
    }

    @Override
    public Optional<DocDeliveryCursor> find(String partitionKey, String docUuid) {
        return template.getRow(TABLE, pK(partitionKey, docUuid)).map(this::mapRow);
    }

    @Override
    public DocDeliveryCursor getOrCreate(String partitionKey, String docUuid) {
        Optional<DocDeliveryCursor> existing = find(partitionKey, docUuid);
        if (existing.isPresent()) {
            return existing.get();
        }
        long now = System.currentTimeMillis();
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put("last_sent_seq", TsColumnValue.of(0L));
        columns.put("blocked_event_seq", TsColumnValue.of(0L));
        columns.put("blocked_reason", TsColumnValue.of(""));
        columns.put("lease_owner", TsColumnValue.of(""));
        columns.put("lease_until", TsColumnValue.of(0L));
        columns.put("update_time", TsColumnValue.of(now));
        try {
            template.putRow(TABLE, new TsRow(pK(partitionKey, docUuid), columns),
                    RowCondition.expectNotExist());
        } catch (TsConditionCheckException e) {
            // 并发初始化：回读
        }
        return find(partitionKey, docUuid).orElseGet(() -> {
            DocDeliveryCursor cursor = new DocDeliveryCursor();
            cursor.setPartitionKey(partitionKey);
            cursor.setDocUuid(docUuid);
            cursor.setUpdateTime(now);
            return cursor;
        });
    }

    @Override
    public boolean claim(DocDeliveryCursor cursor, String owner, long leaseUntil) {
        try {
            template.updateRow(TABLE, pK(cursor.getPartitionKey(), cursor.getDocUuid()),
                    List.of(
                            ColumnUpdate.put("lease_owner", TsColumnValue.of(owner)),
                            ColumnUpdate.put("lease_until", TsColumnValue.of(leaseUntil)),
                            ColumnUpdate.put("update_time",
                                    TsColumnValue.of(System.currentTimeMillis()))),
                    RowCondition.expectExistAndAllEq(Map.of(
                            "last_sent_seq", TsColumnValue.of(cursor.getLastSentSeq()),
                            "lease_owner", TsColumnValue.of(
                                    cursor.getLeaseOwner() == null ? "" : cursor.getLeaseOwner()),
                            "blocked_event_seq",
                            TsColumnValue.of(cursor.getBlockedEventSeq()))));
            cursor.setLeaseOwner(owner);
            cursor.setLeaseUntil(leaseUntil);
            return true;
        } catch (TsConditionCheckException e) {
            return false;
        }
    }

    @Override
    public boolean advance(DocDeliveryCursor cursor, long sentSeq, String owner) {
        long leaseUntil = System.currentTimeMillis() + 60_000L;
        try {
            // 推进 checkpoint 时保持租约，避免同一批连续事件中途被其他实例接管
            template.updateRow(TABLE, pK(cursor.getPartitionKey(), cursor.getDocUuid()),
                    List.of(
                            ColumnUpdate.put("last_sent_seq", TsColumnValue.of(sentSeq)),
                            ColumnUpdate.put("lease_until", TsColumnValue.of(leaseUntil)),
                            ColumnUpdate.put("update_time",
                                    TsColumnValue.of(System.currentTimeMillis()))),
                    RowCondition.expectExistAndAllEq(Map.of(
                            "last_sent_seq", TsColumnValue.of(cursor.getLastSentSeq()),
                            "lease_owner", TsColumnValue.of(owner))));
            cursor.setLastSentSeq(sentSeq);
            cursor.setLeaseUntil(leaseUntil);
            return true;
        } catch (TsConditionCheckException e) {
            return false;
        }
    }

    @Override
    public void block(DocDeliveryCursor cursor, long eventSeq, String reason, String owner) {
        template.updateRow(TABLE, pK(cursor.getPartitionKey(), cursor.getDocUuid()),
                List.of(
                        ColumnUpdate.put("blocked_event_seq", TsColumnValue.of(eventSeq)),
                        ColumnUpdate.put("blocked_reason", TsColumnValue.of(
                                reason == null ? "" : reason)),
                        ColumnUpdate.put("lease_owner", TsColumnValue.of("")),
                        ColumnUpdate.put("lease_until", TsColumnValue.of(0L)),
                        ColumnUpdate.put("update_time",
                                TsColumnValue.of(System.currentTimeMillis()))),
                RowCondition.expectExistAndAllEq(Map.of(
                        "last_sent_seq", TsColumnValue.of(cursor.getLastSentSeq()),
                        "lease_owner", TsColumnValue.of(owner))));
        cursor.setBlockedEventSeq(eventSeq);
        cursor.setBlockedReason(reason);
        cursor.setLeaseOwner("");
        cursor.setLeaseUntil(0L);
    }

    @Override
    public void unblock(String partitionKey, String docUuid) {
        template.updateRow(TABLE, pK(partitionKey, docUuid),
                List.of(
                        ColumnUpdate.put("blocked_event_seq", TsColumnValue.of(0L)),
                        ColumnUpdate.put("blocked_reason", TsColumnValue.of("")),
                        ColumnUpdate.put("update_time",
                                TsColumnValue.of(System.currentTimeMillis()))));
    }

    @Override
    public void release(DocDeliveryCursor cursor, String owner) {
        try {
            template.updateRow(TABLE, pK(cursor.getPartitionKey(), cursor.getDocUuid()),
                    List.of(
                            ColumnUpdate.put("lease_owner", TsColumnValue.of("")),
                            ColumnUpdate.put("lease_until", TsColumnValue.of(0L)),
                            ColumnUpdate.put("update_time",
                                    TsColumnValue.of(System.currentTimeMillis()))),
                    RowCondition.expectExistAndColumnEq("lease_owner",
                            TsColumnValue.of(owner)));
            cursor.setLeaseOwner("");
            cursor.setLeaseUntil(0L);
        } catch (TsConditionCheckException e) {
            // 租约已变更，无需释放
        }
    }

    private DocDeliveryCursor mapRow(TsRow row) {
        DocDeliveryCursor cursor = new DocDeliveryCursor();
        cursor.setPartitionKey(String.valueOf(row.getPrimaryKey().get("partition_key")));
        cursor.setDocUuid(String.valueOf(row.getPrimaryKey().get("doc_uuid")));
        cursor.setLastSentSeq(longOf(row, "last_sent_seq"));
        cursor.setBlockedEventSeq(longOf(row, "blocked_event_seq"));
        cursor.setBlockedReason(row.getString("blocked_reason"));
        cursor.setLeaseOwner(row.getString("lease_owner"));
        cursor.setLeaseUntil(longOf(row, "lease_until"));
        cursor.setUpdateTime(longOf(row, "update_time"));
        return cursor;
    }

    private static TsPrimaryKey pK(String partitionKey, String docUuid) {
        return TsPrimaryKey.builder()
                .add("partition_key", partitionKey)
                .add("doc_uuid", docUuid)
                .build();
    }

    private static long longOf(TsRow row, String column) {
        Long value = row.getLong(column);
        return value == null ? 0L : value;
    }
}
