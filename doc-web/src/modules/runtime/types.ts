// 运行时数据类型与统一查询 DSL（prd/11 §3.4、prd/05 RD-10）

/** 查询操作符（prd/12 §1.8；nested_eq/nested_in 为关联反查字段 relations[].* 专属，阶段四 RD-11） */
export type QueryOp =
  | 'eq' | 'in' | 'gte' | 'lte' | 'gt' | 'lt' | 'contains' | 'prefix' | 'exists'
  | 'nested_eq' | 'nested_in';

export interface QueryFilter {
  field: string;
  op: QueryOp;
  value?: unknown;
}

export interface QuerySort {
  field: string;
  order: 'asc' | 'desc';
}

/** 分页：offset 模式与 token 模式互斥（prd/11 §一「深分页」） */
export interface QueryPage {
  offset?: number;
  limit: number;
  token?: string;
}

export interface QueryAggregation {
  name: string;
  type: 'count' | 'sum' | 'avg' | 'group_by';
  field: string;
}

export interface QueryDsl {
  target: 'entity_data' | 'doc_data';
  filters: QueryFilter[];
  /** 排序条件（后端 QueryRequest 契约字段名 sorts，复数） */
  sorts?: QuerySort[];
  page: QueryPage;
  aggregations?: QueryAggregation[];
}

export interface RelationItem {
  type: string;
  code: string;
  uuid: string;
}

/**
 * 单据状态变更历史条目（后端 GET /data/doc/{docCode}/{uuid}/history 契约，snake_case）：
 * seq / from_status / to_status / operation / operator / operate_time /
 * version_after / from_status_name / to_status_name
 */
export interface StatusHistoryItem {
  /** 历史序号 */
  seq: number;
  from_status?: number;
  to_status: number;
  /** 流转操作名（如 提交/审批） */
  operation?: string;
  operator: string;
  /** 操作时间（毫秒时间戳） */
  operate_time: number;
  /** 流转后记录版本 */
  version_after?: number;
  /** 后端附带的状态名（优先于前端 status_defs 兜底） */
  from_status_name?: string;
  to_status_name?: string;
}

/** 运行时数据记录（实体/单据通用） */
export interface DataRecord {
  uuid: string;
  attributes: Record<string, unknown>;
  /** 记录版本号（乐观锁；编辑保存必带，后端校验不一致返回 40002） */
  version?: number;
  /** 单据当前状态 */
  current_status?: number;
  /** 单据当前状态名（后端详情响应附带） */
  status_name?: string;
  /** 实体数据有效性状态（1 有效 / 0 逻辑删除） */
  status?: number;
  creator?: string;
  /** 单据创建部门（后端详情响应附带） */
  create_dept?: string;
  create_time?: number;
  update_time?: number;
  relations?: RelationItem[];
  status_history?: StatusHistoryItem[];
}

export interface SaveDataPayload {
  attributes: Record<string, unknown>;
  relations?: RelationItem[];
  /** 记录版本号：编辑（PUT）必传（乐观锁），新增（POST）不传 */
  version?: number;
}

/** 400 字段校验错误明细（data.field_errors，字段级回填用） */
export interface FieldError {
  field: string;
  code?: number;
  message: string;
}

/** 422 规则失败明细（prd/11 §3.2 data.failed_rules；后端含 rule_uuid） */
export interface FailedRule {
  rule_uuid?: string;
  rule_name: string;
  reason?: string;
}

/** 状态流转成功响应（后端 TransitionService 契约） */
export interface TransitionResult {
  uuid: string;
  doc_code: string;
  from_status: number;
  to_status: number;
  version: number;
  history_seq?: number;
  saga_triggered?: boolean;
  /** 触发 Saga 时返回的实例 ID（阶段三 TX-02） */
  saga_id?: string;
}

/** 42201 规则失败时的 data 结构 */
export interface TransitionFailedData {
  failed_rules?: FailedRule[];
  saga_executed?: boolean;
}

/** Saga 终态：COMPENSATED 已冲正（单据状态不变）/ DEAD 冲正失败转死信人工处置 */
export type SagaStatus = 'COMPENSATED' | 'DEAD';

/** 42202 事务步骤失败时的 data 结构（阶段三） */
export interface TransitionTxFailedData {
  saga_id?: string;
  saga_status?: SagaStatus;
}
