<script setup lang="ts">
// P-TX-01 事务步骤配置页（prd/04 TX-01）：按流转编辑 steps → PUT 保存 → GET 回读
// 附「Mock 桩故障注入」测试辅助面板（可折叠；fail-once 开关 + 调用记录查看）
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { message } from 'ant-design-vue';
import { getDoc } from '@/modules/config/api/schema';
import { getTxConfig, saveTxConfig } from '@/modules/config/api/txConfig';
import type { TxStep, TxTransition } from '@/modules/config/api/txConfig';
import {
  MOCK_SYSTEMS,
  clearFailOnce,
  clearMockCalls,
  getMockCalls,
  setFailOnce,
} from '@/modules/config/api/mockTx';
import type { MockCallRecord } from '@/modules/config/api/mockTx';
import { ApiError } from '@/shared/types/api';
import { formatTime } from '@/shared/utils/format';
import { toUserMessage } from '@/shared/utils/errorMessages';

const route = useRoute();
const router = useRouter();

const code = computed(() => route.params.code as string);

const docName = ref('');
const transitions = ref<TxTransition[]>([]);
const updateTime = ref<number | null>(null);
const configured = ref(false);
const loading = ref(true);
const saving = ref(false);

async function load(): Promise<void> {
  loading.value = true;
  try {
    const schema = await getDoc(code.value);
    docName.value = schema.doc_name;
    try {
      const cfg = await getTxConfig(code.value, { silent: true });
      transitions.value = (cfg.transitions ?? []).map((t) => ({
        from_status: t.from_status,
        to_status: t.to_status,
        steps: (t.steps ?? []).map((s) => ({ ...s })),
      }));
      updateTime.value = cfg.update_time ?? null;
      configured.value = true;
    } catch (err) {
      // 40401 未配置：空表单引导
      if (err instanceof ApiError && err.code === 40401) {
        transitions.value = [];
        updateTime.value = null;
        configured.value = false;
      } else {
        throw err;
      }
    }
  } catch {
    message.error('单据定义不存在或加载失败');
    void router.replace('/config/doc');
  } finally {
    loading.value = false;
  }
}

function addTransition(): void {
  transitions.value.push({ from_status: 0, to_status: 0, steps: [newStep(1)] });
}

function removeTransition(index: number): void {
  transitions.value.splice(index, 1);
}

function newStep(seq: number): TxStep {
  return { seq, name: '', action_url: '', compensate_url: '', timeout_ms: 10000, retry: 0 };
}

function addStep(t: TxTransition): void {
  const nextSeq = t.steps.reduce((m, s) => Math.max(m, s.seq), 0) + 1;
  t.steps.push(newStep(nextSeq));
}

function removeStep(t: TxTransition, index: number): void {
  t.steps.splice(index, 1);
}

const isHttpUrl = (url: string): boolean => url.startsWith('http://') || url.startsWith('https://');

/** 前端预校验（与后端 validateAndNormalize 同口径，减少 40002 往返） */
function validate(): string | null {
  const seen = new Set<string>();
  for (const t of transitions.value) {
    const key = `${t.from_status}->${t.to_status}`;
    if (t.from_status === null || t.to_status === null) return '每条流转必须填写起始/目标状态';
    if (seen.has(key)) return `重复的流转事务配置：${key}`;
    seen.add(key);
    if (t.steps.length === 0) return `流转 ${key} 至少需要一个步骤`;
    const seqs = new Set<number>();
    for (const s of t.steps) {
      if (!s.seq || s.seq < 1) return `流转 ${key} 步骤序号须 ≥1`;
      if (seqs.has(s.seq)) return `流转 ${key} 步骤序号 ${s.seq} 重复`;
      seqs.add(s.seq);
      if (!s.name.trim()) return `流转 ${key} 步骤 ${s.seq} 缺失步骤名`;
      if (!isHttpUrl(s.action_url.trim())) return `流转 ${key} 步骤 ${s.seq} 的 action_url 须为 http(s) 地址`;
      if (s.compensate_url && !isHttpUrl(s.compensate_url.trim())) {
        return `流转 ${key} 步骤 ${s.seq} 的 compensate_url 须为 http(s) 地址`;
      }
      if (s.timeout_ms != null && (s.timeout_ms < 1 || s.timeout_ms > 60000)) {
        return `流转 ${key} 步骤 ${s.seq} 超时须 1~60000ms`;
      }
      if (s.retry != null && (s.retry < 0 || s.retry > 3)) return `流转 ${key} 步骤 ${s.seq} 重试须 0~3`;
    }
  }
  return null;
}

