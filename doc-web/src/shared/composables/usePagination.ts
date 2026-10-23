// usePagination：从首页开始的 token 连续翻页 + 已访问页游标缓存（prd/11 §一「深分页」）
// 全程只使用 token 模式：首页显式不带 offset，避免 offset 与 token 混用；
// 任意跳页若缺少目标页游标则钳制到可达页，由调用方提示用户逐页续翻。
import { computed, ref } from 'vue';
import type { Ref } from 'vue';
import type { PageResult } from '@/shared/types/api';

export interface PagingParam {
  /** token 模式续翻游标；首页不传（后端按 token 模式首页处理） */
  token?: string;
  limit: number;
}

/** 已访问页游标快照：用于返回详情后恢复同一查询的页链 */
export interface PaginationCursorState {
  pageSize: number;
  page: number;
  tokens: [number, string][];
}

export interface PageChangeResult {
  requested: number;
  page: number;
  clamped: boolean;
  reason?: 'no-cursor' | 'last-page';
}

export function usePagination<T>(
  query: (paging: PagingParam) => Promise<PageResult<T>>,
  options: { pageSize?: number } = {},
) {
  const page = ref(1);
  const pageSize = ref(options.pageSize ?? 20);
  const total = ref(0);
  const items = ref<T[]>([]) as Ref<T[]>;
  const loading = ref(false);
  /** 当前已加载页是否已到末页（最近一次响无 next_token） */
  const reachedEnd = ref(false);

  /**
   * 页边界令牌：tokens.get(n) = 第 n 页响应的 next_token（请求第 n+1 页所需）。
   * 只有连续的 1..n 游标存在时第 n+1 页才可达；仅缓存页码与短 token，响应式为轻量 Map。
   */
  const tokens = ref(new Map<number, string>());
  let requestSeq = 0;
  let lastLoadedPage = 0;

  /** 可达的最大页码：第 1 页恒可达，之后需前一页游标连续存在 */
  const maxReachablePage = computed(() => {
    let reachable = 1;
    while (tokens.value.has(reachable)) reachable += 1;
    return reachable;
  });

  /** 当前页之后是否还有下一页游标 */
  const hasNext = computed(() => tokens.value.has(page.value));

  /** 清理所有游标但保留 pageSize；不触发请求 */
  function clearCursors(): void {
    requestSeq += 1;
    tokens.value.clear();
    reachedEnd.value = false;
    lastLoadedPage = 0;
  }

  /** 加载当前页：首页不带 token；深页必须拿到上一页游标，否则不请求 */
  async function load(): Promise<PageResult<T> | null> {
    const target = page.value;
    const token = target === 1 ? undefined : tokens.value.get(target - 1);
    if (target > 1 && !token) {
      // 缺少游标绝不回退 offset/首页，交由 goToPage 钳制并提示
      page.value = maxReachablePage.value;
      return null;
    }

    const seq = ++requestSeq;
    loading.value = true;
    try {
      const res = await query({ token, limit: pageSize.value });
      if (seq !== requestSeq) return res;
      items.value = res.items;
      total.value = res.total;
      lastLoadedPage = target;
      if (res.next_token) {
        tokens.value.set(target, res.next_token);
        reachedEnd.value = false;
      } else {
        // 末页：清理本页及更深的过期游标，防止再以旧游标越过终点
        for (const key of [...tokens.value.keys()]) {
          if (key >= target) tokens.value.delete(key);
        }
        reachedEnd.value = true;
      }
      return res;
    } finally {
      if (seq === requestSeq) loading.value = false;
    }
  }

  /**
   * 跳页：缺少目标页游标时钳制到当前可达页。
   * - 已到末页继续下一页 → reason=last-page
   * - 任意未取得游标的深页 → reason=no-cursor
   */
  async function goToPage(next: number): Promise<PageChangeResult> {
    const requested = Math.max(1, Math.trunc(next) || 1);
    const max = maxReachablePage.value;
    const clamped = requested > max;
    const target = clamped ? max : requested;
    const reason: PageChangeResult['reason'] = clamped
      ? reachedEnd.value && lastLoadedPage >= max
        ? 'last-page'
        : 'no-cursor'
      : undefined;

    if (target !== page.value || target !== lastLoadedPage) {
      const previous = page.value;
      page.value = target;
      try {
        await load();
      } catch (err) {
        // 请求失败回到原页，避免页码与旧数据不一致（错误提示由请求层统一给出）
        page.value = previous;
        throw err;
      }
    }
    return { requested, page: target, clamped, reason };
  }

  /** 页长变化：游标全部失效，从首页重查 */
  async function changePageSize(size: number): Promise<void> {
    if (!Number.isFinite(size) || size < 1 || size === pageSize.value) return;
    pageSize.value = size;
    reset();
    await load();
  }

  /** 筛选/排序变化：清空游标与旧数据，从首页重查前由调用方 load */
  function reset(): void {
    page.value = 1;
    total.value = 0;
    items.value = [];
    clearCursors();
    loading.value = false;
  }

  /** 导出当前游标链（随列表态缓存保存，详情返回后可恢复） */
  function exportCursors(): PaginationCursorState {
    return {
      pageSize: pageSize.value,
      page: page.value,
      tokens: [...tokens.value.entries()],
    };
  }

  /**
   * 恢复游标链：页长不一致或目标页不可达时回到首页。
   * 返回 true 表示原页码可恢复。
   */
  function restoreCursors(state: PaginationCursorState): boolean {
    if (state.pageSize !== pageSize.value) return false;
    clearCursors();
    for (const [key, token] of state.tokens) {
      if (token) tokens.value.set(key, token);
    }
    const reachable = maxReachablePage.value;
    if (state.page >= 1 && state.page <= reachable) {
      page.value = state.page;
      return true;
    }
    page.value = 1;
    return false;
  }

  return {
    page,
    pageSize,
    total,
    items,
    loading,
    reachedEnd,
    maxReachablePage,
    hasNext,
    load,
    goToPage,
    changePageSize,
    reset,
    exportCursors,
    restoreCursors,
  };
}
