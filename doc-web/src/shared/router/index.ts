// 路由骨架 + 全局前置守卫（规范 N-04，design/10 §2.3）
// 权限集中在守卫统一判定，禁止页面内各自判断
import { createRouter, createWebHistory } from 'vue-router';
import type { RouteRecordRaw } from 'vue-router';
import { useUserStore } from '@/shared/stores/user';
import { useMenuStore } from '@/shared/stores/menu';
import { registerBizRoutes } from '@/shared/router/dynamic';
import type { Role } from '@/shared/types/api';

declare module 'vue-router' {
  interface RouteMeta {
    title?: string;
    /** 允许访问的角色；缺省=全部已登录角色 */
    roles?: Role[];
  }
}

/** 配置控制台：平台管理员 + 业务管理员（prd/01 §2.1） */
const CONFIG_ADMIN_ROLES: Role[] = ['platform_admin', 'business_admin'];
/** 平台管理员专属：事务配置 / 补偿处理 / 事件重发（后端 @RequireRoles(PLATFORM_ADMIN)） */
const PLATFORM_ADMIN_ROLES: Role[] = ['platform_admin'];

// 静态路由（构建期已知）；全部 () => import(...) 懒加载（N-04）
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/shared/views/LoginView.vue'),
    meta: { title: '登录' },
  },
  {
    path: '/',
    name: 'layout',
    component: () => import('@/modules/runtime/views/BizLayout.vue'),
    redirect: '/biz',
    children: [
      {
        // P-RT-00 业务数据工作台（布局壳 + 动态菜单入口）
        path: 'biz',
        name: 'biz-home',
        component: () => import('@/modules/runtime/views/BizHome.vue'),
        meta: { title: '业务数据工作台' },
      },
      {
        // P-EC-01 实体定义列表
        path: 'config/entity',
        name: 'config-entity',
        component: () => import('@/modules/config/views/EntityList.vue'),
        meta: { title: '实体定义', roles: CONFIG_ADMIN_ROLES },
      },
      {
        // P-EC-02 实体定义编辑器（无 code=新建）
        path: 'config/entity/edit/:code?',
        name: 'config-entity-edit',
        component: () => import('@/modules/config/views/EntityEditor.vue'),
        meta: { title: '实体编辑器', roles: CONFIG_ADMIN_ROLES },
      },
      {
        // P-DC-01 单据定义列表（阶段二）
        path: 'config/doc',
        name: 'config-doc',
        component: () => import('@/modules/config/views/DocList.vue'),
        meta: { title: '单据定义', roles: CONFIG_ADMIN_ROLES },
      },
      {
        // P-DC-02 单据定义编辑器（无 code=新建）
        path: 'config/doc/edit/:code?',
        name: 'config-doc-edit',
        component: () => import('@/modules/config/views/DocEditor.vue'),
        meta: { title: '单据编辑器', roles: CONFIG_ADMIN_ROLES },
      },
      {
        // SM-01/02 状态机配置（G6 图形预览，vendor-g6 分包懒加载）
        path: 'config/doc/:code/state-machine',
        name: 'config-doc-state-machine',
        component: () => import('@/modules/config/views/StateMachineConfig.vue'),
        meta: { title: '状态机配置', roles: CONFIG_ADMIN_ROLES },
      },
      {
        // RE-05 单据规则绑定
        path: 'config/doc/:code/rules',
        name: 'config-doc-rules',
        component: () => import('@/modules/config/views/RuleBinding.vue'),
        meta: { title: '规则绑定', roles: CONFIG_ADMIN_ROLES },
      },
      {
        // TX-01 单据事务步骤配置（阶段三 P-TX-01；仅平台管理员，后端 PUT @RequireRoles(PLATFORM_ADMIN)）
        path: 'config/doc/:code/tx-config',
        name: 'config-doc-tx-config',
        component: () => import('@/modules/config/views/TxConfigEditor.vue'),
        meta: { title: '事务步骤配置', roles: PLATFORM_ADMIN_ROLES },
      },
      {
        // RE-01 规则库列表（prd/01 §2.1：平台管理员可编辑，业务管理员只读访问）
        path: 'config/rule',
        name: 'config-rule',
        component: () => import('@/modules/config/views/RuleList.vue'),
        meta: { title: '规则库', roles: CONFIG_ADMIN_ROLES },
      },
      {
        // RE-02 规则编辑器（无 uuid=新建，含在线测试；仅平台管理员，业务管理员只读不进编辑器）
        path: 'config/rule/edit/:uuid?',
        name: 'config-rule-edit',
        component: () => import('@/modules/config/views/RuleEditor.vue'),
        meta: { title: '规则编辑器', roles: PLATFORM_ADMIN_ROLES },
      },
      {
        // P-OP-01 审计日志查询（阶段四；平台管理员全部 + 业务管理员本域，prd/10）
        path: 'ops/audit',
        name: 'ops-audit',
        component: () => import('@/modules/ops/views/AuditQuery.vue'),
        meta: { title: '审计日志', roles: CONFIG_ADMIN_ROLES },
      },
      {
        // P-OP-02 补偿失败处理（Saga 死信；仅平台管理员，prd/10）
        path: 'ops/dead-letter',
        name: 'ops-dead-letter',
        component: () => import('@/modules/ops/views/SagaDeadLetter.vue'),
        meta: { title: '补偿失败处理', roles: PLATFORM_ADMIN_ROLES },
      },
      {
        // P-OP-03 事件重发监控（Outbox；仅平台管理员，prd/10）
        path: 'ops/event-retry',
        name: 'ops-event-retry',
        component: () => import('@/modules/ops/views/EventRetry.vue'),
        meta: { title: '事件重发', roles: PLATFORM_ADMIN_ROLES },
      },
      {
        path: '403',
        name: 'forbidden',
        component: () => import('@/shared/views/ForbiddenView.vue'),
        meta: { title: '暂无权限' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/shared/views/NotFound.vue'),
    meta: { title: '页面不存在' },
  },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

// 全局前置守卫：未登录跳登录（携带 redirect）→ 动态路由注册 → 角色权限 → 放行
router.beforeEach(async (to) => {
  const userStore = useUserStore();

  if (to.name === 'login') {
    return true;
  }
  if (!userStore.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } };
  }

  // 动态路由：菜单未加载（或已失效）时拉取并注册，随后以 fullPath 重入
  const menuStore = useMenuStore();
  if (!menuStore.loaded || menuStore.dirty) {
    try {
      await menuStore.fetchMenus();
      registerBizRoutes(router);
    } catch {
      // 拉取失败不阻断静态路由访问；动态路由缺失将落入 404
    }
    return to.fullPath;
  }

  // 角色权限：meta.roles 不包含当前角色 → 403（prd/06 §7.2）
  if (to.meta.roles && !to.meta.roles.includes(userStore.role)) {
    return { path: '/403' };
  }

  if (to.meta.title) {
    document.title = `${to.meta.title} - 通用单据驱动业务平台`;
  }
  return true;
});

export default router;
