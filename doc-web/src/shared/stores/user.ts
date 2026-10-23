// userStore：登录态 / JWT token / 角色（三角色权限矩阵，design/10 §6.3）
import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import type { Role } from '@/shared/types/api';

const TOKEN_KEY = 'doc_web_token';
const USER_KEY = 'doc_web_username';
const ROLE_KEY = 'doc_web_role';

/**
 * 演示登录开关：显式 VITE_DEMO_LOGIN=false 时关闭。
 * 默认开启仅为本地演示方便；生产构建必须显式关闭并接入统一身份提供方。
 */
export const DEMO_LOGIN_ENABLED = import.meta.env.VITE_DEMO_LOGIN !== 'false';

/** 阶段四角色更名迁移：biz_admin/biz_user → business_admin/business_user */
const LEGACY_ROLE_MAP: Record<string, Role> = {
  biz_admin: 'business_admin',
  biz_user: 'business_user',
};

function loadRole(): Role {
  // 真实模式不接受本地可篡改的角色作为界面权限依据；待身份提供方并入 claim 后补齐
  if (!DEMO_LOGIN_ENABLED) return 'business_user';
  const raw = localStorage.getItem(ROLE_KEY) ?? '';
  if (raw in LEGACY_ROLE_MAP) {
    const migrated = LEGACY_ROLE_MAP[raw];
    localStorage.setItem(ROLE_KEY, migrated);
    return migrated;
  }
  if (raw === 'platform_admin' || raw === 'business_admin' || raw === 'business_user') {
    return raw;
  }
  return 'business_user';
}

/** 读取登录态：真实模式清理历史遗留的演示 token，避免把 mock 身份带到真实网关 */
function loadToken(): string {
  const raw = localStorage.getItem(TOKEN_KEY) ?? '';
  if (!DEMO_LOGIN_ENABLED && raw.startsWith('mock_')) {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    localStorage.removeItem(ROLE_KEY);
    return '';
  }
  return raw;
}

export const useUserStore = defineStore('user', () => {
  const token = ref(loadToken());
  const username = ref(localStorage.getItem(USER_KEY) ?? '');
  const role = ref<Role>(loadRole());

  const isLoggedIn = computed(() => !!token.value);
  const demoLogin = DEMO_LOGIN_ENABLED;

  /**
   * 显式演示登录：仅 demo 模式生成本地 mock_ token 与可选演示角色；
   * 真实模式不伪造 token、不允许在客户端自选角色。
   */
  function login(payload: { username: string; role: Role }): void {
    if (!DEMO_LOGIN_ENABLED) {
      throw new Error('演示登录已关闭，请通过统一身份提供方完成真实认证');
    }
    token.value = `mock_${crypto.randomUUID()}`;
    username.value = payload.username;
    role.value = payload.role;
    // 【遗留】演示 token 持久化在 localStorage：存在 XSS 窃取风险；
    // 真实身份接入后改为「内存持有 token + httpOnly Cookie 会话」方案。
    localStorage.setItem(TOKEN_KEY, token.value);
    localStorage.setItem(USER_KEY, username.value);
    localStorage.setItem(ROLE_KEY, role.value);
  }

  /** 演示模式下切换演示角色（真实模式禁止客户端自选角色） */
  function switchRole(newRole: Role): void {
    if (!DEMO_LOGIN_ENABLED) return;
    role.value = newRole;
    localStorage.setItem(ROLE_KEY, newRole);
  }

  function logout(): void {
    token.value = '';
    username.value = '';
    role.value = 'business_user';
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    localStorage.removeItem(ROLE_KEY);
  }

  return { token, username, role, isLoggedIn, demoLogin, login, switchRole, logout };
});
