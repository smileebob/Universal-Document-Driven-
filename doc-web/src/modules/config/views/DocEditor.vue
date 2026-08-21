<script setup lang="ts">
// P-DC-02 单据定义编辑器（prd/07）：头属性 + 属性定义（table 明细 sub_attributes）
// + 五状态 status_defs 配置 + 菜单挂载 + 预览/保存草稿/保存并发布
// 说明：页面级组件，行数略超 300 行约束（状态字典编辑为单据专属交互，无法再拆复用组件）
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Modal, message } from 'ant-design-vue';
import {
  createDoc,
  getDoc,
  publishDoc,
  putMenuMount,
  updateDoc,
} from '@/modules/config/api/schema';
import type { AttributeDef, MenuMountConfig, StatusDef } from '@/modules/config/types';
import { DEFAULT_STATUS_DEFS, DOC_TYPE_OPTIONS } from '@/modules/config/types';
import AttributeEditDrawer from '@/modules/config/components/AttributeEditDrawer.vue';
import FormPreviewModal from '@/modules/config/components/FormPreviewModal.vue';
import MenuMountPanel from '@/modules/config/components/MenuMountPanel.vue';
import { useMenuStore } from '@/shared/stores/menu';
import { useSchemaStore } from '@/shared/stores/schema';
import { ApiError } from '@/shared/types/api';

const route = useRoute();
const router = useRouter();
const schemaStore = useSchemaStore();
const menuStore = useMenuStore();

const code = computed(() => (route.params.code as string) || '');
const isEdit = computed(() => !!code.value);
// 后端校验口径：[a-z][a-z0-9_]{1,63}
const CODE_PATTERN = /^[a-z][a-z0-9_]{1,63}$/;

const basic = reactive({
  doc_name: '',
  doc_code: '',
  doc_type: 'order',
  description: '',
});
const attributes = ref<AttributeDef[]>([]);
/** 状态字典：默认预置五状态（与后端 defaultStatusDefs 同口径，可增删改） */
const statusDefs = ref<StatusDef[]>(DEFAULT_STATUS_DEFS.map((s) => ({ ...s })));
const menu = ref<MenuMountConfig>({ mounted: false, group_code: 'biz_doc', sort_order: 0 });

const drawerOpen = ref(false);
const editingAttr = ref<AttributeDef | null>(null);
const previewOpen = ref(false);
const saving = ref(false);

onMounted(async () => {
  if (!isEdit.value) return;
  try {
    const schema = await getDoc(code.value);
    basic.doc_name = schema.doc_name;
    basic.doc_code = schema.doc_code;
    basic.doc_type = schema.doc_type ?? 'order';
    basic.description = schema.description ?? '';
    attributes.value = schema.attributes;
    if (schema.status_defs?.length) {
      statusDefs.value = schema.status_defs.map((s) => ({ ...s }));
    }
  } catch {
    message.error('单据定义不存在或已被删除');
    void router.replace('/config/doc');
  }
});

// ---------- 属性定义 ----------
function openAddAttr(): void {
  editingAttr.value = null;
  drawerOpen.value = true;
}

function openEditAttr(attr: AttributeDef): void {
  editingAttr.value = attr;
  drawerOpen.value = true;
}

function onSaveAttr(attr: AttributeDef): void {
  if (editingAttr.value) {
    const index = attributes.value.findIndex((a) => a.code === editingAttr.value!.code);
    if (index >= 0) attributes.value.splice(index, 1, attr);
  } else {
    attributes.value.push(attr);
  }
}

function removeAttr(attr: AttributeDef): void {
  Modal.confirm({
    title: `确认删除属性「${attr.name}」？`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: () => {
      attributes.value = attributes.value.filter((a) => a.code !== attr.code);
    },
  });
}

// ---------- 状态字典（status_defs） ----------
function addStatus(): void {
  statusDefs.value.push({ status: 0, name: '', editable: false, terminal: false });
}

function removeStatus(index: number): void {
  statusDefs.value.splice(index, 1);
}

function resetDefaultStatuses(): void {
  statusDefs.value = DEFAULT_STATUS_DEFS.map((s) => ({ ...s }));
}

