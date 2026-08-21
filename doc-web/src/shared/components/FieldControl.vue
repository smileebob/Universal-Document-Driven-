<script setup lang="ts">
// FieldControl：属性 → 控件映射（design/10 §3.2，prd/06 §4.1）
// DynamicForm 与 DetailTable 单元格共用；table 类型由 DynamicForm 调度 DetailTable
import { computed } from 'vue';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import type { AttributeDef } from '@/modules/config/types';
import { formatDisplayValue } from '@/shared/utils/form';
import RefSelect from './RefSelect.vue';

const props = withDefaults(
  defineProps<{
    attr: AttributeDef;
    modelValue: unknown;
    /** 是否可输入（mode 与 editable 的合成结果由父组件计算） */
    editable?: boolean;
    error?: string;
  }>(),
  { editable: false, error: '' },
);

const emit = defineEmits<{ (e: 'update:modelValue', value: unknown): void }>();

/** ref 值类型收窄（模板内禁用 `|` 联合断言，避免 vue/no-deprecated-filter 误报） */
const refValue = computed<string | undefined>(() => props.modelValue as string | undefined);

const pickerValue = computed<Dayjs | null>(() => {
  if (props.modelValue === undefined || props.modelValue === null || props.modelValue === '') {
    return null;
  }
  const d = dayjs(props.modelValue as string | number);
  return d.isValid() ? d : null;
});

function onDateChange(date: Dayjs | null): void {
  // date 输出 yyyy-MM-dd；datetime 输出 ISO8601（prd/06 §4.1）
  if (!date) {
    emit('update:modelValue', undefined);
  } else if (props.attr.data_type === 'date') {
    emit('update:modelValue', date.format('YYYY-MM-DD'));
  } else {
    emit('update:modelValue', date.toISOString());
  }
}
</script>

<template>
  <!-- ref：引用选择器（独立封装，含只读链接态） -->
  <RefSelect
    v-if="attr.data_type === 'ref' && attr.ref_entity_code"
    :ref-entity-code="attr.ref_entity_code"
    :display-field="attr.display_field"
    :model-value="refValue"
    :readonly="!editable"
    :allow-clear="!attr.required"
    @update:model-value="emit('update:modelValue', $event)"
  />

  <!-- 只读呈现：置灰/纯文本（editable=false 或 readonly 模式，prd/06 §4.2） -->
  <span v-else-if="!editable">
    {{ formatDisplayValue(attr, modelValue) }}
  </span>

  <!-- string：单行文本，maxlength 硬限制 + 字数显示 -->
  <a-input
    v-else-if="attr.data_type === 'string'"
    :value="modelValue as string"
    :maxlength="attr.max_length"
    :show-count="!!attr.max_length"
    allow-clear
    @update:value="emit('update:modelValue', $event)"
  />

  <!-- integer：禁止小数 -->
  <a-input-number
    v-else-if="attr.data_type === 'integer'"
    :value="modelValue as number"
    :precision="0"
    class="w-full"
    @update:value="emit('update:modelValue', $event)"
  />

  <!-- decimal：按 precision 限制小数位 -->
  <a-input-number
    v-else-if="attr.data_type === 'decimal'"
    :value="modelValue as number"
    :precision="attr.precision ?? 2"
    class="w-full"
    @update:value="emit('update:modelValue', $event)"
  />

  <!-- boolean：开关，默认 false -->
  <a-switch
    v-else-if="attr.data_type === 'boolean'"
    :checked="!!modelValue"
    @update:checked="emit('update:modelValue', $event)"
  />

  <!-- date：日期选择器 -->
  <a-date-picker
    v-else-if="attr.data_type === 'date'"
    :value="pickerValue"
    class="w-full"
    @change="onDateChange"
  />

  <!-- datetime：日期时间选择器 -->
  <a-date-picker
    v-else-if="attr.data_type === 'datetime'"
    :value="pickerValue"
    show-time
    class="w-full"
    @change="onDateChange"
  />

  <span
    v-else
    class="text-gray-400"
  >-</span>

  <div
    v-if="error"
    class="mt-1 text-xs text-red-500"
  >
    {{ error }}
  </div>
</template>
