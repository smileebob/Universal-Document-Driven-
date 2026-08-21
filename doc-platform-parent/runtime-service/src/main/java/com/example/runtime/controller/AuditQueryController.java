package com.example.runtime.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;
import com.example.common.infrastructure.tablestore.model.TsColumnValue;
import com.example.common.infrastructure.tablestore.model.TsRow;
import com.example.common.infrastructure.tablestore.query.QuerySpec;
import com.example.common.infrastructure.tablestore.query.SearchResult;
import com.example.common.model.ApiResponse;
import com.example.common.security.PlatformRoles;
import com.example.common.security.RequireRoles;
import com.example.runtime.domain.port.AuditLogStore;

/**
 * 审计日志查询接口（RD-12，步骤 39；GET /api/v1/audit，走 audit_log_index）。
 *
 * <p>筛选维度：target 类型/编码/uuid、操作类型、操作人、操作时间范围；
 * operate_time 倒序 + nextToken 续翻（limit 1~100，默认 20）；
 * 返回 before_value/after_value 快照 JSON 供前端 JSON Diff（design/03 §2.10）。</p>
 *
 * <p>权限：限配置管理员以上（审计为管理面能力，prd/06 §八）。</p>
 */
@RestController
@RequestMapping("/api/v1/audit")
@RequireRoles({PlatformRoles.PLATFORM_ADMIN, PlatformRoles.BUSINESS_ADMIN})
public class AuditQueryController {

    private final AuditLogStore auditLogStore;

    public AuditQueryController(AuditLogStore auditLogStore) {
        this.auditLogStore = auditLogStore;
    }

    /**
     * 审计日志分页查询。
     *
     * @param targetType 目标类型过滤（entity/doc/entity_schema/doc_schema/...，可空）
     * @param targetCode 目标编码过滤（可空）
     * @param targetUuid 目标数据 uuid 过滤（可空）
     * @param operation  操作类型过滤（create/update/delete/transition/config/validation_reject，可空）
     * @param operator   操作人过滤（可空）
     * @param startTime  操作时间下界（毫秒，含，可空）
     * @param endTime    操作时间上界（毫秒，含，可空）
     * @param limit      页长（1~100，默认 20）
     * @param nextToken  续翻游标（上一页返回，可空）
     * @return {@code {total, next_token, items[]}}，item 含 before_value/after_value 快照
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> query(
            @RequestParam(value = "target_type", required = false) String targetType,
            @RequestParam(value = "target_code", required = false) String targetCode,
            @RequestParam(value = "target_uuid", required = false) String targetUuid,
            @RequestParam(value = "operation", required = false) String operation,
            @RequestParam(value = "operator", required = false) String operator,
            @RequestParam(value = "start_time", required = false) Long startTime,
            @RequestParam(value = "end_time", required = false) Long endTime,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "next_token", required = false) String nextToken) {
        if (limit < 1 || limit > 100) {
            throw new BizException(ErrorCode.TYPE_INVALID, "limit 越界（1~100）: " + limit);
        }
        QuerySpec.Builder builder = QuerySpec.builder()
                .sortDesc("operate_time")
                .limit(limit)
                .getTotalCount(true);
        if (notBlank(targetType)) {
            builder.term("target_type", TsColumnValue.of(targetType));
        }
        if (notBlank(targetCode)) {
            builder.term("target_code", TsColumnValue.of(targetCode));
        }
        if (notBlank(targetUuid)) {
            builder.term("target_uuid", TsColumnValue.of(targetUuid));
        }
        if (notBlank(operation)) {
            builder.term("operation", TsColumnValue.of(operation));
        }
        if (notBlank(operator)) {
            builder.term("operator", TsColumnValue.of(operator));
        }
        if (startTime != null || endTime != null) {
            builder.range("operate_time",
                    startTime == null ? null : TsColumnValue.of(startTime), true,
                    endTime == null ? null : TsColumnValue.of(endTime), true);
        }
        if (nextToken != null && !nextToken.isBlank()) {
            builder.nextToken(nextToken);
        }
        SearchResult result = auditLogStore.search(builder.build());

        List<Map<String, Object>> items = new ArrayList<>();
        for (TsRow row : result.getRows()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("log_id", row.getPrimaryKey().get("log_id"));
            item.put("operation", row.getString("operation"));
            item.put("target_type", row.getString("target_type"));
            item.put("target_code", row.getString("target_code"));
            item.put("target_uuid", row.getString("target_uuid"));
            item.put("operator", row.getString("operator"));
            item.put("operate_time", row.getLong("operate_time"));
            item.put("before_value", row.getString("before_value"));
            item.put("after_value", row.getString("after_value"));
            item.put("ip", row.getString("ip"));
            item.put("trace_id", row.getString("trace_id"));
            items.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.getTotalCount());
        data.put("next_token", result.getNextToken());
        data.put("items", items);
        return ApiResponse.ok(data);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
