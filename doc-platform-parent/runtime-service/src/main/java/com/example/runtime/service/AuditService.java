package com.example.runtime.service;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.example.runtime.domain.model.AuditEntry;
import com.example.runtime.domain.port.AuditLogStore;

/**
 * 审计异步旁路服务（RD-12）：create/update/delete 成功后异步写 audit_log。
 *
 * <p>旁路语义：独立线程池（infrastructure 层 auditExecutor Bean）、
 * 只增不改；写入失败仅 WARN 不影响主流程（审计不阻断业务）。</p>
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogStore auditLogStore;
    private final Executor auditExecutor;

    public AuditService(AuditLogStore auditLogStore,
                        @Qualifier("auditExecutor") Executor auditExecutor) {
        this.auditLogStore = auditLogStore;
        this.auditExecutor = auditExecutor;
    }

    /**
     * 异步追加审计日志（失败仅 WARN）。
     *
     * @param entry 审计条目
     */
    public void recordAsync(AuditEntry entry) {
        auditExecutor.execute(() -> {
            try {
                auditLogStore.append(entry);
            } catch (RuntimeException e) {
                log.warn("[audit] 审计日志写入失败（不影响主流程）: logId={}, target={}/{}",
                        entry.getLogId(), entry.getTargetCode(), entry.getTargetUuid(), e);
            }
        });
    }
}
