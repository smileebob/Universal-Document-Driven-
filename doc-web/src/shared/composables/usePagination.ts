// usePagination：offset 分页 + next_token 深分页续翻（prd/11 §一「深分页」）
// offset 模式上限 offset+limit ≤ 2000；超阈值改走 token 模式（透传上一页 next_token）
import { ref } from 'vue';
import type { PageResult } from '@/shared/types/api';

const DEEP_LIMIT = 2000;

export interface PagingParam {
  /** offset 模式（与 token 互斥） */
  offset?: number;
  /** token 模式：透传上一页响应的 next_token */
  token?: string;
  limit: number;
}

export function usePagination<T>(query: (paging: PagingParam) => Promise<PageResult<T>>) {
  const page = ref(1);
  const pageSize = ref(20);
  const total = ref(0);
  const items = ref<T[]>([]) as { value: T[] };
  const loading = ref(false);
  /** 页边界令牌：第 n 页响应返回的 next_token（供第 n+1 页 token 模式续翻） */
  const tokens = new Map<number, string>();
  /** 最近一次响应的 next_token（供「下一页」续翻） */
  let lastResult: PageResult<T> | null = null;

  async function load(): Promise<PageResult<T> | null> {
    loading.value = true;
    try {
      const offset = (page.value - 1) * pageSize.value;
      const deep = offset + pageSize.value > DEEP_LIMIT;
      const res = deep
        ? await query({ token: tokens.get(page.value - 1), limit: pageSize.value })
        : await query({ offset, limit: pageSize.value });
      items.value = res.items;
      total.value = res.total;
      lastResult = res;
      if (res.next_token) {
        tokens.set(page.value, res.next_token);
      }
      return res;
    } finally {
      loading.value = false;
    }
  }

  function changePage(next: number): void {
    page.value = next;
    void load();
  }

  function changePageSize(size: number): void {
    pageSize.value = size;
    page.value = 1;
    tokens.clear();
    void load();
  }

  /** 重置到第 1 页并清空令牌（筛选/排序变化时调用） */
  function reset(): void {
    page.value = 1;
    tokens.clear();
    lastResult = null;
  }

  /** 当前页 next_token（为空表示末页） */
  function nextToken(): string | undefined {
    return lastResult?.next_token;
  }

  return { page, pageSize, total, items, loading, load, changePage, changePageSize, reset, nextToken };
}
