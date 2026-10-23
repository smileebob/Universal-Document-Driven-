<script setup lang="ts">
// P-RT-01 通用列表页（prd/09）：动态筛选/列 + 状态分布条 + POST /query + next_token 深分页
// 阶段四（P-RT-05）：useListStateStore 列表态保持——详情返回时恢复筛选/分页/排序/滚动
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router';
import { Modal, message } from 'ant-design-vue';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import type { AttributeDef, ObjType } from '@/modules/config/types';
import { isDocSchema, listColumns, schemaName } from '@/modules/config/types';
import { deleteData } from '@/modules/runtime/api/data';
import { queryData } from '@/modules/runtime/api/query';
import { useListStateStore } from '@/modules/runtime/stores/listState';
import type { DataRecord, QueryDsl, QueryFilter } from '@/modules/runtime/types';
import RefSelect from '@/shared/components/RefSelect.vue';
import StatusTag from '@/shared/components/StatusTag.vue';
import { usePagination } from '@/shared/composables/usePagination';
import type { PagingParam } from '@/shared/composables/usePagination';
import { usePermission } from '@/shared/composables/usePermission';
import { useSchema } from '@/shared/composables/useSchema';
import { useMenuStore } from '@/shared/stores/menu';
import { useSchemaStore } from '@/shared/stores/schema';
import { formatDecimal, formatTime } from '@/shared/utils/format';

const route = useRoute();
const router = useRouter();
const { can } = usePermission();
const schemaStore = useSchemaStore();
const menuStore = useMenuStore();
const listState = useListStateStore();
const { schema, loading: schemaLoading, loadSchema } = useSchema();

const objType = computed(() => route.params.objType as ObjType);
const code = computed(() => route.params.code as string);
const isDoc = computed(() => objType.value === 'doc');

/**
 * contains 筛选输入最大字符数：与后端口径一致为 30。
 * （后端契约值 30；服务端包装两侧通配符后 wildcard 共 32 字符含 *）
 */
const CONTAINS_MAX_LEN = 30;

// ---------- 筛选 ----------
const filterValues = ref<Record<string, unknown>>({});
const statusFilter = ref<number | null>(null);
const creatorFilter = ref('');
const createTimeRange = ref<[Dayjs, Dayjs] | null>(null);
const statusCounts = ref<{ key: number; count: number }[]>([]);
/** ref 列展示值：attrCode → uuid → 展示文本 */
const refLabels = ref<Record<string, Record<string, string>>>({});
const sortState = ref<{ field: string; order: 'asc' | 'desc' }>({ field: 'create_time', order: 'desc' });

const searchableAttrs = computed(() => schema.value?.attributes.filter((a) => a.is_searchable) ?? []);
/** 单据状态字典（后端契约字段 status_defs） */
const statusDefs = computed(() => (schema.value && isDocSchema(schema.value) ? schema.value.status_defs : []));

function buildFilters(): QueryFilter[] {
  const filters: QueryFilter[] = [
    { field: isDoc.value ? 'doc_code' : 'entity_code', op: 'eq', value: code.value },
  ];
  if (!isDoc.value) filters.push({ field: 'status', op: 'eq', value: 1 });
  for (const attr of searchableAttrs.value) {
    const v = filterValues.value[attr.code];
    const field = `attributes.${attr.code}`;
    if (v === undefined || v === null || v === '') continue;
    if (attr.data_type === 'string') {
      filters.push({ field, op: 'contains', value: v });
    } else if (attr.data_type === 'ref') {
      filters.push({ field, op: 'eq', value: v });
    } else if (attr.data_type === 'integer' || attr.data_type === 'decimal') {
      const [lo, hi] = v as [number?, number?];
      if (lo !== undefined && lo !== null) filters.push({ field, op: 'gte', value: lo });
      if (hi !== undefined && hi !== null) filters.push({ field, op: 'lte', value: hi });
    } else if (attr.data_type === 'boolean') {
      filters.push({ field, op: 'eq', value: v === 'true' });
    } else if (attr.data_type === 'date' || attr.data_type === 'datetime') {
      const [start, end] = v as [Dayjs, Dayjs];
      filters.push({ field, op: 'gte', value: start.startOf('day').valueOf() });
      filters.push({ field, op: 'lte', value: end.endOf('day').valueOf() });
    }
  }
  if (isDoc.value) {
    if (statusFilter.value !== null) {
      filters.push({ field: 'current_status', op: 'eq', value: statusFilter.value });
    }
    if (creatorFilter.value.trim()) {
      filters.push({ field: 'creator', op: 'contains', value: creatorFilter.value.trim() });
    }
  }
  if (createTimeRange.value) {
    filters.push({ field: 'create_time', op: 'gte', value: createTimeRange.value[0].startOf('day').valueOf() });
    filters.push({ field: 'create_time', op: 'lte', value: createTimeRange.value[1].endOf('day').valueOf() });
  }
  return filters;
}

