<script setup lang="ts">
// SubAttrEditModal：table 属性的子属性编辑弹窗（prd/07 P-DC-02）
// 子属性类型域为标量子集（不可再为 table/ref，明细行内不支持远程引用选择）
import { reactive, ref, watch } from 'vue';
import type { AttributeDef, DataType } from '@/modules/config/types';

const props = withDefaults(
  defineProps<{
    open: boolean;
    /** null=新增子属性 */
    attr: AttributeDef | null;
    /** 已存在的子属性编码（重复校验） */
    existingCodes: string[];
  }>(),
  { attr: null },
);

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void;
  (e: 'save', attr: AttributeDef): void;
}>();

const CODE_PATTERN = /^[a-z][a-z0-9_]{1,49}$/;

const SUB_TYPE_OPTIONS: { value: DataType; label: string }[] = [
  { value: 'string', label: '字符串 string' },
  { value: 'integer', label: '整数 integer' },
  { value: 'decimal', label: '小数 decimal' },
  { value: 'boolean', label: '布尔 boolean' },
  { value: 'date', label: '日期 date' },
  { value: 'datetime', label: '日期时间 datetime' },
];

function emptyForm(): AttributeDef {
  return {
    name: '',
    code: '',
    data_type: 'string',
    required: false,
    visible: true,
    editable: true,
    is_list_column: false,
    is_searchable: false,
  };
}

const form = reactive<AttributeDef>(emptyForm());
const errorText = ref('');

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    Object.assign(form, props.attr ? { ...props.attr } : emptyForm());
    errorText.value = '';
  },
);

function close(): void {
  emit('update:open', false);
}

function save(): void {
  if (!form.name.trim()) {
    errorText.value = '请输入子属性名称';
    return;
  }
  if (!CODE_PATTERN.test(form.code)) {
    errorText.value = '编码须小写字母开头，仅小写字母/数字/下划线，2~50 字符';
    return;
  }
  if (!props.attr && props.existingCodes.includes(form.code)) {
    errorText.value = '子属性编码已存在';
    return;
  }
  emit('save', { ...form });
  close();
}
</script>

<template>
  <a-modal
    :open="open"
    :title="attr ? '编辑子属性' : '添加子属性'"
    :width="440"
    @cancel="close"
    @ok="save"
  >
    <a-form layout="vertical">
      <a-form-item
        label="子属性名称"
        required
      >
        <a-input
          v-model:value="form.name"
          placeholder="如：数量"
        />
      </a-form-item>
      <a-form-item
        label="子属性编码"
        required
      >
        <a-input
          v-model:value="form.code"
          :disabled="!!attr"
          placeholder="如：qty"
        />
      </a-form-item>
      <a-form-item
        label="数据类型"
        required
      >
        <a-select
          v-model:value="form.data_type"
          :options="SUB_TYPE_OPTIONS"
        />
      </a-form-item>
      <a-form-item
        v-if="form.data_type === 'string'"
        label="最大长度"
      >
        <a-input-number
          v-model:value="form.max_length"
          :min="1"
          class="w-full"
        />
      </a-form-item>
      <a-form-item
        v-if="form.data_type === 'decimal'"
        label="小数位数"
      >
        <a-input-number
          v-model:value="form.precision"
          :min="0"
          :max="10"
          class="w-full"
        />
      </a-form-item>
      <a-form-item label="必填">
        <a-switch v-model:checked="form.required" />
      </a-form-item>
      <div
        v-if="errorText"
        class="text-xs text-red-500"
      >
        {{ errorText }}
      </div>
    </a-form>
  </a-modal>
</template>
