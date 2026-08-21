<script setup lang="ts">
// 应用根组件：ConfigProvider 集中配置 zh_CN 语言包（design/10 §1.2）
import zhCN from 'ant-design-vue/es/locale/zh_CN';
</script>

<template>
  <a-config-provider :locale="zhCN">
    <router-view />
  </a-config-provider>
</template>
