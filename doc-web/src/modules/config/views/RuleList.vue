<script setup lang="ts">
// P-RE-01 规则库列表页（prd/08）：keyword/enabled 筛选 + 启停开关 + 删除（40010 bound_docs）
// 权限（prd/01 §2.1）：平台管理员可编辑与测试；业务管理员只读查看
// 后端规则测试接口 @RequireRoles(PLATFORM_ADMIN)，前端入口同步仅平台管理员可见
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Modal, message } from 'ant-design-vue';
import { deleteRule, listRules, updateRule } from '@/modules/config/api/rule';
import type { RuleDef } from '@/modules/config/types';
import RuleTestPanel from '@/modules/config/components/RuleTestPanel.vue';
import { usePermission } from '@/shared/composables/usePermission';
import { ApiError } from '@/shared/types/api';
import { formatTime } from '@/shared/utils/format';
import { toUserMessage } from '@/shared/utils/errorMessages';

const router = useRouter();
const { can, hasRole } = usePermission();
/** 规则库写权限：无则整页只读呈现（业务管理员，prd/01 §2.1） */
const canManage = computed(() => can('rule:manage'));
/** 在线测试入口：与后端一致仅平台管理员 */
const canTestRule = computed(() => hasRole(['platform_admin']));

const filters = reactive<{ keyword: string; enabled: boolean | '' }>({
  keyword: '',
  enabled: '',
});

const data = ref<RuleDef[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);
const loading = ref(false);

// ---------- 在线测试弹层（P-RE-03：列表页入口，两角色均可用） ----------
const testOpen = ref(false);
const testRuleUuid = ref('');
const testRuleName = ref('');

function openTest(record: RuleDef): void {
  if (!canTestRule.value) return;
  testRuleUuid.value = record.rule_uuid;
  testRuleName.value = record.rule_name;
  testOpen.value = true;
}

async function load(): Promise<void> {
  loading.value = true;
  try {
    const res = await listRules({
      keyword: filters.keyword || undefined,
      enabled: filters.enabled === '' ? undefined : filters.enabled,
      offset: (page.value - 1) * pageSize.value,
      limit: pageSize.value,
    });
    data.value = res.items;
    total.value = res.total;
  } finally {
    loading.value = false;
  }
}

function search(): void {
  page.value = 1;
  void load();
}

function resetFilters(): void {
  filters.keyword = '';
  filters.enabled = '';
  search();
}

/** 启停开关：无独立启停接口，走 PUT 全量更新（enabled 切换） */
async function toggleEnabled(record: RuleDef, enabled: boolean): Promise<void> {
  try {
    await updateRule(record.rule_uuid, {
      rule_name: record.rule_name,
      rule_type: record.rule_type,
      expression: record.expression,
      interface_code: record.interface_code,
      enabled,
      description: record.description,
    });
    record.enabled = enabled;
    message.success('操作成功');
  } catch (err) {
    if (err instanceof ApiError) message.error(toUserMessage(err));
    void load();
  }
}

function remove(record: RuleDef): void {
  Modal.confirm({
    title: `确认删除规则「${record.rule_name}」？`,
    content: '删除后不可恢复',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        await deleteRule(record.rule_uuid);
        message.success('操作成功');
        void load();
      } catch (err) {
        // 被单据绑定：40010 + bound_docs 清单（prd/08 P-RE-01）
        if (err instanceof ApiError && err.code === 40010) {
          const boundDocs = (err.data as { bound_docs?: string[] } | undefined)?.bound_docs;
          Modal.error({
            title: '规则已被单据绑定，无法删除',
            content: boundDocs?.length ? `绑定单据：${boundDocs.join('、')}` : err.message,
          });
        } else if (err instanceof ApiError) {
          message.error(toUserMessage(err));
        }
      }
    },
  });
}

const columns = [
  { title: '规则名称', dataIndex: 'rule_name', key: 'rule_name' },
  { title: '类型', key: 'rule_type' },
  { title: '表达式/接口', key: 'body' },
  { title: '启用', key: 'enabled', width: 90 },
  { title: '版本', dataIndex: 'version', key: 'version', width: 70 },
  { title: '更新时间', key: 'update_time' },
  { title: '操作', key: 'action', width: 170 },
];

onMounted(() => void load());
</script>

<template>
  <a-card>
    <div class="mb-4 flex flex-wrap items-center gap-3">
      <a-input
        v-model:value="filters.keyword"
        placeholder="名称 模糊搜索"
        class="w-55"
        allow-clear
        @press-enter="search"
      />
      <a-select
        v-model:value="filters.enabled"
        class="w-30"
        :options="[
          { value: '', label: '全部启用状态' },
          { value: true, label: '已启用' },
          { value: false, label: '已停用' },
        ]"
      />
      <a-button
        type="primary"
        @click="search"
      >
        查询
      </a-button>
      <a-button @click="resetFilters">
        重置
      </a-button>
      <!-- 新建：仅平台管理员（无权限不渲染，prd/06 §八） -->
      <div
        v-if="canManage"
        class="ml-auto"
      >
        <a-button
          type="primary"
          @click="router.push('/config/rule/edit')"
        >
          + 新建规则
        </a-button>
      </div>
    </div>

    <a-table
      :columns="columns"
      :data-source="data"
      :loading="loading"
      row-key="rule_uuid"
      :pagination="{
        current: page,
        pageSize,
        total,
        showSizeChanger: true,
        pageSizeOptions: ['10', '20', '50', '100'],
        showTotal: (t: number) => `共 ${t} 条`,
        onChange: (p: number, s: number) => {
          page = p;
          pageSize = s;
          load();
        },
      }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'rule_type'">
          <a-tag :color="record.rule_type === 'expression' ? 'blue' : 'purple'">
            {{ record.rule_type === 'expression' ? '表达式' : '接口' }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'body'">
          <span class="text-xs text-gray-500">
            {{ record.rule_type === 'expression' ? record.expression : record.interface_code }}
          </span>
        </template>
        <template v-else-if="column.key === 'enabled'">
          <!-- 启停：仅平台管理员可操作；业务管理员只读标签 -->
          <a-switch
            v-if="canManage"
            :checked="record.enabled"
            size="small"
            @change="(v: boolean) => toggleEnabled(record, v)"
          />
          <a-tag
            v-else
            :color="record.enabled ? 'green' : 'default'"
          >
            {{ record.enabled ? '已启用' : '已停用' }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'update_time'">
          {{ formatTime(record.update_time) }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-button
            v-if="canManage"
            type="link"
            size="small"
            @click="router.push(`/config/rule/edit/${record.rule_uuid}`)"
          >
            编辑
          </a-button>
          <!-- 在线测试：后端仅平台管理员可调用 -->
          <a-button
            v-if="canTestRule"
            type="link"
            size="small"
            @click="openTest(record)"
          >
            测试
          </a-button>
          <a-button
            v-if="canManage"
            type="link"
            danger
            size="small"
            @click="remove(record)"
          >
            删除
          </a-button>
        </template>
      </template>

      <template #emptyText>
        <a-empty :description="canManage ? '暂无规则，点击右上角新建' : '暂无规则'" />
      </template>
    </a-table>

    <!-- 在线测试弹层（P-RE-03）：v-if 保证每次打开重建面板状态 -->
    <a-modal
      v-model:open="testOpen"
      :title="`在线测试 - ${testRuleName}`"
      :width="760"
      :footer="null"
      destroy-on-close
    >
      <RuleTestPanel
        v-if="testOpen"
        :rule-uuid="testRuleUuid"
      />
    </a-modal>
  </a-card>
</template>
