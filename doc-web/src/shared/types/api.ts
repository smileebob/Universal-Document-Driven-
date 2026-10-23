// 统一响应包装类型（与 prd/11 §一 契约对应）
export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  trace_id?: string;
}

// 列表响应统一结构（prd/11 §一：{ total, items }，深分页 token 模式另返回 next_token）
export interface PageResult<T> {
  total: number;
  items: T[];
  next_token?: string;
  aggregations?: Record<string, AggregationBucket[]>;
}

export interface AggregationBucket {
  key: number | string;
  count: number;
}

/**
 * 统一业务错误（design/10 §7.1）：
 * - code≠0 的业务错误与 4xx/5xx 均归一化为 ApiError
 * - 页面侧以 silent 请求关闭默认 Toast 后可捕获本类做字段级/弹窗级处理
 */
export class ApiError extends Error {
  /** 业务错误码（prd/12 §三），网络异常时为 50000 */
  code: number;
  /** HTTP 状态码（网络错误时为 undefined） */
  httpStatus?: number;
  /** 错误明细（422 failed_rules / failed_step、400 field_errors 等） */
  data?: unknown;
  /** 链路追踪 ID，错误态展示供排查 */
  traceId?: string;
  /** 429 响应的 Retry-After（秒，prd/11 §一） */
  retryAfter?: number;

  constructor(code: number, message: string, data?: unknown, traceId?: string) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.data = data;
    this.traceId = traceId;
  }
}

/**
 * 角色常量（prd/01 §2.1 权限矩阵；与后端 PlatformRoles 对齐）。
 * 前端角色只控制展示与交互：演示模式可自选，真实模式应由已验证身份提供；
 * 服务端始终是最终授权方。
 */
export type Role = 'platform_admin' | 'business_admin' | 'business_user';

export const ROLE_LABELS: Record<Role, string> = {
  platform_admin: '平台管理员',
  business_admin: '业务管理员',
  business_user: '业务用户',
};