function buildDsl(paging: PagingParam): QueryDsl {
  return {
    target: isDoc.value ? 'doc_data' : 'entity_data',
    filters: buildFilters(),
    // 后端 QueryRequest 契约字段为 sorts（复数）
    sorts: [{ field: sortState.value.field, order: sortState.value.order }],
    // 统一 token 模式：首页不传 token（也不是 offset=0），深页透传上一页游标
    page: paging.token
      ? { token: paging.token, limit: paging.limit }
      : { limit: paging.limit },
    aggregations: isDoc.value
      ? [{ name: 'count_by_status', type: 'group_by', field: 'current_status' }]
      : [],
  };
}

const pager = usePagination<DataRecord>(async (paging) => {
  const res = await queryData(buildDsl(paging));
  // 状态分布条（单据，来自聚合 count group_by current_status，design/10 §4.1）
  statusCounts.value = (res.aggregations?.count_by_status ?? []).map((b) => ({
    key: Number(b.key),
    count: b.count,
  }));
  void loadRefLabels(res.items);
  return res;
});

// ---------- 列表态保持（design/10 §6.1：详情返回恢复筛选/分页/滚动） ----------
/** 待恢复滚动位置（数据加载完成后应用） */
let pendingScrollTop = 0;
/** 游标不可恢复时的提示（加载完成后弹出） */
const restoreNotice = ref('');

function restoreState(): boolean {
  const snap = listState.get(objType.value, code.value);
  if (!snap) return false;
  filterValues.value = { ...snap.filterValues };
  statusFilter.value = snap.statusFilter;
  creatorFilter.value = snap.creatorFilter;
  createTimeRange.value = snap.createTimeRange
    ? [dayjs(snap.createTimeRange[0].valueOf()), dayjs(snap.createTimeRange[1].valueOf())]
    : null;
  sortState.value = { ...snap.sort };
  // 先清空上一路由/上一查询遗留的游标，防止使用其他实体的 token 跳页
  pager.reset();
  pager.pageSize.value = snap.pageSize;
  // token 游标随列表现场缓存保存：可恢复则回到原页，不可恢复明确回首页并提示
  const cursorRestored = snap.cursor ? pager.restoreCursors(snap.cursor) : false;
  if (snap.page > 1 && !cursorRestored) {
    pager.page.value = 1;
    restoreNotice.value = `原第 ${snap.page} 页的续翻游标已失效，已回到第 1 页，请逐页浏览。`;
    pendingScrollTop = 0;
  } else {
    pendingScrollTop = snap.scrollTop;
  }
  return true;
}

onBeforeRouteLeave(() => {
  listState.save(objType.value, code.value, {
    filterValues: { ...filterValues.value },
    statusFilter: statusFilter.value,
    creatorFilter: creatorFilter.value,
    createTimeRange: createTimeRange.value,
    sort: { ...sortState.value },
    page: pager.page.value,
    pageSize: pager.pageSize.value,
    scrollTop: document.querySelector('.ant-layout-content')?.scrollTop ?? 0,
    cursor: pager.exportCursors(),
  });
});

function applyPendingScroll(): void {
  if (pendingScrollTop <= 0) return;
  const top = pendingScrollTop;
  pendingScrollTop = 0;
  void nextTick(() => {
    const el = document.querySelector('.ant-layout-content');
    if (el) el.scrollTop = top;
  });
}

