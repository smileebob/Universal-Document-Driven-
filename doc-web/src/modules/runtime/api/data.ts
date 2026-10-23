// 运行时数据接口（prd/11 §2.3：实体 /data/{code}；阶段二单据 /data/doc/{code}）
import type { AxiosRequestConfig } from 'axios';
import { del, get, post, put } from '@/shared/api/request';
import type { ObjType } from '@/modules/config/types';
import type { DataRecord, SaveDataPayload, StatusHistoryItem, TransitionResult } from '../types';

/** 数据基础路径：entity → /data/{code}；doc → /data/doc/{code}（后端 DocDataController） */
function dataPath(objType: ObjType, code: string): string {
  return objType === 'doc' ? `/data/doc/${code}` : `/data/${code}`;
}

/** 新增数据（RD-01/05；幂等头由 request.ts 自动生成） */
export function createData(objType: ObjType, code: string, payload: SaveDataPayload): Promise<DataRecord> {
  return post(dataPath(objType, code), payload, { silent: true });
}

/** 查询详情（RD-04/09；写后立即读走本点查接口而非列表，保证一致性） */
export function getData(objType: ObjType, code: string, uuid: string): Promise<DataRecord> {
  return get(`${dataPath(objType, code)}/${uuid}`);
}

/** 修改数据（RD-02/06；PUT 必带 version 乐观锁） */
export function updateData(
  objType: ObjType,
  code: string,
  uuid: string,
  payload: SaveDataPayload,
): Promise<DataRecord> {
  return put(`${dataPath(objType, code)}/${uuid}`, payload, { silent: true });
}

/** 删除数据（RD-03/07） */
export function deleteData(objType: ObjType, code: string, uuid: string): Promise<void> {
  return del(`${dataPath(objType, code)}/${uuid}`);
}

/** 状态历史（RD-08；查询失败由页面展示错误与重试，不推断“接口未开放”） */
export function getStatusHistory(
  objType: ObjType,
  code: string,
  uuid: string,
  config?: AxiosRequestConfig,
): Promise<StatusHistoryItem[]> {
  return get(`${dataPath(objType, code)}/${uuid}/history`, undefined, config);
}

/**
 * 单据状态流转（八步链路入口，POST /data/doc/{code}/{uuid}/transition）。
 * silent：42201 规则失败/42901 锁忙由页面精细处理（design/10 §7.3）；
 * 幂等键由调用方显式生成（{uuid}:transition:{token}），重放回放首次结果。
 */
export function transitionDocData(
  code: string,
  uuid: string,
  targetStatus: number,
  options?: { event?: string; idempotencyKey?: string },
): Promise<TransitionResult> {
  return post(
    `/data/doc/${code}/${uuid}/transition`,
    { target_status: targetStatus, event: options?.event ?? 'transition' },
    {
      silent: true,
      headers: options?.idempotencyKey ? { 'X-Idempotency-Key': options.idempotencyKey } : undefined,
    },
  );
}
