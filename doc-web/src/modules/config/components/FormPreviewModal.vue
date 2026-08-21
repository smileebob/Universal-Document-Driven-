<script setup lang="ts">
// FormPreviewModal：表单预览（P-EC-03）
// 复用 DynamicForm preview 模式：控件可交互但不提交（prd/06 §十复用要求）
import { ref, watch } from 'vue';
import type { AttributeDef } from '@/modules/config/types';
import DynamicForm from '@/shared/components/DynamicForm.vue';
import { buildDefaultModel } from '@/shared/utils/form';

const props = withDefaults(
  defineProps<{
    open: boolean;
    title?: string;
    attributes: AttributeDef[];
  }>(),
  { title: '表单预览' },
);

const emit = defineEmits<{ (e: 'update:open', value: boolean): void }>();

const model = ref<Record<string, unknown>>({});

watch(
  () => props.open,
  (open) => {
    if (open) {
      // 预览以默认值初始化，直观检查控件类型/必填标记/可见性
      model.value = buildDefaultModel(props.attributes);
    }
  },
);

function close(): void {
  emit('update:open', false);
}
</script>

<template>
  <a-modal
    :open="open"
    :title="title"
    width="70%"
    :footer="null"
    @cancel="close"
  >
    <DynamicForm
      :attributes="attributes"
      :model-value="model"
      mode="preview"
    />
    <div class="mt-4 text-right">
      <a-button @click="close">
        关闭
      </a-button>
    </div>
  </a-modal>
</template>
