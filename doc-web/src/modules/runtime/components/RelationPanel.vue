<script setup lang="ts">
// RelationPanel：关联面板（prd/09 P-RT-05，RD-09/11）
// 「引用了谁」：record.relations 正向展开；「被谁引用」：POST /query relations[].uuid nested_eq 反查
// 点击条目跳转对应详情（参数化路由 /biz/{type}/{code}/detail/{uuid}）
// 后端契约：relations[].type 取值为 biz_entity / biz_doc（RelationItem 原始值）
import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import type { ObjType } from '@/modules/config/types';
import { schemaName } from '@/modules/config/types';
import { queryData } from '@/modules/runtime/api/query';
import type { DataRecord, RelationItem } from '@/modules/runtime/types';
import { useSchemaStore } from '@/shared/stores/schema';
import { formatTime } from '@/shared/utils/format';

const props = defineProps<{
  objType: ObjType;
  code: string;
  uuid: string;
  recordData: DataRecord | null;
}>();

const router = useRouter();
const schemaStore = useSchemaStore();

/** 关联面板搜索框上限：与列表 contains 筛选同口径（30 字符） */
const SEARCH_MAX_LEN = 30;
const PAGE_SIZE = 10;

/** relations[].type（biz_entity/biz_doc）→ 路由 objType */
function routeType(relType: string): ObjType {
  return relType === 'biz_doc' ? 'doc' : 'entity';
}

function goTo(objType: ObjType, code: string, uuid: string): void {
  void router.push(`/biz/${objType}/${code}/detail/${uuid}`);
}

/** 展示字段：is_list_column 的首个 string 属性，兜底 name（与 GenericList 同口径） */
async function displayFieldOf(objType: ObjType, code: string): Promise<string> {
  try {
    const s = await schemaStore.getSchema(objType, code);
    return s.attributes.find((a) => a.data_type === 'string' && a.is_list_column)?.code ?? 'name';
  } catch {
    return 'name';
  }
}

async function schemaLabel(objType: ObjType, code: string): Promise<string> {
  try {
    return schemaName(await schemaStore.getSchema(objType, code));
  } catch {
    return code;
  }
}

// ---------- 引用了谁（正向：record.relations） ----------
interface OutgoingRow {
  type: ObjType;
  code: string;
  uuid: string;
  objLabel: string;
  label: string;
}

const outgoing = ref<OutgoingRow[]>([]);
const outgoingLoading = ref(false);

async function loadOutgoing(): Promise<void> {
  const relations = props.recordData?.relations ?? [];
  outgoingLoading.value = true;
  try {
    // 按 (type,code) 分组批量取展示值
    const groups = new Map<string, RelationItem[]>();
    for (const r of relations) {
      const k = `${routeType(r.type)}:${r.code}`;
      groups.set(k, [...(groups.get(k) ?? []), r]);
    }
    const rows: OutgoingRow[] = [];
    for (const [k, items] of groups) {
      const [typeStr, code] = k.split(':');
      const objType = typeStr as ObjType;
      const target = objType === 'doc' ? 'doc_data' : 'entity_data';
      const objLabel = await schemaLabel(objType, code);
      const field = await displayFieldOf(objType, code);
      const labels = new Map<string, string>();
      try {
        const res = await queryData({
          target,
          filters: [{ field: 'uuid', op: 'in', value: items.map((i) => i.uuid) }],
          page: { offset: 0, limit: 100 },
        });
        for (const it of res.items) labels.set(it.uuid, String(it.attributes[field] ?? ''));
      } catch {
        /* 展示值解析失败不阻断 */
      }
      for (const it of items) {
        rows.push({
          type: objType, code, uuid: it.uuid, objLabel,
          label: labels.get(it.uuid) || it.uuid.slice(0, 8),
        });
      }
    }
    outgoing.value = rows;
  } finally {
    outgoingLoading.value = false;
  }
}

// ---------- 被谁引用（反查：relations[].uuid nested_eq） ----------
interface RefByRow {
  uuid: string;
  code: string;
  objLabel: string;
  label: string;
  creator?: string;
  createTime?: number;
}

interface RefByGroup {
  target: 'doc_data' | 'entity_data';
  codeField: 'doc_code' | 'entity_code';
  title: string;
  rows: RefByRow[];
  total: number;
  page: number;
  loading: boolean;
}

const refByGroups = ref<RefByGroup[]>([
  { target: 'doc_data', codeField: 'doc_code', title: '引用它的单据', rows: [], total: 0, page: 1, loading: false },
  { target: 'entity_data', codeField: 'entity_code', title: '引用它的实体数据', rows: [], total: 0, page: 1, loading: false },
]);
const keyword = ref('');

