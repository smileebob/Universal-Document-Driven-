<script setup lang="ts">
// P-DC-01 单据定义列表页（prd/07）：发布/停用/删除 + 状态机/规则绑定/事务步骤入口
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Modal, message } from 'ant-design-vue';
import {
  deleteDoc,
  disableDoc,
  listDocs,
  publishDoc,
} from '@/modules/config/api/schema';
import type { DocSchema, SchemaStatus } from '@/modules/config/types';
import { DOC_TYPE_OPTIONS } from '@/modules/config/types';
import StatusTag from '@/shared/components/StatusTag.vue';
import { useMenuStore } from '@/shared/stores/menu';
import { useSchemaStore } from '@/shared/stores/schema';
import { ApiError } from '@/shared/types/api';
import { formatTime } from '@/shared/utils/format';
import { toUserMessage } from '@/shared/utils/errorMessages';

const router = useRouter();
const schemaStore = useSchemaStore();
const menuStore = useMenuStore();

const filters = reactive<{ keyword: string; status: SchemaStatus | '' }>({
  keyword: '',
  status: '',
});

const data = ref<DocSchema[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);
const loading = ref(false);

async function load(): Promise<void> {
  loading.value = true;
  try {
    const res = await listDocs({
      keyword: filters.keyword || undefined,
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
  filters.status = '';
  search();
}

/** 发布/停用/保存后：schema 即时失效 + 菜单下次进入更新（design/10 §6.2 ①） */
function invalidateAfterChange(code: string): void {
  schemaStore.invalidate(code);
  menuStore.invalidate();
}

async function togglePublish(record: DocSchema): Promise<void> {
  const publishing = record.status === 100;
  const actionText = publishing ? '发布' : '停用';
  Modal.confirm({
    title: `确认${actionText}「${record.doc_name}」？`,
    content: publishing ? '发布后运行时即可使用该单据' : '停用后该单据将不可用于业务数据',
    okText: actionText,
    cancelText: '取消',
    onOk: async () => {
      try {
        if (publishing) {
          await publishDoc(record.doc_code, { silent: true });
        } else {
          await disableDoc(record.doc_code, { silent: true });
        }
        message.success('操作成功');
        invalidateAfterChange(record.doc_code);
        void load();
      } catch (err) {
        // 发布校验失败（40011）：弹出原因清单（prd/07 P-DC-01）
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

function remove(record: DocSchema): void {
  Modal.confirm({
    title: `确认删除「${record.doc_name}」？`,
    content: '删除后不可恢复',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      await deleteDoc(record.doc_code);
      message.success('操作成功');
      invalidateAfterChange(record.doc_code);
      void load();
    },
  });
}

function docTypeLabel(type: string): string {
  return DOC_TYPE_OPTIONS.find((o) => o.value === type)?.label.replace(/ .+$/, '') ?? type ?? '-';
}

const columns = [
  { title: '编码', dataIndex: 'doc_code', key: 'doc_code' },
  { title: '名称', dataIndex: 'doc_name', key: 'doc_name' },
  { title: '类型', key: 'doc_type' },
  { title: '属性数', key: 'attr_count' },
  { title: '状态数', key: 'status_count' },
  { title: '状态', key: 'status' },
  { title: '更新时间', key: 'update_time' },
  { title: '操作', key: 'action', width: 360 },
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
          @click="router.push('/config/doc/edit')"
        >
          + 新建单据
        </a-button>
      </div>
    </div>

    <a-table
      :columns="columns"
      :data-source="data"
      :loading="loading"
      row-key="doc_code"
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
        <template v-if="column.key === 'doc_type'">
          {{ docTypeLabel(record.doc_type) }}
        </template>
        <template v-else-if="column.key === 'attr_count'">
          {{ record.attributes?.length ?? 0 }}
        </template>
        <template v-else-if="column.key === 'status_count'">
          {{ record.status_defs?.length ?? 0 }}
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
            @click="router.push(`/config/doc/edit/${record.doc_code}`)"
          >
            编辑
          </a-button>
          <a-button
            type="link"
            size="small"
            @click="router.push(`/config/doc/${record.doc_code}/state-machine`)"
          >
            状态机
          </a-button>
          <a-button
            type="link"
            size="small"
            @click="router.push(`/config/doc/${record.doc_code}/rules`)"
          >
            规则
          </a-button>
          <!-- 事务步骤（阶段三 TX-01，P-TX-01 配置页入口） -->
          <a-button
            type="link"
            size="small"
            @click="router.push(`/config/doc/${record.doc_code}/tx-config`)"
          >
            事务步骤
          </a-button>
          <a-button
            type="link"
            size="small"
            @click="togglePublish(record)"
          >
            {{ record.status === 100 ? '发布' : '停用' }}
          </a-button>
          <!-- 删除仅草稿显示（prd/07 P-DC-01） -->
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
        <a-empty description="暂无单据定义，点击右上角新建" />
      </template>
    </a-table>
  </a-card>
</template>