// ---------- 校验与保存 ----------
function validateBasic(): string {
  if (!basic.doc_name.trim() || basic.doc_name.length > 50) return '单据名称必填，1~50 字';
  if (!CODE_PATTERN.test(basic.doc_code)) return '单据编码须小写字母开头，仅小写字母/数字/下划线，2~64 字符';
  if (basic.description.length > 200) return '描述不超过 200 字';
  return '';
}

function validateStatusDefs(): string {
  if (statusDefs.value.length === 0) return '至少配置一个业务状态';
  const seen = new Set<number>();
  for (const s of statusDefs.value) {
    if (!Number.isFinite(s.status)) return '状态值必须为数字';
    if (!s.name.trim()) return '每个状态必须填写状态名';
    if (seen.has(s.status)) return `状态值 ${s.status} 重复`;
    seen.add(s.status);
  }
  return '';
}

/** 写成功后：schema 即时失效 + 菜单失效（design/10 §6.2 ①） */
function invalidate(): void {
  schemaStore.invalidate(basic.doc_code);
  menuStore.invalidate();
}

async function persist(): Promise<void> {
  const common = {
    doc_name: basic.doc_name.trim(),
    doc_type: basic.doc_type,
    description: basic.description,
    attributes: attributes.value,
    status_defs: statusDefs.value,
  };
  if (isEdit.value) {
    await updateDoc(code.value, common);
  } else {
    await createDoc({ ...common, doc_code: basic.doc_code.trim() });
  }
  // 单据菜单挂载为独立接口（后端 DocSchemaCreateRequest 不含 menu）
  await putMenuMount('doc', basic.doc_code.trim(), menu.value);
}

async function saveDraft(): Promise<void> {
  const errText = validateBasic() || validateStatusDefs();
  if (errText) {
    message.error(errText);
    return;
  }
  saving.value = true;
  try {
    await persist();
    invalidate();
    message.success('操作成功');
    void router.push('/config/doc');
  } finally {
    saving.value = false;
  }
}

async function saveAndPublish(): Promise<void> {
  const errText = validateBasic() || validateStatusDefs();
  if (errText) {
    message.error(errText);
    return;
  }
  if (attributes.value.length === 0) {
    message.error('至少配置一个属性');
    return;
  }
  saving.value = true;
  try {
    await persist();
    await publishDoc(basic.doc_code, { silent: true });
    invalidate();
    message.success('发布成功');
    void router.push('/config/doc');
  } catch (err) {
    // 40011：发布校验失败弹出原因清单（prd/07 P-DC-02）
    if (err instanceof ApiError && err.code === 40011) {
      const reasons = (err.data as { reasons?: string[] } | undefined)?.reasons;
      Modal.error({
        title: '发布校验未通过',
        content: reasons?.length ? reasons.join('；') : err.message,
      });
    }
  } finally {
    saving.value = false;
  }
}

const attrColumns = [
  { title: '属性名', dataIndex: 'name', key: 'name' },
  { title: '编码', dataIndex: 'code', key: 'code' },
  { title: '类型', dataIndex: 'data_type', key: 'data_type' },
  { title: '必填', key: 'required' },
  { title: '可见', key: 'visible' },
  { title: '可编辑', key: 'editable' },
  { title: '引用实体', dataIndex: 'ref_entity_code', key: 'ref_entity_code' },
  { title: '子属性数', key: 'sub_count' },
  { title: '列表列', key: 'is_list_column' },
  { title: '筛选', key: 'is_searchable' },
  { title: '操作', key: 'action', width: 120 },
];
</script>

