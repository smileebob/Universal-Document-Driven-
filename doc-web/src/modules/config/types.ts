// 实体/单据定义元数据类型（与 prd/11 §3.1 报文对齐，design/10 §3.1）

export type DataType =
  | 'string'
  | 'integer'
  | 'decimal'
  | 'boolean'
  | 'date'
  | 'datetime'
  | 'ref'
  | 'table';

export interface AttributeDef {
  /** 属性名称（界面标签） */
  name: string;
  /** 属性编码（数据 key） */
  code: string;
  data_type: DataType;
  required: boolean;
  visible: boolean;
  editable: boolean;
  unique?: boolean;
  /** string 最大长度 */
  max_length?: number;
  /** decimal 小数位 */
  precision?: number;
  /** 新增时回填的默认值 */
  default_value?: string;
  /** ref：被引用实体编码 */
  ref_entity_code?: string;
  /** ref：展示属性（默认=被引用实体第一个 string 且 is_list_column 的属性） */
  display_field?: string;
  is_list_column: boolean;
  is_searchable: boolean;
  list_order?: number;
  /** table：子属性（子属性不可再为 table，prd/07 P-DC-02） */
  sub_attributes?: AttributeDef[];
}

/** 定义状态（prd/12 §1.6）：100 草稿 / 1 生效 / 0 停用 */
export type SchemaStatus = 100 | 1 | 0;

export type EntityType = 'base_data' | 'biz_entity';

/**
 * 单据类型（后端契约为字符串，如 order/flow；prd/12 §1.2 语义：订单类/流水单）。
 * 后端为自由字符串，前端提供预置选项并兼容任意值展示。
 */
export type DocType = string;

export const DOC_TYPE_OPTIONS: { value: string; label: string }[] = [
  { value: 'order', label: '订单类 order' },
  { value: 'flow', label: '流水单 flow' },
];

// 菜单分组（prd/12 §1.11，平台预置固定值）
export type MenuGroupCode = 'basic_data' | 'biz_doc' | 'ops';

export const MENU_GROUP_LABELS: Record<MenuGroupCode, string> = {
  basic_data: '基础数据',
  biz_doc: '业务单据',
  ops: '运营监控',
};

/** 菜单挂载配置（EC-05） */
export interface MenuMountConfig {
  mounted: boolean;
  menu_name?: string;
  icon?: string;
  sort_order?: number;
  group_code?: MenuGroupCode | '';
}

export interface EntitySchema {
  entity_name: string;
  entity_code: string;
  entity_type: EntityType;
  description?: string;
  attributes: AttributeDef[];
  status: SchemaStatus;
  /** schema 行版本号（prd/11 §一，发布时自增，供前端缓存比对） */
  version: number;
  creator?: string;
  create_time?: number;
  update_time?: number;
  menu?: MenuMountConfig;
}

/**
 * 单据状态定义（后端契约 status_defs 元素，prd/12 §四）：
 * {status, name, editable, terminal}
 */
export interface StatusDef {
  status: number;
  name: string;
  /** 该状态下单据是否可编辑（暂存 100 默认可编辑） */
  editable?: boolean;
  /** 终态标记（如已关闭 99） */
  terminal?: boolean;
}

/** 平台预置默认五状态（后端 DocSchemaService.defaultStatusDefs 同口径） */
export const DEFAULT_STATUS_DEFS: StatusDef[] = [
  { status: 100, name: '暂存', editable: true },
  { status: 10, name: '已提交' },
  { status: 20, name: '已审核' },
  { status: 30, name: '已生效' },
  { status: 99, name: '已关闭', terminal: true },
];

export interface DocSchema {
  uuid?: string;
  doc_name: string;
  doc_code: string;
  doc_type: DocType;
  description?: string;
  attributes: AttributeDef[];
  /** 状态字典（后端契约字段名 status_defs） */
  status_defs: StatusDef[];
  status: SchemaStatus;
  version: number;
  creator?: string;
  create_time?: number;
  update_time?: number;
  published_at?: number;
  menu?: MenuMountConfig;
}

export type SchemaDef = EntitySchema | DocSchema;
export type ObjType = 'entity' | 'doc';

/** 已挂载菜单项（GET /menu/mounted，design/10 §2.1） */
export interface MenuItem {
  code: string;
  obj_type: ObjType;
  menu_name: string;
  group_code: MenuGroupCode | '';
  sort_order: number;
  icon?: string;
}

export interface MountedMenuResult {
  items: MenuItem[];
  /** 全局版本探针（prd/11 §一，design/10 附录增补 #2） */
  schema_version: number;
}

// ---------- 规则库（RE-01/02，后端 RuleController 视图契约） ----------

/** 规则类型：expression 表达式 / interface 接口 */
export type RuleType = 'expression' | 'interface';

export interface RuleDef {
  rule_uuid: string;
  rule_name: string;
  rule_type: string;
  expression?: string;
  interface_code?: string;
  enabled: boolean;
  description?: string;
  version: number;
  creator?: string;
  create_time?: number;
  update_time?: number;
}

// ---------- 状态机（SM-01/02，后端 StateMachineController 视图契约） ----------

/** 状态机流转定义：start 源状态 → end 目标状态集合 */
export interface StateMachineTransition {
  start: number;
  end: number[];
}

export interface StateMachineView {
  doc_code: string;
  states: StatusDef[];
  transitions: StateMachineTransition[];
  version: number;
  update_time?: number;
}

// ---------- 单据规则绑定（RE-05，后端 DocRuleConfigController 视图契约） ----------

/** 流转时机规则绑定：status 为目标状态 */
export interface StatusRuleBinding {
  status: number;
  rules: string[];
}

export interface DocRuleConfigView {
  doc_code: string;
  create_rules: string[];
  status_rules: StatusRuleBinding[];
  version: number;
  update_time?: number;
}

export function isDocSchema(schema: SchemaDef): schema is DocSchema {
  return 'doc_code' in schema;
}

export function schemaCode(schema: SchemaDef): string {
  return isDocSchema(schema) ? schema.doc_code : schema.entity_code;
}

export function schemaName(schema: SchemaDef): string {
  return isDocSchema(schema) ? schema.doc_name : schema.entity_name;
}

/** 取 ref 属性的展示字段：显式配置优先，否则取被引用实体第一个 string 且 is_list_column 的属性 */
export function resolveDisplayField(attr: AttributeDef, refSchema?: EntitySchema): string {
  if (attr.display_field) return attr.display_field;
  const candidate = refSchema?.attributes.find((a) => a.data_type === 'string' && a.is_list_column);
  return candidate?.code ?? '';
}

/** 列表列：is_list_column=true 按 list_order 排列（design/10 §4.1） */
export function listColumns(schema: SchemaDef): AttributeDef[] {
  return schema.attributes
    .filter((a) => a.is_list_column && a.visible)
    .sort((a, b) => (a.list_order ?? 0) - (b.list_order ?? 0));
}
