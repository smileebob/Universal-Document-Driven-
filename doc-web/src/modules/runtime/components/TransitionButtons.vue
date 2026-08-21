<script setup lang="ts">
// TransitionButtons：单据状态流转按钮组（prd/09 P-RT-03）
// 依据状态机 transitions（start=当前状态）渲染目标状态按钮；
// 429 锁忙冷却期由父组件传入 disabled + cooldownText
import { computed } from 'vue';
import type { StateMachineTransition, StatusDef } from '@/modules/config/types';

const props = withDefaults(
  defineProps<{
    currentStatus: number;
    transitions: StateMachineTransition[];
    statusDefs: StatusDef[];
    /** 冷却/请求中禁用全部按钮 */
    disabled?: boolean;
    /** 冷却倒计时提示文案 */
    cooldownText?: string;
  }>(),
  { disabled: false, cooldownText: '' },
);

const emit = defineEmits<{
  (e: 'transition', targetStatus: number): void;
}>();

const targets = computed(() => {
  const t = props.transitions.find((tr) => tr.start === props.currentStatus);
  return (t?.end ?? []).map((status) => ({
    status,
    def: props.statusDefs.find((s) => s.status === status),
  }));
});

function buttonLabel(status: number, def?: StatusDef): string {
  return def ? `流转到「${def.name}」` : `流转到 ${status}`;
}
</script>

<template>
  <div class="flex items-center gap-2">
    <template v-if="targets.length">
      <a-button
        v-for="t in targets"
        :key="t.status"
        :type="t.def?.terminal ? 'default' : 'primary'"
        :danger="t.def?.terminal"
        :disabled="disabled"
        @click="emit('transition', t.status)"
      >
        {{ buttonLabel(t.status, t.def) }}
      </a-button>
    </template>
    <span
      v-else
      class="text-xs text-gray-400"
    >
      当前状态无可用流转
    </span>
    <span
      v-if="cooldownText"
      class="text-xs text-orange-500"
    >
      {{ cooldownText }}
    </span>
  </div>
</template>
