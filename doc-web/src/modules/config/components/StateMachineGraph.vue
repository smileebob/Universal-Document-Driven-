<script setup lang="ts">
// StateMachineGraph：状态机可视化（@antv/g6 v5，dagre 有向无环布局）
// 仅被 StateMachineConfig 路由级懒加载页引用 → vendor-g6 分包按需拉取
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Graph } from '@antv/g6';
import type { GraphData } from '@antv/g6';
import type { StateMachineTransition, StatusDef } from '@/modules/config/types';

const props = defineProps<{
  states: StatusDef[];
  transitions: StateMachineTransition[];
}>();

const containerRef = ref<HTMLDivElement | null>(null);
let graph: Graph | null = null;

/** 节点样式：终态红框、可编辑态浅蓝底、其余灰底 */
function nodeStyle(def: StatusDef): Record<string, unknown> {
  if (def.terminal) {
    return { fill: '#fff1f0', stroke: '#cf1322', labelFill: '#cf1322' };
  }
  if (def.editable) {
    return { fill: '#e6f4ff', stroke: '#1677ff', labelFill: '#1f1f1f' };
  }
  return { fill: '#fafafa', stroke: '#8c8c8c', labelFill: '#1f1f1f' };
}

function buildData(): GraphData {
  const nodes = props.states.map((s) => ({
    id: String(s.status),
    data: { label: `${s.name || s.status}(${s.status})` },
    style: { ...nodeStyle(s), labelText: `${s.name || s.status}(${s.status})`, labelFontSize: 12 },
  }));
  const ids = new Set(props.states.map((s) => s.status));
  const edges: { id: string; source: string; target: string }[] = [];
  for (const t of props.transitions) {
    if (!ids.has(t.start)) continue;
    for (const end of t.end ?? []) {
      if (!ids.has(end)) continue;
      edges.push({ id: `${t.start}->${end}`, source: String(t.start), target: String(end) });
    }
  }
  return { nodes, edges };
}

async function render(): Promise<void> {
  if (!containerRef.value) return;
  // 数据变化重建实例（配置页编辑频繁，重建成本可接受且最稳）
  if (graph) {
    graph.destroy();
    graph = null;
  }
  graph = new Graph({
    container: containerRef.value,
    width: containerRef.value.clientWidth || 800,
    height: 300,
    autoFit: 'view',
    padding: 24,
    data: buildData(),
    layout: {
      type: 'dagre',
      rankdir: 'LR',
      nodesep: 28,
      ranksep: 56,
    },
    node: {
      style: {
        size: [120, 36],
        radius: 6,
        labelText: (d) => (d.data?.label as string | undefined) ?? '',
        labelPlacement: 'center',
      },
    },
    edge: {
      type: 'polyline',
      style: {
        stroke: '#bfbfbf',
        endArrow: true,
        router: { type: 'orth' },
      },
    },
    behaviors: ['drag-canvas', 'zoom-canvas'],
    animation: false,
  });
  await graph.render();
}

onMounted(() => void render());
watch(
  () => [props.states, props.transitions],
  () => void render(),
  { deep: true },
);
onBeforeUnmount(() => {
  graph?.destroy();
  graph = null;
});
</script>

<template>
  <div>
    <!-- 图例：与节点配色一致 -->
    <div class="mb-2 flex items-center gap-4 text-xs text-gray-500">
      <span class="flex items-center gap-1">
        <span class="inline-block h-3 w-3 rounded border border-blue-500 bg-blue-50" /> 可编辑态
      </span>
      <span class="flex items-center gap-1">
        <span class="inline-block h-3 w-3 rounded border border-red-600 bg-red-50" /> 终态
      </span>
      <span class="flex items-center gap-1">
        <span class="inline-block h-3 w-3 rounded border border-gray-400 bg-gray-50" /> 普通态
      </span>
    </div>
    <div
      ref="containerRef"
      class="w-full rounded border border-gray-100 bg-white"
      style="height: 300px"
    />
  </div>
</template>
