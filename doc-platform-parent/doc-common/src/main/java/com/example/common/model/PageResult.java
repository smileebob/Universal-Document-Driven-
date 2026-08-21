package com.example.common.model;

import java.util.List;

/**
 * 列表类接口统一分页结构（prd/11 §一 通用约定：data = {total, items}）。
 *
 * @param <T> 条目类型
 */
public class PageResult<T> {

    /** 总条数（过滤后、分页前） */
    private long total;

    /** 当前页条目 */
    private List<T> items;

    public PageResult() {
    }

    public PageResult(long total, List<T> items) {
        this.total = total;
        this.items = items;
    }

    /** 由全量列表构造（不分页） */
    public static <T> PageResult<T> of(List<T> all) {
        return new PageResult<>(all == null ? 0 : all.size(), all == null ? List.of() : all);
    }

    /** 内存分页（offset/limit 非法值按 0/全量兜底） */
    public static <T> PageResult<T> of(List<T> all, Integer offset, Integer limit) {
        List<T> source = all == null ? List.of() : all;
        int from = offset == null || offset < 0 ? 0 : offset;
        if (from >= source.size()) {
            return new PageResult<>(source.size(), List.of());
        }
        int size = limit == null || limit <= 0 ? source.size() : limit;
        return new PageResult<>(source.size(),
                source.subList(from, Math.min(from + size, source.size())));
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public List<T> getItems() {
        return items;
    }

    public void setItems(List<T> items) {
        this.items = items;
    }
}
