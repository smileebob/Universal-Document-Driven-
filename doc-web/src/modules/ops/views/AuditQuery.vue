<script setup lang="ts">
// P-OP-01 审计日志查询（prd/10）：筛选表单 + operate_time 倒序列表 + next_token 续翻
// 行展开 JsonDiffViewer（before/after JSON 快照 Diff）；
// dev 仿真限制：meta 侧配置审计与 runtime 数据审计分库，配置类审计缺失时友好空态
import { computed, onMounted, ref } from 'vue';
import { message } from 'ant-design-vue';
import type { Dayjs } from 'dayjs';
import { AUDIT_LIMIT_MAX, queryAuditLogs } from '@/modules/ops/api/audit';
import type { AuditItem, AuditOperation } from '@/modules/ops/auditTypes';
import {
  AUDIT_OPERATION_COLORS,
  AUDIT_OPERATION_LABELS,
  AUDIT_TARGET_TYPES,
} from '@/modules/ops/auditTypes';
import JsonDiffViewer from '@/shared/components/JsonDiffViewer.vue';
import { ApiError } from '@/shared/types/api';
import { toUserMessage } from '@/shared/utils/errorMessages';
import { formatTime } from '@/shared/utils/format';

// ---------- 筛选 ----------
const targetType = ref<string | undefined>(undefined);
const targetCode = ref('');
const operation = ref<AuditOperation | undefined>(undefined);
const operator = ref('');
const timeRange = ref<[Dayjs, Dayjs] | null>(null);

const operationOptions = Object.entries(AUDIT_OPERATION_LABELS).map(([value, label]) => ({ value, label }));
const targetTypeOptions = AUDIT_TARGET_TYPES.map((t) => ({ value: t, label: t }));

// ---------- 分页（next_token 续翻） ----------
const items = ref<AuditItem[]>([]);
const total = ref(0);
const loading = ref(false);
const page = ref(1);
const pageSize = ref(20);
/** 页边界令牌：第 n 页响应的 next_token 供第 n+1 页使用 */
const tokens = new Map<number, string>();

function buildParams(token?: string) {
  return {
    target_type: targetType.value || undefined,
    target_code: targetCode.value.trim() || undefined,
    operation: operation.value,
    operator: operator.value.trim() || undefined,
    start_time: timeRange.value ? timeRange.value[0].startOf('day').valueOf() : undefined,
    end_time: timeRange.value ? timeRange.value[1].endOf('day').valueOf() : undefined,
    limit: pageSize.value,
    next_token: token,
  };
}

async function fetchPage(n: number, token?: string): Promise<void> {
  const res = await queryAuditLogs(buildParams(token));
  items.value = res.items;
  total.value = res.total;
  page.value = n;
  if (res.next_token) tokens.set(n, res.next_token);
}

/** 链式续翻：目标页令牌缺失时自最近已知令牌向后逐页推进（审计接口仅支持顺序 token） */
async function goToPage(n: number): Promise<void> {
  loading.value = true;
  try {
    if (n === 1) {
      await fetchPage(1);
      return;
    }
    let cursor = n - 1;
    while (cursor >= 1 && !tokens.has(cursor)) cursor -= 1;
    let token = cursor >= 1 ? tokens.get(cursor) : undefined;
    for (let p = cursor + 1; p <= n; p += 1) {
      await fetchPage(p, token);
      token = tokens.get(p);
    }
  } catch (err) {
    // limit 越界（40002）等统一文案提示
    message.error(err instanceof ApiError ? toUserMessage(err) : '查询失败，请稍后重试');
  } finally {
    loading.value = false;
  }
}

function search(): void {
  tokens.clear();
  page.value = 1;
  void goToPage(1);
}

function resetFilters(): void {
  targetType.value = undefined;
  targetCode.value = '';
  operation.value = undefined;
  operator.value = '';
  timeRange.value = null;
  search();
}

