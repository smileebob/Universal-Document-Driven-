<script setup lang="ts">
// 登录页：演示登录仅在显式 demo 模式可用（本地模拟身份，不代表真实权限）
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useUserStore } from '@/shared/stores/user';
import { ROLE_LABELS } from '@/shared/types/api';
import type { Role } from '@/shared/types/api';

const route = useRoute();
const router = useRouter();
const userStore = useUserStore();

const form = reactive({
  username: 'admin',
  role: 'platform_admin' as Role,
});
const loading = ref(false);

const roleOptions = (Object.keys(ROLE_LABELS) as Role[]).map((value) => ({
  value,
  label: ROLE_LABELS[value],
}));

function handleLogin(): void {
  if (!userStore.demoLogin) return;
  if (!form.username.trim()) return;
  loading.value = true;
  try {
    userStore.login({ username: form.username.trim(), role: form.role });
  } catch {
    loading.value = false;
    return;
  }
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/';
  void router.push(redirect).finally(() => {
    loading.value = false;
  });
}
</script>

<template>
  <div class="flex h-screen items-center justify-center bg-gray-100">
    <a-card
      class="w-100"
      :bordered="false"
    >
      <div class="mb-6 text-center">
        <h1 class="text-xl font-bold">
          通用单据驱动业务平台
        </h1>
        <p class="mt-2 text-sm text-gray-500">
          doc-web
        </p>
      </div>

      <!-- 演示身份必须显著提示，避免被误认为真实权限验证 -->
      <a-alert
        v-if="userStore.demoLogin"
        type="warning"
        show-icon
        class="mb-4"
        message="演示登录，不代表真实权限"
        description="本页在本地生成模拟身份与角色，仅用于本地演示；真实环境请通过平台统一身份提供方登录。"
      />
      <a-alert
        v-else
        type="info"
        show-icon
        class="mb-4"
        message="真实认证模式"
        description="演示登录已关闭，本页不生成模拟 token，也不提供角色切换。请通过平台统一身份提供方完成登录。"
      />

      <a-form
        v-if="userStore.demoLogin"
        :model="form"
        layout="vertical"
        @finish="handleLogin"
      >
        <a-form-item
          label="演示用户名"
          name="username"
          :rules="[{ required: true, message: '请输入用户名' }]"
        >
          <a-input
            v-model:value="form.username"
            placeholder="请输入演示用户名"
            allow-clear
          />
        </a-form-item>
        <a-form-item
          label="演示角色（不代表真实授权）"
          name="role"
        >
          <a-select
            v-model:value="form.role"
            :options="roleOptions"
          />
        </a-form-item>
        <a-button
          type="primary"
          block
          html-type="submit"
          :loading="loading"
        >
          演示登录
        </a-button>
      </a-form>
    </a-card>
  </div>
</template>