async function save(): Promise<void> {
  const reason = validate();
  if (reason) {
    message.error(reason);
    return;
  }
  saving.value = true;
  try {
    const payload = {
      transitions: transitions.value.map((t) => ({
        from_status: t.from_status,
        to_status: t.to_status,
        steps: t.steps
          .slice()
          .sort((a, b) => a.seq - b.seq)
          .map((s) => ({
            seq: s.seq,
            name: s.name.trim(),
            action_url: s.action_url.trim(),
            compensate_url: s.compensate_url?.trim() || undefined,
            timeout_ms: s.timeout_ms ?? 10000,
            retry: s.retry ?? 0,
          })),
      })),
    };
    const res = await saveTxConfig(code.value, payload);
    updateTime.value = res.update_time;
    configured.value = true;
    message.success('事务步骤配置已保存（即时生效）');
    void load();
  } catch (err) {
    if (err instanceof ApiError) message.error(toUserMessage(err));
  } finally {
    saving.value = false;
  }
}

// ---------- Mock 桩故障注入（测试辅助） ----------
/** fail-once 开关状态（页面本地维护；注入后下一次调用失败自动复位） */
const failOnceOn = ref<Record<string, boolean>>({});
const callsOpen = ref(false);
const callsSystem = ref('');
const callsData = ref<MockCallRecord[]>([]);
const callsLoading = ref(false);

async function toggleFailOnce(system: string, checked: boolean): Promise<void> {
  try {
    if (checked) {
      await setFailOnce(system);
      message.success(`已注入：${system} 下一次步骤调用将失败（触发冲正）`);
    } else {
      await clearFailOnce(system);
      message.success(`已清除 ${system} 的故障注入`);
    }
    failOnceOn.value[system] = checked;
  } catch {
    failOnceOn.value[system] = !checked;
  }
}

async function viewCalls(system: string): Promise<void> {
  callsSystem.value = system;
  callsOpen.value = true;
  callsLoading.value = true;
  try {
    const res = await getMockCalls(system);
    callsData.value = [...res.calls].reverse();
  } finally {
    callsLoading.value = false;
  }
}

async function resetCalls(system: string): Promise<void> {
  await clearMockCalls(system);
  callsData.value = [];
  message.success('调用记录已清空');
}

const callColumns = [
  { title: '阶段', key: 'phase', width: 90 },
  { title: 'Saga ID（透传头）', key: 'saga_id_header' },
  { title: '结果', key: 'result', width: 80 },
  { title: '时间', key: 'ts', width: 170 },
];

onMounted(() => void load());
</script>

