package com.example.runtime.service.saga;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.runtime.domain.saga.TxPlan;

/**
 * Saga 步骤出站 HTTP 客户端（TX-01：显式 connect/read 超时，仅连接异常重试 1 次）。
 *
 * <p>每请求按步骤 timeoutMs 设置 read 超时（HttpRequest.timeout），连接超时
 * 经共享 HttpClient（3s）。出站头透传 X-Saga-Id（全链路追踪）。</p>
 */
@Component
public class SagaStepHttpClient {

    /** 出站连接超时（与 Feign 全局口径一致 P-02） */
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(3000);

    /** 默认读超时（步骤 timeoutMs 缺失时兜底） */
    private static final long DEFAULT_READ_TIMEOUT_MS = 10_000L;

    private static final Logger log = LoggerFactory.getLogger(SagaStepHttpClient.class);

    private final HttpClient httpClient;

    public SagaStepHttpClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /**
     * 执行一次步骤出站调用。
     *
     * @param url       步骤端点（action / compensate）
     * @param bodyJson  请求体 JSON
     * @param step      步骤定义（超时/重试）
     * @param sagaId    Saga 实例 ID（X-Saga-Id 透传）
     * @return 响应结果
     * @throws StepInvocationException 调用失败（含重试耗尽/非 2xx）
     */
    public StepResponse post(String url, String bodyJson, TxPlan.TxStep step, String sagaId) {
        long readTimeoutMs = step.timeoutMs() > 0 ? step.timeoutMs() : DEFAULT_READ_TIMEOUT_MS;
        // 连接类异常重试次数：任务清单 3「仅连接异常重试 1 次」——至少 1 次，
        // 配置 retry>0 时按配置（meta 侧封顶 3）
        int connectRetries = Math.max(1, step.retry());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(Duration.ofMillis(readTimeoutMs))
                .header("Content-Type", "application/json")
                .header("X-Saga-Id", sagaId)
                .header("X-Saga-Step", step.name() + "/" + step.seq())
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();
        IOException lastIoError = null;
        for (int attempt = 0; attempt <= connectRetries; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return new StepResponse(response.statusCode(), response.body(), false);
                }
                // 非 2xx：业务/系统拒绝，不重试（仅连接异常可重试）
                return new StepResponse(response.statusCode(), response.body(), true);
            } catch (ConnectException e) {
                lastIoError = e;
                log.warn("[saga-step] 连接失败（第 {}/{} 次）: url={}, cause={}",
                        attempt + 1, connectRetries + 1, url, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StepInvocationException("步骤调用被中断: " + url, e, true, true);
            } catch (IOException e) {
                // 读超时等 IO 异常：按连接异常口径重试（连接建立后读超时多为对端慢，重试幂等步骤安全）
                lastIoError = e;
                log.warn("[saga-step] IO 异常（第 {}/{} 次）: url={}, cause={}",
                        attempt + 1, connectRetries + 1, url, e.getMessage());
            }
        }
        boolean unknownResult = !(lastIoError instanceof ConnectException);
        throw new StepInvocationException("步骤调用失败（连接异常重试 "
                + connectRetries + " 次后仍失败）: " + url + ", cause="
                + (lastIoError == null ? "unknown" : lastIoError.getMessage()),
                lastIoError, true, unknownResult);
    }

    /** 步骤响应（statusCode + 原文；failed=非 2xx 语义位） */
    public record StepResponse(int statusCode, String body, boolean failed) {

        /**
         * 结果未知：5xx 可能已在参与方生效（读到超时/处理中失败），
         * 不能按“未执行”跳过补偿。
         */
        public boolean unknownResult() {
            return statusCode >= 500;
        }
    }

    /** 步骤调用异常（携带连接失败/结果未知语义位，供编排器决定补偿） */
    public static class StepInvocationException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient boolean connectFailure;

        private final transient boolean unknownResult;

        public StepInvocationException(String message, Throwable cause, boolean connectFailure) {
            this(message, cause, connectFailure, true);
        }

        public StepInvocationException(String message, Throwable cause, boolean connectFailure,
                                       boolean unknownResult) {
            super(message, cause);
            this.connectFailure = connectFailure;
            this.unknownResult = unknownResult;
        }

        public boolean isConnectFailure() {
            return connectFailure;
        }

        /**
         * 调用超时/响应丢失时结果为未知（可能已执行），必须纳入补偿或人工核对；
         * 连接建立失败可确认未发送。
         */
        public boolean isUnknownResult() {
            return unknownResult;
        }
    }
}
