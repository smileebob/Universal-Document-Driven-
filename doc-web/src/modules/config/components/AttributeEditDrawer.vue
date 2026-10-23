<script setup lang="ts">
// AttributeEditDrawer：属性编辑抽屉（prd/07 P-EC-02 / P-DC-02）
// 数据类型联动：string→max_length；decimal→precision；ref→引用实体+展示属性；
// table→子属性编辑（仅单据 allowTable=true 时可用，子属性不可再为 table）
import { computed, reactive, ref, watch } from 'vue';
import { listEntities } from '@/modules/config/api/schema';
import type { AttributeDef, DataType, EntitySchema } from '@/modules/config/types';
import SubAttrEditModal from './SubAttrEditModal.vue';

const props = withDefaults(
  defineProps<{
    open: boolean;
    /** null=新增属性 */
    attr: AttributeDef | null;
    /** 已存在的属性编码（重复校验） */
    existingCodes: string[];
    /** 允许 table 类型（单据编辑器传 true，实体编辑器默认 false） */
    allowTable?: boolean;
  }>(),
  { attr: null, allowTable: false },
);

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void;
  (e: 'save', attr: AttributeDef): void;
}>();

const CODE_PATTERN = /^[a-z][a-z0-9_]{1,49}$/;

// 数据类型选项：table 仅单据可用（prd/07 P-DC-02）
const dataTypeOptions = computed<{ value: DataType; label: string }[]>(() => {
  const base: { value: DataType; label: string }[] = [
    { value: 'string', label: '字符串 string' },
    { value: 'integer', label: '整数 integer' },
    { value: 'decimal', label: '小数 decimal' },
    { value: 'boolean', label: '布尔 boolean' },
    { value: 'date', label: '日期 date' },
    { value: 'datetime', label: '日期时间 datetime' },
    { value: 'ref', label: '引用 ref' },
  ];
  if (props.allowTable) base.push({ value: 'table', label: '明细表 table' });
  return base;
});

function emptyForm(): AttributeDef {
  return {
    name: '',
    code: '',
    data_type: 'string',
    required: false,
    visible: true,
    editable: true,
    unique: false,
    is_list_column: false,
    is_searchable: false,
  };
}

const form = reactive<AttributeDef>(emptyForm());
const errorText = ref('');
const refEntities = ref<EntitySchema[]>([]);
const refSchemaCache = new Map<string, EntitySchema>();
const displayFieldOptions = ref<{ value: string; label: string }[]>([]);

// ---------- table 子属性编辑 ----------
const subModalOpen = ref(false);
const editingSub = ref<AttributeDef | null>(null);

const isTable = computed(() => form.data_type === 'table');

function openAddSub(): void {
  editingSub.value = null;
  subModalOpen.value = true;
}

function openEditSub(attr: AttributeDef): void {
  editingSub.value = attr;
  subModalOpen.value = true;
}

function onSaveSub(attr: AttributeDef): void {
  const subs = [...(form.sub_attributes ?? [])];
  if (editingSub.value) {
    const index = subs.findIndex((a) => a.code === editingSub.value!.code);
    if (index >= 0) subs.splice(index, 1, attr);
  } else {
    subs.push(attr);
  }
  form.sub_attributes = subs;
}

function removeSub(attr: AttributeDef): void {
  form.sub_attributes = (form.sub_attributes ?? []).filter((a) => a.code !== attr.code);
}

const isEdit = () => !!props.attr;

watch(
  () => props.open,
  async (open) => {
    if (!open) return;
    Object.assign(form, props.attr ? { ...props.attr } : emptyForm());
    if (props.attr?.sub_attributes) {
      form.sub_attributes = props.attr.sub_attributes.map((a) => ({ ...a }));
    }
    errorText.value = '';
    if (refEntities.value.length === 0) {
      // 引用实体数据源 = 已发布的基础数据实体（prd/07 P-EC-02）
      const res = await listEntities({
        entity_type: 'base_data',
        status: 1,
        offset: 0,
        limit: 100,
      });
      refEntities.value = res.items;
    }
    if (form.data_type === 'ref' && form.ref_entity_code) {
      await loadDisplayFields(form.ref_entity_code);
    }
  },
);

async function loadDisplayFields(entityCode: string): Promise<void> {
  let schema = refSchemaCache.get(entityCode);
  if (!schema) {
    schema = refEntities.value.find((e) => e.entity_code === entityCode);
    if (!schema) return;
    refSchemaCache.set(entityCode, schema);
  }
  displayFieldOptions.value = schema.attributes
    .filter((a) => a.data_type === 'string')
    .map((a) => ({ value: a.code, label: `${a.name}(${a.code})` }));
}

async function onRefEntityChange(code: string): Promise<void> {
  form.display_field = undefined;
  await loadDisplayFields(code);
}

function close(): void {
  emit('update:open', false);
}