async function loadReferencedBy(group: RefByGroup): Promise<void> {
  group.loading = true;
  try {
    const res = await queryData({
      target: group.target,
      filters: [{ field: 'relations[].uuid', op: 'nested_eq', value: props.uuid }],
      sorts: [{ field: 'create_time', order: 'desc' }],
      page: { offset: (group.page - 1) * PAGE_SIZE, limit: PAGE_SIZE },
    });
    const objType: ObjType = group.target === 'doc_data' ? 'doc' : 'entity';
    // 按 code 缓存展示字段与定义名
    const fieldCache = new Map<string, string>();
    const nameCache = new Map<string, string>();
    const rows: RefByRow[] = [];
    for (const it of res.items as (DataRecord & Record<string, unknown>)[]) {
      const recCode = String(it[group.codeField] ?? '');
      if (recCode && !fieldCache.has(recCode)) {
        fieldCache.set(recCode, await displayFieldOf(objType, recCode));
        nameCache.set(recCode, await schemaLabel(objType, recCode));
      }
      const field = fieldCache.get(recCode) ?? 'name';
      rows.push({
        uuid: it.uuid,
        code: recCode,
        objLabel: nameCache.get(recCode) ?? recCode,
        label: String(it.attributes[field] ?? '') || it.uuid.slice(0, 8),
        creator: it.creator,
        createTime: it.create_time,
      });
    }
    group.rows = rows;
    group.total = res.total;
  } catch {
    group.rows = [];
    group.total = 0;
  } finally {
    group.loading = false;
  }
}

function onPageChange(group: RefByGroup, page: number): void {
  group.page = page;
  void loadReferencedBy(group);
}

function visibleRows(group: RefByGroup): RefByRow[] {
  const kw = keyword.value.trim().toLowerCase();
  if (!kw) return group.rows;
  return group.rows.filter(
    (r) => r.label.toLowerCase().includes(kw)
      || r.objLabel.toLowerCase().includes(kw)
      || (r.creator ?? '').toLowerCase().includes(kw),
  );
}

const noOutgoing = computed(() => !outgoingLoading.value && outgoing.value.length === 0);

async function loadAll(): Promise<void> {
  await Promise.all([
    loadOutgoing(),
    ...refByGroups.value.map((g) => {
      g.page = 1;
      return loadReferencedBy(g);
    }),
  ]);
}

onMounted(() => void loadAll());
watch(() => props.uuid, () => void loadAll());
</script>

<template>
  <div class="space-y-4">
    <!-- 引用了谁（正向关联：record.relations） -->
    <section>
      <h3 class="mb-2 font-bold">
        引用了谁
      </h3>
      <a-table
        :data-source="outgoing"
        :loading="outgoingLoading"
        :pagination="false"
        size="small"
        row-key="uuid"
      >
        <a-table-column
          title="对象"
          data-index="objLabel"
        />
        <a-table-column
          key="label"
          title="数据"
        >
          <template #default="{ record }">
            <a @click.prevent="goTo(record.type, record.code, record.uuid)">{{ record.label }}</a>
          </template>
        </a-table-column>
        <a-table-column
          title="UUID"
          data-index="uuid"
          ellipsis
        />
        <template #emptyText>
          <a-empty
            v-if="noOutgoing"
            description="未引用其他数据"
          />
        </template>
      </a-table>
    </section>

    <!-- 被谁引用（反查：relations[].uuid nested_eq，分页展示可跳转） -->
    <section>
      <div class="mb-2 flex items-center justify-between">
        <h3 class="font-bold">
          被谁引用
        </h3>
        <!-- 关键字过滤：30 字符上限（与列表 contains 口径一致） -->
        <a-input
          v-model:value="keyword"
          placeholder="关键字过滤"
          class="w-48"
          :maxlength="SEARCH_MAX_LEN"
          show-count
          allow-clear
        />
      </div>
      <div
        v-for="group in refByGroups"
        :key="group.target"
        class="mb-3"
      >
        <h4 class="mb-1 text-sm text-gray-500">
          {{ group.title }}（{{ group.total }}）
        </h4>
        <a-table
          :data-source="visibleRows(group)"
          :loading="group.loading"
          size="small"
          row-key="uuid"
          :pagination="{
            current: group.page,
            pageSize: PAGE_SIZE,
            total: group.total,
            size: 'small',
            showTotal: (t: number) => `共 ${t} 条`,
            onChange: (p: number) => onPageChange(group, p),
          }"
        >
          <a-table-column
            title="对象"
            data-index="objLabel"
          />
          <a-table-column
            key="label"
            title="数据"
          >
            <template #default="{ record }">
              <a @click.prevent="goTo(group.target === 'doc_data' ? 'doc' : 'entity', record.code, record.uuid)">
                {{ record.label }}
              </a>
            </template>
          </a-table-column>
          <a-table-column
            key="creator"
            title="创建人"
          >
            <template #default="{ record }">
              {{ record.creator ?? '-' }}
            </template>
          </a-table-column>
          <a-table-column
            key="createTime"
            title="创建时间"
          >
            <template #default="{ record }">
              {{ record.createTime ? formatTime(record.createTime) : '-' }}
            </template>
          </a-table-column>
          <template #emptyText>
            <a-empty description="暂无引用记录" />
          </template>
        </a-table>
      </div>
    </section>
  </div>
</template>