function onPageChange(n: number, size: number): void {
  if (size !== pageSize.value) {
    pageSize.value = Math.min(size, AUDIT_LIMIT_MAX);
    tokens.clear();
    void goToPage(1);
    return;
  }
  void goToPage(n);
}

const expandedKeys = ref<string[]>([]);

/** 友好空态：dev 仿真下配置类审计分库缺失属已知限制 */
const emptyHint = computed(() =>
  targetType.value === 'entity_schema' || targetType.value === 'doc_schema'
    ? '未查询到配置类审计记录（dev 仿真环境配置审计与数据审计分库，属已知限制）'
    : '暂无审计记录',
);

onMounted(() => void goToPage(1));
</script>

<template>
  <a-card title="审计日志">
    <!-- 筛选表单（prd/10 P-OP-01） -->
    <div class="mb-3 flex flex-wrap items-center gap-3">
      <a-select
        v-model:value="targetType"
        :options="targetTypeOptions"
        placeholder="目标类型"
        class="w-36"
        allow-clear
      />
      <a-input
        v-model:value="targetCode"
        placeholder="目标对象编码"
        class="w-40"
        allow-clear
      />
      <a-select
        v-model:value="operation"
        :options="operationOptions"
        placeholder="操作类型"
        class="w-32"
        allow-clear
      />
      <a-input
        v-model:value="operator"
        placeholder="操作人"
        class="w-32"
        allow-clear
      />
      <a-range-picker v-model:value="timeRange" />
      <a-button
        type="primary"
        @click="search"
      >
        查询
      </a-button>
      <a-button @click="resetFilters">
        重置
      </a-button>
    </div>

    <!-- 列表：operate_time 倒序，行展开 JSON Diff -->
    <a-table
      :columns="[
        { title: '操作时间', key: 'operate_time', width: 170 },
        { title: '操作', key: 'operation', width: 100 },
        { title: '目标类型', dataIndex: 'target_type', width: 110 },
        { title: '目标对象', dataIndex: 'target_code', width: 130 },
        { title: '目标 UUID', dataIndex: 'target_uuid', ellipsis: true },
        { title: '操作人', dataIndex: 'operator', width: 110 },
        { title: 'IP', dataIndex: 'ip', width: 130 },
      ]"
      :data-source="items"
      :loading="loading"
      row-key="log_id"
      size="middle"
      :expanded-row-keys="expandedKeys"
      :pagination="{
        current: page,
        pageSize,
        total,
        showSizeChanger: true,
        pageSizeOptions: ['20', '50', '100'],
        showTotal: (t: number) => `共 ${t} 条`,
        onChange: onPageChange,
      }"
      @expanded-rows-change="(keys: string[]) => (expandedKeys = keys)"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'operate_time'">
          {{ formatTime(record.operate_time) }}
        </template>
        <template v-else-if="column.key === 'operation'">
          <a-tag :color="AUDIT_OPERATION_COLORS[record.operation as AuditOperation] ?? 'default'">
            {{ AUDIT_OPERATION_LABELS[record.operation as AuditOperation] ?? record.operation }}
          </a-tag>
        </template>
        <template v-else-if="column.dataIndex === 'target_uuid'">
          {{ record.target_uuid ?? '-' }}
        </template>
        <template v-else-if="column.dataIndex === 'ip'">
          {{ record.ip ?? '-' }}
        </template>
      </template>

      <!-- 行展开：before/after 快照 JSON Diff（新增绿/删除红/变更黄） -->
      <template #expandedRowRender="{ record }">
        <div class="mb-1 text-xs text-gray-500">
          Trace：{{ record.trace_id ?? '-' }} ｜ 变更前/后快照 Diff
        </div>
        <JsonDiffViewer
          :before="record.before_value"
          :after="record.after_value"
        />
      </template>

      <template #emptyText>
        <a-empty :description="emptyHint" />
      </template>
    </a-table>
  </a-card>
</template>
