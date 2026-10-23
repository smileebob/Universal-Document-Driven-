package com.example.meta.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.common.model.ApiResponse;
import com.example.common.security.PlatformRoles;
import com.example.common.security.RequireRoles;
import com.example.meta.infrastructure.index.IndexPublishTask;
import com.example.meta.infrastructure.index.IndexPublishTaskStore;
import com.example.meta.infrastructure.index.OnlineIndexPublisher;

/**
 * 在线索引发布运维接口：查看任务、重试失败候选、回滚到保留的旧索引。
 */
@RestController
@RequestMapping("/api/v1/admin/index-tasks")
@RequireRoles(PlatformRoles.PLATFORM_ADMIN)
public class IndexPublishAdminController {

    private final IndexPublishTaskStore taskStore;
    private final OnlineIndexPublisher publisher;

    public IndexPublishAdminController(IndexPublishTaskStore taskStore,
                                       OnlineIndexPublisher publisher) {
        this.taskStore = taskStore;
        this.publisher = publisher;
    }

    @GetMapping
    public ApiResponse<Object> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        List<Map<String, Object>> items = taskStore.listByStatus(status, limit).stream()
                .map(this::toView).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", items.size());
        data.put("items", items);
        return ApiResponse.ok(data);
    }

    @PostMapping("/{taskId}/retry")
    public ApiResponse<Object> retry(@PathVariable("taskId") String taskId) {
        return ApiResponse.ok(toView(publisher.retry(taskId)));
    }

    @PostMapping("/{taskId}/rollback")
    public ApiResponse<Object> rollback(@PathVariable("taskId") String taskId) {
        return ApiResponse.ok(toView(publisher.rollback(taskId)));
    }

    private Map<String, Object> toView(IndexPublishTask task) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("task_id", task.getTaskId());
        view.put("table_name", task.getTableName());
        view.put("logical_index", task.getLogicalIndex());
        view.put("candidate_index", task.getCandidateIndex());
        view.put("previous_index", task.getPreviousIndex());
        view.put("status", task.getStatus());
        view.put("error", task.getError());
        view.put("create_time", task.getCreateTime());
        view.put("update_time", task.getUpdateTime());
        return view;
    }
}
