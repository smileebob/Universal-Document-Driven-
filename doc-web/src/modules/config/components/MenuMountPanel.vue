<script setup lang="ts">
// MenuMountPanel：菜单挂载配置区（EC-05，prd/07 P-EC-02）
// group_code 为平台预置固定值（prd/12 §1.11）
import type { MenuGroupCode, MenuMountConfig } from '@/modules/config/types';
import { MENU_GROUP_LABELS } from '@/modules/config/types';

const props = withDefaults(
  defineProps<{
    modelValue: MenuMountConfig;
    /** 定义名称：菜单名留空时保存将使用该实际值 */
    defaultName?: string;
    /** 挂载配置未加载成功时禁用编辑，避免以默认值覆盖远端配置 */
    disabled?: boolean;
  }>(),
  { defaultName: '', disabled: false },
);

const emit = defineEmits<{ (e: 'update:modelValue', value: MenuMountConfig): void }>();

const GROUP_OPTIONS = [
  ...(Object.keys(MENU_GROUP_LABELS) as MenuGroupCode[]).map((code) => ({
    value: code,
    label: MENU_GROUP_LABELS[code],
  })),
  { value: '', label: '顶层菜单（不分组）' },
];

function update(patch: Partial<MenuMountConfig>): void {
  emit('update:modelValue', { ...props.modelValue, ...patch });
}
</script>

<template>
  <div class="flex flex-wrap items-center gap-6">
    <label class="flex items-center gap-2">
      <span class="text-sm">挂载到业务菜单</span>
      <a-switch
        :checked="modelValue.enabled"
        :disabled="disabled"
        @update:checked="update({ enabled: $event })"
      />
    </label>
    <template v-if="modelValue.enabled">
      <label class="flex items-center gap-2">
        <span class="text-sm">菜单显示名</span>
        <a-input
          :value="modelValue.menu_name"
          :placeholder="defaultName ? `留空则使用「${defaultName}」` : '留空则使用定义名称'"
          :disabled="disabled"
          class="w-45"
          allow-clear
          @update:value="update({ menu_name: $event })"
        />
      </label>
      <label class="flex items-center gap-2">
        <span class="text-sm">分组</span>
        <a-select
          :value="modelValue.group_code ?? ''"
          :options="GROUP_OPTIONS"
          :disabled="disabled"
          class="w-40"
          @update:value="update({ group_code: $event })"
        />
      </label>
      <label class="flex items-center gap-2">
        <span class="text-sm">排序</span>
        <a-input-number
          :value="modelValue.sort_order"
          :min="0"
          :disabled="disabled"
          class="w-24"
          @update:value="update({ sort_order: $event ?? 0 })"
        />
      </label>
    </template>
  </div>
</template>
