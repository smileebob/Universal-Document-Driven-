<script setup lang="ts">
// 登录页（阶段一：后端未就绪，前端 mock 登录；网关联调后替换为真实接口）
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
  if (!form.username.trim()) return;
  loading.value = true;
  userStore.login({ username: form.username.trim(), role: form.role });
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
      <div class="mb-8 text-center">
        <h1 class="text-xl font-bold">
          通用单据驱动业务平台
        </h1>
        <p class="mt-2 text-sm text-gray-500">
          doc-web · 阶段一 mock 登录
        </p>
      </div>
      <a-form
        :model="form"
        layout="vertical"
        @finish="handleLogin"
      >
        <a-form-item
          label="用户名"
          name="username"
          :rules="[{ required: true, message: '请输入用户名' }]"
        >
          <a-input
            v-model:value="form.username"
            placeholder="请输入用户名"
            allow-clear
          />
        </a-form-item>
        <a-form-item
          label="登录角色"
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
          登 录
        </a-button>
      </a-form>
    </a-card>
  </div>
</template>
