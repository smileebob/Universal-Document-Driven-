<script setup lang="ts">
// 404 页（数据不存在/已删除亦复用该文案，prd/06 §7.2）
import { useRouter } from 'vue-router';

const router = useRouter();
</script>

<template>
  <a-result
    status="404"
    title="页面不存在"
    sub-title="数据不存在或已被删除"
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
