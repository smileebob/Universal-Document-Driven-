<script setup lang="ts">
// P-RT-03 单据详情页（prd/09）：DynamicForm readonly + 明细表 + 状态时间线 + 流转按钮
// 流转错误精细处理：42201 规则失败弹窗；42202 Saga 冲正/死信提示；
// 429/42901 Retry-After 冷却禁用；40901 整页刷新
// 关联 Tab（P-RT-05，阶段四 RD-09/11）：RelationPanel 正向展开 + nested_eq 反查
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Modal, message } from 'ant-design-vue';
import { isDocSchema, schemaName } from '@/modules/config/types';
import type { StateMachineTransition } from '@/modules/config/types';
import { getStateMachine } from '@/modules/config/api/stateMachine';
import { deleteData, getData, transitionDocData } from '@/modules/runtime/api/data';
import type {
  DataRecord,
  FailedRule,
  TransitionFailedData,
  TransitionTxFailedData,
} from '@/modules/runtime/types';
import RelationPanel from '@/modules/runtime/components/RelationPanel.vue';
import StatusTimeline from '@/modules/runtime/components/StatusTimeline.vue';
import TransitionButtons from '@/modules/runtime/components/TransitionButtons.vue';
import DynamicForm from '@/shared/components/DynamicForm.vue';
import RuleFailedModal from '@/shared/components/RuleFailedModal.vue';
import StatusTag from '@/shared/components/StatusTag.vue';
import { usePermission } from '@/shared/composables/usePermission';
import { useSchema } from '@/shared/composables/useSchema';
import { ApiError } from '@/shared/types/api';
import { formatTime } from '@/shared/utils/format';

const route = useRoute();
const router = useRouter();
const { can } = usePermission();
const { schema, loading: schemaLoading, loadSchema } = useSchema();

const code = computed(() => route.params.code as string);
const uuid = computed(() => route.params.uuid as string);

const record = ref<DataRecord | null>(null);
const loading = ref(false);
const notFound = ref(false);
const activeTab = ref('basic');
const transitions = ref<StateMachineTransition[]>([]);
/** 时间线 remount key：流转成功后强制重拉历史 */
const timelineKey = ref(0);

const statusDefs = computed(() => (schema.value && isDocSchema(schema.value) ? schema.value.status_defs : []));

/** 当前状态展示名：后端 status_name 优先，其次 status_defs 兜底 */
const currentStatusName = computed<string | number>(() => {
  const r = record.value;
  if (!r) return '-';
  return r.status_name ?? statusDefs.value.find((s) => s.status === r.current_status)?.name ?? r.current_status ?? '-';
});

// ---------- 流转交互状态 ----------
const transitioning = ref(false);
const cooldownLeft = ref(0);
let cooldownTimer: ReturnType<typeof setInterval> | null = null;
const ruleModalOpen = ref(false);
const failedRules = ref<FailedRule[]>([]);
/** 最近一次流转触发 Saga 的证据（saga_triggered=true 时展示，可复制 saga_id） */
const lastSagaId = ref<string | null>(null);

const cooldownText = computed(() => (cooldownLeft.value > 0 ? `单据锁忙，${cooldownLeft.value}s 后可重试` : ''));
const transitionDisabled = computed(() => transitioning.value || cooldownLeft.value > 0);

/** 编辑可见性：权限 + 当前状态 editable（status_defs 契约） */
const canEdit = computed(() => {
  if (!can('data:edit') || !record.value) return false;
  return statusDefs.value.find((s) => s.status === record.value!.current_status)?.editable ?? false;
});

/** 删除：仅暂存（100）可删（prd/09 P-RT-01） */
const canDelete = computed(() => can('data:delete') && record.value?.current_status === 100);

async function load(): Promise<void> {
  loading.value = true;
  notFound.value = false;
  try {
    const s = await loadSchema('doc', code.value);
    if (!s) {
      notFound.value = true;
      return;
    }
    record.value = await getData('doc', code.value, uuid.value);
    // 状态机：未配置（40401）→ 无流转按钮；失败不阻断详情
    try {
      const sm = await getStateMachine(code.value);
      transitions.value = sm.transitions ?? [];
    } catch {
      transitions.value = [];
    }
  } catch {
    record.value = null;
    notFound.value = true;
  } finally {
    loading.value = false;
  }
}

