<script setup lang="ts">
// P-OP-03 事件重发监控页（prd/10）：Outbox 滞留事件手动重发 + 消费证据面板
// 查询区块区分 idle/loading/success/error：失败不等同于链路正常，空结果仅表示当前查询范围为空
import { computed, onMounted, ref } from 'vue';
import { Modal, message } from 'ant-design-vue';
import { listConsumedEvidences, listRetryableEvents, resendEvent } from '@/modules/ops/api/ops';
import type { ConsumedEvidence, OutboxEventItem } from '@/modules/ops/types';
import { SEND_STATUS_LABELS } from '@/modules/ops/types';
import { ApiError } from '@/shared/types/api';
import { formatTime } from '@/shared/utils/format';
import { toUserMessage } from '@/shared/utils/errorMessages';

type LoadState = 'idle' | 'loading' | 'success' | 'error';

interface LoadFailure {
  message: string;
  traceId?: string;
}

function toFailure(err: unknown, fallback: string): LoadFailure {
  if (err instanceof ApiError) {
    return { message: toUserMessage(err), traceId: err.traceId };
  }
  return { message: fallback };
}

const EVENT_LIMIT = 50;
const EVIDENCE_LIMIT = 20;

// ---------- 滞留事件 ----------
const events = ref<OutboxEventItem[]>([]);
const eventState = ref<LoadState>('idle');
const eventError = ref<LoadFailure | null>(null);
const eventTotal = ref(0);
const eventLoading = computed(() => eventState.value === 'loading');
const eventTruncated = computed(() => eventTotal.value > events.value.length);

async function loadEvents(): Promise<void> {
  eventState.value = 'loading';
  eventError.value = null;
  try {
    const res = await listRetryableEvents(EVENT_LIMIT, { silent: true });
    events.value = res.items;
    eventTotal.value = res.total;
    eventState.value = 'success';
  } catch (err) {
    // 保留上次成功数据但明确标记为刷新失败，不把未知当正常
    eventError.value = toFailure(err, '待处理事件查询失败，请稍后重试');
    eventState.value = 'error';
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
        // 受理只代表进入重投队列，不代表消费者已处理成功
        message.success('重发已受理（受理不代表已投递，请以发送状态与消费证据为准）');
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
const evidenceState = ref<LoadState>('idle');
const evidenceError = ref<LoadFailure | null>(null);
const evidenceTotal = ref(0);
const evidenceLoading = computed(() => evidenceState.value === 'loading');
const evidenceTruncated = computed(() => evidenceTotal.value > evidences.value.length);

async function loadEvidences(): Promise<void> {
  evidenceState.value = 'loading';
  evidenceError.value = null;
  try {
    const res = await listConsumedEvidences(EVIDENCE_LIMIT, { silent: true });
    evidences.value = [...res.items].reverse();
    evidenceTotal.value = res.total;
    evidenceState.value = 'success';
  } catch (err) {
    evidenceError.value = toFailure(err, '消费证据查询失败，请稍后重试');
    evidenceState.value = 'error';
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
        <a-button
          :loading="eventLoading"
          @click="void loadEvents()"
        >
          刷新
        </a-button>
        <span class="text-xs text-gray-400">列表仅展示待人工 / 滞留待发事件；已发送事件不出列</span>
      </div>

      <!-- 查询失败：保留上次成功数据也要标注，不能显示为链路正常 -->
      <a-alert
        v-if="eventError"
        type="error"
        show-icon
        class="mb-3"
        message="待处理事件查询失败"
      >
        <template #description>
          <div>{{ eventError.message }}</div>
          <div
            v-if="eventError.traceId"
            class="mt-1 font-mono text-xs text-gray-500"
          >
            trace: {{ eventError.traceId }}
          </div>
          <div
            v-if="events.length"
            class="mt-1"
          >
            以下为上次成功数据，当前刷新失败。
          </div>
        </template>
        <template #action>
          <a-button
            size="small"
            :loading="eventLoading"
            @click="void loadEvents()"
          >
            重试
          </a-button>
        </template>
      </a-alert>

      <!-- 固定 50 条上限：明确提示截断，不把 items.size 当作全量 -->
      <a-alert
        v-if="eventState === 'success' && eventTruncated"
        type="warning"
        show-icon
        class="mb-3"
        message="查询结果已截断"
        :description="`接口当前固定最多返回 ${EVENT_LIMIT} 条，本次返回 ${events.length} 条，共 ${eventTotal} 条。请等待完整分页能力或缩小排查范围。`"
      />

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
          <a-empty
            v-if="eventState === 'error'"
            description="查询失败，无法判断投递状态，请重试"
          />
          <a-empty
            v-else-if="eventState === 'loading'"
            description="正在查询…"
          />
          <a-empty
            v-else-if="eventState === 'success'"
            description="未查询到待处理事件（本次查询成功，当前范围为空）"
          />
          <a-empty
            v-else
            description="尚未查询"
          />
        </template>
      </a-table>
    </a-card>

    <!-- 消费证据：Outbox 链路可观测（无真实订阅方时的消费落地证明） -->
    <a-card title="消费证据（最近 20 条）">
      <template #extra>
        <a-button
          size="small"
          :loading="evidenceLoading"
          @click="void loadEvidences()"
        >
          刷新
        </a-button>
      </template>

      <div class="mb-3 text-xs text-gray-500">
        本地 Outbox 发送状态与消费者处理证据是不同事实；“已发送”不代表消费方已处理成功。
      </div>

      <a-alert
        v-if="evidenceError"
        type="error"
        show-icon
        class="mb-3"
        message="消费证据查询失败"
      >
        <template #description>
          <div>{{ evidenceError.message }}</div>
          <div
            v-if="evidenceError.traceId"
            class="mt-1 font-mono text-xs text-gray-500"
          >
            trace: {{ evidenceError.traceId }}
          </div>
          <div
            v-if="evidences.length"
            class="mt-1"
          >
            以下为上次成功数据，当前刷新失败。
          </div>
        </template>
        <template #action>
          <a-button
            size="small"
            :loading="evidenceLoading"
            @click="void loadEvidences()"
          >
            重试
          </a-button>
        </template>
      </a-alert>

      <a-alert
        v-if="evidenceState === 'success' && evidenceTruncated"
        type="warning"
        show-icon
        class="mb-3"
        message="消费证据已截断"
        :description="`接口当前最多返回 ${EVIDENCE_LIMIT} 条，共 ${evidenceTotal} 条，仅展示最近 ${evidences.length} 条。`"
      />

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
          <a-empty
            v-if="evidenceState === 'error'"
            description="查询失败，无法判断消费证据，请重试"
          />
          <a-empty
            v-else-if="evidenceState === 'loading'"
            description="正在查询…"
          />
          <a-empty
            v-else-if="evidenceState === 'success'"
            description="本次查询成功，暂无消费记录（触发一次流转后将产生 doc_status_changed 事件证据）"
          />
          <a-empty
            v-else
            description="尚未查询"
          />
        </template>
      </a-table>
    </a-card>
  </div>
</template>
