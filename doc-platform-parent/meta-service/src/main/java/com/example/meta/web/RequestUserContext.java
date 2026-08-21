package com.example.meta.web;

/**
 * 请求级操作人上下文（ThreadLocal）：网关透传的 {@code X-User-Id} 由
 * {@link RequestUserFilter} 在请求入口写入、出口清理，Service 层经本持有者
 * 读取操作人（避免 Service 直接依赖 HttpServletRequest）。
 *
 * <p>dev 网关认证关闭且外部未携带时缺省 {@code system}（与既有 creator 口径一致）。</p>
 */
public final class RequestUserContext {

    /** 缺省操作人（无身份链路时） */
    public static final String SYSTEM = "system";

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private RequestUserContext() {
    }

    /** 写入当前线程操作人（null 归一为 system） */
    public static void set(String userId) {
        HOLDER.set(userId == null || userId.isBlank() ? SYSTEM : userId.trim());
    }

    /** 读取当前线程操作人（未设置返回 system） */
    public static String operator() {
        String value = HOLDER.get();
        return value == null ? SYSTEM : value;
    }

    /** 清理当前线程（请求出口必调，防线程池串线） */
    public static void clear() {
        HOLDER.remove();
    }
}
