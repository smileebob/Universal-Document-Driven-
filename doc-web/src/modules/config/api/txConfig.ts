// 单据事务步骤配置 API（TX-01：PUT/GET /schema/doc/{code}/tx-config，保存即生效）
import { get, put } from '@/shared/api/request';

/** 单个事务步骤（后端 TxConfigDTO.TxStepDTO 契约） */
export interface TxStep {
  /** 步骤序号（1 起，升序执行/逆序冲正） */
  seq: number;
  /** 步骤名（SAP 同步 / APS 排程 / 通知 等） */
  name: string;
  /** 正向执行端点（出站 HTTP POST，必填 http(s)） */
  action_url: string;
  /** 冲正端点（可空=该步无冲正动作） */
  compensate_url?: string;
  /** 超时毫秒（默认 10000，1~60000） */
  timeout_ms?: number;
  /** 连接类异常重试次数（默认 0，0~3） */
  retry?: number;
}

/** 单个流转的事务步骤组（transition{from,to} + steps[]） */
export interface TxTransition {
  from_status: number;
  to_status: number;
  steps: TxStep[];
}

/** GET 响应视图 */
export interface TxConfigView {
  doc_code: string;
  transitions: TxTransition[];
  update_time?: number;
}

/** PUT 请求体（仅 transitions） */
export interface TxConfigSavePayload {
  transitions: TxTransition[];
}

/** PUT 响应 */
export interface TxConfigSaveResult {
  doc_code: string;
  update_time: number;
}

/** 查询事务步骤配置（未配置 → 40401，页面 silent 接管） */
export function getTxConfig(code: string, config?: { silent?: boolean }): Promise<TxConfigView> {
  return get(`/schema/doc/${code}/tx-config`, undefined, config);
}

/** 保存事务步骤配置（upsert，保存即时失效缓存） */
export function saveTxConfig(code: string, payload: TxConfigSavePayload): Promise<TxConfigSaveResult> {
  return put(`/schema/doc/${code}/tx-config`, payload);
}