// ---------- ref 列展示值批量解析（uuid → 展示值） ----------
async function resolveDisplayFieldCode(attr: AttributeDef): Promise<string> {
  if (attr.display_field) return attr.display_field;
  try {
    const refSchema = await schemaStore.getSchema('entity', attr.ref_entity_code!);
    return refSchema.attributes.find((a) => a.data_type === 'string' && a.is_list_column)?.code ?? 'name';
  } catch {
    return 'name';
  }
}

async function loadRefLabels(rows: DataRecord[]): Promise<void> {
  if (!schema.value) return;
  for (const attr of listColumns(schema.value).filter((a) => a.data_type === 'ref' && a.ref_entity_code)) {
    const uuids = [...new Set(rows.map((r) => r.attributes[attr.code]).filter(Boolean) as string[])];
    const known = { ...(refLabels.value[attr.code] ?? {}) };
    const missing = uuids.filter((u) => !known[u]);
    if (missing.length === 0) continue;
    const field = await resolveDisplayFieldCode(attr);
    try {
      const res = await queryData({
        target: 'entity_data',
        filters: [{ field: 'uuid', op: 'in', value: missing }],
        page: { offset: 0, limit: 100 },
      });
      for (const item of res.items) {
        known[item.uuid] = String(item.attributes[field] ?? item.uuid);
      }
      refLabels.value = { ...refLabels.value, [attr.code]: known };
    } catch {
      /* 展示值解析失败不阻断列表渲染 */
    }
  }
}

// ---------- 列与展示 ----------
const columns = computed(() => {
  if (!schema.value) return [];
  const dynamic = listColumns(schema.value).map((a) => ({ title: a.name, key: `attr:${a.code}` }));
  const fixed = [
    ...(isDoc.value ? [{ title: '状态', key: 'status' }] : []),
    { title: '创建人', key: 'creator' },
    { title: '创建时间', key: 'create_time', sorter: true },
    { title: '操作', key: 'action', width: 160 },
  ];
  return [...dynamic, ...fixed];
});

function displayCell(record: DataRecord, attr: AttributeDef): string {
  const v = record.attributes[attr.code];
  if (attr.data_type === 'ref') return refLabels.value[attr.code]?.[v as string] ?? String(v ?? '-');
  if (attr.data_type === 'decimal') return formatDecimal(v, attr.precision ?? 2);
  if (attr.data_type === 'datetime' && typeof v === 'number') return formatTime(v);
  if (attr.data_type === 'boolean') return v ? '是' : '否';
  return v === undefined || v === null || v === '' ? '-' : String(v);
}

function goDetail(record: DataRecord): void {
  void router.push(`/biz/${objType.value}/${code.value}/detail/${record.uuid}`);
}

/** 编辑可见性：权限 + 单据当前状态 editable（后端 status_defs 契约，prd/09 P-RT-01） */
function canEdit(record: DataRecord): boolean {
  if (!can('data:edit')) return false;
  if (!isDoc.value) return true;
  return statusDefs.value.find((s) => s.status === record.current_status)?.editable ?? false;
}

function canDelete(record: DataRecord): boolean {
  if (!can('data:delete')) return false;
  return isDoc.value ? record.current_status === 100 : true;
}

function removeRow(record: DataRecord): void {
  Modal.confirm({
    title: '确认删除该条数据？',
    content: '删除后不可恢复',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      await deleteData(objType.value, code.value, record.uuid);
      message.success('操作成功');
      void pager.load();
    },
  });
}

function search(): void {
  // 筛选/排序变化：游标链失效，从首页重新查询
  pager.reset();
  statusCounts.value = [];
  void pager.load();
}

/** 跳页：无目标页游标时钳制到可达页并提示，绝不省略 token 回首页冒充深页 */
async function handlePageChange(next: number): Promise<void> {
  try {
    const result = await pager.goToPage(next);
    if (!result.clamped) return;
    message.warning(
      result.reason === 'last-page'
        ? `已到末页，无法继续翻到第 ${result.requested} 页`
        : `第 ${result.requested} 页缺少续翻游标，已停留在第 ${result.page} 页；深分页请使用上一页/下一页逐页浏览。`,
    );
  } catch {
    // 请求层已提示错误；goToPage 已回退原页
  }
}

