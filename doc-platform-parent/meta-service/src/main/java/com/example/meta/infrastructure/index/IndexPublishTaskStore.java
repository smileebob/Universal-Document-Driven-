package com.example.meta.infrastructure.index;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.common.infrastructure.tablestore.TablestoreTemplate;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsPrimaryKey;
import com.example.common.infrastructure.tablestore.model.TsRow;

/**
 * 索引发布任务持久化（index_publish_task，PLAIN 单键）。
 */
@Component
public class IndexPublishTaskStore {

    static final String TABLE = "index_publish_task";

    private final TablestoreTemplate template;

    public IndexPublishTaskStore(TablestoreTemplate template) {
        this.template = template;
    }

    public void save(IndexPublishTask task) {
        Map<String, TsColumnValue> columns = new LinkedHashMap<>();
        columns.put("table_name", TsColumnValue.of(task.getTableName()));
        columns.put("logical_index", TsColumnValue.of(task.getLogicalIndex()));
        columns.put("candidate_index", TsColumnValue.of(nullToEmpty(task.getCandidateIndex())));
        columns.put("previous_index", TsColumnValue.of(nullToEmpty(task.getPreviousIndex())));
        columns.put("status", TsColumnValue.of(task.getStatus()));
        columns.put("error", TsColumnValue.of(nullToEmpty(task.getError())));
        columns.put("definition_json", TsColumnValue.of(
                nullToEmpty(task.getDefinitionJson())));
        columns.put("create_time", TsColumnValue.of(task.getCreateTime()));
        columns.put("update_time", TsColumnValue.of(task.getUpdateTime()));
        template.putRow(TABLE, new TsRow(pk(task.getTaskId()), columns));
    }

    public Optional<IndexPublishTask> find(String taskId) {
        return template.getRow(TABLE, pk(taskId)).map(this::mapRow);
    }

    public List<IndexPublishTask> listByStatus(String status, int limit) {
        List<IndexPublishTask> tasks = new ArrayList<>();
        for (TsRow row : template.getRange(TABLE)) {
            IndexPublishTask task = mapRow(row);
            if (status == null || status.equals(task.getStatus())) {
                tasks.add(task);
            }
        }
        tasks.sort(Comparator.comparingLong(IndexPublishTask::getCreateTime).reversed());
        int safeLimit = Math.min(Math.max(1, limit), 100);
        return tasks.size() <= safeLimit ? tasks : tasks.subList(0, safeLimit);
    }

    private IndexPublishTask mapRow(TsRow row) {
        IndexPublishTask task = new IndexPublishTask();
        task.setTaskId(String.valueOf(row.getPrimaryKey().get("task_id")));
        task.setTableName(row.getString("table_name"));
        task.setLogicalIndex(row.getString("logical_index"));
        task.setCandidateIndex(row.getString("candidate_index"));
        task.setPreviousIndex(row.getString("previous_index"));
        task.setStatus(row.getString("status"));
        task.setError(row.getString("error"));
        task.setDefinitionJson(row.getString("definition_json"));
        task.setCreateTime(longOf(row, "create_time"));
        task.setUpdateTime(longOf(row, "update_time"));
        return task;
    }

    private static TsPrimaryKey pk(String taskId) {
        return TsPrimaryKey.builder().add("task_id", taskId).build();
    }

    private static long longOf(TsRow row, String column) {
        Long value = row.getLong(column);
        return value == null ? 0L : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