<template>
  <a-card :loading="loading">
    <div class="mb-4 flex items-center justify-between">
      <div class="flex items-center gap-3">
        <a-button @click="router.push('/config/doc')">
          返回
        </a-button>
        <h2 class="text-lg font-bold">
          事务步骤配置：{{ docName || code }}
        </h2>
        <a-tag v-if="configured">
          最近更新 {{ updateTime ? formatTime(updateTime) : '-' }}
        </a-tag>
        <a-tag
          v-else
          color="warning"
        >
          未配置
        </a-tag>
      </div>
      <a-button
        type="primary"
        :loading="saving"
        @click="save"
      >
        保存配置
      </a-button>
    </div>

    <a-alert
      type="info"
      show-icon
      class="mb-4"
      message="步骤按 seq 升序正向执行、逆序冲正；compensate_url 留空表示该步骤无冲正动作"
    />

    <!-- 流转事务组编辑 -->
    <div
      v-for="(t, ti) in transitions"
      :key="ti"
      class="mb-4 rounded border border-gray-200 p-4"
    >
      <div class="mb-3 flex items-center gap-3">
        <span class="font-medium">流转</span>
        <a-input-number
          v-model:value="t.from_status"
          class="w-24"
          placeholder="起始状态"
        />
        <span class="text-gray-400">→</span>
        <a-input-number
          v-model:value="t.to_status"
          class="w-24"
          placeholder="目标状态"
        />
        <a-button
          size="small"
          @click="addStep(t)"
        >
          + 步骤
        </a-button>
        <a-button
          danger
          size="small"
          class="ml-auto"
          @click="removeTransition(ti)"
        >
          删除流转
        </a-button>
      </div>

      <a-table
        :data-source="t.steps"
        :pagination="false"
        size="small"
        row-key="seq"
        :columns="[
          { title: 'seq', key: 'seq', width: 70 },
          { title: '步骤名', key: 'name' },
          { title: 'action_url', key: 'action_url' },
          { title: '冲正', key: 'compensate' },
          { title: '超时(ms)', key: 'timeout_ms', width: 110 },
          { title: '重试', key: 'retry', width: 80 },
          { title: '', key: 'op', width: 60 },
        ]"
      >
        <template #bodyCell="{ column, record, index }">
          <template v-if="column.key === 'seq'">
            <a-input-number
              v-model:value="record.seq"
              :min="1"
              class="w-15"
              size="small"
            />
          </template>
          <template v-else-if="column.key === 'name'">
            <a-input
              v-model:value="record.name"
              placeholder="如 SAP 同步"
              size="small"
            />
          </template>
          <template v-else-if="column.key === 'action_url'">
            <a-input
              v-model:value="record.action_url"
              placeholder="http://127.0.0.1:8082/api/v1/mock/tx/sap/action"
              size="small"
            />
          </template>
          <template v-else-if="column.key === 'compensate'">
            <div class="flex flex-col gap-1">
              <a-input
                v-model:value="record.compensate_url"
                placeholder="compensate_url（可空）"
                size="small"
              />
            </div>
          </template>
          <template v-else-if="column.key === 'timeout_ms'">
            <a-input-number
              v-model:value="record.timeout_ms"
              :min="1"
              :max="60000"
              size="small"
              class="w-full"
            />
          </template>
          <template v-else-if="column.key === 'retry'">
            <a-input-number
              v-model:value="record.retry"
              :min="0"
              :max="3"
              size="small"
              class="w-full"
            />
          </template>
          <template v-else-if="column.key === 'op'">
            <a-button
              type="link"
              danger
              size="small"
              :disabled="t.steps.length <= 1"
              @click="removeStep(t, index)"
            >
              删除
            </a-button>
          </template>
        </template>
      </a-table>
    </div>

    <a-button
      block
      dashed
      @click="addTransition"
    >
      + 添加流转事务
    </a-button>

    <!-- Mock 桩故障注入（测试辅助，验收演示用；可折叠） -->
    <a-collapse class="mt-6">
      <a-collapse-panel
        key="mock"
        header="Mock 桩故障注入（测试辅助）"
      >
        <a-alert
          type="warning"
          show-icon
          class="mb-3"
          message="仅用于演示验收：fail-once 开启后，该系统下一次 Saga 步骤调用将返回 500 触发冲正；命中后自动复位"
        />
        <div
          v-for="system in MOCK_SYSTEMS"
          :key="system"
          class="mb-2 flex items-center gap-4 rounded border border-gray-100 p-3"
        >
          <span class="w-16 font-mono font-medium">{{ system }}</span>
          <span class="text-xs text-gray-500">下一次调用失败</span>
          <a-switch
            :checked="!!failOnceOn[system]"
            @change="(v: boolean | string) => toggleFailOnce(system, v as boolean)"
          />
          <div class="ml-auto flex gap-2">
            <a-button
              size="small"
              @click="viewCalls(system)"
            >
              调用记录
            </a-button>
          </div>
        </div>
      </a-collapse-panel>
    </a-collapse>

    <!-- 调用记录弹窗 -->
    <a-modal
      v-model:open="callsOpen"
      :title="`Mock 桩调用记录：${callsSystem}`"
      :footer="null"
      width="720px"
    >
      <div class="mb-3 flex justify-end">
        <a-button
          size="small"
          danger
          @click="resetCalls(callsSystem)"
        >
          清空记录
        </a-button>
      </div>
      <a-table
        :columns="callColumns"
        :data-source="callsData"
        :loading="callsLoading"
        size="small"
        :pagination="false"
        :scroll="{ y: 360 }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'phase'">
            <a-tag :color="record.phase === 'compensate' ? 'orange' : 'blue'">
              {{ record.phase }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'saga_id_header'">
            <span class="text-xs">{{ record.saga_id_header || '-' }}</span>
          </template>
          <template v-else-if="column.key === 'result'">
            <a-tag :color="record.result === 'ok' ? 'green' : 'red'">
              {{ record.result }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'ts'">
            {{ formatTime(record.ts) }}
          </template>
        </template>

        <template #emptyText>
          <a-empty description="暂无调用记录（触发一次 Saga 流转后出现）" />
        </template>
      </a-table>
    </a-modal>
  </a-card>
</template>