function resetFilters(): void {
  filterValues.value = {};
  statusFilter.value = null;
  creatorFilter.value = '';
  createTimeRange.value = null;
}

function filterString(code: string): string | undefined {
  const v = filterValues.value[code];
  return typeof v === 'string' ? v : undefined;
}

function filterRange(code: string): [Dayjs, Dayjs] | undefined {
  const v = filterValues.value[code];
  return Array.isArray(v) ? (v as [Dayjs, Dayjs]) : undefined;
}

async function onTableChange(
  pagination: { current?: number; pageSize?: number },
  _filters: unknown,
  sorter: { field?: string; order?: 'ascend' | 'descend' },
): Promise<void> {
  // 排序变化：清空游标并回到首页（prd/06 §五）
  if (sorter.field) {
    const rawField = String(sorter.field);
    sortState.value = {
      field: rawField === 'create_time' ? 'create_time' : `attributes.${rawField.slice(5)}`,
      order: sorter.order === 'ascend' ? 'asc' : 'desc',
    };
    search();
    return;
  }
  if (pagination.pageSize && pagination.pageSize !== pager.pageSize.value) {
    await pager.changePageSize(pagination.pageSize);
    return;
  }
  if (pagination.current && pagination.current !== pager.page.value) {
    await handlePageChange(pagination.current);
  }
}

async function init(): Promise<void> {
  // 详情返回恢复优先；无缓存则走默认筛选（prd/09 P-RT-05）
  const restored = restoreState();
  if (!restored) {
    pager.reset();
    resetFilters();
    sortState.value = { field: 'create_time', order: 'desc' };
    refLabels.value = {};
    statusCounts.value = [];
  }
  await loadSchema(objType.value, code.value);
  await pager.load();
  if (restoreNotice.value) {
    message.warning(restoreNotice.value);
    restoreNotice.value = '';
  }
  applyPendingScroll();
}

/** 索引/元数据版本变化：旧 token 不再可用，清空游标回首页重查 */
watch(
  () => menuStore.schemaVersion,
  (version, previous) => {
    if (version === previous) return;
    void (async () => {
      pager.reset();
      statusCounts.value = [];
      message.warning('索引版本已更新，分页游标已重置，正在重新查询');
      await loadSchema(objType.value, code.value, true);
      await pager.load();
    })();
  },
);

onMounted(() => void init());
watch([objType, code], () => void init());
</script>

