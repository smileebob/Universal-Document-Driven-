// 规则库与单据规则绑定接口（RE-01/02/05，后端 RuleController / DocRuleConfigController）
import type { AxiosRequestConfig } from 'axios';
import { del, get, post, put } from '@/shared/api/request';
import type { PageResult } from '@/shared/types/api';
import type { DocRuleConfigView, RuleDef, StatusRuleBinding } from '../types';

export interface RuleListParams {
  keyword?: string;
  enabled?: boolean;
  offset: number;
  limit: number;
}

export interface RuleSavePayload {
  rule_name: string;
  rule_type: string;
  expression?: string;
  interface_code?: string;
  enabled?: boolean;
  description?: string;
}

/** 写操作响应（后端口径：rule_uuid/version） */
export interface RuleWriteResult {
  rule_uuid: string;
  version: number;
}

/** 在线测试响应（后端 RuleLibService.test：passed/skipped/reason） */
export interface RuleTestResult {
  rule_uuid: string;
  passed: boolean;
  skipped: boolean;
  reason?: string;
}

// ---------- 规则库 CRUD（RE-01/02） ----------
export function listRules(params: RuleListParams): Promise<PageResult<RuleDef>> {
  return get('/rule', params as unknown as Record<string, unknown>);
}

export function getRule(uuid: string): Promise<RuleDef> {
  return get(`/rule/${uuid}`);
}

export function createRule(payload: RuleSavePayload): Promise<RuleWriteResult> {
  return post('/rule', payload);
}

export function updateRule(uuid: string, payload: RuleSavePayload): Promise<RuleWriteResult> {
  return put(`/rule/${uuid}`, payload);
}

/** 删除规则（被单据绑定 40010+bound_docs） */
export function deleteRule(uuid: string): Promise<void> {
  return del(`/rule/${uuid}`);
}

/** 在线测试（POST /rule/{uuid}/test，与运行时同一执行器） */
export function testRule(uuid: string, context: Record<string, unknown>): Promise<RuleTestResult> {
  return post(`/rule/${uuid}/test`, { context }, { silent: true });
}

// ---------- 单据规则绑定（RE-05，保存即生效） ----------

export interface DocRuleConfigSavePayload {
  create_rules: string[];
  status_rules: StatusRuleBinding[];
}

/** 保存绑定（引用规则不存在 40004+missing_rules） */
export function saveDocRuleConfig(
  code: string,
  payload: DocRuleConfigSavePayload,
  config?: AxiosRequestConfig,
): Promise<{ doc_code: string; version: number }> {
  return put(`/schema/doc/${code}/rules`, payload, config);
}

/** 查询绑定（未配置 40401，页面按空配置处理） */
export function getDocRuleConfig(code: string): Promise<DocRuleConfigView> {
  return get(`/schema/doc/${code}/rules`);
}
