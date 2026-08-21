// 统一查询接口（prd/11 §2.3 RD-10；POST /query）
import { post } from '@/shared/api/request';
import type { PageResult } from '@/shared/types/api';
import type { DataRecord, QueryDsl } from '../types';

export function queryData(dsl: QueryDsl): Promise<PageResult<DataRecord>> {
  return post('/query', dsl);
}