<template>
  <a-card :loading="schemaLoading && !schema">
    <h2 class="mb-3 text-lg font-bold">
      {{ schema ? schemaName(schema) : '' }}
    </h2>

    <!-- 状态分布条（仅单据；点击=快捷筛选，prd/09 P-RT-01） -->
    <div
      v-if="isDoc"
      class="mb-3 flex flex-wrap gap-2"
    >
      <a-tag
        :color="statusFilter === null ? 'blue' : 'default'"
        class="cursor-pointer"
        @click="statusFilter = null; search()"
      >
        全部 {{ pager.total.value }}
      </a-tag>
      <a-tag
        v-for="item in statusCounts"
        :key="item.key"
        :color="statusFilter === item.key ? 'blue' : 'default'"
        class="cursor-pointer"
        @click="statusFilter = item.key; search()"
      >
        {{ statusDefs.find((s) => s.status === item.key)?.name ?? item.key }} {{ item.count }}
      </a-tag>
    </div>

    <!-- 筛选区：is_searchable 属性动态生成 + 固定项（prd/06 §五） -->
    <div class="mb-3 flex flex-wrap items-center gap-3">
      <template
        v-for="attr in searchableAttrs"
        :key="attr.code"
      >
        <!-- contains 筛选：maxLength=30 与后端口径一致（契约值 30） -->
        <a-input
          v-if="attr.data_type === 'string'"
          v-model:value="filterValues[attr.code]"
          :placeholder="attr.name"
          class="w-40"
          :maxlength="CONTAINS_MAX_LEN"
          show-count
          allow-clear
        />
        <RefSelect
          v-else-if="attr.data_type === 'ref' && attr.ref_entity_code"
          :ref-entity-code="attr.ref_entity_code"
          :display-field="attr.display_field"
          :model-value="filterString(attr.code)"
          class="w-45"
          :placeholder="attr.name"
          @update:model-value="filterValues[attr.code] = $event"
        />
        <span
          v-else-if="attr.data_type === 'integer' || attr.data_type === 'decimal'"
          class="flex items-center gap-1"
        >
          <a-input-number
            :value="(filterValues[attr.code] as [number?, number?])?.[0]"
            :placeholder="`${attr.name}起`"
            class="w-28"
            @update:value="filterValues[attr.code] = [$event, (filterValues[attr.code] as [number?, number?])?.[1]]"
          />
          <span>~</span>
          <a-input-number
            :value="(filterValues[attr.code] as [number?, number?])?.[1]"
            :placeholder="`${attr.name}止`"
            class="w-28"
            @update:value="filterValues[attr.code] = [(filterValues[attr.code] as [number?, number?])?.[0], $event]"
          />
        </span>
        <a-select
          v-else-if="attr.data_type === 'boolean'"
          v-model:value="filterValues[attr.code]"
          :placeholder="attr.name"
          class="w-28"
          allow-clear
          :options="[
            { value: 'true', label: '是' },
            { value: 'false', label: '否' },
          ]"
        />
        <a-range-picker
          v-else-if="attr.data_type === 'date' || attr.data_type === 'datetime'"
          :value="filterRange(attr.code)"
          @update:value="filterValues[attr.code] = $event"
        />
      </template>
      <!-- 创建人 contains 筛选：同样受 30 字符口径约束 -->
      <a-input
        v-if="isDoc"
        v-model:value="creatorFilter"
        placeholder="创建人"
        class="w-30"
        :maxlength="CONTAINS_MAX_LEN"
        show-count
        allow-clear
      />
      <a-range-picker v-model:value="createTimeRange" />
      <a-button
        type="primary"
        @click="search"
      >
        查询
      </a-button>
      <a-button @click="resetFilters(); search()">
        重置
      </a-button>
      <div class="ml-auto">
        <a-button
          v-if="can('data:create')"
          type="primary"
          @click="router.push(`/biz/${objType}/${code}/form`)"
        >
          + 新增
        </a-button>
      </div>
    </div>

    <!-- 表格：动态列（is_list_column 按 list_order）+ 固定列 -->
    <a-table
      :columns="columns"
      :data-source="pager.items.value"
      :loading="pager.loading.value"
      row-key="uuid"
      size="middle"
      :custom-row="(record: DataRecord) => ({ onClick: () => goDetail(record) })"
      :pagination="{
        current: pager.page.value,
        pageSize: pager.pageSize.value,
        total: pager.total.value,
        showSizeChanger: true,
        pageSizeOptions: ['10', '20', '50', '100'],
        showTotal: (t: number) => `共 ${t} 条`,
      }"
      @change="onTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="String(column.key).startsWith('attr:')">
          {{ displayCell(record, schema!.attributes.find((a) => a.code === String(column.key).slice(5))!) }}
        </template>
        <template v-else-if="column.key === 'status'">
          <StatusTag
            :status="record.current_status ?? 0"
            :status-defs="statusDefs"
          />
        </template>
        <template v-else-if="column.key === 'creator'">
          {{ record.creator ?? '-' }}
        </template>
        <template v-else-if="column.key === 'create_time'">
          {{ formatTime(record.create_time) }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-button
            type="link"
            size="small"
            @click.stop="goDetail(record)"
          >
            查看
          </a-button>
          <a-button
            v-if="canEdit(record)"
            type="link"
            size="small"
            @click.stop="router.push(`/biz/${objType}/${code}/form/${record.uuid}`)"
          >
            编辑
          </a-button>
          <a-button
            v-if="canDelete(record)"
            type="link"
            danger
            size="small"
            @click.stop="removeRow(record)"
          >
            删除
          </a-button>
        </template>
      </template>

      <template #emptyText>
        <a-empty :description="can('data:create') ? '暂无数据，点击「+新增」创建第一条数据' : '暂无数据'" />
      </template>
    </a-table>
  </a-card>
</template>
