<script setup lang="ts">
// P-RE-05 单据规则绑定页（prd/08）：create_rules + status_rules 按状态分组多选，保存即生效
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Modal, message } from 'ant-design-vue';
import { getDoc } from '@/modules/config/api/schema';
import { getDocRuleConfig, listRules, saveDocRuleConfig } from '@/modules/config/api/rule';
import type { RuleDef, StatusDef, StatusRuleBinding } from '@/modules/config/types';
import { ApiError } from '@/shared/types/api';

const route = useRoute();
const router = useRouter();

const code = computed(() => route.params.code as string);

const docName = ref('');
const statusDefs = ref<StatusDef[]>([]);
const rules = ref<RuleDef[]>([]);
const createRules = ref<string[]>([]);
/** 按目标状态分组的流转规则绑定 */
const statusRules = ref<StatusRuleBinding[]>([]);
const version = ref<number | null>(null);
const loading = ref(true);
const saving = ref(false);

const ruleOptions = computed(() =>
  rules.value.map((r) => ({
    value: r.rule_uuid,
    label: r.enabled ? `${r.rule_name}` : `${r.rule_name}（已停用）`,
  })),
);

async function load(): Promise<void> {
  loading.value = true;
  try {
    const [schema, ruleRes] = await Promise.all([
      getDoc(code.value),
      listRules({ offset: 0, limit: 200 }),
    ]);
    docName.value = schema.doc_name;
    statusDefs.value = schema.status_defs ?? [];
    rules.value = ruleRes.items;

    // 已绑定配置：未配置（40401）按空处理
    try {
      const config = await getDocRuleConfig(code.value);
      createRules.value = [...(config.create_rules ?? [])];
      statusRules.value = (config.status_rules ?? []).map((s) => ({ status: s.status, rules: [...s.rules] }));
      version.value = config.version;
    } catch (err) {
      if (err instanceof ApiError && err.code === 40401) {
        createRules.value = [];
        statusRules.value = [];
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

function rulesOf(status: number): string[] {
  return statusRules.value.find((s) => s.status === status)?.rules ?? [];
}

function onStatusRulesChange(status: number, uuids: string[]): void {
  const existing = statusRules.value.find((s) => s.status === status);
  if (existing) {
    existing.rules = uuids;
  } else {
    statusRules.value.push({ status, rules: uuids });
  }
}

async function save(): Promise<void> {
  saving.value = true;
  try {
    const res = await saveDocRuleConfig(
      code.value,
      {
        create_rules: createRules.value,
        // 仅提交非空分组，减少噪声
        status_rules: statusRules.value.filter((s) => s.rules.length > 0),
      },
      { silent: true },
    );
    version.value = res.version;
    message.success('规则绑定保存成功');
  } catch (err) {
    if (err instanceof ApiError && err.code === 40004) {
      // 引用规则不存在：missing_rules 清单（RE-05）
      const missing = (err.data as { missing_rules?: string[] } | undefined)?.missing_rules;
      Modal.error({
        title: '引用的规则不存在',
        content: missing?.length ? `缺失规则：${missing.join('、')}` : err.message,
      });
    } else if (err instanceof ApiError) {
      message.error(err.message);
    }
  } finally {
    saving.value = false;
  }
}

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
          规则绑定：{{ docName || code }}
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
        保存绑定
      </a-button>
    </div>

    <!-- 创建时机规则（新建单据时校验） -->
    <h3 class="mb-2 text-base font-medium">
      创建时机规则
    </h3>
    <a-select
      v-model:value="createRules"
      mode="multiple"
      :options="ruleOptions"
      placeholder="选择创建时校验的规则（可多选）"
      class="mb-6 w-full"
      allow-clear
    />

    <!-- 流转时机规则：按目标状态分组多选（status_rules） -->
    <h3 class="mb-2 text-base font-medium">
      流转时机规则（按目标状态分组）
    </h3>
    <div
      v-if="statusDefs.length === 0"
      class="text-sm text-gray-400"
    >
      该单据未配置业务状态
    </div>
    <div
      v-for="st in statusDefs"
      :key="st.status"
      class="mb-3 rounded border border-gray-100 p-3"
    >
      <div class="mb-1 text-sm font-medium">
        流转到「{{ st.name }}（{{ st.status }}）」时校验：
        <a-tag
          v-if="st.terminal"
          color="red"
          class="ml-1"
        >
          终态
        </a-tag>
      </div>
      <a-select
        :value="rulesOf(st.status)"
        mode="multiple"
        :options="ruleOptions"
        placeholder="选择规则（可多选）"
        class="w-full"
        allow-clear
        @update:value="(v: string[]) => onStatusRulesChange(st.status, v)"
      />
    </div>
  </a-card>
</template>
