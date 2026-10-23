import axios, { AxiosError } from 'axios';
import type { AxiosRequestConfig } from 'axios';
import { message } from 'ant-design-vue';
import { ApiError } from '@/shared/types/api';
import type { ApiResponse } from '@/shared/types/api';
import { toUserMessage } from '@/shared/utils/errorMessages';
import { useUserStore } from '@/shared/stores/user';

// 扩展 axios 请求配置：silent=true 时拦截器不弹默认 Toast，
// 由调用方自行捕获 ApiError 做字段级/弹窗级处理（design/10 §7.1）
declare module 'axios' {
  export interface AxiosRequestConfig {
    silent?: boolean;
  }
}

// 唯一 axios 实例：全应用仅此处创建（规范 N-05）
// baseURL 仅经网关统一入口 /api/v1（vite dev 代理保留，.env.example）
const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  timeout: 10000,
});

const WRITE_METHODS = new Set(['post', 'put', 'patch', 'delete']);

// 请求拦截器：注入 JWT 请求头 + 写请求自动生成幂等头（N-05、prd/11 §一）
request.interceptors.request.use(
  (config) => {
    const userStore = useUserStore();
    if (userStore.token) {
      config.headers.Authorization = `Bearer ${userStore.token}`;
    }
    // 演示身份头仅在显式 demo token（mock_ 前缀）下发送：
    // dev 关闭网关认证时才由网关保留，真实 JWT 模式会被权威 claims 覆盖/忽略。
    if (userStore.token.startsWith('mock_')) {
      if (userStore.username && !config.headers['X-User-Id']) {
        config.headers['X-User-Id'] = userStore.username;
      }
      if (!config.headers['X-User-Roles']) {
        config.headers['X-User-Roles'] = userStore.role;
      }
    }
    // 写请求自动生成 X-Idempotency-Key（调用方已显式传入时不覆盖）
    const method = (config.method ?? 'get').toLowerCase();
    if (WRITE_METHODS.has(method) && !config.headers['X-Idempotency-Key']) {
      config.headers['X-Idempotency-Key'] = crypto.randomUUID();
    }
    return config;
  },
  (error) => Promise.reject(error),
);

/** 401 统一跳登录（N-05）；直接走 location 避免与 router 的循环依赖 */
function redirectToLogin(): void {
  const current = window.location.pathname + window.location.search;
  if (!current.startsWith('/login')) {
    window.location.href = `/login?redirect=${encodeURIComponent(current)}`;
  }
}

/** 统一错误消息出口：非 silent 请求弹 Toast（design/10 §7.1） */
function finishError(err: ApiError, config?: AxiosRequestConfig): ApiError {
  if (!config?.silent) {
    message.error(toUserMessage(err));
  }
  return err;
}

/** 将 axios 错误/HTTP 错误体归一化为 ApiError（4xx/5xx 响应体仍为统一结构） */
function normalizeAxiosError(error: AxiosError): ApiError {
  const status = error.response?.status;
  const body = error.response?.data as ApiResponse<unknown> | undefined;
  let err: ApiError;
  if (body && typeof body === 'object' && typeof body.code === 'number') {
    err = new ApiError(body.code, body.message, body.data, body.trace_id);
  } else if (status === 403) {
    // 服务端角色校验 403 响应体非统一结构时兜底（H-01：不泄漏要求明细）
    err = new ApiError(403, '权限不足，拒绝访问');
  } else if (error.code === 'ECONNABORTED') {
    err = new ApiError(50000, '请求超时，请稍后重试');
  } else {
    err = new ApiError(50000, '网络异常，请检查网络连接');
  }
  err.httpStatus = status;
  // 429 读取 Retry-After 头（秒，prd/11 §一 / §5.1）
  if (status === 429) {
    const retryAfter = Number(error.response?.headers?.['retry-after']);
    if (!Number.isNaN(retryAfter) && retryAfter > 0) {
      err.retryAfter = retryAfter;
    }
  }
  return err;
}

// 响应拦截器：统一解包 { code, message, data, trace_id }（prd/11 §一）
request.interceptors.response.use(
  (resp) => {
    const body = resp.data as ApiResponse<unknown>;
    if (body && typeof body.code === 'number') {
      if (body.code !== 0) {
        const err = new ApiError(body.code, body.message, body.data, body.trace_id);
        err.httpStatus = resp.status;
        return Promise.reject(finishError(err, resp.config));
      }
      // 调用方直接拿到 data
      return body.data as never;
    }
    // 非统一结构响应原样返回
    return resp.data;
  },
  (error: AxiosError) => {
    if (error.response?.status === 401) {
      // 401：清理本地会话（token/角色），再跳登录；403 保持会话并展示无权限
      useUserStore().logout();
      redirectToLogin();
    }
    return Promise.reject(finishError(normalizeAxiosError(error), error.config));
  },
);

// 类型化请求助手：拦截器已解包，返回值即 data（N-05：域 api 经此封装，不直连 axios）
export function get<T>(url: string, params?: Record<string, unknown>, config?: AxiosRequestConfig): Promise<T> {
  return request.get(url, { params, ...config }) as Promise<T>;
}

export function post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return request.post(url, data, config) as Promise<T>;
}

export function put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return request.put(url, data, config) as Promise<T>;
}

export function del<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return request.delete(url, config) as Promise<T>;
}

export default request;
