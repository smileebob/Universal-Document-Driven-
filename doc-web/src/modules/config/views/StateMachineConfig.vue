<script setup lang="ts">
// P-DC-SM 状态机配置页（SM-01/02）：状态集 + 流转矩阵 + G6 图形预览，保存即生效
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Modal, message } from 'ant-design-vue';
import { getDoc } from '@/modules/config/api/schema';
import { getStateMachine, saveStateMachine } from '@/modules/config/api/stateMachine';
import type { StateMachineTransition, StatusDef } from '@/modules/config/types';
import StateMachineGraph from '@/modules/config/components/StateMachineGraph.vue';
import { ApiError } from '@/shared/types/api';

const route = useRoute();
const router = useRouter();

const code = computed(() => route.params.code as string);

const docName = ref('');
const states = ref<StatusDef[]>([]);
const transitions = ref<StateMachineTransition[]>([]);
const version = ref<number | null>(null);
const loading = ref(true);
const saving = ref(false);
const warnings = ref<string[]>([]);

async function load(): Promise<void> {
  loading.value = true;
  warnings.value = [];
  try {
    const schema = await getDoc(code.value);
    docName.value = schema.doc_name;
    // 状态基线：已保存的状态机优先；未配置（40401）回落到单据定义 status_defs
    try {
      const sm = await getStateMachine(code.value);
      states.value = (sm.states ?? []).map((s) => ({ ...s }));
      transitions.value = (sm.transitions ?? []).map((t) => ({ start: t.start, end: [...(t.end ?? [])] }));
      version.value = sm.version;
    } catch (err) {
      if (err instanceof ApiError && err.code === 40401) {
        states.value = (schema.status_defs ?? []).map((s) => ({ ...s }));
        transitions.value = [];
        version.value = null;
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

/** 流转矩阵取值：start → end 数组 */
function targetsOf(start: number): number[] {
  return transitions.value.find((t) => t.start === start)?.end ?? [];
}

function onTargetsChange(start: number, ends: number[]): void {
  const existing = transitions.value.find((t) => t.start === start);
  if (existing) {
    existing.end = ends;
  } else {
    transitions.value.push({ start, end: ends });
  }
  // 触发图组件 watch（深拷贝替换引用）
  transitions.value = transitions.value.map((t) => ({ ...t, end: [...t.end] }));
}

/** 目标选项：排除自身（自流转无意义） */
function targetOptions(start: number): { label: string; value: number }[] {
  return states.value
    .filter((s) => s.status !== start)
    .map((s) => ({ label: `${s.name || s.status}(${s.status})`, value: s.status }));
}

async function save(): Promise<void> {
  if (states.value.length === 0) {
    message.error('至少保留一个状态');
    return;
  }
  const seen = new Set<number>();
  for (const s of states.value) {
    if (!Number.isFinite(s.status) || !s.name.trim()) {
      message.error('每个状态必须有状态值与状态名');
      return;
    }
    if (seen.has(s.status)) {
      message.error(`状态值 ${s.status} 重复`);
      return;
    }
    seen.add(s.status);
  }
  saving.value = true;
  try {
    const payload = {
      states: states.value,
      transitions: transitions.value.filter((t) => t.end.length > 0),
    };
    const res = await saveStateMachine(code.value, payload, { silent: true });
    version.value = res.version;
    warnings.value = res.warnings ?? [];
    message.success('状态机保存成功');
  } catch (err) {
    if (err instanceof ApiError && err.code === 40011) {
      // 硬校验失败：原因清单弹窗（SM-01）
      const reasons = (err.data as { reasons?: string[] } | undefined)?.reasons;
      Modal.error({
        title: '状态机校验未通过',
        content: reasons?.length ? reasons.join('；') : err.message,
      });
    } else if (err instanceof ApiError && err.code === 40901) {
      // 并发保存竞态：刷新后重试
      message.error('状态机已被并发修改，已为你重新加载');
      void load();
    } else if (err instanceof ApiError) {
      message.error(err.message);
    }
  } finally {
    saving.value = false;
  }
}

const stateColumns = [
  { title: '状态值', key: 'status', width: 120 },
  { title: '状态名', key: 'name' },
  { title: '可编辑', key: 'editable', width: 90 },
  { title: '终态', key: 'terminal', width: 90 },
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
          状态机配置：{{ docName || code }}
        </h2>
        <a-tag v-if="version !== null">
          v{{ version }}
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
        保存状态机
      </a-button>
    </div>

    <!-- 软告警：保存成功但存在不可达状态等提示（SM-01 warnings） -->
    <a-alert
      v-if="warnings.length"
      type="warning"
      show-icon
      class="mb-4"
      message="保存成功，但存在以下告警"
      :description="warnings.join('；')"
    />

    <a-row :gutter="16">
      <!-- 左：状态集编辑 -->
      <a-col :span="10">
        <h3 class="mb-2 text-base font-medium">
          状态集
        </h3>
        <a-table
          :columns="stateColumns"
          :data-source="states"
          :pagination="false"
          row-key="status"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'status'">
              <a-input-number
                v-model:value="record.status"
                :min="0"
                class="w-full"
              />
            </template>
            <template v-else-if="column.key === 'name'">
              <a-input v-model:value="record.name" />
            </template>
            <template v-else-if="column.key === 'editable'">
              <a-switch v-model:checked="record.editable" />
            </template>
            <template v-else-if="column.key === 'terminal'">
              <a-switch v-model:checked="record.terminal" />
            </template>
          </template>
        </a-table>
      </a-col>

      <!-- 右：流转矩阵（start → 目标状态多选） -->
      <a-col :span="14">
        <h3 class="mb-2 text-base font-medium">
          流转规则
        </h3>
        <div
          v-if="states.length === 0"
          class="text-sm text-gray-400"
        >
          请先添加状态
        </div>
        <div
          v-for="st in states"
          :key="st.status"
          class="mb-3 rounded border border-gray-100 p-3"
        >
          <div class="mb-1 text-sm font-medium">
            {{ st.name || st.status }}（{{ st.status }}）可流转到：
          </div>
          <a-checkbox-group
            :value="targetsOf(st.status)"
            :options="targetOptions(st.status)"
            @change="(v: unknown) => onTargetsChange(st.status, v as number[])"
          />
          <div
            v-if="targetsOf(st.status).length === 0"
            class="mt-1 text-xs text-gray-400"
          >
            未配置出向流转
          </div>
        </div>
      </a-col>
    </a-row>

    <!-- G6 图形预览（vendor-g6 分包，随本页懒加载） -->
    <h3 class="mb-2 mt-6 text-base font-medium">
      图形预览
    </h3>
    <StateMachineGraph
      :states="states"
      :transitions="transitions"
    />
  </a-card>
</template>
