// 审计日志域类型（P-OP-01，阶段四；后端 AuditQueryController snake_case 契约）
// GET /api/v1/audit：target_type/target_code/target_uuid/operation/operator/start_time/end_time/limit(1~100)/next_token
import type { PageResult } from '@/shared/types/api';

/** 审计操作类型（后端枚举） */
export type AuditOperation = 'create' | 'update' | 'delete' | 'transition' | 'config' | 'validation_reject';

export const AUDIT_OPERATION_LABELS: Record<AuditOperation, string> = {
  create: '创建',
  update: '更新',
  delete: '删除',
  transition: '状态流转',
  config: '配置变更',
  validation_reject: '校验拒绝',
};

export const AUDIT_OPERATION_COLORS: Record<AuditOperation, string> = {
  create: 'green',
  update: 'blue',
  delete: 'red',
  transition: 'purple',
  config: 'orange',
  validation_reject: 'default',
};

/** 审计目标类型（dev 仿真：/audit 仅含数据/流转审计，配置类审计缺失为已知限制） */
export const AUDIT_TARGET_TYPES = ['entity', 'doc', 'entity_schema', 'doc_schema'] as const;

export interface AuditItem {
  log_id: string;
  operation: AuditOperation;
  target_type: string;
  target_code: string;
  target_uuid?: string;
  operator: string;
  /** 操作时间（毫秒时间戳；列表按此倒序） */
  operate_time: number;
  /** 变更前 JSON 字符串快照（前端 JSON.parse 后 Diff） */
  before_value?: string;
  /** 变更后 JSON 字符串快照 */
  after_value?: string;
  ip?: string;
  trace_id?: string;
}

/** 审计查询参数（空值不下发） */
export interface AuditQueryParams {
  target_type?: string;
  target_code?: string;
  target_uuid?: string;
  operation?: AuditOperation;
  operator?: string;
  start_time?: number;
  end_time?: number;
  limit: number;
  next_token?: string;
}

export type AuditPageResult = PageResult<AuditItem>;
