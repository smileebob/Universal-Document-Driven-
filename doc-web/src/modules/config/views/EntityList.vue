<script setup lang="ts">
// P-EC-01 实体定义列表页（prd/07）
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Modal, message } from 'ant-design-vue';
import {
  deleteEntity,
  disableEntity,
  listEntities,
  publishEntity,
} from '@/modules/config/api/schema';
import type { EntitySchema, EntityType, SchemaStatus } from '@/modules/config/types';
import StatusTag from '@/shared/components/StatusTag.vue';
import { useMenuStore } from '@/shared/stores/menu';
import { useSchemaStore } from '@/shared/stores/schema';
import { ApiError } from '@/shared/types/api';
import { formatTime } from '@/shared/utils/format';
import { toUserMessage } from '@/shared/utils/errorMessages';

const router = useRouter();
const schemaStore = useSchemaStore();
const menuStore = useMenuStore();

const filters = reactive<{ keyword: string; entity_type: EntityType | ''; status: SchemaStatus | '' }>({
  keyword: '',
  entity_type: '',
  status: '',
});

const data = ref<EntitySchema[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);
const loading = ref(false);

async function load(): Promise<void> {
  loading.value = true;
  try {
    const res = await listEntities({
      keyword: filters.keyword || undefined,
      entity_type: filters.entity_type || undefined,
      status: filters.status === '' ? undefined : filters.status,
      offset: (page.value - 1) * pageSize.value,
      limit: pageSize.value,
    });
    data.value = res.items;
    total.value = res.total;
  } finally {
    loading.value = false;
  }
}

function search(): void {
  page.value = 1;
  void load();
}

function resetFilters(): void {
  filters.keyword = '';
  filters.entity_type = '';
  filters.status = '';
  search();
}

/** 发布/停用/保存后：schema 即时失效 + 菜单下次进入更新（design/10 §6.2 ①） */
function invalidateAfterChange(code: string): void {
  schemaStore.invalidate(code);
  menuStore.invalidate();
}

async function togglePublish(record: EntitySchema): Promise<void> {
  const publishing = record.status === 100;
  const actionText = publishing ? '发布' : '停用';
  Modal.confirm({
    title: `确认${actionText}「${record.entity_name}」？`,
    content: publishing ? '发布后运行时即可使用该实体' : '停用后该实体将不可用于业务数据',
    okText: actionText,
    cancelText: '取消',
    onOk: async () => {
      try {
        if (publishing) {
          await publishEntity(record.entity_code, { silent: true });
        } else {
          await disableEntity(record.entity_code, { silent: true });
        }
        message.success('操作成功');
        invalidateAfterChange(record.entity_code);
        void load();
      } catch (err) {
        // 发布校验失败（40011）：弹出原因清单（prd/07 P-EC-01）
        if (err instanceof ApiError && err.code === 40011) {
          const reasons = (err.data as { reasons?: string[] } | undefined)?.reasons;
          Modal.error({
            title: '发布校验未通过',
            content: reasons?.length ? reasons.join('；') : err.message,
          });
        } else if (err instanceof ApiError) {
          message.error(toUserMessage(err));
        }
      }
    },
  });
}

function remove(record: EntitySchema): void {
  Modal.confirm({
    title: `确认删除「${record.entity_name}」？`,
    content: '删除后不可恢复',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      await deleteEntity(record.entity_code);
      message.success('操作成功');
      invalidateAfterChange(record.entity_code);
      void load();
    },
  });
}

const columns = [
  { title: '编码', dataIndex: 'entity_code', key: 'entity_code' },
  { title: '名称', dataIndex: 'entity_name', key: 'entity_name' },
  { title: '类型', dataIndex: 'entity_type', key: 'entity_type' },
  { title: '属性数', key: 'attr_count' },
  { title: '状态', key: 'status' },
  { title: '创建人', dataIndex: 'creator', key: 'creator' },
  { title: '更新时间', key: 'update_time' },
  { title: '操作', key: 'action', width: 200 },
];

onMounted(() => void load());
</script>

<template>
  <a-card>
    <!-- 筛选区 -->
    <div class="mb-4 flex flex-wrap items-center gap-3">
      <a-input
        v-model:value="filters.keyword"
        placeholder="名称/编码 模糊搜索"
        class="w-55"
        allow-clear
        @press-enter="search"
      />
      <a-select
        v-model:value="filters.entity_type"
        class="w-35"
        :options="[
          { value: '', label: '全部类型' },
          { value: 'base_data', label: '基础数据' },
          { value: 'biz_entity', label: '业务实体' },
        ]"
      />
      <a-select
        v-model:value="filters.status"
        class="w-30"
        :options="[
          { value: '', label: '全部状态' },
          { value: 100, label: '草稿' },
          { value: 1, label: '生效' },
          { value: 0, label: '停用' },
        ]"
      />
      <a-button
        type="primary"
        @click="search"
      >
        查询
      </a-button>
      <a-button @click="resetFilters">
        重置
      </a-button>
      <div class="ml-auto">
        <a-button
          type="primary"
          @click="router.push('/config/entity/edit')"
        >
          + 新建实体
        </a-button>
      </div>
    </div>

    <a-table
      :columns="columns"
      :data-source="data"
      :loading="loading"
      row-key="entity_code"
      :pagination="{
        current: page,
        pageSize,
        total,
        showSizeChanger: true,
        pageSizeOptions: ['10', '20', '50', '100'],
        showTotal: (t: number) => `共 ${t} 条`,
        onChange: (p: number, s: number) => {
          page = p;
          pageSize = s;
          load();
        },
      }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'entity_type'">
          {{ record.entity_type === 'base_data' ? '基础数据' : '业务实体' }}
        </template>
        <template v-else-if="column.key === 'attr_count'">
          {{ record.attributes?.length ?? 0 }}
        </template>
        <template v-else-if="column.key === 'status'">
          <StatusTag
            kind="schema"
            :status="record.status"
          />
        </template>
        <template v-else-if="column.key === 'update_time'">
          {{ formatTime(record.update_time) }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-button
            type="link"
            size="small"
            @click="router.push(`/config/entity/edit/${record.entity_code}`)"
          >
            编辑
          </a-button>
          <a-button
            type="link"
            size="small"
            @click="togglePublish(record)"
          >
            {{ record.status === 100 ? '发布' : '停用' }}
          </a-button>
          <!-- 删除仅草稿显示（prd/07 P-EC-01） -->
          <a-button
            v-if="record.status === 100"
            type="link"
            danger
            size="small"
            @click="remove(record)"
          >
            删除
          </a-button>
        </template>
      </template>

      <template #emptyText>
        <a-empty description="暂无实体定义，点击右上角新建" />
      </template>
    </a-table>
  </a-card>
</template>
