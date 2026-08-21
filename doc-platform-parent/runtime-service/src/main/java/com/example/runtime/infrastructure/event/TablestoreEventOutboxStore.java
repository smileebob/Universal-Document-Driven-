package com.example.runtime.infrastructure.event;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.exception.TsConditionCheckException;
import com.example.common.infrastructure.tablestore.model.ColumnUpdate;
import com.example.common.infrastructure.tablestore.model.RowCondition;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.query.QuerySpec;
import com.example.common.infrastructure.tablestore.query.SearchResult;
import com.example.runtime.domain.event.EventOutboxStore;
import com.example.runtime.domain.event.OutboxEvent;

/**
 * event_outbox 中继侧 Tablestore 实现（EV-01/02，design/07 §四）。
 *
 * <p>扫描经多元索引 event_outbox_index（send_status + next_retry_time /
 * create_time，limit≤100 + next_token 续翻）；CAS 抢占经条件 UpdateRow
 * （expectExistAndColumnEq next_retry_time）防多实例重发。写入侧（建单/流转
 * 局部事务内落行）位于 TablestoreDocDataStore。</p>
 */
@Component
public class TablestoreEventOutboxStore implements EventOutboxStore {

    static final String TABLE = "event_outbox";
    static final String INDEX = "event_outbox_index";

    private final TablestoreTemplate template;

    public TablestoreEventOutboxStore(TablestoreTemplate template) {
        this.template = template;
    }

    @Override
    public ScanPage scanPending(long now, int limit, String nextToken) {
        QuerySpec.Builder builder = QuerySpec.builder()
                .term("send_status", TsColumnValue.of(0L))
                .range("next_retry_time", null, true, TsColumnValue.of(now), true)
                .limit(Math.min(Math.max(1, limit), QuerySpec.MAX_LIMIT));
        if (nextToken != null && !nextToken.isEmpty()) {
            builder.nextToken(nextToken);
        }
        SearchResult result = template.search(TABLE, INDEX, builder.build());
        return new ScanPage(mapRows(result.getRows()), result.getNextToken());
    }

    @Override
    public Optional<OutboxEvent> find(String partitionKey, String eventId) {
        return template.getRow(TABLE, outboxPk(partitionKey, eventId)).map(this::mapRow);
    }

