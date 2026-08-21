// 审计日志查询 API（P-OP-01；GET /api/v1/audit，next_token 续翻，limit 1~100 越界 40002）
import { get } from '@/shared/api/request';
import type { AuditPageResult, AuditQueryParams } from '@/modules/ops/auditTypes';

/** limit 契约上限（后端 1~100，越界返回 40002） */
export const AUDIT_LIMIT_MAX = 100;

export function queryAuditLogs(params: AuditQueryParams): Promise<AuditPageResult> {
  const query: Record<string, string | number> = { limit: params.limit };
  if (params.target_type) query.target_type = params.target_type;
  if (params.target_code) query.target_code = params.target_code;
  if (params.target_uuid) query.target_uuid = params.target_uuid;
  if (params.operation) query.operation = params.operation;
  if (params.operator) query.operator = params.operator;
  if (params.start_time !== undefined) query.start_time = params.start_time;
  if (params.end_time !== undefined) query.end_time = params.end_time;
  if (params.next_token) query.next_token = params.next_token;
  return get<AuditPageResult>('/audit', query);
}
