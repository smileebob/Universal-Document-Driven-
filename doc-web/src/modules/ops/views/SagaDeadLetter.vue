<script setup lang="ts">
// P-OP-02 补偿失败处理页（prd/10）：Saga 冲正失败死信的人工处置
// 行操作：重试冲正（二次确认）/ 标记已人工处理（必填说明）；操作后刷新列表
import { onMounted, reactive, ref } from 'vue';
import { Modal, message } from 'ant-design-vue';
import { listDeadLetters, resolveDeadLetter, retryDeadLetter } from '@/modules/ops/api/ops';
import type { SagaDeadLetterItem } from '@/modules/ops/types';
import { HANDLE_STATUS_LABELS } from '@/modules/ops/types';
import { ApiError } from '@/shared/types/api';
import { formatTime } from '@/shared/utils/format';
import { toUserMessage } from '@/shared/utils/errorMessages';

const items = ref<SagaDeadLetterItem[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);
const loading = ref(false);
const filters = reactive<{ handle_status: number | '' }>({ handle_status: '' });

async function load(): Promise<void> {
  loading.value = true;
  try {
    const res = await listDeadLetters({
      handle_status: filters.handle_status === '' ? undefined : filters.handle_status,
      page: page.value,
      size: pageSize.value,
    });
    items.value = res.items;
    total.value = res.total;
  } finally {
    loading.value = false;
  }
}

function search(): void {
  page.value = 1;
  void load();
}

/** 失败阶段文案：action 正向执行失败 / compensate 冲正失败 */
function failPhaseLabel(phase?: string): string {
  if (phase === 'compensate') return '冲正失败';
  if (phase === 'action') return '正向执行失败';
  return phase || '-';
}

// ---------- 重试冲正（二次确认） ----------
async function retry(record: SagaDeadLetterItem): Promise<void> {
  Modal.confirm({
    title: '确认重试冲正？',
    content: `将对 Saga ${record.saga_id} 执行补完冲正，失败将保留在待处理列表。`,
    okText: '重试',
    cancelText: '取消',
    onOk: async () => {
      try {
        await retryDeadLetter(record.saga_id, { silent: true });
        message.success('补完冲正成功');
        void load();
      } catch (err) {
        // 42202：重试仍失败（保持待处理）
        if (err instanceof ApiError && err.code === 42202) {
          message.error('重试仍失败，记录保持待处理状态');
        } else if (err instanceof ApiError) {
          message.error(toUserMessage(err));
        }
        void load();
      }
    },
  });
}

// ---------- 标记已人工处理（必填说明弹窗） ----------
const resolveOpen = ref(false);
const resolveTarget = ref<SagaDeadLetterItem | null>(null);
const resolveRemark = ref('');
const resolving = ref(false);

function openResolve(record: SagaDeadLetterItem): void {
  resolveTarget.value = record;
  resolveRemark.value = '';
  resolveOpen.value = true;
}

async function confirmResolve(): Promise<void> {
  if (!resolveTarget.value) return;
  if (!resolveRemark.value.trim()) {
    message.warning('请填写处理说明');
    return;
  }
  resolving.value = true;
  try {
    await resolveDeadLetter(resolveTarget.value.saga_id, resolveRemark.value.trim(), { silent: true });
    message.success('已标记为人工处理');
    resolveOpen.value = false;
    void load();
  } catch (err) {
    if (err instanceof ApiError) message.error(toUserMessage(err));
  } finally {
    resolving.value = false;
  }
}

// ---------- 详情弹窗 ----------
const detailOpen = ref(false);
const detailTarget = ref<SagaDeadLetterItem | null>(null);

function openDetail(record: SagaDeadLetterItem): void {
  detailTarget.value = record;
  detailOpen.value = true;
}

const columns = [
  { title: 'Saga ID', key: 'saga_id', width: 200 },
  { title: '单据', key: 'doc' },
  { title: '流转', key: 'transition', width: 110 },
  { title: '失败步骤', key: 'failed_step', width: 90 },
  { title: '失败阶段', key: 'fail_phase', width: 110 },
  { title: '失败原因', key: 'error_reason' },
  { title: '处置状态', key: 'handle_status', width: 100 },
  { title: '时间', key: 'create_time', width: 170 },
  { title: '操作', key: 'action', width: 230 },
];

onMounted(() => void load());
</script>

