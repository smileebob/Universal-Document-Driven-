<script setup lang="ts">
// P-RE-02 规则编辑器（prd/08）：expression 编辑 + 在线测试面板
// 创建响应字段 rule_uuid（非 doc_code），保存后跳转编辑页以启用测试
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { message } from 'ant-design-vue';
import { createRule, getRule, updateRule } from '@/modules/config/api/rule';
import type { RuleSavePayload } from '@/modules/config/api/rule';
import RuleTestPanel from '@/modules/config/components/RuleTestPanel.vue';
import { ApiError } from '@/shared/types/api';
import { toUserMessage } from '@/shared/utils/errorMessages';

const route = useRoute();
const router = useRouter();

const uuid = computed(() => (route.params.uuid as string) || '');
const isEdit = computed(() => !!uuid.value);

const form = reactive<RuleSavePayload>({
  rule_name: '',
  rule_type: 'expression',
  expression: '',
  interface_code: '',
  enabled: true,
  description: '',
});
const version = ref<number | null>(null);
const saving = ref(false);

onMounted(async () => {
  if (!isEdit.value) return;
  try {
    const rule = await getRule(uuid.value);
    form.rule_name = rule.rule_name;
    form.rule_type = rule.rule_type;
    form.expression = rule.expression ?? '';
    form.interface_code = rule.interface_code ?? '';
    form.enabled = rule.enabled;
    form.description = rule.description ?? '';
    version.value = rule.version;
  } catch {
    message.error('规则不存在或已被删除');
    void router.replace('/config/rule');
  }
});

function validate(): string {
  if (!form.rule_name.trim() || form.rule_name.length > 50) return '规则名称必填，1~50 字';
  if (form.rule_type === 'expression' && !form.expression?.trim()) return '表达式规则必须填写表达式';
  if (form.rule_type === 'interface' && !form.interface_code?.trim()) return '接口规则必须填写接口编码';
  if ((form.description ?? '').length > 200) return '描述不超过 200 字';
  return '';
}

async function save(): Promise<void> {
  const errText = validate();
  if (errText) {
    message.error(errText);
    return;
  }
  saving.value = true;
  try {
    if (isEdit.value) {
      const res = await updateRule(uuid.value, { ...form });
      version.value = res.version;
      message.success('保存成功');
    } else {
      // 创建响应携带 rule_uuid，跳转编辑页启用在线测试
      const res = await createRule({ ...form });
      message.success('创建成功');
      void router.replace(`/config/rule/edit/${res.rule_uuid}`);
    }
  } catch (err) {
    if (err instanceof ApiError) message.error(toUserMessage(err));
  } finally {
    saving.value = false;
  }
}

function goBack(): void {
  void router.push('/config/rule');
}
</script>

<template>
  <a-card :title="isEdit ? `编辑规则：${uuid}` : '新建规则'">
    <a-row :gutter="24">
      <a-col :span="14">
        <a-form layout="vertical">
          <a-form-item
            label="规则名称"
            required
          >
            <a-input
              v-model:value="form.rule_name"
              placeholder="如：金额大于0"
            />
          </a-form-item>
          <a-form-item
            label="规则类型"
            required
          >
            <a-select
              v-model:value="form.rule_type"
              :options="[
                { value: 'expression', label: '表达式 expression' },
                { value: 'interface', label: '接口 interface' },
              ]"
            />
          </a-form-item>
          <a-form-item
            v-if="form.rule_type === 'expression'"
            label="表达式"
            required
          >
            <a-textarea
              v-model:value="form.expression"
              :rows="4"
              placeholder="如：amount > 0"
            />
          </a-form-item>
          <a-form-item
            v-else
            label="接口编码"
            required
          >
            <a-input
              v-model:value="form.interface_code"
              placeholder="外部校验接口编码"
            />
          </a-form-item>
          <a-form-item label="启用">
            <a-switch v-model:checked="form.enabled" />
          </a-form-item>
          <a-form-item label="描述">
            <a-input
              v-model:value="form.description"
              placeholder="≤200 字"
            />
          </a-form-item>

          <div class="border-t border-gray-100 pt-4">
            <a-tag
              v-if="version !== null"
              class="mr-2"
            >
              v{{ version }}
            </a-tag>
            <a-button
              class="mr-2"
              @click="goBack"
            >
              返回
            </a-button>
            <a-button
              type="primary"
              :loading="saving"
              @click="save"
            >
              保存
            </a-button>
          </div>
        </a-form>
      </a-col>

      <!-- 在线测试面板（与运行时同一执行器） -->
      <a-col :span="10">
        <RuleTestPanel :rule-uuid="uuid" />
      </a-col>
    </a-row>
  </a-card>
</template>
