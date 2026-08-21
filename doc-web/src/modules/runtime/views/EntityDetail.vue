<script setup lang="ts">
// P-RT-04 实体详情页（prd/09）：写后读走本点查接口（GET /data/{code}/{uuid}）
// 单据详情（P-RT-03）已拆至 DocDetail.vue（含状态时间线与流转按钮）
// 关联 Tab（P-RT-05，阶段四 RD-09/11）：RelationPanel 正向展开 + nested_eq 反查
import { onMounted, ref, watch, computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Modal, message } from 'ant-design-vue';
import { schemaName } from '@/modules/config/types';
import { deleteData, getData } from '@/modules/runtime/api/data';
import type { DataRecord } from '@/modules/runtime/types';
import RelationPanel from '@/modules/runtime/components/RelationPanel.vue';
import DynamicForm from '@/shared/components/DynamicForm.vue';
import { usePermission } from '@/shared/composables/usePermission';
import { useSchema } from '@/shared/composables/useSchema';
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

/** 编辑可见性：实体数据由权限决定（无状态机约束，prd/09） */
const canEdit = computed(() => can('data:edit') && !!record.value);
const canDelete = computed(() => can('data:delete') && !!record.value);

async function load(): Promise<void> {
  loading.value = true;
  notFound.value = false;
  try {
    const s = await loadSchema('entity', code.value);
    if (!s) {
      notFound.value = true;
      return;
    }
    record.value = await getData('entity', code.value, uuid.value);
  } catch {
    // 404/已删除：展示空态而非报错（design/10 §7.2）
    record.value = null;
    notFound.value = true;
  } finally {
    loading.value = false;
  }
}

function goBack(): void {
  void router.push(`/biz/entity/${code.value}`);
}

function goEdit(): void {
  void router.push(`/biz/entity/${code.value}/form/${uuid.value}`);
}

function remove(): void {
  Modal.confirm({
    title: '确认删除该条数据？',
    content: '删除后不可恢复',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      await deleteData('entity', code.value, uuid.value);
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
      title="数据不存在或已被删除"
      sub-title="该数据可能已被删除，或定义已停用"
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
      <!-- 头部信息 -->
      <div class="mb-4 flex items-center justify-between">
        <a-breadcrumb>
          <a-breadcrumb-item>
            <a @click.prevent="goBack">{{ schemaName(schema) }}</a>
          </a-breadcrumb-item>
          <a-breadcrumb-item>详情</a-breadcrumb-item>
        </a-breadcrumb>
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
        <a-descriptions-item label="数据编号">
          {{ record.uuid }}
        </a-descriptions-item>
        <a-descriptions-item label="创建人">
          {{ record.creator ?? '-' }}
        </a-descriptions-item>
        <a-descriptions-item label="创建时间">
          {{ record.create_time ? formatTime(record.create_time) : '-' }}
        </a-descriptions-item>
        <a-descriptions-item label="更新时间">
          {{ record.update_time ? formatTime(record.update_time) : '-' }}
        </a-descriptions-item>
      </a-descriptions>

      <a-tabs v-model:active-key="activeTab">
        <!-- 基本信息：DynamicForm readonly 复用（design/10 §3.1 禁止分支复制） -->
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

        <!-- 关联（RD-09/11）：引用了谁 + 被谁引用 -->
        <a-tab-pane
          key="relations"
          tab="关联"
        >
          <RelationPanel
            obj-type="entity"
            :code="code"
            :uuid="uuid"
            :record-data="record"
          />
        </a-tab-pane>
      </a-tabs>
    </template>
  </a-card>
</template>
