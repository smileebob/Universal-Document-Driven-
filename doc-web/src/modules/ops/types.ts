// 运营监控域类型（prd/10 P-OP-02/03，后端 snake_case 契约）

// ---------- Saga 死信（P-OP-02） ----------

/** 处置状态：0 待处理 / 1 已重试（补完冲正成功）/ 2 已人工关闭 */
export type DeadLetterHandleStatus = 0 | 1 | 2;

export const HANDLE_STATUS_LABELS: Record<DeadLetterHandleStatus, string> = {
  0: '待处理',
  1: '已重试',
  2: '已人工关闭',
};

export interface SagaDeadLetterItem {
  saga_id: string;
  doc_code: string;
  doc_uuid: string;
  from_status: number;
  to_status: number;
  /** 失败步骤序号 */
  failed_step_seq?: number;
  /** 失败阶段：action 正向执行 / compensate 冲正 */
  fail_phase?: string;
  error_reason?: string;
  handle_status: number;
  handler?: string;
  handle_time?: number;
  handle_remark?: string;
  create_time?: number;
}

/** 死信分页响应（后端 SagaDeadLetterController：page/size 分页） */
export interface DeadLetterPageResult {
  total: number;
  page: number;
  size: number;
  items: SagaDeadLetterItem[];
}

/** 重试冲正成功响应 */
export interface DeadLetterRetryResult {
  saga_id: string;
  handle_status: number;
  message?: string;
}

/** 人工处置关闭响应 */
export interface DeadLetterResolveResult {
  saga_id: string;
  handle_status: number;
  handler?: string;
}

// ---------- Outbox 事件（P-OP-03） ----------

/** 发送状态：0 待发送 / 1 已发送 / 2 待人工（重试耗尽） */
export type EventSendStatus = 0 | 1 | 2;

export const SEND_STATUS_LABELS: Record<EventSendStatus, string> = {
  0: '待发送',
  1: '已发送',
  2: '待人工',
};

export interface OutboxEventItem {
  event_id: string;
  event_type?: string;
  target_code?: string;
  target_uuid?: string;
  send_status: number;
  retry_count?: number;
  last_error?: string;
  next_retry_time?: number;
  create_time?: number;
}

export interface RetryEventListResult {
  total: number;
  items: OutboxEventItem[];
}

/** 手动重发受理响应 */
export interface ResendResult {
  event_id: string;
  accepted: boolean;
}

// ---------- 消费证据（Outbox 链路可观测） ----------

export interface ConsumedEvidence {
  event_id?: string;
  event_type?: string;
  /** 消费模式：mock 模拟订阅 / rocketmq 等 */
  mode?: string;
  consumed_at?: number;
  /** 消息体摘要（≤200 字符） */
  body_summary?: string;
}

export interface ConsumedListResult {
  total: number;
  items: ConsumedEvidence[];
}
