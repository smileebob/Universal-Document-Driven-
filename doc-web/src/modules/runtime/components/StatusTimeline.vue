<script setup lang="ts">
// StatusTimeline：单据状态时间线（RD-08）
// 查询失败按错误态展示（错误信息 + trace + 重试），不再把失败解释为历史接口缺失
// 契约字段（GET /data/doc/{docCode}/{uuid}/history）：
// seq/from_status/to_status/operation/operator/operate_time/version_after/from_status_name/to_status_name
import { onMounted, ref, watch } from 'vue';
import type { StatusDef } from '@/modules/config/types';
import { getStatusHistory } from '@/modules/runtime/api/data';
import type { StatusHistoryItem } from '@/modules/runtime/types';
import { ApiError } from '@/shared/types/api';
import { formatTime } from '@/shared/utils/format';
import { toUserMessage } from '@/shared/utils/errorMessages';

const props = defineProps<{
  code: string;
  uuid: string;
  statusDefs: StatusDef[];
  currentStatus?: number;
}>();

const history = ref<StatusHistoryItem[]>([]);
/** 查询错误：保留错误文案与 trace，供运营/开发排查 */
const loadError = ref<{ message: string; traceId?: string } | null>(null);
const loading = ref(true);
/** 上次成功加载的目标：目标变化时不得复用上一条单据的历史数据 */
let loadedKey = '';

/** 状态名：后端 *_status_name 优先，其次本地 status_defs 兜底 */
function stateName(value: number | undefined, backendName?: string): string {
  if (backendName) return backendName;
  return props.statusDefs.find((s) => s.status === value)?.name ?? (value === undefined ? '-' : String(value));
}

async function load(): Promise<void> {
  const key = `${props.code}:${props.uuid}`;
  if (key !== loadedKey) {
    history.value = [];
    loadedKey = key;
  }
  loading.value = true;
  loadError.value = null;
  try {
    history.value = await getStatusHistory('doc', props.code, props.uuid, { silent: true });
  } catch (err) {
    // 保留上次成功历史，明确标注本次刷新失败；首次失败则仅展示当前状态节点
    loadError.value =
      err instanceof ApiError
        ? { message: toUserMessage(err), traceId: err.traceId }
        : { message: '状态变更历史加载失败，请稍后重试' };
  } finally {
    loading.value = false;
  }
}

onMounted(() => void load());
watch(() => [props.code, props.uuid], () => void load());
</script>

<template>
  <a-spin :spinning="loading">
    <!-- 查询失败：错误信息 + trace + 重试；失败不是“正常”，也不是“接口未开放” -->
    <a-alert
      v-if="loadError"
      type="error"
      show-icon
      class="mb-2"
      message="状态变更历史加载失败"
    >
      <template #description>
        <div>{{ loadError.message }}</div>
        <div
          v-if="loadError.traceId"
          class="mt-1 font-mono text-xs text-gray-500"
        >
          trace: {{ loadError.traceId }}
        </div>
        <div
          v-if="history.length"
          class="mt-1"
        >
          以下为上次成功数据，当前刷新失败。
        </div>
      </template>
      <template #action>
        <a-button
          size="small"
          :loading="loading"
          @click="void load()"
        >
          重试
        </a-button>
      </template>
    </a-alert>

    <template v-if="history.length">
      <a-timeline>
        <a-timeline-item
          v-for="(item, idx) in history"
          :key="item.seq ?? idx"
        >
          <div class="font-medium">
            {{ stateName(item.from_status, item.from_status_name) }} →
            {{ stateName(item.to_status, item.to_status_name) }}
          </div>
          <div class="text-xs text-gray-500">
            <span v-if="item.operation">{{ item.operation }} · </span>
            {{ item.operator }} · {{ formatTime(item.operate_time) }}
          </div>
        </a-timeline-item>
      </a-timeline>
    </template>
    <div v-else>
      <!-- 查询失败时仍可展示当前状态节点，但不声称历史为空 -->
      <a-timeline v-if="currentStatus !== undefined">
        <a-timeline-item color="blue">
          <div class="font-medium">
            当前状态：{{ stateName(currentStatus) }}
          </div>
        </a-timeline-item>
      </a-timeline>
      <a-empty
        v-if="!loading && !loadError && currentStatus === undefined"
        description="暂无状态变更记录"
      />
      <div
        v-else-if="!loading && !loadError"
        class="text-xs text-gray-500"
      >
        暂无状态变更记录
      </div>
    </div>
  </a-spin>
</template>