<template>
  <a-card :title="isEdit ? `编辑单据：${code}` : '新建单据'">
    <!-- 基础信息区（头属性） -->
    <h3 class="mb-3 text-base font-medium">
      基础信息
    </h3>
    <a-row :gutter="16">
      <a-col :span="6">
        <a-form-item
          label="单据名称"
          required
        >
          <a-input
            v-model:value="basic.doc_name"
            placeholder="1~50 字"
          />
        </a-form-item>
      </a-col>
      <a-col :span="6">
        <a-form-item
          label="单据编码"
          required
        >
          <a-input
            v-model:value="basic.doc_code"
            :disabled="isEdit"
            placeholder="如 export_order"
          />
        </a-form-item>
      </a-col>
      <a-col :span="6">
        <a-form-item
          label="单据类型"
          required
        >
          <a-select
            v-model:value="basic.doc_type"
            :options="DOC_TYPE_OPTIONS"
          />
        </a-form-item>
      </a-col>
      <a-col :span="6">
        <a-form-item label="描述">
          <a-input
            v-model:value="basic.description"
            placeholder="≤200 字"
          />
        </a-form-item>
      </a-col>
    </a-row>

    <!-- 属性定义区（table 明细支持 sub_attributes） -->
    <div class="mb-3 mt-4 flex items-center justify-between">
      <h3 class="text-base font-medium">
        属性定义
      </h3>
      <a-button
        type="dashed"
        @click="openAddAttr"
      >
        + 添加属性
      </a-button>
    </div>
    <a-table
      :columns="attrColumns"
      :data-source="attributes"
      :pagination="false"
      row-key="code"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="['required', 'visible', 'editable', 'is_list_column', 'is_searchable'].includes(column.key)">
          {{ record[column.key] ? '✔' : '-' }}
        </template>
        <template v-else-if="column.key === 'ref_entity_code'">
          {{ record.ref_entity_code ?? '-' }}
        </template>
        <template v-else-if="column.key === 'sub_count'">
          {{ record.data_type === 'table' ? (record.sub_attributes?.length ?? 0) : '-' }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-button
            type="link"
            size="small"
            @click="openEditAttr(record)"
          >
            编辑属性
          </a-button>
          <a-button
            type="link"
            danger
            size="small"
            @click="removeAttr(record)"
          >
            删除
          </a-button>
        </template>
      </template>
    </a-table>

    <!-- 状态定义区（status_defs：默认五状态，可增删改） -->
    <div class="mb-3 mt-6 flex items-center justify-between">
      <h3 class="text-base font-medium">
        业务状态定义
      </h3>
      <div class="flex gap-2">
        <a-button
          size="small"
          @click="resetDefaultStatuses"
        >
          恢复默认五状态
        </a-button>
        <a-button
          type="dashed"
          size="small"
          @click="addStatus"
        >
          + 添加状态
        </a-button>
      </div>
    </div>
    <a-table
      :columns="[
        { title: '状态值', key: 'status', width: 140 },
        { title: '状态名', key: 'name' },
        { title: '可编辑', key: 'editable', width: 100 },
        { title: '终态', key: 'terminal', width: 100 },
        { title: '操作', key: 'action', width: 80 },
      ]"
      :data-source="statusDefs"
      :pagination="false"
      size="small"
      row-key="status"
    >
      <template #bodyCell="{ column, record, index }">
        <template v-if="column.key === 'status'">
          <a-input-number
            v-model:value="record.status"
            :min="0"
            class="w-full"
          />
        </template>
        <template v-else-if="column.key === 'name'">
          <a-input
            v-model:value="record.name"
            placeholder="如：暂存"
          />
        </template>
        <template v-else-if="column.key === 'editable'">
          <a-switch v-model:checked="record.editable" />
        </template>
        <template v-else-if="column.key === 'terminal'">
          <a-switch v-model:checked="record.terminal" />
        </template>
        <template v-else-if="column.key === 'action'">
          <a-button
            type="link"
            danger
            size="small"
            @click="removeStatus(index)"
          >
            删除
          </a-button>
        </template>
      </template>
    </a-table>

    <!-- 菜单挂载区（EC-05） -->
    <h3 class="mb-3 mt-6 text-base font-medium">
      菜单挂载
    </h3>
    <MenuMountPanel
      v-model="menu"
      :default-name="basic.doc_name"
    />

    <!-- 操作区 -->
    <div class="mt-8 border-t border-gray-100 pt-4">
      <a-button
        class="mr-2"
        @click="previewOpen = true"
      >
        预览表单
      </a-button>
      <a-button
        class="mr-2"
        :loading="saving"
        @click="saveDraft"
      >
        保存草稿
      </a-button>
      <a-button
        type="primary"
        :loading="saving"
        @click="saveAndPublish"
      >
        保存并发布
      </a-button>
    </div>

    <AttributeEditDrawer
      v-model:open="drawerOpen"
      :attr="editingAttr"
      :existing-codes="attributes.map((a) => a.code)"
      allow-table
      @save="onSaveAttr"
    />
    <FormPreviewModal
      v-model:open="previewOpen"
      :title="`表单预览：${basic.doc_name || '未命名单据'}`"
      :attributes="attributes"
    />
  </a-card>
</template>
