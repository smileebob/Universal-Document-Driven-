// 实体/单据定义（Schema）与菜单挂载接口（prd/11 §2.1；域 api 只写接口函数，N-05）
import type { AxiosRequestConfig } from 'axios';
import { del, get, post, put } from '@/shared/api/request';
import type { PageResult } from '@/shared/types/api';
import type {
  AttributeDef,
  DocSchema,
  EntitySchema,
  EntityType,
  MenuMountConfig,
  MountedMenuResult,
  ObjType,
  SchemaStatus,
  StatusDef,
} from '../types';

export interface EntityListParams {
  keyword?: string;
  entity_type?: EntityType | '';
  status?: SchemaStatus | '';
  offset: number;
  limit: number;
}

export interface EntitySavePayload {
  entity_name: string;
  entity_code: string;
  entity_type: EntityType;
  description?: string;
  attributes: AttributeDef[];
  menu?: MenuMountConfig;
}

/** 写操作响应（后端口径：code/status/version） */
export interface SchemaWriteResult {
  doc_code?: string;
  entity_code?: string;
  status: number;
  version: number;
}

// ---------- 实体定义（EC-01~05） ----------
export function listEntities(params: EntityListParams): Promise<PageResult<EntitySchema>> {
  return get('/schema/entity', params as unknown as Record<string, unknown>);
}

export function getEntity(code: string): Promise<EntitySchema> {
  return get(`/schema/entity/${code}`);
}

export function createEntity(payload: EntitySavePayload): Promise<EntitySchema> {
  return post('/schema/entity', payload);
}

export function updateEntity(code: string, payload: EntitySavePayload): Promise<EntitySchema> {
  return put(`/schema/entity/${code}`, payload);
}

/** 发布（失败时 40011 携带原因清单；页面侧以 silent 接管展示，prd/07 P-EC-01） */
export function publishEntity(
  code: string,
  config?: AxiosRequestConfig,
): Promise<EntitySchema> {
  return post(`/schema/entity/${code}/publish`, undefined, config);
}

export function disableEntity(
  code: string,
  config?: AxiosRequestConfig,
): Promise<EntitySchema> {
  return post(`/schema/entity/${code}/disable`, undefined, config);
}

export function deleteEntity(code: string): Promise<void> {
  return del(`/schema/entity/${code}`);
}

// ---------- 单据定义（DC-01~05，后端 DocSchemaController） ----------

export interface DocListParams {
  keyword?: string;
  status?: SchemaStatus | '';
  offset: number;
  limit: number;
}

/** 创建 payload（doc_code 仅创建时携带；菜单挂载走独立接口） */
export interface DocCreatePayload {
  doc_code: string;
  doc_name: string;
  doc_type: string;
  description?: string;
  attributes: AttributeDef[];
  status_defs: StatusDef[];
}

/** 修改 payload（后端 DocSchemaUpdateRequest：无 doc_code） */
export type DocUpdatePayload = Omit<DocCreatePayload, 'doc_code'>;

export function listDocs(params: DocListParams): Promise<PageResult<DocSchema>> {
  return get('/schema/doc', params as unknown as Record<string, unknown>);
}

export function getDoc(code: string): Promise<DocSchema> {
  return get(`/schema/doc/${code}`);
}

export function createDoc(payload: DocCreatePayload): Promise<SchemaWriteResult> {
  return post('/schema/doc', payload);
}

export function updateDoc(code: string, payload: DocUpdatePayload): Promise<SchemaWriteResult> {
  return put(`/schema/doc/${code}`, payload);
}

/** 发布（校验失败 40011+reasons；页面 silent 接管，prd/07 P-DC-01） */
export function publishDoc(code: string, config?: AxiosRequestConfig): Promise<SchemaWriteResult> {
  return post(`/schema/doc/${code}/publish`, undefined, config);
}

export function disableDoc(code: string, config?: AxiosRequestConfig): Promise<SchemaWriteResult> {
  return post(`/schema/doc/${code}/disable`, undefined, config);
}

/** 删除（仅草稿态，40009） */
export function deleteDoc(code: string): Promise<void> {
  return del(`/schema/doc/${code}`);
}

// ---------- 菜单挂载（EC-05） ----------
export function putMenuMount(
  objType: ObjType,
  code: string,
  config: MenuMountConfig,
): Promise<void> {
  return put(`/schema/${objType}/${code}/menu`, config);
}

/** 拉取已挂载菜单（动态菜单 + 全局 schema_version 探针，prd/11 §一） */
export function fetchMountedMenus(): Promise<MountedMenuResult> {
  return get('/menu/mounted');
}
