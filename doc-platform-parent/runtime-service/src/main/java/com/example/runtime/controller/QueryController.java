package com.example.runtime.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.common.model.ApiResponse;
import com.example.runtime.domain.query.QueryDsl.QueryRequest;
import com.example.runtime.domain.query.QueryDsl.QueryResult;
import com.example.runtime.service.QueryService;

/**
 * 统一查询控制器（RD-10，POST /api/v1/query，design/09 §2.1/§四）。
 *
 * <p>请求体直接绑定 {@link QueryRequest}（snake_case 由全局 Jackson 策略承载），
 * DSL 校验与降级矩阵（L1~L7）集中在 {@link QueryService}；响应基线
 * PRD 11 §3.4：{@code {total, items, next_token, aggregations}}。</p>
 */
@RestController
public class QueryController {

    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 统一查询（target=filters+sorts+page+aggregations）。
     *
     * @param request DSL 请求
     * @return 查询结果
     */
    @PostMapping("/api/v1/query")
    public ApiResponse<QueryResult> query(@RequestBody(required = false) QueryRequest request) {
        return ApiResponse.ok(queryService.execute(request));
    }
}
