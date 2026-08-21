// 错误码 → 用户文案映射（design/10 §7.2；完整错误码以 prd/12 §三为准）
// 后端契约保证非 0 码返回可读 message（prd/11 §五），故优先展示后端文案；
// 后端文案缺失（如网关直出 429）时回退到本映射表。
import { ApiError } from '@/shared/types/api';

const FALLBACK_TEXT: Record<number, string> = {
  403: '权限不足，拒绝访问',
  40001: '必填项缺失，请检查表单',
  40002: '字段格式不正确，请检查输入',
  40003: '当前状态不允许该流转操作',
  40004: '引用的数据不存在或已停用',
  40005: '字段值已存在',
  40006: '编码已存在',
  40007: '该字段不可修改',
  40008: '当前状态不允许修改',
  40009: '仅暂存状态的单据可删除',
  40010: '该数据正被其他记录引用，无法删除/停用',
  40011: '发布校验未通过',
  40401: '数据不存在或已被删除',
  40901: '数据已被他人修改，请刷新后重试',
  42201: '规则校验未通过',
  42202: '操作失败，已回滚',
  42901: '操作过于频繁，请稍后再试',
  50000: '系统繁忙，请稍后重试',
};

/** 归一化错误 → 用户可读文案（不直接暴露技术异常，prd/11 §五） */
export function toUserMessage(err: ApiError): string {
  // 403 角色越权：统一「权限不足」文案（步骤 40；后端 message 优先）
  if ((err.code === 403 || err.httpStatus === 403) && (!err.message || err.message === 'error')) {
    return FALLBACK_TEXT[403];
  }
  // 429 携带 Retry-After 时附带等待提示
  if (err.code === 42901 && err.retryAfter && err.retryAfter > 0) {
    return `操作过于频繁，请 ${err.retryAfter} 秒后重试`;
  }
  const backendText = err.message && err.message !== 'error' ? err.message : '';
  return backendText || FALLBACK_TEXT[err.code] || FALLBACK_TEXT[50000];
}
