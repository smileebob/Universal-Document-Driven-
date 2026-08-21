<script setup lang="ts">
// RefSelect：引用选择器（design/10 §3.4、prd/06 §4.3）
// 存储值=被引用数据 uuid；展示值=display_field；远程搜索防抖 300ms
import { onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import type { EntitySchema } from '@/modules/config/types';
import { queryData } from '@/modules/runtime/api/query';
import type { QueryFilter } from '@/modules/runtime/types';
import { useSchemaStore } from '@/shared/stores/schema';

/** contains 搜索关键字最大字符数：与后端口径一致为 30（wildcard 32 含两侧 *） */
const CONTAINS_MAX_LEN = 30;

const props = withDefaults(
  defineProps<{
    refEntityCode: string;
    displayField?: string;
    /** uuid */
    modelValue?: string;
    readonly?: boolean;
    allowClear?: boolean;
    placeholder?: string;
  }>(),
  { displayField: '', modelValue: undefined, readonly: false, allowClear: true, placeholder: '请选择' },
);

const emit = defineEmits<{ (e: 'update:modelValue', uuid?: string): void }>();

const router = useRouter();
const schemaStore = useSchemaStore();

const options = ref<{ value: string; label: string }[]>([]);
const searching = ref(false);
/** 只读态展示文本 */
const displayText = ref('');
/** 解析后的展示属性编码 */
let fieldCode = '';
let debounceTimer: ReturnType<typeof setTimeout> | null = null;

/** 展示字段：显式配置优先，否则取被引用实体第一个 string 且 is_list_column 的属性 */
async function ensureField(): Promise<string> {
  if (fieldCode) return fieldCode;
  if (props.displayField) {
    fieldCode = props.displayField;
    return fieldCode;
  }
  try {
    const schema = (await schemaStore.getSchema('entity', props.refEntityCode)) as EntitySchema;
    fieldCode =
      schema.attributes.find((a) => a.data_type === 'string' && a.is_list_column)?.code ?? 'name';
  } catch {
    fieldCode = 'name';
  }
  return fieldCode;
}

async function search(keyword: string): Promise<void> {
  const field = await ensureField();
  searching.value = true;
  try {
    const filters: QueryFilter[] = [
      { field: 'entity_code', op: 'eq', value: props.refEntityCode },
      { field: 'status', op: 'eq', value: 1 },
    ];
    // contains 口径：关键字截断至 30 字符，避免后端 400（契约值 30）
    const kw = keyword.trim().slice(0, CONTAINS_MAX_LEN);
    if (kw && field) {
      filters.push({ field: `attributes.${field}`, op: 'contains' as const, value: kw });
    }
    const res = await queryData({
      target: 'entity_data',
      filters,
      // 后端 QueryRequest 契约字段为 sorts（复数）
      sorts: [{ field: 'create_time', order: 'desc' }],
      page: { offset: 0, limit: 20 },
    });
    options.value = res.items.map((r) => ({
      value: r.uuid,
      label: String(r.attributes[field] ?? r.uuid),
    }));
  } finally {
    searching.value = false;
  }
}

/** 输入关键字防抖 300ms 检索 */
function onSearch(keyword: string): void {
  if (debounceTimer) clearTimeout(debounceTimer);
  debounceTimer = setTimeout(() => void search(keyword), 300);
}

/** 回填已选值的展示文本（编辑回显 / 只读态） */
async function ensureSelectedLabel(): Promise<void> {
  if (!props.modelValue) {
    displayText.value = '';
    return;
  }
  const field = await ensureField();
  try {
    const res = await queryData({
      target: 'entity_data',
      filters: [{ field: 'uuid', op: 'eq', value: props.modelValue }],
      page: { offset: 0, limit: 1 },
    });
    const record = res.items[0];
    if (record) {
      const label = String(record.attributes[field] ?? props.modelValue);
      displayText.value = label;
      if (!options.value.some((o) => o.value === props.modelValue)) {
        options.value = [{ value: props.modelValue, label }, ...options.value];
      }
    } else {
      displayText.value = '（引用数据不存在）';
    }
  } catch {
    displayText.value = props.modelValue;
  }
}

/** 只读态：可点击跳转被引用数据详情（prd/06 §六） */
function jumpToRef(): void {
  if (props.modelValue) {
    void router.push(`/biz/entity/${props.refEntityCode}/detail/${props.modelValue}`);
  }
}

function handleChange(uuid?: string): void {
  emit('update:modelValue', uuid || undefined);
}

onMounted(() => {
  void search('');
  void ensureSelectedLabel();
});

watch(
  () => props.modelValue,
  () => void ensureSelectedLabel(),
);
</script>

<template>
  <!-- 只读态：展示值 + 可点击跳转链接 -->
  <a
    v-if="readonly && modelValue"
    href="#"
    @click.prevent="jumpToRef"
  >
    {{ displayText || modelValue }}
  </a>
  <span
    v-else-if="readonly"
    class="text-gray-400"
  >-</span>
  <a-select
    v-else
    :value="modelValue"
    :options="options"
    :loading="searching"
    :allow-clear="allowClear"
    :placeholder="placeholder"
    show-search
    :filter-option="false"
    @search="onSearch"
    @change="handleChange"
  />
</template>
