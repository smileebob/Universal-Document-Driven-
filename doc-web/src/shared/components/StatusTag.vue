<script setup lang="ts">
// StatusTag：状态标签（严格引用 prd/12 §四配色语义，design/10 §4.2）
import { computed } from 'vue';
import type { StatusDef } from '@/modules/config/types';

const props = withDefaults(
  defineProps<{
    status: number;
    /** 单据状态定义 status_defs（提供时展示状态名） */
    statusDefs?: StatusDef[];
    /** doc=业务状态；schema=定义状态（草稿100/生效1/停用0，配置侧列表用） */
    kind?: 'doc' | 'schema';
  }>(),
  { statusDefs: () => [], kind: 'doc' },
);

// 定义状态（prd/12 §4.2）：草稿灰 / 生效绿 / 停用橙
const SCHEMA_TAG: Record<number, { text: string; color: string }> = {
  100: { text: '草稿', color: 'default' },
  1: { text: '生效', color: 'success' },
  0: { text: '停用', color: 'warning' },
};

const tag = computed(() => {
  if (props.kind === 'schema') {
    return SCHEMA_TAG[props.status] ?? { text: String(props.status), color: 'default' };
  }
  const def = props.statusDefs.find((s) => s.status === props.status);
  const name = def?.name ?? String(props.status);
  // 业务状态颜色推导（prd/12 §4.1）：终态深灰、100 暂存灰、其余进行中蓝
  if (def?.terminal) return { text: name, color: '#595959' };
  if (props.status === 100) return { text: name, color: 'default' };
  return { text: name, color: 'processing' };
});
</script>

<template>
  <a-tag :color="tag.color">
    {{ tag.text }}
  </a-tag>
</template>