<template>
  <a-card title="补偿失败处理（Saga 死信）">
    <!-- 筛选区 -->
    <div class="mb-4 flex items-center gap-3">
      <a-select
        v-model:value="filters.handle_status"
        class="w-40"
        :options="[
          { value: '', label: '全部状态' },
          { value: 0, label: '待处理' },
          { value: 1, label: '已重试' },
          { value: 2, label: '已人工关闭' },
        ]"
        @change="search"
      />
      <a-button
        type="primary"
        @click="search"
      >
        查询
      </a-button>
      <a-button @click="void load()">
        刷新
      </a-button>
    </div>

    <a-alert
      v-if="items.some((i) => i.handle_status === 0)"
      type="error"
      show-icon
      class="mb-4"
      message="存在待处理的冲正失败死信，请尽快人工处置以兜底数据一致性"
    />

    <a-table
      :columns="columns"
      :data-source="items"
      :loading="loading"
      row-key="saga_id"
      size="middle"
      :pagination="{
        current: page,
        pageSize,
        total,
        showTotal: (t: number) => `共 ${t} 条`,
        onChange: (p: number, s: number) => {
          page = p;
          pageSize = s;
          load();
        },
      }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'saga_id'">
          <a-typography-text
            :content="record.saga_id"
            copyable
            class="text-xs"
          />
        </template>
        <template v-else-if="column.key === 'doc'">
          {{ record.doc_code }}
        </template>
        <template v-else-if="column.key === 'transition'">
          {{ record.from_status }} → {{ record.to_status }}
        </template>
        <template v-else-if="column.key === 'failed_step'">
          {{ record.failed_step_seq ?? '-' }}
        </template>
        <template v-else-if="column.key === 'fail_phase'">
          <a-tag :color="record.fail_phase === 'compensate' ? 'red' : 'orange'">
            {{ failPhaseLabel(record.fail_phase) }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'error_reason'">
          <a-tooltip :title="record.error_reason">
            <span class="line-clamp-1 text-gray-600">{{ record.error_reason || '-' }}</span>
          </a-tooltip>
        </template>
        <template v-else-if="column.key === 'handle_status'">
          <a-tag :color="record.handle_status === 0 ? 'red' : record.handle_status === 1 ? 'blue' : 'green'">
            {{ HANDLE_STATUS_LABELS[record.handle_status as 0 | 1 | 2] ?? record.handle_status }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'create_time'">
          {{ formatTime(record.create_time) }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-button
            type="link"
            size="small"
            @click="openDetail(record)"
          >
            详情
          </a-button>
          <!-- 仅待处理可处置（已关闭 40901 兜底由后端拦截） -->
          <template v-if="record.handle_status === 0">
            <a-button
              type="link"
              size="small"
              @click="retry(record)"
            >
              重试冲正
            </a-button>
            <a-button
              type="link"
              size="small"
              @click="openResolve(record)"
            >
              标记已处理
            </a-button>
          </template>
        </template>
      </template>

      <template #emptyText>
        <a-empty description="当前无 Saga 冲正失败死信，事务运行正常" />
      </template>
    </a-table>

    <!-- 标记已人工处理：必填处理说明（prd/10 P-OP-02） -->
    <a-modal
      v-model:open="resolveOpen"
      title="标记已人工处理"
      ok-text="确认处理"
      cancel-text="取消"
      :confirm-loading="resolving"
      @ok="confirmResolve"
    >
      <a-descriptions
        :column="1"
        size="small"
        class="mb-3"
      >
        <a-descriptions-item label="Saga ID">
          {{ resolveTarget?.saga_id }}
        </a-descriptions-item>
        <a-descriptions-item label="失败原因">
          {{ resolveTarget?.error_reason || '-' }}
        </a-descriptions-item>
      </a-descriptions>
      <a-textarea
        v-model:value="resolveRemark"
        :rows="3"
        placeholder="必填：人工处理说明（将写入审计）"
        :maxlength="200"
        show-count
      />
    </a-modal>

    <!-- 失败详情弹窗 -->
    <a-modal
      v-model:open="detailOpen"
      title="死信详情"
      :footer="null"
      width="640px"
    >
      <a-descriptions
        v-if="detailTarget"
        :column="2"
        size="small"
        bordered
      >
        <a-descriptions-item
          label="Saga ID"
          :span="2"
        >
          {{ detailTarget.saga_id }}
        </a-descriptions-item>
        <a-descriptions-item label="单据">
          {{ detailTarget.doc_code }} / {{ detailTarget.doc_uuid }}
        </a-descriptions-item>
        <a-descriptions-item label="流转">
          {{ detailTarget.from_status }} → {{ detailTarget.to_status }}
        </a-descriptions-item>
        <a-descriptions-item label="失败步骤">
          第 {{ detailTarget.failed_step_seq ?? '-' }} 步
        </a-descriptions-item>
        <a-descriptions-item label="失败阶段">
          {{ failPhaseLabel(detailTarget.fail_phase) }}
        </a-descriptions-item>
        <a-descriptions-item
          label="失败原因"
          :span="2"
        >
          {{ detailTarget.error_reason || '-' }}
        </a-descriptions-item>
        <a-descriptions-item label="处置状态">
          {{ HANDLE_STATUS_LABELS[detailTarget.handle_status as 0 | 1 | 2] ?? detailTarget.handle_status }}
        </a-descriptions-item>
        <a-descriptions-item label="处置人">
          {{ detailTarget.handler || '-' }}
        </a-descriptions-item>
        <a-descriptions-item
          label="处理说明"
          :span="2"
        >
          {{ detailTarget.handle_remark || '-' }}
        </a-descriptions-item>
        <a-descriptions-item label="处理时间">
          {{ detailTarget.handle_time ? formatTime(detailTarget.handle_time) : '-' }}
        </a-descriptions-item>
        <a-descriptions-item label="创建时间">
          {{ formatTime(detailTarget.create_time) }}
        </a-descriptions-item>
      </a-descriptions>
    </a-modal>
  </a-card>
</template>
