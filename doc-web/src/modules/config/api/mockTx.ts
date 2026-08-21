// Saga 步骤 Mock 桩 API（runtime-service：/mock/tx/{system}/*，测试辅助）
// 故障注入 fail-once：下一次 action/compensate 调用强制失败后自动复位
import { del, get, post } from '@/shared/api/request';

/** 预设目标系统（任意标识均可，面板预置常用三项） */
export const MOCK_SYSTEMS = ['sap', 'aps', 'notify'] as const;
export type MockSystem = (typeof MOCK_SYSTEMS)[number] | string;

/** 调用记录条目（GET /{system}/calls） */
export interface MockCallRecord {
  /** action 正向 / compensate 冲正 */
  phase: string;
  /** X-Saga-Id 透传头证据 */
  saga_id_header?: string;
  body?: Record<string, unknown>;
  /** ok / fail */
  result: string;
  ts?: number;
}

export interface MockCallsResult {
  system: string;
  count: number;
  calls: MockCallRecord[];
}

export interface FailOnceResult {
  system: string;
  fail_once: boolean;
}

/** 设置故障注入：指定系统下一次调用强制失败 */
export function setFailOnce(system: MockSystem): Promise<FailOnceResult> {
  return post(`/mock/tx/${system}/fail-once`);
}

/** 清除故障注入开关 */
export function clearFailOnce(system: MockSystem): Promise<FailOnceResult> {
  return del(`/mock/tx/${system}/fail-once`);
}

/** 查询调用记录（最近 200 条） */
export function getMockCalls(system: MockSystem): Promise<MockCallsResult> {
  return get(`/mock/tx/${system}/calls`);
}

/** 清空调用记录 */
export function clearMockCalls(system: MockSystem): Promise<{ system: string; cleared: boolean }> {
  return del(`/mock/tx/${system}/calls`);
}
