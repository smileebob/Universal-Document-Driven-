// 运营监控 API（P-OP-02/03：Saga 死信处置 + Outbox 事件重发/消费证据）
import type { AxiosRequestConfig } from 'axios';
import { get, post } from '@/shared/api/request';
import type {
  ConsumedListResult,
  DeadLetterPageResult,
  DeadLetterResolveResult,
  DeadLetterRetryResult,
  ResendResult,
  RetryEventListResult,
} from '@/modules/ops/types';

// ---------- Saga 死信（runtime-service） ----------

export interface DeadLetterListParams {
  /** 处置状态过滤（可空=全部） */
  handle_status?: number;
  page: number;
  size: number;
}

export function listDeadLetters(params: DeadLetterListParams): Promise<DeadLetterPageResult> {
  return get('/saga/dead-letter', params as unknown as Record<string, unknown>);
}

/** 补完冲正重试；仍失败返回 42202（页面 silent 接管） */
export function retryDeadLetter(sagaId: string, config?: AxiosRequestConfig): Promise<DeadLetterRetryResult> {
  return post(`/saga/dead-letter/${sagaId}/retry`, undefined, config);
}

/** 人工处置关闭；已关闭返回 40901 */
export function resolveDeadLetter(
  sagaId: string,
  remark: string,
  config?: AxiosRequestConfig,
): Promise<DeadLetterResolveResult> {
  return post(`/saga/dead-letter/${sagaId}/resolve`, { remark }, config);
}

// ---------- Outbox 事件（runtime-service） ----------

/** 滞留/待人工事件列表（send_status=2 或 retry_count>0） */
export function listRetryableEvents(limit = 50): Promise<RetryEventListResult> {
  return get('/event/retry', { limit });
}

/** 手动重发：复位即投；已发送返回 40901 */
export function resendEvent(eventId: string, config?: AxiosRequestConfig): Promise<ResendResult> {
  return post(`/event/retry/${eventId}/resend`, undefined, config);
}

/** 消费证据（无真实订阅方时的消费落地证明，Outbox 链路可观测） */
export function listConsumedEvidences(limit = 20): Promise<ConsumedListResult> {
  return get('/event/consumed', { limit });
}
