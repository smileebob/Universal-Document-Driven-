<script setup lang="ts">
// DynamicForm：动态表单引擎（design/10 §三，prd/06 §四）
// 配置侧预览（P-EC-03）与运行侧录入（P-RT-02）复用同一渲染器；
// preview/create/edit/readonly 四模式走同一渲染管线，禁止分支复制
import { computed, ref } from 'vue';
import type { AttributeDef } from '@/modules/config/types';
import { buildDefaultModel, isEmptyValue } from '@/shared/utils/form';
import DetailTable from './DetailTable.vue';
import FieldControl from './FieldControl.vue';

export type FormMode = 'preview' | 'create' | 'edit' | 'readonly';

const props = defineProps<{
  attributes: AttributeDef[];
  modelValue: Record<string, unknown>;
  mode: FormMode;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', value: Record<string, unknown>): void;
}>();

const fieldErrors = ref<Record<string, string>>({});
/** table 属性 → DetailTable 实例（行级校验委托） */
const tableRefs: Record<string, InstanceType<typeof DetailTable> | null> = {};

// visible=false 不渲染（新增与详情都不显示，prd/06 §4.2）
const visibleAttrs = computed(() => props.attributes.filter((a) => a.visible));

/** editable=false → 只读呈现；preview 模式控件可交互（但不提交，P-EC-03） */
function isEditable(attr: AttributeDef): boolean {
  if (props.mode === 'readonly') return false;
  if (props.mode === 'preview') return true;
  return attr.editable;
}

function setField(code: string, value: unknown): void {
  if (fieldErrors.value[code]) {
    const next = { ...fieldErrors.value };
    delete next[code];
    fieldErrors.value = next;
  }
  emit('update:modelValue', { ...props.modelValue, [code]: value });
}

function setTableRef(code: string, el: unknown): void {
  tableRefs[code] = el as InstanceType<typeof DetailTable> | null;
}

/** 前端校验：必填/明细行；失败定位首个错误并滚动标红（prd/09 P-RT-02） */
function validate(): Promise<{ valid: boolean; firstError?: string }> {
  const errors: Record<string, string> = {};
  let firstError = '';
  for (const attr of visibleAttrs.value) {
    if (attr.data_type === 'table') {
      const ok = tableRefs[attr.code]?.validate() ?? true;
      if (!ok) {
        errors[attr.code] = `请检查『${attr.name}』的填写内容`;
        if (!firstError) firstError = attr.code;
      }
      continue;
    }
    if (attr.required && isEmptyValue(props.modelValue[attr.code])) {
      errors[attr.code] = `『${attr.name}』为必填项`;
      if (!firstError) firstError = attr.code;
    }
  }
  fieldErrors.value = errors;
  if (firstError) {
    document
      .getElementById(`field-${firstError}`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }
  return Promise.resolve({ valid: !firstError, firstError: firstError || undefined });
}

/** 后端 400 字段校验错误回填入口（design/10 §3.6） */
function setFieldError(code: string, msg: string): void {
  fieldErrors.value = { ...fieldErrors.value, [code]: msg };
}

function resetFields(): void {
  fieldErrors.value = {};
  emit('update:modelValue', buildDefaultModel(props.attributes));
}

defineExpose({ validate, resetFields, setFieldError });
</script>

<template>
  <a-row :gutter="[16, 0]">
    <a-col
      v-for="attr in visibleAttrs"
      :id="`field-${attr.code}`"
      :key="attr.code"
      :span="attr.data_type === 'table' ? 24 : 12"
      class="mb-4"
    >
      <!-- table：明细表独占整行 -->
      <template v-if="attr.data_type === 'table'">
        <div class="mb-1 text-sm font-medium">
          <span
            v-if="attr.required"
            class="mr-1 text-red-500"
          >*</span>
          {{ attr.name }}
        </div>
        <DetailTable
          :ref="(el: unknown) => setTableRef(attr.code, el)"
          :sub-attributes="attr.sub_attributes ?? []"
          :model-value="(modelValue[attr.code] as Record<string, unknown>[]) ?? []"
          :readonly="!isEditable(attr)"
          :required="attr.required"
          :table-name="attr.name"
          @update:model-value="setField(attr.code, $event)"
        />
        <div
          v-if="fieldErrors[attr.code]"
          class="mt-1 text-xs text-red-500"
        >
          {{ fieldErrors[attr.code] }}
        </div>
      </template>

      <!-- 标量/ref：两列栅格 -->
      <template v-else>
        <label class="mb-1 block text-sm text-gray-700">
          <span
            v-if="attr.required && isEditable(attr)"
            class="mr-1 text-red-500"
          >*</span>
          {{ attr.name }}
        </label>
        <FieldControl
          :attr="attr"
          :model-value="modelValue[attr.code]"
          :editable="isEditable(attr)"
          @update:model-value="setField(attr.code, $event)"
        />
        <div
          v-if="fieldErrors[attr.code]"
          class="mt-1 text-xs text-red-500"
        >
          {{ fieldErrors[attr.code] }}
        </div>
      </template>
    </a-col>
  </a-row>
</template>
