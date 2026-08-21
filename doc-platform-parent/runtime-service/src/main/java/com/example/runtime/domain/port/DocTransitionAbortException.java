package com.example.runtime.domain.port;

/**
 * 流转事务内业务中断（非条件冲突：单据缺失/已删），外层映射 40401。
 *
 * <p>置于 domain 层以便 service 层捕获而不反向依赖 infrastructure
 * （ArchUnit 分层/环依赖门禁）。</p>
 */
public class DocTransitionAbortException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocTransitionAbortException(String message) {
        super(message);
    }
}