    @Override
    public Optional<OutboxEvent> findByEventId(String eventId) {
        // 手动重发兜底路径：行数小规模（TTL 30d + 7d 归档），全表扫描过滤
        for (TsRow row : template.getRange(TABLE)) {
            if (eventId.equals(String.valueOf(row.getPrimaryKey().get("event_id")))) {
                return Optional.of(mapRow(row));
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean casClaim(OutboxEvent event, long expectNextRetryTime, long claimedNextRetryTime) {
        try {
            template.updateRow(TABLE, outboxPk(event.getPartitionKey(), event.getEventId()),
                    List.of(ColumnUpdate.put("next_retry_time", TsColumnValue.of(claimedNextRetryTime))),
                    RowCondition.expectExistAndColumnEq("next_retry_time",
                            TsColumnValue.of(expectNextRetryTime)));
            return true;
        } catch (TsConditionCheckException e) {
            // 已被其他实例抢占（或行状态已变化）
            return false;
        }
    }

    @Override
    public void markSent(OutboxEvent event) {
        template.updateRow(TABLE, outboxPk(event.getPartitionKey(), event.getEventId()),
                List.of(ColumnUpdate.put("send_status", TsColumnValue.of(1L))));
    }

    @Override
    public void markRetry(OutboxEvent event, long nextRetryTime, String lastError, boolean toManual) {
        List<ColumnUpdate> updates = new ArrayList<>();
        updates.add(ColumnUpdate.put("retry_count", TsColumnValue.of(event.getRetryCount() + 1)));
        updates.add(ColumnUpdate.put("next_retry_time", TsColumnValue.of(nextRetryTime)));
        updates.add(ColumnUpdate.put("last_error", TsColumnValue.of(
                lastError == null ? "" : lastError)));
        if (toManual) {
            updates.add(ColumnUpdate.put("send_status", TsColumnValue.of(2L)));
        }
        template.updateRow(TABLE, outboxPk(event.getPartitionKey(), event.getEventId()), updates);
    }

    @Override
    public void resetForResend(OutboxEvent event, long now) {
        template.updateRow(TABLE, outboxPk(event.getPartitionKey(), event.getEventId()),
                List.of(ColumnUpdate.put("send_status", TsColumnValue.of(0L)),
                        ColumnUpdate.put("retry_count", TsColumnValue.of(0L)),
                        ColumnUpdate.put("next_retry_time", TsColumnValue.of(now)),
                        ColumnUpdate.put("last_error", TsColumnValue.of(""))));
    }

    @Override
    public List<OutboxEvent> listRetryable(int limit) {
        List<OutboxEvent> result = new ArrayList<>();
        for (TsRow row : template.getRange(TABLE)) {
            OutboxEvent event = mapRow(row);
            // 待人工（send_status=2），或 retry_count>0 的滞留待发
            if (event.getSendStatus() == 2L
                    || (event.getSendStatus() == 0L && event.getRetryCount() > 0L)) {
                result.add(event);
            }
        }
        result.sort(Comparator.comparingLong(OutboxEvent::getCreateTime));
        int safeLimit = Math.min(Math.max(1, limit), QuerySpec.MAX_LIMIT);
        return result.size() <= safeLimit ? result : result.subList(0, safeLimit);
    }

    @Override
    public void delete(OutboxEvent event) {
        template.deleteRow(TABLE, outboxPk(event.getPartitionKey(), event.getEventId()));
    }

    @Override
    public ScanPage scanSent(long before, int limit, String nextToken) {
        QuerySpec.Builder builder = QuerySpec.builder()
                .term("send_status", TsColumnValue.of(1L))
                .range("create_time", null, true, TsColumnValue.of(before), true)
                .limit(Math.min(Math.max(1, limit), QuerySpec.MAX_LIMIT));
        if (nextToken != null && !nextToken.isEmpty()) {
            builder.nextToken(nextToken);
        }
        SearchResult result = template.search(TABLE, INDEX, builder.build());
        return new ScanPage(mapRows(result.getRows()), result.getNextToken());
    }

    // ==================== 行映射 ====================

    private List<OutboxEvent> mapRows(List<TsRow> rows) {
        List<OutboxEvent> events = new ArrayList<>(rows.size());
        for (TsRow row : rows) {
            events.add(mapRow(row));
        }
        return events;
    }

    private OutboxEvent mapRow(TsRow row) {
        OutboxEvent event = new OutboxEvent();
        event.setPartitionKey(String.valueOf(row.getPrimaryKey().get("partition_key")));
        event.setEventId(String.valueOf(row.getPrimaryKey().get("event_id")));
        event.setEventType(row.getString("event_type"));
        event.setEventBody(row.getString("event_body"));
        event.setTargetCode(row.getString("target_code"));
        event.setTargetUuid(row.getString("target_uuid"));
        event.setSendStatus(longOf(row, "send_status"));
        event.setRetryCount(longOf(row, "retry_count"));
        event.setLastError(row.getString("last_error"));
        event.setNextRetryTime(longOf(row, "next_retry_time"));
        event.setCreateTime(longOf(row, "create_time"));
        return event;
    }

    /** 主键构造：partition_key + event_id（与业务行同分区） */
    static TsPrimaryKey outboxPk(String partitionKey, String eventId) {
        return TsPrimaryKey.builder()
                .add("partition_key", partitionKey)
                .add("event_id", eventId)
                .build();
    }

    private static long longOf(TsRow row, String column) {
        Long value = row.getLong(column);
        return value == null ? 0L : value;
    }
}
