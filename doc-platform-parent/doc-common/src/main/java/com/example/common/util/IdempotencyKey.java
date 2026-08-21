package com.example.common.util;

import java.util.UUID;
import java.util.regex.Pattern;

import com.example.common.exception.BizException;
import com.example.common.exception.ErrorCode;

/**
 * 幂等键工具（B-01：X-Idempotency-Key 头判重）。
 *
 * <p>幂等键一次用户操作一个键，推荐格式 {@code {uuid}:{action}:{clientToken}}
 * （design/05 §5.2）；平台侧仅约束长度与可见性，判重真相源是
 * idempotent_record 表的 EXPECT_NOT_EXIST 单行 CAS。</p>
 */
public final class IdempotencyKey {

    /** 幂等键最大长度（超出按参数错误拒绝） */
    public static final int MAX_LENGTH = 128;

    /** 标准 UUID v4 格式 */
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE);

    /** 可见字符（不含空白控制符） */
    private static final Pattern VISIBLE_PATTERN = Pattern.compile("^[\\x21-\\x7E]+$");

    private IdempotencyKey() {
    }

    /**
     * 生成标准 UUID v4 幂等键。
     *
     * @return UUID 字符串
     */
    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /**
     * 校验是否为标准 UUID v4。
     *
     * @param key 待校验键
     * @return true=合法 UUID v4
     */
    public static boolean isValidUuid(String key) {
        return key != null && UUID_PATTERN.matcher(key).matches();
    }

    /**
     * 校验通用幂等键合法性：非空、长度 ≤ {@link #MAX_LENGTH}、仅可见 ASCII 字符。
     *
     * @param key 待校验键
     * @return true=合法
     */
    public static boolean isValid(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_LENGTH) {
            return false;
        }
        return VISIBLE_PATTERN.matcher(key).matches();
    }

    /**
     * 断言幂等键合法，否则抛 40002。
     *
     * @param key 待校验键
     * @return 原键
     * @throws BizException TYPE_INVALID（40002）
     */
    public static String requireValid(String key) {
        if (!isValid(key)) {
            throw new BizException(ErrorCode.TYPE_INVALID, "X-Idempotency-Key 格式不正确");
        }
        return key;
    }
}
