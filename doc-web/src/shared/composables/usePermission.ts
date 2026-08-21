// usePermission：角色权限判定（prd/01 §2.1 权限矩阵 → 路由守卫与按钮渲染统一调用）
// 无权限的按钮不渲染（而非置灰，prd/06 §八）；角色名与后端 PlatformRoles 对齐
import { useUserStore } from '@/shared/stores/user';
import type { Role } from '@/shared/types/api';

export type PermAction =
  | 'config:manage' // 配置控制台（P-EC/P-DC/状态机/规则绑定）
  | 'rule:view' // 规则库页面访问（prd/01 §2.1：平台管理员✔ / 业务管理员查看）
  | 'rule:manage' // 规则库写操作（新增/编辑/删除/启停；后端 RuleController 写接口 @RequireRoles(PLATFORM_ADMIN)）
  | 'tx:manage' // 事务步骤配置（仅平台管理员，后端 TxConfig PUT @RequireRoles(PLATFORM_ADMIN)）
  | 'ops:manage' // 运营监控：补偿失败处理/事件重发（仅平台管理员）
  | 'audit:query' // 审计日志查询（后端 /audit @RequireRoles(PLATFORM_ADMIN, BUSINESS_ADMIN)）
  | 'data:create'
  | 'data:edit'
  | 'data:delete'
  | 'data:transition'; // 单据状态流转（后端无角色限制，三角色均可执行状态操作）

const PLATFORM: Role = 'platform_admin';
const BUSINESS_ADMIN: Role = 'business_admin';
const BUSINESS_USER: Role = 'business_user';

/**
 * 权限矩阵（阶段四落位，prd/01 §2.1 + 后端 @RequireRoles 口径）：
 * - business_user 只读（数据写接口服务端限管理员，越权返回 403）
 * - business_admin：配置写 + 数据写 + 审计；规则库只读（查看/在线测试）；无事务配置/运营
 * - platform_admin：全权
 */
const MATRIX: Record<PermAction, Role[]> = {
  'config:manage': [PLATFORM, BUSINESS_ADMIN],
  'rule:view': [PLATFORM, BUSINESS_ADMIN],
  'rule:manage': [PLATFORM],
  'tx:manage': [PLATFORM],
  'ops:manage': [PLATFORM],
  'audit:query': [PLATFORM, BUSINESS_ADMIN],
  'data:create': [PLATFORM, BUSINESS_ADMIN],
  'data:edit': [PLATFORM, BUSINESS_ADMIN],
  'data:delete': [PLATFORM, BUSINESS_ADMIN],
  'data:transition': [PLATFORM, BUSINESS_ADMIN, BUSINESS_USER],
};

export function usePermission() {
  const userStore = useUserStore();

  function can(action: PermAction): boolean {
    return MATRIX[action].includes(userStore.role);
  }

  function hasRole(roles: Role[]): boolean {
    return roles.includes(userStore.role);
  }

  return { can, hasRole };
}
