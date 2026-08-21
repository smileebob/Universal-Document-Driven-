package com.example.runtime.domain.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单据状态机运行域模型（SM 契约适配产物，流转合法性校验全走 runtime 缓存）。
 *
 * <p>结构按 prd/04 SM-01：{@code [{start:100,end:[10]},...]}，同 start 的 end
 * 已合并。合法性语义（design/02）：仅 transitions 登记的 (from,to) 对允许流转。</p>
 */
public class DocStateMachine {

    private final String docCode;
    private final Map<Long, Set<Long>> transitions;

    public DocStateMachine(String docCode, Map<Long, Set<Long>> transitions) {
        this.docCode = docCode;
        Map<Long, Set<Long>> copy = new HashMap<>();
        if (transitions != null) {
            transitions.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
        }
        this.transitions = Collections.unmodifiableMap(copy);
    }

    public String getDocCode() {
        return docCode;
    }

    public Map<Long, Set<Long>> getTransitions() {
        return transitions;
    }

    /**
     * 流转合法性判断（SM-02：仅登记路径合法）。
     *
     * @param fromStatus 当前状态
     * @param toStatus   目标状态
     * @return true=允许流转
     */
    public boolean isLegal(long fromStatus, long toStatus) {
        Set<Long> ends = transitions.get(fromStatus);
        return ends != null && ends.contains(toStatus);
    }

    /** 某起始状态的全部可达目标（未登记返回空集） */
    public Set<Long> reachable(long fromStatus) {
        return transitions.getOrDefault(fromStatus, Set.of());
    }

    /** 便捷构造（测试与适配层用） */
    public static DocStateMachine of(String docCode, List<long[]> pairs) {
        Map<Long, Set<Long>> map = new HashMap<>();
        for (long[] pair : pairs) {
            map.computeIfAbsent(pair[0], k -> new java.util.HashSet<>()).add(pair[1]);
        }
        return new DocStateMachine(docCode, map);
    }
}
