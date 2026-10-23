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
  getMenuMount,
  publishDoc,
  putMenuMount,
  updateDoc,
} from '@/modules/config/api/schema';
import type { AttributeDef, MenuMountConfig, StatusDef } from '@/modules/config/types';
import {
  DEFAULT_STATUS_DEFS,
  DOC_TYPE_OPTIONS,
  defaultMenuConfig,
  fromMenuMountView,
  toMenuMountPayload,
} from '@/modules/config/types';
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

/** 当前编辑编码：创建成功后先回填并进入编辑态，再执行菜单挂载 */
const currentCode = ref((route.params.code as string) || '');
const isEdit = computed(() => !!currentCode.value);
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
const menu = ref<MenuMountConfig>(defaultMenuConfig('biz_doc'));
const menuLoaded = ref(!isEdit.value);
const menuLoading = ref(false);
const menuLoadError = ref('');
const mountFailed = ref(false);
const mountErrorText = ref('');
const retryingMount = ref(false);

const drawerOpen = ref(false);
const editingAttr = ref<AttributeDef | null>(null);
const previewOpen = ref(false);
const saving = ref(false);

function errorText(err: unknown, fallback: string): string {
  return err instanceof ApiError ? err.message || fallback : fallback;
}

async function loadMenu(targetCode: string): Promise<void> {
  if (!targetCode) return;
  menuLoading.value = true;
  menuLoadError.value = '';
  try {
    const view = await getMenuMount('doc', targetCode, { silent: true });
    menu.value = fromMenuMountView(view);
    menuLoaded.value = true;
  } catch (err) {
    menuLoaded.value = false;
    menuLoadError.value = errorText(err, '菜单挂载配置加载失败');
  } finally {
    menuLoading.value = false;
  }
}

onMounted(async () => {
  if (!isEdit.value) return;
  try {
    const schema = await getDoc(currentCode.value);
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
    return;
  }
  await loadMenu(currentCode.value);
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

function buildDefinitionPayload() {
  return {
    doc_name: basic.doc_name.trim(),
    doc_type: basic.doc_type,
    description: basic.description,
    attributes: attributes.value,
    status_defs: statusDefs.value,
  };
}

/**
 * 保存定义。创建成功后立即更新编辑态并切换到 edit 路由，
 * 后续发布/挂载失败重试不会重复创建定义。
 */
async function persistDefinition(): Promise<string> {
  if (isEdit.value) {
    await updateDoc(currentCode.value, buildDefinitionPayload());
    return currentCode.value;
  }
  const created = await createDoc({ ...buildDefinitionPayload(), doc_code: basic.doc_code.trim() });
  const newCode = created.doc_code || basic.doc_code.trim();
  currentCode.value = newCode;
  basic.doc_code = newCode;
  await router.replace({ name: 'config-doc-edit', params: { code: newCode } });
  return newCode;
}

async function mountSaved(targetCode: string): Promise<boolean> {
  try {
    await putMenuMount('doc', targetCode, toMenuMountPayload(menu.value, basic.doc_name), {
      silent: true,
    });
    mountFailed.value = false;
    mountErrorText.value = '';
    menuStore.invalidate();
    return true;
  } catch (err) {
    mountFailed.value = true;
    mountErrorText.value = errorText(err, '菜单挂载失败');
    return false;
  }
}

async function reloadMenu(): Promise<void> {
  await loadMenu(currentCode.value);
  if (menuLoaded.value) message.success('菜单配置已重新加载');
}

async function retryMount(): Promise<void> {
  if (!isEdit.value) return;
  retryingMount.value = true;
  try {
    if (!menuLoaded.value) await loadMenu(currentCode.value);
    if (!menuLoaded.value) return;
    const ok = await mountSaved(currentCode.value);
    if (ok) message.success('菜单挂载成功');
  } finally {
    retryingMount.value = false;
  }
}

async function saveDraft(): Promise<void> {
  const errText = validateBasic() || validateStatusDefs();
  if (errText) {
    message.error(errText);
    return;
  }
  saving.value = true;
  mountFailed.value = false;
  try {
    const savedCode = await persistDefinition();
    schemaStore.invalidate(savedCode);
    if (!menuLoaded.value) {
      message.warning('定义已保存；菜单配置未加载，本次未提交菜单变更，请重新加载后重试');
      return;
    }
    const mounted = await mountSaved(savedCode);
    if (!mounted) {
      message.error('定义已保存，菜单挂载失败，可重试');
      return;
    }
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
  mountFailed.value = false;
  try {
    const savedCode = await persistDefinition();
    schemaStore.invalidate(savedCode);
    try {
      await publishDoc(savedCode, { silent: true });
    } catch (err) {
      // 40011：发布校验失败弹出原因清单（prd/07 P-DC-02）
      if (err instanceof ApiError && err.code === 40011) {
        const reasons = (err.data as { reasons?: string[] } | undefined)?.reasons;
        Modal.error({
          title: '发布校验未通过',
          content: reasons?.length ? reasons.join('；') : err.message,
        });
      } else {
        message.error(errorText(err, '发布失败'));
      }
      return;
    }
    if (!menuLoaded.value) {
      message.warning('定义已保存并发布；菜单配置未加载，本次未提交菜单变更，请重新加载后重试');
      return;
    }
    const mounted = await mountSaved(savedCode);
    if (!mounted) {
      message.error('定义已保存，菜单挂载失败，可重试');
      return;
    }
    message.success('发布成功');
    void router.push('/config/doc');
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
  <a-card :title="isEdit ? `编辑单据：${currentCode}` : '新建单据'">
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

    <!-- 菜单挂载区（EC-05，独立接口） -->
    <h3 class="mb-3 mt-6 text-base font-medium">
      菜单挂载
    </h3>
    <a-alert
      v-if="menuLoadError"
      type="error"
      show-icon
      class="mb-3"
      message="菜单挂载配置加载失败"
      :description="`${menuLoadError}；为避免覆盖远端配置，保存时将不会提交菜单变更。`"
    >
      <template #action>
        <a-button
          size="small"
          :loading="menuLoading"
          @click="reloadMenu"
        >
          重新加载
        </a-button>
      </template>
    </a-alert>
    <MenuMountPanel
      v-model="menu"
      :default-name="basic.doc_name"
      :disabled="!menuLoaded"
    />

    <!-- 定义已保存但挂载失败：只重试挂载，不重复创建/保存定义 -->
    <a-alert
      v-if="mountFailed"
      type="error"
      show-icon
      class="mt-4"
      message="定义已保存，菜单挂载失败，可重试"
      :description="mountErrorText"
    >
      <template #action>
        <a-button
          size="small"
          type="primary"
          :loading="retryingMount"
          @click="retryMount"
        >
          重试挂载
        </a-button>
      </template>
    </a-alert>

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
