// useListStateStore：列表页筛选/分页/排序/滚动位置缓存（design/10 §6.1，prd/09 P-RT-05）
// 详情页返回列表时恢复先前视图状态；key 维度为 `${objType}:${code}`
// 内存级缓存（刷新页面即失效），故筛选值可原样持有 Dayjs 实例，无需序列化
import { defineStore } from 'pinia';
import type { Dayjs } from 'dayjs';

export interface ListStateSnapshot {
  filterValues: Record<string, unknown>;
  statusFilter: number | null;
  creatorFilter: string;
  createTimeRange: [Dayjs, Dayjs] | null;
  sort: { field: string; order: 'asc' | 'desc' };
  page: number;
  pageSize: number;
  scrollTop: number;
}

export const useListStateStore = defineStore('runtime-list-state', () => {
  const states = new Map<string, ListStateSnapshot>();

  function stateKey(objType: string, code: string): string {
    return `${objType}:${code}`;
  }

  function save(objType: string, code: string, snapshot: ListStateSnapshot): void {
    states.set(stateKey(objType, code), snapshot);
  }

  function get(objType: string, code: string): ListStateSnapshot | undefined {
    return states.get(stateKey(objType, code));
  }

  function clear(objType: string, code: string): void {
    states.delete(stateKey(objType, code));
  }

  return { save, get, clear };
});
