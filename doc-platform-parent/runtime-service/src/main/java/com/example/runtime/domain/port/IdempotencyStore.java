package com.example.runtime.domain.port;

import java.util.Optional;

/**
 * 接口幂等记录端口（idempotent_record，design/05 §5.2，B-01 变通）。
 *
 * <p>判重载体：PutRow + EXPECT_NOT_EXIST（status=PROCESSING）→ 执行业务 →
 * 回填 DONE + response_snapshot / error_snapshot；表级 TTL 24h。</p>
 */
public interface IdempotencyStore {

    /** 记录状态：执行中 */
    String STATUS_PROCESSING = "PROCESSING";

    /** 记录状态：已完成 */
    String STATUS_DONE = "DONE";

    /**
     * 尝试占位幂等键（EXPECT_NOT_EXIST 单行 CAS，写入 PROCESSING）。
     *
     * @param key     幂等键原文（≤128，已校验）
     * @param apiPath 接口路径（跨接口防撞）
     * @return empty=首次占位成功；否则返回既有记录快照
     */
    Optional<Record> tryBegin(String key, String apiPath);

    /**
     * 回填执行结果（DONE + 快照）。
     *
     * @param key             幂等键原文
     * @param apiPath         接口路径
     * @param responseSnapshot 成功响应快照 JSON（失败为 null）
     * @param errorSnapshot   失败码与消息（成功为 null）
     */
    void complete(String key, String apiPath, String responseSnapshot, String errorSnapshot);

    /** 幂等记录快照 */
    record Record(String key, String apiPath, String status,
                  String responseSnapshot, String errorSnapshot) {
    }
}
