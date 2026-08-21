<script setup lang="ts">
// P-RT-00 业务数据工作台：默认重定向至首个可见菜单（design/10 §八）
import { onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useMenuStore } from '@/shared/stores/menu';
import { usePermission } from '@/shared/composables/usePermission';

const router = useRouter();
const menuStore = useMenuStore();
const { can } = usePermission();

onMounted(() => {
  const first = menuStore.menus[0];
  if (first) {
    void router.replace(menuStore.menuPath(first));
  }
});
</script>

<template>
  <a-card>
    <a-empty description="暂无已挂载的业务对象菜单">
      <template
        v-if="can('config:manage')"
        #default
      >
        <p class="text-gray-500">
          请前往「配置管理 → 实体定义」创建实体并配置菜单挂载（EC-05）
        </p>
        <a-button
          type="primary"
          @click="router.push('/config/entity')"
        >
          前往实体定义
        </a-button>
      </template>
    </a-empty>
  </a-card>
</template>
