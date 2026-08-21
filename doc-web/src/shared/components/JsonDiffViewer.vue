<script setup lang="ts">
// JsonDiffViewer：审计快照左右双栏 JSON Diff（P-OP-01）
// before/after 为 JSON 字符串快照，解析后按扁平化 key 路径对比：
// 新增=绿 / 删除=红 / 变更=黄；格式化缩进展示。解析失败降级为原文只读展示
import { computed } from 'vue';

const props = defineProps<{
  before?: string;
  after?: string;
}>();

type DiffKind = 'same' | 'added' | 'removed' | 'changed';

interface DiffRow {
  path: string;
  beforeText: string;
  afterText: string;
  kind: DiffKind;
}

function safeParse(raw?: string): { ok: boolean; value: unknown } {
  if (raw === undefined || raw === null || raw === '') return { ok: true, value: undefined };
  try {
    return { ok: true, value: JSON.parse(raw) as unknown };
  } catch {
    return { ok: false, value: raw };
  }
}

/** 扁平化：对象/数组 → 「path → 叶子值字符串」；叶子值保持 JSON 序列化形态 */
function flatten(value: unknown, prefix = '', out = new Map<string, string>()): Map<string, string> {
  if (value === null || value === undefined || typeof value !== 'object') {
    out.set(prefix || '(root)', JSON.stringify(value ?? null));
    return out;
  }
  const entries: [string, unknown][] = Array.isArray(value)
    ? (value as unknown[]).map((v, i) => [String(i), v])
    : Object.entries(value as Record<string, unknown>);
  if (entries.length === 0) {
    out.set(prefix || '(root)', JSON.stringify(value));
    return out;
  }
  for (const [k, v] of entries) {
    const path = prefix ? `${prefix}.${k}` : k;
    flatten(v, path, out);
  }
  return out;
}

const parsed = computed(() => ({
  before: safeParse(props.before),
  after: safeParse(props.after),
}));

/** 解析失败降级：原文展示（左侧 before / 右侧 after） */
const fallback = computed(() => !parsed.value.before.ok || !parsed.value.after.ok);

const rows = computed<DiffRow[]>(() => {
  if (fallback.value) return [];
  const bMap = flatten(parsed.value.before.value);
  const aMap = flatten(parsed.value.after.value);
  const paths = [...new Set([...bMap.keys(), ...aMap.keys()])];
  return paths.map((path) => {
    const hasB = bMap.has(path);
    const hasA = aMap.has(path);
    const beforeText = bMap.get(path) ?? '';
    const afterText = aMap.get(path) ?? '';
    let kind: DiffKind = 'same';
    if (hasB && !hasA) kind = 'removed';
    else if (!hasB && hasA) kind = 'added';
    else if (beforeText !== afterText) kind = 'changed';
    return { path, beforeText, afterText, kind };
  });
});

const kindClass: Record<DiffKind, string> = {
  same: '',
  added: 'diff-added',
  removed: 'diff-removed',
  changed: 'diff-changed',
};

/** 格式化原文（解析失败时展示原始字符串） */
const beforeRaw = computed(() => formatRaw(props.before));
const afterRaw = computed(() => formatRaw(props.after));

function formatRaw(raw?: string): string {
  if (!raw) return '（空）';
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}
</script>

<template>
  <div class="json-diff">
    <!-- 降级：JSON 解析失败，原文双栏展示 -->
    <div
      v-if="fallback"
      class="grid grid-cols-2 gap-3"
    >
      <pre class="diff-raw">{{ beforeRaw }}</pre>
      <pre class="diff-raw">{{ afterRaw }}</pre>
    </div>

    <!-- 正常：扁平化逐路径 Diff -->
    <table
      v-else
      class="diff-table"
    >
      <thead>
        <tr>
          <th class="w-48">
            字段路径
          </th>
          <th>变更前</th>
          <th>变更后</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="row in rows"
          :key="row.path"
        >
          <td class="diff-path">
            {{ row.path }}
          </td>
          <td :class="kindClass[row.kind]">
            {{ row.kind === 'added' ? '—' : row.beforeText }}
          </td>
          <td :class="kindClass[row.kind]">
            {{ row.kind === 'removed' ? '—' : row.afterText }}
          </td>
        </tr>
        <tr v-if="rows.length === 0">
          <td
            colspan="3"
            class="text-center text-gray-400"
          >
            无快照数据
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.json-diff {
  font-size: 12px;
}
.diff-table {
  width: 100%;
  border-collapse: collapse;
}
.diff-table th,
.diff-table td {
  border: 1px solid #f0f0f0;
  padding: 4px 8px;
  text-align: left;
  vertical-align: top;
  word-break: break-all;
}
.diff-table th {
  background: #fafafa;
  font-weight: 600;
}
.diff-path {
  color: #555;
  font-family: monospace;
}
/* 新增=绿 / 删除=红 / 变更=黄（prd/10 P-OP-01） */
.diff-added {
  background: #f6ffed;
  color: #389e0d;
}
.diff-removed {
  background: #fff1f0;
  color: #cf1322;
}
.diff-changed {
  background: #fffbe6;
  color: #d48806;
}
.diff-raw {
  max-height: 320px;
  overflow: auto;
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 4px;
  padding: 8px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
