<script setup lang="ts">
// RuleFailedModal：规则校验失败明细弹窗（design/10 §7.3、prd/09 P-RT-03）
// 422 RULE_FAILED：逐条列出未通过规则与原因
import type { FailedRule } from '@/modules/runtime/types';

withDefaults(
  defineProps<{
    open: boolean;
    failedRules: FailedRule[];
    /** 已通过规则名（契约增补 #4，可选） */
    passedRules?: string[];
    title?: string;
  }>(),
  { passedRules: () => [], title: '规则校验未通过' },
);

const emit = defineEmits<{ (e: 'update:open', value: boolean): void }>();

function close(): void {
  emit('update:open', false);
}
</script>

<template>
  <a-modal
    :open="open"
    :title="title"
    :footer="null"
    @cancel="close"
  >
    <ul class="m-0 list-none p-0">
      <li
        v-for="(rule, i) in failedRules"
        :key="`f-${i}`"
        class="mb-2 flex items-start"
      >
        <span class="mr-2 text-red-500">❌</span>
        <span>
          <b>{{ rule.rule_name }}</b>
          <span
            v-if="rule.reason"
            class="text-gray-600"
          >：{{ rule.reason }}</span>
          <span
            v-else
            class="text-gray-400"
          >：规则『{{ rule.rule_name }}』校验未通过</span>
        </span>
      </li>
      <li
        v-for="(name, i) in passedRules"
        :key="`p-${i}`"
        class="mb-2 flex items-start"
      >
        <span class="mr-2 text-green-600">✅</span>
        <span>{{ name }}：通过</span>
      </li>
    </ul>
    <div class="mt-4 text-right">
      <a-button
        type="primary"
        @click="close"
      >
        知道了
      </a-button>
    </div>
  </a-modal>
</template>
