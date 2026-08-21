<script setup lang="ts">
// P-EC-02 实体定义编辑器（prd/07）：基础信息 + 属性定义 + 菜单挂载 + 预览/保存/发布
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Modal, message } from 'ant-design-vue';
import {
  createEntity,
  getEntity,
  publishEntity,
  updateEntity,
} from '@/modules/config/api/schema';
import type { EntitySavePayload } from '@/modules/config/api/schema';
import type { AttributeDef, EntityType, MenuMountConfig } from '@/modules/config/types';
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
const CODE_PATTERN = /^[a-z][a-z0-9_]{1,49}$/;

const basic = reactive({
  entity_name: '',
  entity_code: '',
  entity_type: 'base_data' as EntityType,
  description: '',
});
const attributes = ref<AttributeDef[]>([]);
const menu = ref<MenuMountConfig>({ mounted: false, group_code: 'basic_data', sort_order: 0 });

const drawerOpen = ref(false);
const editingAttr = ref<AttributeDef | null>(null);
const previewOpen = ref(false);
const saving = ref(false);

onMounted(async () => {
  if (!isEdit.value) return;
  try {
    const schema = await getEntity(code.value);
    basic.entity_name = schema.entity_name;
    basic.entity_code = schema.entity_code;
    basic.entity_type = schema.entity_type;
    basic.description = schema.description ?? '';
    attributes.value = schema.attributes;
    menu.value = schema.menu ?? menu.value;
  } catch {
    message.error('实体定义不存在或已被删除');
    void router.replace('/config/entity');
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

// ---------- 校验与保存 ----------
function validateBasic(): string {
  if (!basic.entity_name.trim() || basic.entity_name.length > 50) return '实体名称必填，1~50 字';
  if (!CODE_PATTERN.test(basic.entity_code)) return '实体编码须小写字母开头，仅小写字母/数字/下划线，2~50 字符';
  if (basic.description.length > 200) return '描述不超过 200 字';
  return '';
}

function buildPayload(): EntitySavePayload {
  return {
    entity_name: basic.entity_name.trim(),
    entity_code: basic.entity_code.trim(),
    entity_type: basic.entity_type,
    description: basic.description,
    attributes: attributes.value,
    menu: menu.value,
  };
}

/** 写成功后：schema 即时失效 + 菜单失效（design/10 §6.2 ①） */
function invalidate(): void {
  schemaStore.invalidate(basic.entity_code);
  menuStore.invalidate();
}

async function persist(): Promise<boolean> {
  if (isEdit.value) {
    await updateEntity(code.value, buildPayload());
  } else {
    await createEntity(buildPayload());
  }
  return true;
}

async function saveDraft(): Promise<void> {
  const errText = validateBasic();
  if (errText) {
    message.error(errText);
    return;
  }
  saving.value = true;
  try {
    await persist();
    invalidate();
    message.success('操作成功');
    void router.push('/config/entity');
  } finally {
    saving.value = false;
  }
}

async function saveAndPublish(): Promise<void> {
  const errText = validateBasic();
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
    await publishEntity(basic.entity_code, { silent: true });
    invalidate();
    message.success('发布成功');
    void router.push('/config/entity');
  } catch (err) {
    // 40011：发布校验失败弹出原因清单（prd/07 P-EC-02）
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
  { title: '唯一', key: 'unique' },
  { title: '引用实体', dataIndex: 'ref_entity_code', key: 'ref_entity_code' },
  { title: '列表列', key: 'is_list_column' },
  { title: '筛选', key: 'is_searchable' },
  { title: '操作', key: 'action', width: 120 },
];
</script>

<template>
  <a-card :title="isEdit ? `编辑实体：${code}` : '新建实体'">
    <!-- 基础信息区 -->
    <h3 class="mb-3 text-base font-medium">
      基础信息
    </h3>
    <a-row :gutter="16">
      <a-col :span="6">
        <a-form-item
          label="实体名称"
          required
        >
          <a-input
            v-model:value="basic.entity_name"
            placeholder="1~50 字"
          />
        </a-form-item>
      </a-col>
      <a-col :span="6">
        <a-form-item
          label="实体编码"
          required
        >
          <a-input
            v-model:value="basic.entity_code"
            :disabled="isEdit"
            placeholder="如 product_model"
          />
        </a-form-item>
      </a-col>
      <a-col :span="6">
        <a-form-item
          label="实体类型"
          required
        >
          <a-select
            v-model:value="basic.entity_type"
            :disabled="isEdit"
            :options="[
              { value: 'base_data', label: '基础数据' },
              { value: 'biz_entity', label: '业务实体' },
            ]"
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

    <!-- 属性定义区 -->
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
        <template v-if="['required', 'visible', 'editable', 'unique', 'is_list_column', 'is_searchable'].includes(column.key)">
          {{ record[column.key] ? '✔' : '-' }}
        </template>
        <template v-else-if="column.key === 'ref_entity_code'">
          {{ record.ref_entity_code ?? '-' }}
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

    <!-- 菜单挂载区（EC-05） -->
    <h3 class="mb-3 mt-6 text-base font-medium">
      菜单挂载
    </h3>
    <MenuMountPanel
      v-model="menu"
      :default-name="basic.entity_name"
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
      @save="onSaveAttr"
    />
    <FormPreviewModal
      v-model:open="previewOpen"
      :title="`表单预览：${basic.entity_name || '未命名实体'}`"
      :attributes="attributes"
    />
  </a-card>
</template>
