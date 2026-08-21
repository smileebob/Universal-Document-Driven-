<script setup lang="ts">
// P-RT-02 通用表单页（新增/编辑，prd/09）：动态渲染 + 前后端两级校验
// 写后读一致性：保存成功后跳转详情页（走 GET 详情点查接口，而非列表）
// 阶段四：按钮级权限——无数据写权限（business_user）时整页只读且隐藏保存（prd/06 §八）
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { message } from 'ant-design-vue';
import type { ObjType } from '@/modules/config/types';
import { isDocSchema, schemaName } from '@/modules/config/types';
import { createData, getData, updateData } from '@/modules/runtime/api/data';
import type { FailedRule, FieldError, SaveDataPayload } from '@/modules/runtime/types';
import DynamicForm from '@/shared/components/DynamicForm.vue';
import RuleFailedModal from '@/shared/components/RuleFailedModal.vue';
import { usePermission } from '@/shared/composables/usePermission';
import { useSchema } from '@/shared/composables/useSchema';
import { ApiError } from '@/shared/types/api';
import { buildDefaultModel } from '@/shared/utils/form';
import { toUserMessage } from '@/shared/utils/errorMessages';

const route = useRoute();
const router = useRouter();
const { can } = usePermission();
const { schema, loading, loadSchema } = useSchema();

const objType = computed(() => route.params.objType as ObjType);
const code = computed(() => route.params.code as string);
const uuid = computed(() => (route.params.uuid as string) || '');
const isEdit = computed(() => !!uuid.value);

/** 写权限：新增=data:create，编辑=data:edit（business_user 只读，服务端 403 兜底） */
const canWrite = computed(() => (isEdit.value ? can('data:edit') : can('data:create')));

const formRef = ref<InstanceType<typeof DynamicForm> | null>(null);
const model = ref<Record<string, unknown>>({});
const saving = ref(false);
/**
 * 编辑模式的记录版本号（乐观锁）：
 * 加载时取自 getData 返回的 record.version，保存 PUT 必带，
 * 否则后端报 40002（version 缺失/不一致）。
 */
const recordVersion = ref<number | undefined>(undefined);
/** 单据非可编辑状态进入编辑页 → 整页只读（prd/09 P-RT-02） */
const stateLocked = ref(false);
const ruleModalOpen = ref(false);
const failedRules = ref<FailedRule[]>([]);

onMounted(async () => {
  const s = await loadSchema(objType.value, code.value);
  if (!s) return;
  if (isEdit.value) {
    try {
      const record = await getData(objType.value, code.value, uuid.value);
      model.value = { ...record.attributes };
      // 记录当前版本号，保存时随 PUT 提交（乐观锁）
      recordVersion.value = record.version;
      if (isDocSchema(s)) {
        // 状态可编辑性取后端 status_defs 契约（editable）
        const state = s.status_defs.find((st) => st.status === record.current_status);
        stateLocked.value = !(state?.editable ?? false);
      }
    } catch {
      message.error('数据不存在或已被删除');
      void router.replace(`/biz/${objType.value}/${code.value}`);
    }
  } else {
    // default_value 仅新增模式自动填充（prd/06 §4.5）
    model.value = buildDefaultModel(s.attributes);
  }
});

const formMode = computed(() => {
  if (stateLocked.value || !canWrite.value) return 'readonly';
  return isEdit.value ? 'edit' : 'create';
});

async function save(): Promise<void> {
  if (!schema.value || !formRef.value || !canWrite.value) return;
  const { valid } = await formRef.value.validate();
  if (!valid) return;
  saving.value = true;
  try {
    // 编辑模式必须携带 version（乐观锁，缺失/过期 → 40002）；新增不带
    const payload: SaveDataPayload = isEdit.value
      ? { attributes: model.value, version: recordVersion.value }
      : { attributes: model.value };
    const saved = isEdit.value
      ? await updateData(objType.value, code.value, uuid.value, payload)
      : await createData(objType.value, code.value, payload);
    // 保存成功后同步后端返回的最新 version（若停留本页继续保存，避免版本过期）
    if (isEdit.value) {
      recordVersion.value = saved.version;
    }
    message.success('操作成功');
    // 写后立即读：跳转详情页走详情点查接口（design/10 §4.2）
    void router.replace(`/biz/${objType.value}/${code.value}/detail/${saved.uuid}`);
  } catch (err) {
    handleSaveError(err);
  } finally {
    saving.value = false;
  }
}

/** 后端错误精细处理（silent 请求，design/10 §7.1/§7.3） */
function handleSaveError(err: unknown): void {
  if (!(err instanceof ApiError)) return;
  if (err.code === 42201) {
    // 规则失败明细弹窗（prd/09 P-RT-02）
    failedRules.value = (err.data as { failed_rules?: FailedRule[] } | undefined)?.failed_rules ?? [];
    ruleModalOpen.value = true;
    return;
  }
  // 400 字段级错误回填（40001/40002/40004/40005/40007）
  const fieldErrors = (err.data as { field_errors?: FieldError[] } | undefined)?.field_errors;
  if (fieldErrors?.length && formRef.value) {
    for (const fe of fieldErrors) {
      formRef.value.setFieldError(fe.field, fe.message);
    }
    message.error('表单校验未通过，请检查标红字段');
    return;
  }
  message.error(toUserMessage(err));
}

function cancel(): void {
  void router.push(`/biz/${objType.value}/${code.value}`);
}
</script>

<template>
  <a-card :loading="loading && !schema">
    <template v-if="schema">
      <a-breadcrumb class="mb-3">
        <a-breadcrumb-item>
          <a @click.prevent="cancel">{{ schemaName(schema) }}</a>
        </a-breadcrumb-item>
        <a-breadcrumb-item>{{ isEdit ? '编辑' : '新增' }}</a-breadcrumb-item>
      </a-breadcrumb>

      <a-alert
        v-if="!canWrite"
        type="warning"
        show-icon
        message="当前角色无数据写权限，仅可查看"
        class="mb-4"
      />
      <a-alert
        v-else-if="stateLocked"
        type="warning"
        show-icon
        message="当前状态不可修改"
        class="mb-4"
      />

      <DynamicForm
        ref="formRef"
        v-model="model"
        :attributes="schema.attributes"
        :mode="formMode"
      />

      <div
        v-if="!stateLocked && canWrite"
        class="mt-4 border-t border-gray-100 pt-4"
      >
        <a-button
          class="mr-2"
          @click="cancel"
        >
          取消
        </a-button>
        <a-button
          type="primary"
          :loading="saving"
          @click="save"
        >
          保存
        </a-button>
      </div>

      <RuleFailedModal
        v-model:open="ruleModalOpen"
        :failed-rules="failedRules"
      />
    </template>
  </a-card>
</template>
