// userStore：登录态 / JWT token / 角色（三角色权限矩阵，design/10 §6.3）
import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import type { Role } from '@/shared/types/api';

const TOKEN_KEY = 'doc_web_token';
const USER_KEY = 'doc_web_username';
const ROLE_KEY = 'doc_web_role';

/** 阶段四角色更名迁移：biz_admin/biz_user → business_admin/business_user */
const LEGACY_ROLE_MAP: Record<string, Role> = {
  biz_admin: 'business_admin',
  biz_user: 'business_user',
};

function loadRole(): Role {
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

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) ?? '');
  const username = ref(localStorage.getItem(USER_KEY) ?? '');
  const role = ref<Role>(loadRole());

  const isLoggedIn = computed(() => !!token.value);

  /**
   * 阶段一后端未就绪：登录为前端 mock（本地生成 token），
   * 网关联调后替换为真实登录接口（遗留项）。
   */
  function login(payload: { username: string; role: Role }): void {
    token.value = `mock_${crypto.randomUUID()}`;
    username.value = payload.username;
    role.value = payload.role;
    // 【遗留】token 持久化在 localStorage：存在 XSS 窃取风险，
    // 真实登录接入时改为「内存持有 token + httpOnly Cookie 会话」方案，
    // 届时一并移除 TOKEN_KEY 相关读写（含初始化与 logout 清理）。
    localStorage.setItem(TOKEN_KEY, token.value);
    localStorage.setItem(USER_KEY, username.value);
    localStorage.setItem(ROLE_KEY, role.value);
  }

  /** mock 环境下切换角色（便于三角色权限矩阵自验） */
  function switchRole(newRole: Role): void {
    role.value = newRole;
    localStorage.setItem(ROLE_KEY, newRole);
  }

  function logout(): void {
    token.value = '';
    username.value = '';
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    localStorage.removeItem(ROLE_KEY);
  }

  return { token, username, role, isLoggedIn, login, switchRole, logout };
});