/** 429 Retry-After 冷却（秒），期间禁用全部流转按钮 */
function startCooldown(seconds: number): void {
  cooldownLeft.value = Math.max(1, Math.ceil(seconds));
  if (cooldownTimer) clearInterval(cooldownTimer);
  cooldownTimer = setInterval(() => {
    cooldownLeft.value -= 1;
    if (cooldownLeft.value <= 0 && cooldownTimer) {
      clearInterval(cooldownTimer);
      cooldownTimer = null;
    }
  }, 1000);
}

async function doTransition(targetStatus: number): Promise<void> {
  transitioning.value = true;
  lastSagaId.value = null;
  try {
    // 幂等键格式：{uuid}:transition:{token}（后端 IdempotencyService 回放首次结果）
    const res = await transitionDocData(code.value, uuid.value, targetStatus, {
      idempotencyKey: `${uuid.value}:transition:${crypto.randomUUID()}`,
    });
    message.success('流转成功');
    // Saga 执行提示（阶段三）：saga_triggered=true 时展示 saga_id 供追溯
    if (res.saga_triggered) {
      lastSagaId.value = res.saga_id ?? null;
    }
    timelineKey.value += 1;
    await load();
  } catch (err) {
    handleTransitionError(err);
  } finally {
    transitioning.value = false;
  }
}

function handleTransitionError(err: unknown): void {
  if (!(err instanceof ApiError)) return;
  if (err.code === 42201) {
    // 规则失败明细弹窗（prd/09 P-RT-03）
    failedRules.value = (err.data as TransitionFailedData | undefined)?.failed_rules ?? [];
    ruleModalOpen.value = true;
    return;
  }
  if (err.code === 42202) {
    // 事务步骤失败（阶段三 TX-02）：COMPENSATED 已冲正 / DEAD 转死信人工处置
    const data = err.data as TransitionTxFailedData | undefined;
    const sagaId = data?.saga_id ?? '-';
    if (data?.saga_status === 'DEAD') {
      Modal.error({
        title: '事务步骤执行失败且冲正失败',
        content: `已转死信人工处置（运营监控 → 补偿失败处理），单据状态不变。Saga：${sagaId}`,
      });
    } else {
      Modal.warning({
        title: '事务步骤执行失败，已完成冲正',
        content: `外部系统动作已冲正，单据状态不变，可稍后重试。Saga：${sagaId}`,
      });
    }
    void load();
    return;
  }
  if (err.code === 42901 || err.httpStatus === 429) {
    // 锁忙：按 Retry-After 冷却禁用
    startCooldown(err.retryAfter ?? 3);
    return;
  }
  if (err.code === 40901) {
    // 状态冲突：整页刷新取最新状态
    message.warning('单据状态已变化，正在刷新');
    void load();
    return;
  }
  message.error(err.message);
}

function goBack(): void {
  void router.push(`/biz/doc/${code.value}`);
}

function goEdit(): void {
  void router.push(`/biz/doc/${code.value}/form/${uuid.value}`);
}

function remove(): void {
  Modal.confirm({
    title: '确认删除该单据？',
    content: '删除后不可恢复',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      await deleteData('doc', code.value, uuid.value);
      message.success('操作成功');
      goBack();
    },
  });
}

onMounted(() => void load());
watch([code, uuid], () => void load());
</script>

