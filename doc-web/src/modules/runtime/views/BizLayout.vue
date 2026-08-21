<script setup lang="ts">
// BizLayout：布局壳（P-RT-00，prd/06 §二）
// 顶部栏（平台名/面包屑/用户）+ 左侧导航（配置管理 + 运营监控 + 业务数据动态菜单）+ 内容区
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useMenuStore } from '@/shared/stores/menu';
import { useSchemaStore } from '@/shared/stores/schema';
import { useUserStore } from '@/shared/stores/user';
import { usePermission } from '@/shared/composables/usePermission';
import { ROLE_LABELS } from '@/shared/types/api';
import type { Role } from '@/shared/types/api';

const route = useRoute();
const router = useRouter();
const userStore = useUserStore();
const menuStore = useMenuStore();
const schemaStore = useSchemaStore();
const { can } = usePermission();

const selectedKeys = computed(() => [route.path]);
const openKeys = ref<string[]>([
  'config',
  'ops',
  ...menuStore.groupedMenus.map((g) => `group:${g.code}`),
]);

/** 规则库菜单：平台管理员 + 业务管理员可见（prd/01 §2.1；业务管理员进入后只读呈现） */
const showRuleMenu = computed(() => can('rule:view'));

/** 运营监控分组：平台管理员全量；业务管理员仅审计日志（阶段四权限矩阵） */
const showOpsMenu = computed(() => can('ops:manage') || can('audit:query'));

function onMenuClick(info: { key: string }): void {
  void router.push(info.key);
}

function onUserMenuClick(info: { key: string }): void {
  if (info.key === '__logout') {
    userStore.logout();
    void router.push('/login');
    return;
  }
  // 切换角色（mock）：角色变化后回到工作台，由守卫重新做权限判定
  userStore.switchRole(info.key as Role);
  void router.push('/biz');
}

/** 菜单接口失败降级重试（后端不可用时展示提示而非无限重定向） */
const retrying = ref(false);
async function retryMenus(): Promise<void> {
  retrying.value = true;
  try {
    await menuStore.retryMenus();
  } finally {
    retrying.value = false;
  }
}

onMounted(() => {
  // ③ TTL 兜底：前台期间每 30s 静默比对全局 schema_version（design/10 §6.2）
  schemaStore.startVersionProbe();
});
</script>

<template>
  <a-layout class="h-screen">
    <a-layout-sider
      :width="220"
      theme="light"
      class="overflow-auto"
    >
      <div class="flex h-14 items-center justify-center border-b border-gray-100 font-bold">
        通用单据驱动平台
      </div>
      <a-menu
        v-model:open-keys="openKeys"
        mode="inline"
        :selected-keys="selectedKeys"
        @click="onMenuClick"
      >
        <a-menu-item key="/biz">
          业务数据工作台
        </a-menu-item>

        <!-- 配置管理分组：平台管理员/业务管理员可见（prd/06 §二） -->
        <a-sub-menu
          v-if="can('config:manage')"
          key="config"
        >
          <template #title>
            配置管理
          </template>
          <a-menu-item key="/config/entity">
            实体定义
          </a-menu-item>
          <a-menu-item key="/config/doc">
            单据定义
          </a-menu-item>
          <!-- 规则库：仅平台管理员（路由守卫二次兜底） -->
          <a-menu-item
            v-if="showRuleMenu"
            key="/config/rule"
          >
            规则库
          </a-menu-item>
        </a-sub-menu>

        <!-- 运营监控分组（prd/10 P-OP）：死信/事件重发仅平台管理员，审计日志平台+业务管理员 -->
        <a-sub-menu
          v-if="showOpsMenu"
          key="ops"
        >
          <template #title>
            运营监控
          </template>
          <a-menu-item
            v-if="can('audit:query')"
            key="/ops/audit"
          >
            审计日志
          </a-menu-item>
          <a-menu-item
            v-if="can('ops:manage')"
            key="/ops/dead-letter"
          >
            补偿失败处理
          </a-menu-item>
          <a-menu-item
            v-if="can('ops:manage')"
            key="/ops/event-retry"
          >
            事件重发
          </a-menu-item>
        </a-sub-menu>

        <!-- 业务数据分组：菜单挂载配置动态生成（EC-05） -->
        <a-sub-menu
          v-for="group in menuStore.groupedMenus"
          :key="`group:${group.code}`"
        >
          <template #title>
            {{ group.label }}
          </template>
          <a-menu-item
            v-for="item in group.items"
            :key="menuStore.menuPath(item)"
          >
            {{ item.menu_name }}
          </a-menu-item>
        </a-sub-menu>
      </a-menu>
    </a-layout-sider>

    <a-layout>
      <a-layout-header
        class="flex items-center justify-between bg-white px-6"
        :style="{ height: '56px' }"
      >
        <a-breadcrumb>
          <a-breadcrumb-item>通用单据驱动业务平台</a-breadcrumb-item>
          <a-breadcrumb-item>{{ route.meta.title ?? '' }}</a-breadcrumb-item>
        </a-breadcrumb>
        <a-dropdown>
          <span class="cursor-pointer text-sm">
            {{ userStore.username }}（{{ ROLE_LABELS[userStore.role] }}）
          </span>
          <template #overlay>
            <a-menu @click="onUserMenuClick">
              <a-menu-item-group title="切换角色（mock）">
                <a-menu-item
                  v-for="(label, key) in ROLE_LABELS"
                  :key="key"
                >
                  {{ label }}
                </a-menu-item>
              </a-menu-item-group>
              <a-menu-divider />
              <a-menu-item key="__logout">
                退出登录
              </a-menu-item>
            </a-menu>
          </template>
        </a-dropdown>
      </a-layout-header>

      <a-layout-content class="overflow-auto bg-gray-50 p-4">
        <!-- 菜单加载失败降级：错误提示 + 重试，避免白屏 -->
        <a-alert
          v-if="menuStore.loadError"
          type="error"
          show-icon
          closable
          class="mb-4"
          message="业务菜单加载失败"
          description="后端菜单接口暂不可用，静态页面仍可访问，服务恢复后请点击重试。"
        >
          <template #action>
            <a-button
              size="small"
              type="primary"
              :loading="retrying"
              @click="retryMenus"
            >
              重试
            </a-button>
          </template>
        </a-alert>
        <router-view />
      </a-layout-content>
    </a-layout>
  </a-layout>
</template>
