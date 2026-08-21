package com.example.common.infrastructure.messaging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进程内消息仿真 Broker（doc.mq.mode=mock 的分发中枢）。
 *
 * <p>语义近似（任务清单 1：mock 模式供集成测试与演示）：</p>
 * <ul>
 *   <li>BROADCAST：topic 下全部监听器逐一分发（每实例各收一份的真实语义
 *       由「发布方本地分发 + Redis 桥接跨实例分发」共同近似）；</li>
 *   <li>ORDERLY：发布线程同步串行分发，同一发布源内 shardingKey 消息
 *       保持到达顺序（单发布源近似 FIFO）；</li>
 *   <li>NORMAL：同步分发（mock 不建独立消费线程）。</li>
 * </ul>
 *
 * <p>消费异常仅记 WARN（BC-04：广播失败不重投）；全部已发布消息留痕
 * （环形上限 {@value #RECORD_LIMIT} 条）供测试断言与演示证据。</p>
 */
public class InMemoryMessageBroker {

    /** 消息留痕上限（环形淘汰） */
    public static final int RECORD_LIMIT = 2000;

    private static final Logger log = LoggerFactory.getLogger(InMemoryMessageBroker.class);

    private final List<DocMessageListener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedDeque<DocMessage> records = new ConcurrentLinkedDeque<>();

    public InMemoryMessageBroker(List<DocMessageListener> listeners) {
        if (listeners != null) {
            this.listeners.addAll(listeners);
        }
        log.info("[mock-mq] InMemoryMessageBroker 就绪，监听器数={}", this.listeners.size());
    }

    /**
     * 本地分发一条消息（发布方自消费 + 跨实例桥接收到消息的落点）。
     *
     * @param message 消息
     * @return 成功分发的监听器数
     */
    public int dispatchLocal(DocMessage message) {
        int delivered = 0;
        for (DocMessageListener listener : listeners) {
            if (!listener.topic().equals(message.getTopic())) {
                continue;
            }
            try {
                listener.onMessage(message);
                delivered++;
            } catch (RuntimeException e) {
                log.warn("[mock-mq] 消费异常（不重投，BC-04）: listener={}, group={}, cause={}",
                        listener.getClass().getSimpleName(), listener.consumerGroup(), e.getMessage());
            }
        }
        log.debug("[mock-mq] 本地分发: {} -> delivered={}", message, delivered);
        return delivered;
    }

    /** 已发布消息留痕（发布方视角，含未分发成功的消息） */
    public void record(DocMessage message) {
        records.addLast(message);
        while (records.size() > RECORD_LIMIT) {
            records.pollFirst();
        }
    }

    /** 全部留痕消息（只读快照） */
    public List<DocMessage> records() {
        return Collections.unmodifiableList(new ArrayList<>(records));
    }

    /** 按 topic 过滤留痕消息 */
    public List<DocMessage> recordsOf(String topic) {
        List<DocMessage> filtered = new ArrayList<>();
        for (DocMessage message : records) {
            if (topic.equals(message.getTopic())) {
                filtered.add(message);
            }
        }
        return filtered;
    }

    /** 已注册监听器数（诊断/测试用） */
    public int listenerCount() {
        return listeners.size();
    }

    /** 清空留痕（测试隔离用） */
    public void clearRecords() {
        records.clear();
    }
}
