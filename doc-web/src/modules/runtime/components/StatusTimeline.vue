<script setup lang="ts">
// StatusTimeline：单据状态时间线（RD-08）
// 后端暂无历史读接口时优雅降级：仅展示当前状态节点 + 提示（design/10 §7.2）
// 契约字段（GET /data/doc/{docCode}/{uuid}/history）：
// seq/from_status/to_status/operation/operator/operate_time/version_after/from_status_name/to_status_name
import { onMounted, ref, watch } from 'vue';
import type { StatusDef } from '@/modules/config/types';
import { getStatusHistory } from '@/modules/runtime/api/data';
import type { StatusHistoryItem } from '@/modules/runtime/types';
import { formatTime } from '@/shared/utils/format';

const props = defineProps<{
  code: string;
  uuid: string;
  statusDefs: StatusDef[];
  currentStatus?: number;
}>();

const history = ref<StatusHistoryItem[]>([]);
/** 历史接口不可用 → 降级展示 */
const degraded = ref(false);
const loading = ref(true);

/** 状态名：后端 *_status_name 优先，其次本地 status_defs 兜底 */
function stateName(value: number | undefined, backendName?: string): string {
  if (backendName) return backendName;
  return props.statusDefs.find((s) => s.status === value)?.name ?? (value === undefined ? '-' : String(value));
}

async function load(): Promise<void> {
  loading.value = true;
  degraded.value = false;
  try {
    history.value = await getStatusHistory('doc', props.code, props.uuid);
  } catch {
    // 后端未提供历史读接口（阶段二遗留项）：降级不阻断详情页
    history.value = [];
    degraded.value = true;
  } finally {
    loading.value = false;
  }
}

onMounted(() => void load());
watch(() => [props.code, props.uuid], () => void load());
</script>

<template>
  <a-spin :spinning="loading">
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
      <!-- 降级：仅展示当前状态节点 -->
      <a-timeline v-if="currentStatus !== undefined">
        <a-timeline-item color="blue">
          <div class="font-medium">
            当前状态：{{ stateName(currentStatus) }}
          </div>
        </a-timeline-item>
      </a-timeline>
      <a-alert
        v-if="degraded"
        type="info"
        show-icon
        message="状态变更历史暂不可用"
        description="历史读取接口尚未开放，当前仅展示最新状态。"
        class="mt-2"
      />
      <a-empty
        v-else-if="currentStatus === undefined"
        description="暂无状态变更记录"
      />
    </div>
  </a-spin>
</template>