function save(): void {
  if (!form.name.trim()) {
    errorText.value = '请输入属性名称';
    return;
  }
  if (!CODE_PATTERN.test(form.code)) {
    errorText.value = '属性编码须小写字母开头，仅小写字母/数字/下划线，2~50 字符';
    return;
  }
  // 编辑已有属性时 code 不变；新增时查重
  if (!isEdit() && props.existingCodes.includes(form.code)) {
    errorText.value = '属性编码已存在';
    return;
  }
  if (form.data_type === 'ref' && !form.ref_entity_code) {
    errorText.value = '请选择引用实体';
    return;
  }
  if (isTable.value && (form.sub_attributes?.length ?? 0) === 0) {
    errorText.value = '明细表属性至少配置一个子属性';
    return;
  }
  const result: AttributeDef = { ...form };
  if (isTable.value) {
    // table：不参与列表列/筛选/唯一约束；清除标量专属字段
    result.is_list_column = false;
    result.is_searchable = false;
    result.unique = false;
    result.max_length = undefined;
    result.precision = undefined;
    result.default_value = undefined;
    result.ref_entity_code = undefined;
    result.display_field = undefined;
  } else {
    result.sub_attributes = undefined;
  }
  emit('save', result);
  close();
}

const subColumns = [
  { title: '名称', dataIndex: 'name', key: 'name' },
  { title: '编码', dataIndex: 'code', key: 'code' },
  { title: '类型', dataIndex: 'data_type', key: 'data_type' },
  { title: '必填', key: 'required' },
  { title: '操作', key: 'action', width: 110 },
];
</script>

<template>
  <a-drawer
    :open="open"
    :title="attr ? '编辑属性' : '添加属性'"
    :width="520"
    @close="close"
  >
    <a-form layout="vertical">
      <a-form-item
        label="属性名称"
        required
      >
        <a-input
          v-model:value="form.name"
          placeholder="如：型号编码"
        />
      </a-form-item>
      <a-form-item
        label="属性编码"
        required
      >
        <a-input
          v-model:value="form.code"
          :disabled="!!attr"
          placeholder="如：model_code"
        />
      </a-form-item>
      <a-form-item
        label="数据类型"
        required
      >
        <a-select
          v-model:value="form.data_type"
          :options="dataTypeOptions"
        />
      </a-form-item>

      <!-- table：子属性编辑区（prd/07 P-DC-02，子属性不可再为 table） -->
      <template v-if="isTable">
        <div class="mb-2 flex items-center justify-between">
          <span class="text-sm font-medium">子属性（明细列）</span>
          <a-button
            type="dashed"
            size="small"
            @click="openAddSub"
          >
            + 添加子属性
          </a-button>
        </div>
        <a-table
          :columns="subColumns"
          :data-source="form.sub_attributes ?? []"
          :pagination="false"
          row-key="code"
          size="small"
          class="mb-4"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'data_type'">
              {{ record.data_type === 'ref' ? `ref → ${record.ref_entity_code ?? '-'}` : record.data_type }}
            </template>
            <template v-else-if="column.key === 'required'">
              {{ record.required ? '✔' : '-' }}
            </template>
            <template v-else-if="column.key === 'action'">
              <a-button
                type="link"
                size="small"
                @click="openEditSub(record)"
              >
                编辑
              </a-button>
              <a-button
                type="link"
                danger
                size="small"
                @click="removeSub(record)"
              >
                删除
              </a-button>
            </template>
          </template>
          <template #emptyText>
            <span class="text-xs text-gray-400">尚未添加子属性</span>
          </template>
        </a-table>
      </template>

      <!-- 标量/ref 专属配置 -->
      <template v-else>
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
            show-search
            option-filter-prop="label"
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
        <a-form-item label="默认值">
          <a-input
            v-model:value="form.default_value"
            placeholder="仅新增数据时自动填充"
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
          <a-form-item label="唯一">
            <a-switch v-model:checked="form.unique" />
          </a-form-item>
          <a-form-item label="列表列">
            <a-switch v-model:checked="form.is_list_column" />
          </a-form-item>
          <a-form-item label="可筛选">
            <a-switch v-model:checked="form.is_searchable" />
          </a-form-item>
        </div>
      </template>

      <!-- table 仅保留必填/可见开关（列表列/筛选不适用） -->
      <div
        v-if="isTable"
        class="grid grid-cols-3 gap-2"
      >
        <a-form-item label="必填">
          <a-switch v-model:checked="form.required" />
        </a-form-item>
        <a-form-item label="可见">
          <a-switch v-model:checked="form.visible" />
        </a-form-item>
      </div>

      <div
        v-if="errorText"
        class="mb-2 text-xs text-red-500"
      >
        {{ errorText }}
      </div>
    </a-form>
    <template #footer>
      <div class="text-right">
        <a-button
          class="mr-2"
          @click="close"
        >
          取消
        </a-button>
        <a-button
          type="primary"
          @click="save"
        >
          确定
        </a-button>
      </div>
    </template>

    <SubAttrEditModal
      v-model:open="subModalOpen"
      :attr="editingSub"
      :existing-codes="(form.sub_attributes ?? []).map((a) => a.code)"
      @save="onSaveSub"
    />
  </a-drawer>
</template>
