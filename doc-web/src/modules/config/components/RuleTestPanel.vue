<script setup lang="ts">
// RuleTestPanel：规则在线测试面板（RE-02，POST /rule/{uuid}/test）
// 上下文 key-value 录入（值优先按 JSON 解析：数字/布尔），结果展示 passed/skipped/reason
import { ref } from 'vue';
import { testRule } from '@/modules/config/api/rule';
import type { RuleTestResult } from '@/modules/config/api/rule';
import { ApiError } from '@/shared/types/api';
import { toUserMessage } from '@/shared/utils/errorMessages';

const props = defineProps<{
  /** 规则 uuid；为空表示尚未保存，测试不可用 */
  ruleUuid: string;
}>();

interface ContextRow {
  key: string;
  value: string;
}

const rows = ref<ContextRow[]>([{ key: '', value: '' }]);
const testing = ref(false);
const result = ref<RuleTestResult | null>(null);
const errorText = ref('');

function addRow(): void {
  rows.value.push({ key: '', value: '' });
}

function removeRow(index: number): void {
  rows.value.splice(index, 1);
}

/** 值解析：优先 JSON（数字/布尔/带引号字符串），失败按原始字符串 */
function parseValue(raw: string): unknown {
  try {
    return JSON.parse(raw) as unknown;
  } catch {
    return raw;
  }
}

function buildContext(): Record<string, unknown> {
  const ctx: Record<string, unknown> = {};
  for (const row of rows.value) {
    const key = row.key.trim();
    if (!key) continue;
    ctx[key] = parseValue(row.value);
  }
  return ctx;
}

async function runTest(): Promise<void> {
  if (!props.ruleUuid) return;
  errorText.value = '';
  result.value = null;
  testing.value = true;
  try {
    result.value = await testRule(props.ruleUuid, buildContext());
  } catch (err) {
    errorText.value = err instanceof ApiError ? toUserMessage(err) : '测试请求失败';
  } finally {
    testing.value = false;
  }
}
</script>

<template>
  <a-card
    size="small"
    title="在线测试"
    class="border border-gray-100"
  >
    <a-alert
      v-if="!ruleUuid"
      type="info"
      show-icon
      message="请先保存规则后再进行在线测试"
      class="mb-3"
    />

    <div class="mb-2 text-xs text-gray-500">
      上下文变量（值按 JSON 解析：如 100、true、"abc"；无法解析时按原始字符串）
    </div>
    <div
      v-for="(row, idx) in rows"
      :key="idx"
      class="mb-2 flex items-center gap-2"
    >
      <a-input
        v-model:value="row.key"
        placeholder="变量名，如 amount"
        class="w-40"
      />
      <a-input
        v-model:value="row.value"
        placeholder="值，如 100"
        class="flex-1"
      />
      <a-button
        type="link"
        danger
        size="small"
        :disabled="rows.length <= 1"
        @click="removeRow(idx)"
      >
        删除
      </a-button>
    </div>
    <div class="mb-3 flex gap-2">
      <a-button
        size="small"
        @click="addRow"
      >
        + 添加变量
      </a-button>
      <a-button
        type="primary"
        size="small"
        :disabled="!ruleUuid"
        :loading="testing"
        @click="runTest"
      >
        执行测试
      </a-button>
    </div>

    <!-- 测试结果：passed ✅ / 失败 ❌ reason / skipped ⚠️（RE-02） -->
    <a-alert
      v-if="result"
      :type="result.skipped ? 'warning' : result.passed ? 'success' : 'error'"
      show-icon
      :message="result.skipped ? '规则被跳过（skipped）' : result.passed ? '测试通过（passed）' : '测试未通过（failed）'"
      :description="result.reason || (result.passed ? '规则校验通过' : '')"
    />
    <a-alert
      v-else-if="errorText"
      type="error"
      show-icon
      :message="errorText"
    />
  </a-card>
</template>
