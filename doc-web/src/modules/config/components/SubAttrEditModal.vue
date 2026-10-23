<script setup lang="ts">
// SubAttrEditModal：table 属性的子属性编辑弹窗（prd/07 P-DC-02）
// 支持标量与 ref（引用选择），仅禁止 table 再嵌 table
import { computed, reactive, ref, watch } from 'vue';
import { listEntities } from '@/modules/config/api/schema';
import type { AttributeDef, DataType, EntitySchema } from '@/modules/config/types';

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
  { value: 'ref', label: '引用 ref' },
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
const refEntities = ref<EntitySchema[]>([]);
const refLoading = ref(false);

const displayFieldOptions = computed(() => {
  const schema = refEntities.value.find((e) => e.entity_code === form.ref_entity_code);
  return (schema?.attributes ?? [])
    .filter((a) => a.data_type === 'string')
    .map((a) => ({ value: a.code, label: `${a.name}(${a.code})` }));
});

async function ensureRefEntities(): Promise<void> {
  if (refEntities.value.length > 0) return;
  refLoading.value = true;
  try {
    const res = await listEntities({
      entity_type: 'base_data',
      status: 1,
      offset: 0,
      limit: 100,
    });
    refEntities.value = res.items;
  } catch {
    // 加载失败保留空候选，已配置值仍按原值展示
  } finally {
    refLoading.value = false;
  }
}

function onTypeChange(type: DataType): void {
  if (type !== 'string') form.max_length = undefined;
  if (type !== 'decimal') form.precision = undefined;
  if (type !== 'ref') {
    form.ref_entity_code = undefined;
    form.display_field = undefined;
  } else {
    void ensureRefEntities();
  }
}

function onRefEntityChange(): void {
  form.display_field = undefined;
}

watch(
  () => props.open,
  async (open) => {
    if (!open) return;
    Object.assign(form, props.attr ? { ...props.attr } : emptyForm());
    // 子属性不可再嵌 table，进入弹窗时清空任何历史嵌套
    form.sub_attributes = undefined;
    errorText.value = '';
    if (form.data_type === 'ref') await ensureRefEntities();
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
  if (form.data_type === 'ref' && !form.ref_entity_code) {
    errorText.value = '请选择引用实体';
    return;
  }
  emit('save', { ...form, sub_attributes: undefined });
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
          @change="onTypeChange"
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
      <a-form-item
        v-if="form.data_type === 'ref'"
        label="引用实体"
        required
      >
        <a-select
          v-model:value="form.ref_entity_code"
          :options="refEntities.map((e) => ({ value: e.entity_code, label: e.entity_name }))"
          :loading="refLoading"
          show-search
          option-filter-prop="label"
          placeholder="选择基础数据实体"
          @change="onRefEntityChange"
        />
      </a-form-item>
      <a-form-item
        v-if="form.data_type === 'ref'"
        label="展示属性"
      >
        <a-select
          v-model:value="form.display_field"
          :options="displayFieldOptions"
          allow-clear
          placeholder="默认取第一个字符串列表列"
        />
      </a-form-item>
      <div class="grid grid-cols-3 gap-2">
        <a-form-item label="必填">
          <a-switch v-model:checked="form.required" />
        </a-form-item>
        <a-form-item label="可见">
          <a-switch v-model:checked="form.visible" />
        </a-form-item>
        <a-form-item label="可编辑">
          <a-switch v-model:checked="form.editable" />
        </a-form-item>
      </div>
      <div
        v-if="errorText"
        class="text-xs text-red-500"
      >
        {{ errorText }}
      </div>
    </a-form>
  </a-modal>
</template>
