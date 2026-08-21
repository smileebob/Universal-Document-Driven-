<script setup lang="ts">
// 403 无权限页（prd/06 §7.2：「暂无权限访问」+ 返回入口）
import { useRouter } from 'vue-router';

const router = useRouter();
</script>

<template>
  <a-result
    status="403"
    title="暂无权限访问"
    sub-title="您没有访问该页面的权限，请联系管理员"
  >
    <template #extra>
      <a-button
        type="primary"
        @click="router.push('/biz')"
      >
        返回工作台
      </a-button>
    </template>
  </a-result>
</template>
