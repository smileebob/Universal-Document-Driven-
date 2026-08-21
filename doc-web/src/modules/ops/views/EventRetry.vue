<script setup lang="ts">
// P-OP-03 事件重发监控页（prd/10）：Outbox 滞留事件手动重发 + 消费证据面板
// 消费证据体现 Outbox 链路可观测（event_type/mode/消费时间）
import { onMounted, ref } from 'vue';
import { Modal, message } from 'ant-design-vue';
import { listConsumedEvidences, listRetryableEvents, resendEvent } from '@/modules/ops/api/ops';
import type { ConsumedEvidence, OutboxEventItem } from '@/modules/ops/types';
import { SEND_STATUS_LABELS } from '@/modules/ops/types';
import { ApiError } from '@/shared/types/api';
import { formatTime } from '@/shared/utils/format';
import { toUserMessage } from '@/shared/utils/errorMessages';

// ---------- 滞留事件 ----------
const events = ref<OutboxEventItem[]>([]);
const eventLoading = ref(false);

async function loadEvents(): Promise<void> {
  eventLoading.value = true;
  try {
    const res = await listRetryableEvents(50);
    events.value = res.items;
  } finally {
    eventLoading.value = false;
  }
}

/** 手动重发：二次确认 → 复位即投（prd/10 P-OP-03） */
function resend(record: OutboxEventItem): void {
  Modal.confirm({
    title: '确认手动重发该事件？',
    content: `事件 ${record.event_id} 将复位并即时入队投递。`,
    okText: '重发',
    cancelText: '取消',
    onOk: async () => {
      try {
        await resendEvent(record.event_id, { silent: true });
        message.success('重发已受理');
        void loadEvents();
        void loadEvidences();
      } catch (err) {
        if (err instanceof ApiError) message.error(toUserMessage(err));
      }
    },
  });
}

function sendStatusColor(status: number): string {
  if (status === 2) return 'red';
  if (status === 1) return 'green';
  return 'orange';
}

// ---------- 消费证据 ----------
const evidences = ref<ConsumedEvidence[]>([]);
const evidenceLoading = ref(false);

async function loadEvidences(): Promise<void> {
  evidenceLoading.value = true;
  try {
    const res = await listConsumedEvidences(20);
    evidences.value = [...res.items].reverse();
  } finally {
    evidenceLoading.value = false;
  }
}

const eventColumns = [
  { title: '事件 ID', key: 'event_id', width: 220 },
  { title: '单据', key: 'target' },
  { title: '事件类型', key: 'event_type', width: 160 },
  { title: '重试次数', key: 'retry_count', width: 90 },
  { title: '最近错误', key: 'last_error' },
  { title: '发送状态', key: 'send_status', width: 100 },
  { title: '创建时间', key: 'create_time', width: 170 },
  { title: '操作', key: 'action', width: 90 },
];

const evidenceColumns = [
  { title: '事件 ID', key: 'event_id', width: 220 },
  { title: '事件类型', key: 'event_type', width: 160 },
  { title: '消费模式', key: 'mode', width: 100 },
  { title: '消费时间', key: 'consumed_at', width: 170 },
  { title: '报文摘要', key: 'body_summary' },
];

onMounted(() => {
  void loadEvents();
  void loadEvidences();
});
</script>

<template>
  <div class="flex flex-col gap-4">
    <a-card title="事件重发监控（Outbox 滞留事件）">
      <div class="mb-4 flex items-center gap-3">
        <a-button @click="void loadEvents()">
          刷新
        </a-button>
        <span class="text-xs text-gray-400">列表仅展示待人工 / 滞留待发事件；已发送事件不出列</span>
      </div>

      <a-table
        :columns="eventColumns"
        :data-source="events"
        :loading="eventLoading"
        row-key="event_id"
        size="middle"
        :pagination="false"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'event_id'">
            <a-typography-text
              :content="record.event_id"
              copyable
              class="text-xs"
            />
          </template>
          <template v-else-if="column.key === 'target'">
            {{ record.target_code || '-' }}
          </template>
          <template v-else-if="column.key === 'event_type'">
            {{ record.event_type || '-' }}
          </template>
          <template v-else-if="column.key === 'retry_count'">
            {{ record.retry_count ?? 0 }}
          </template>
          <template v-else-if="column.key === 'last_error'">
            <a-tooltip :title="record.last_error">
              <span class="line-clamp-1 text-gray-600">{{ record.last_error || '-' }}</span>
            </a-tooltip>
          </template>
          <template v-else-if="column.key === 'send_status'">
            <a-tag :color="sendStatusColor(record.send_status)">
              {{ SEND_STATUS_LABELS[record.send_status as 0 | 1 | 2] ?? record.send_status }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'create_time'">
            {{ formatTime(record.create_time) }}
          </template>
          <template v-else-if="column.key === 'action'">
            <a-button
              type="link"
              size="small"
              @click="resend(record)"
            >
              手动重发
            </a-button>
          </template>
        </template>

        <template #emptyText>
          <a-empty description="当前无待处理事件，投递正常" />
        </template>
      </a-table>
    </a-card>

    <!-- 消费证据：Outbox 链路可观测（无真实订阅方时的消费落地证明） -->
    <a-card title="消费证据（最近 20 条）">
      <template #extra>
        <a-button
          size="small"
          @click="void loadEvidences()"
        >
          刷新
        </a-button>
      </template>
      <a-table
        :columns="evidenceColumns"
        :data-source="evidences"
        :loading="evidenceLoading"
        size="small"
        row-key="event_id"
        :pagination="false"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'event_id'">
            <span class="text-xs">{{ record.event_id || '-' }}</span>
          </template>
          <template v-else-if="column.key === 'event_type'">
            {{ record.event_type || '-' }}
          </template>
          <template v-else-if="column.key === 'mode'">
            <a-tag color="blue">
              {{ record.mode || '-' }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'consumed_at'">
            {{ formatTime(record.consumed_at) }}
          </template>
          <template v-else-if="column.key === 'body_summary'">
            <a-tooltip :title="record.body_summary">
              <span class="line-clamp-1 font-mono text-xs text-gray-500">{{ record.body_summary || '-' }}</span>
            </a-tooltip>
          </template>
        </template>

        <template #emptyText>
          <a-empty description="暂无消费记录（触发一次流转后将产生 doc_status_changed 事件证据）" />
        </template>
      </a-table>
    </a-card>
  </div>
</template>
