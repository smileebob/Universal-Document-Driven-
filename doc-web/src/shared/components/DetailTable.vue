<script setup lang="ts">
// DetailTable：可编辑明细表格（design/10 §3.5、prd/06 §4.4）
// 列 = table 属性的 sub_attributes；行内控件复用 FieldControl；只读场景渲染普通表格
import { computed, ref } from 'vue';
import { Modal } from 'ant-design-vue';
import type { AttributeDef } from '@/modules/config/types';
import { isEmptyValue } from '@/shared/utils/form';
import FieldControl from './FieldControl.vue';

const props = withDefaults(
  defineProps<{
    subAttributes: AttributeDef[];
    modelValue: Record<string, unknown>[];
    readonly?: boolean;
    required?: boolean;
    /** 表名（错误文案用） */
    tableName?: string;
  }>(),
  { readonly: false, required: false, tableName: '明细表' },
);

const emit = defineEmits<{
  (e: 'update:modelValue', rows: Record<string, unknown>[]): void;
}>();

/** 单元格错误：`${rowIndex}:${code}` → 文案 */
const cellErrors = ref<Record<string, string>>({});
const tableError = ref('');

const columns = computed(() => {
  const base = props.subAttributes
    .filter((a) => a.visible)
    .map((a) => ({ title: a.required ? `*${a.name}` : a.name, dataIndex: a.code, key: a.code }));
  return props.readonly ? base : [...base, { title: '操作', dataIndex: '__action', key: '__action' }];
});

/**
 * 携带稳定行键的数据源（AntDV 4 已弃用 rowKey 函数的 index 入参，
 * dev 控制台会报 error 级告警；改为在行对象上挂 __rowKey 字段）
 */
const keyedRows = computed(() => props.modelValue.map((row, i) => ({ ...row, __rowKey: String(i) })));

function findAttr(code: string): AttributeDef | undefined {
  return props.subAttributes.find((a) => a.code === code);
}

function updateCell(index: number, code: string, value: unknown): void {
  const next = props.modelValue.map((row, i) => (i === index ? { ...row, [code]: value } : row));
  delete cellErrors.value[`${index}:${code}`];
  tableError.value = '';
  emit('update:modelValue', next);
}

function addRow(): void {
  tableError.value = '';
  emit('update:modelValue', [...props.modelValue, {}]);
}

function removeRow(index: number): void {
  const row = props.modelValue[index];
  const hasData = Object.values(row).some((v) => !isEmptyValue(v));
  const doRemove = () => {
    emit('update:modelValue', props.modelValue.filter((_, i) => i !== index));
  };
  // 行内已有数据时二次确认（prd/09 P-RT-02）
  if (hasData) {
    Modal.confirm({
      title: '确认删除该行？',
      content: '该行已填写数据，删除后不可恢复',
      okText: '删除',
      cancelText: '取消',
      onOk: doRemove,
    });
  } else {
    doRemove();
  }
}

/** 行级校验：子属性 required；必填表至少 1 行（design/10 §3.5） */
function validate(): boolean {
  const errors: Record<string, string> = {};
  let ok = true;
  if (props.required && props.modelValue.length === 0) {
    tableError.value = `『${props.tableName}』至少需要一行`;
    return false;
  }
  tableError.value = '';
  props.modelValue.forEach((row, index) => {
    for (const sub of props.subAttributes.filter((a) => a.visible)) {
      if (sub.required && isEmptyValue(row[sub.code])) {
        errors[`${index}:${sub.code}`] = `『${sub.name}』为必填项`;
        ok = false;
      }
    }
  });
  cellErrors.value = errors;
  return ok;
}

defineExpose({ validate });
</script>

<template>
  <div>
    <a-table
      :columns="columns"
      :data-source="keyedRows"
      :pagination="false"
      size="small"
      row-key="__rowKey"
    >
      <template #bodyCell="{ column, record, index }">
        <a-button
          v-if="column.key === '__action'"
          type="link"
          danger
          size="small"
          @click="removeRow(index)"
        >
          删除
        </a-button>
        <FieldControl
          v-else
          :attr="findAttr(column.dataIndex)!"
          :model-value="record[column.dataIndex]"
          :editable="!readonly"
          :error="cellErrors[`${index}:${column.dataIndex}`] ?? ''"
          @update:model-value="updateCell(index, column.dataIndex, $event)"
        />
      </template>
    </a-table>

    <div class="mt-2 flex items-center justify-between">
      <a-button
        v-if="!readonly"
        type="dashed"
        size="small"
        @click="addRow"
      >
        + 添加行
      </a-button>
      <span v-else />
      <span class="text-xs text-gray-500">共 {{ modelValue.length }} 行</span>
    </div>
    <div
      v-if="tableError"
      class="mt-1 text-xs text-red-500"
    >
      {{ tableError }}
    </div>
  </div>
</template>