<template>
  <a-card :loading="(loading || schemaLoading) && !record && !notFound">
    <!-- 空态：数据不存在或已被删除 -->
    <a-result
      v-if="notFound"
      status="404"
      title="单据不存在或已被删除"
      sub-title="该单据可能已被删除，或定义已停用"
    >
      <template #extra>
        <a-button
          type="primary"
          @click="goBack"
        >
          返回列表
        </a-button>
      </template>
    </a-result>

    <template v-else-if="schema && record">
      <!-- 头部：面包屑 + 状态标签 + 操作 -->
      <div class="mb-4 flex items-center justify-between">
        <div class="flex items-center gap-3">
          <a-breadcrumb>
            <a-breadcrumb-item>
              <a @click.prevent="goBack">{{ schemaName(schema) }}</a>
            </a-breadcrumb-item>
            <a-breadcrumb-item>详情</a-breadcrumb-item>
          </a-breadcrumb>
          <StatusTag
            :status="record.current_status ?? 0"
            :status-defs="statusDefs"
          />
        </div>
        <div class="flex gap-2">
          <a-button
            v-if="canEdit"
            type="primary"
            @click="goEdit"
          >
            编辑
          </a-button>
          <a-button
            v-if="canDelete"
            danger
            @click="remove"
          >
            删除
          </a-button>
          <a-button @click="goBack">
            返回列表
          </a-button>
        </div>
      </div>

      <a-descriptions
        :column="3"
        size="small"
        bordered
        class="mb-4"
      >
        <a-descriptions-item label="单据编号">
          {{ record.uuid }}
        </a-descriptions-item>
        <a-descriptions-item label="当前状态">
          {{ currentStatusName }}
        </a-descriptions-item>
        <a-descriptions-item label="版本">
          v{{ record.version ?? 0 }}
        </a-descriptions-item>
        <a-descriptions-item label="创建人">
          {{ record.creator ?? '-' }}
        </a-descriptions-item>
        <a-descriptions-item label="创建部门">
          {{ record.create_dept ?? '-' }}
        </a-descriptions-item>
        <a-descriptions-item label="创建时间">
          {{ record.create_time ? formatTime(record.create_time) : '-' }}
        </a-descriptions-item>
      </a-descriptions>

      <!-- 流转按钮区（状态机驱动；422/429/409 精细处理） -->
      <div class="mb-4 rounded border border-gray-100 bg-gray-50 p-3">
        <TransitionButtons
          :current-status="record.current_status ?? 0"
          :transitions="transitions"
          :status-defs="statusDefs"
          :disabled="transitionDisabled"
          :cooldown-text="cooldownText"
          @transition="doTransition"
        />
        <!-- Saga 执行提示（阶段三）：saga_id 可复制，供运营追溯 -->
        <a-alert
          v-if="lastSagaId !== null"
          type="success"
          show-icon
          class="mt-3"
          message="本次流转已触发 Saga 事务编排"
        >
          <template #description>
            <span class="mr-2">Saga ID：</span>
            <a-typography-text
              :content="lastSagaId || '（后端未返回）'"
              copyable
              class="text-xs"
            />
          </template>
        </a-alert>
      </div>

      <a-tabs v-model:active-key="activeTab">
        <!-- 基本信息：DynamicForm readonly（table 属性由 DetailTable 只读渲染） -->
        <a-tab-pane
          key="basic"
          tab="基本信息"
        >
          <DynamicForm
            :attributes="schema.attributes"
            :model-value="record.attributes"
            mode="readonly"
          />
        </a-tab-pane>

        <!-- 状态时间线（RD-08，历史接口缺失时降级展示） -->
        <a-tab-pane
          key="timeline"
          tab="状态时间线"
        >
          <StatusTimeline
            :key="timelineKey"
            :code="code"
            :uuid="uuid"
            :status-defs="statusDefs"
            :current-status="record.current_status"
          />
        </a-tab-pane>

        <!-- 关联（RD-09/11）：引用了谁 + 被谁引用 -->
        <a-tab-pane
          key="relations"
          tab="关联"
        >
          <RelationPanel
            obj-type="doc"
            :code="code"
            :uuid="uuid"
            :record-data="record"
          />
        </a-tab-pane>
      </a-tabs>

      <RuleFailedModal
        v-model:open="ruleModalOpen"
        :failed-rules="failedRules"
        title="流转规则校验未通过"
      />
    </template>
  </a-card>
</template>
