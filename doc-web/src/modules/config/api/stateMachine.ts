// 单据状态机配置接口（SM-01/02，后端 StateMachineController；保存即生效）
import type { AxiosRequestConfig } from 'axios';
import { get, put } from '@/shared/api/request';
import type { StateMachineTransition, StateMachineView, StatusDef } from '../types';

export interface StateMachineSavePayload {
  states: StatusDef[];
  transitions: StateMachineTransition[];
}

export interface StateMachineSaveResult {
  doc_code: string;
  version: number;
  /** 软告警（如不可达状态），保存成功但需提示 */
  warnings: string[];
}

/** 保存状态机（硬校验失败 40011+reasons；并发冲突 40901） */
export function saveStateMachine(
  code: string,
  payload: StateMachineSavePayload,
  config?: AxiosRequestConfig,
): Promise<StateMachineSaveResult> {
  return put(`/schema/doc/${code}/state-machine`, payload, config);
}

/** 查询状态机（未配置 40401，页面侧按「未配置」处理） */
export function getStateMachine(code: string): Promise<StateMachineView> {
  return get(`/schema/doc/${code}/state-machine`);
}
