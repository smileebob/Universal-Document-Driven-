// 本地 mock 数据层（任务 #11 任务8）：后端未就绪期间支撑页面开发自验。
// 数据模型与 prd/11 §一/§3.1 契约字段一致；仅 vite dev 生效（见 mock/index.ts）。
import { randomUUID } from 'node:crypto';

export interface MockAttribute {
  name: string;
  code: string;
  data_type: 'string' | 'integer' | 'decimal' | 'boolean' | 'date' | 'datetime' | 'ref' | 'table';
  required: boolean;
  visible: boolean;
  editable: boolean;
  unique?: boolean;
  max_length?: number;
  precision?: number;
  default_value?: string;
  ref_entity_code?: string;
  display_field?: string;
  is_list_column: boolean;
  is_searchable: boolean;
  list_order?: number;
  sub_attributes?: MockAttribute[];
}

export interface MockMenu {
  mounted: boolean;
  menu_name?: string;
  icon?: string;
  sort_order?: number;
  group_code?: string;
}

export interface MockEntitySchema {
  entity_name: string;
  entity_code: string;
  entity_type: 'base_data' | 'biz_entity';
  description?: string;
  attributes: MockAttribute[];
  status: 100 | 1 | 0;
  version: number;
  creator?: string;
  create_time?: number;
  update_time?: number;
  menu?: MockMenu;
}

export interface MockDocSchema {
  doc_name: string;
  doc_code: string;
  doc_type: 1 | 2;
  description?: string;
  attributes: MockAttribute[];
  states: { value: number; name: string; is_default?: boolean; is_editable?: boolean }[];
  status: 100 | 1 | 0;
  version: number;
  creator?: string;
  create_time?: number;
  update_time?: number;
  menu?: MockMenu;
}

export interface MockRecord {
  uuid: string;
  attributes: Record<string, unknown>;
  current_status?: number;
  status?: number;
  creator?: string;
  create_time?: number;
  update_time?: number;
  status_history?: { from_status?: number; to_status: number; operator: string; time: number; remark?: string }[];
}

/** 属性种子简写 */
function attr(partial: Partial<MockAttribute> & Pick<MockAttribute, 'name' | 'code' | 'data_type'>): MockAttribute {
  return {
    required: false,
    visible: true,
    editable: true,
    is_list_column: false,
    is_searchable: false,
    ...partial,
  };
}

const now = Date.now();

// ---------- 实体定义种子 ----------
export const entities = new Map<string, MockEntitySchema>([
  [
    'currency',
    {
      entity_name: '币种',
      entity_code: 'currency',
      entity_type: 'base_data',
      description: '基础币种档案',
      status: 1,
      version: 1,
      creator: 'mock_admin',
      create_time: now - 86400000 * 3,
      update_time: now - 86400000 * 3,
      menu: { mounted: true, menu_name: '币种', sort_order: 1, group_code: 'basic_data' },
      attributes: [
        attr({ name: '币种编码', code: 'code', data_type: 'string', required: true, unique: true, max_length: 10, is_list_column: true, is_searchable: true, list_order: 1 }),
        attr({ name: '币种名称', code: 'name', data_type: 'string', required: true, max_length: 50, is_list_column: true, is_searchable: true, list_order: 2 }),
        attr({ name: '符号', code: 'symbol', data_type: 'string', max_length: 5, is_list_column: true, list_order: 3 }),
      ],
    },
  ],
  [
    'customer',
    {
      entity_name: '客户',
      entity_code: 'customer',
      entity_type: 'base_data',
      description: '客户主数据',
      status: 1,
      version: 2,
      creator: 'mock_admin',
      create_time: now - 86400000 * 3,
      update_time: now - 86400000,
      menu: { mounted: true, menu_name: '客户', sort_order: 2, group_code: 'basic_data' },
      attributes: [
        attr({ name: '客户名称', code: 'name', data_type: 'string', required: true, max_length: 100, is_list_column: true, is_searchable: true, list_order: 1 }),
        attr({ name: '客户等级', code: 'level', data_type: 'string', max_length: 20, default_value: '普通', is_list_column: true, is_searchable: true, list_order: 2 }),
        attr({ name: '联系人', code: 'contact', data_type: 'string', max_length: 50, is_list_column: true, list_order: 3 }),
        attr({ name: '结算币种', code: 'currency', data_type: 'ref', ref_entity_code: 'currency', display_field: 'name', is_list_column: true, is_searchable: true, list_order: 4 }),
        attr({ name: '是否VIP', code: 'is_vip', data_type: 'boolean', is_searchable: true }),
      ],
    },
  ],
  [
    'product_model',
    {
      entity_name: '产品型号',
      entity_code: 'product_model',
      entity_type: 'base_data',
      description: '产品型号档案',
      status: 1,
      version: 1,
      creator: 'mock_admin',
      create_time: now - 86400000 * 2,
      update_time: now - 86400000 * 2,
      menu: { mounted: true, menu_name: '产品型号', sort_order: 3, group_code: 'basic_data' },
      attributes: [
        attr({ name: '型号', code: 'model_no', data_type: 'string', required: true, unique: true, max_length: 50, is_list_column: true, is_searchable: true, list_order: 1 }),
        attr({ name: '名称', code: 'name', data_type: 'string', required: true, max_length: 100, is_list_column: true, is_searchable: true, list_order: 2 }),
        attr({ name: '标准售价', code: 'price', data_type: 'decimal', precision: 2, is_list_column: true, is_searchable: true, list_order: 3 }),
      ],
    },
  ],
]);

// ---------- 单据定义种子 ----------
export const docs = new Map<string, MockDocSchema>([
  [
    'sales_order',
    {
      doc_name: '销售订单',
      doc_code: 'sales_order',
      doc_type: 1,
      description: '销售订单单据（mock 种子）',
      status: 1,
      version: 1,
      creator: 'mock_admin',
      create_time: now - 86400000 * 2,
      update_time: now - 86400000 * 2,
      menu: { mounted: true, menu_name: '销售订单', sort_order: 1, group_code: 'biz_doc' },
      states: [
        { value: 100, name: '暂存', is_default: true, is_editable: true },
        { value: 10, name: '已提交' },
        { value: 20, name: '已审核' },
        { value: 30, name: '已下达' },
        { value: 99, name: '已关闭' },
      ],
      attributes: [
        attr({ name: '客户', code: 'customer', data_type: 'ref', required: true, ref_entity_code: 'customer', display_field: 'name', is_list_column: true, is_searchable: true, list_order: 1 }),
        attr({ name: '订单日期', code: 'order_date', data_type: 'date', required: true, is_list_column: true, is_searchable: true, list_order: 2 }),
        attr({ name: '订单总额', code: 'total_amount', data_type: 'decimal', precision: 2, is_list_column: true, is_searchable: true, list_order: 3 }),
        attr({ name: '备注', code: 'remark', data_type: 'string', max_length: 200 }),
        attr({
          name: '订单明细',
          code: 'lines',
          data_type: 'table',
          required: true,
          sub_attributes: [
            attr({ name: '产品型号', code: 'product', data_type: 'ref', required: true, ref_entity_code: 'product_model', display_field: 'name', is_list_column: true }),
            attr({ name: '数量', code: 'qty', data_type: 'integer', required: true, is_list_column: true }),
            attr({ name: '单价', code: 'unit_price', data_type: 'decimal', precision: 2, required: true, is_list_column: true }),
            attr({ name: '金额', code: 'amount', data_type: 'decimal', precision: 2, is_list_column: true }),
          ],
        }),
      ],
    },
  ],
]);

/** 全局 schema_version 探针（发布/停用/菜单变更时自增） */
export let schemaVersion = 1;
export function bumpSchemaVersion(): void {
  schemaVersion += 1;
}

// ---------- 运行时数据种子 ----------
function uuid(): string {
  return randomUUID();
}

export const records = new Map<string, MockRecord[]>();

function seed(): void {
  const currencies: MockRecord[] = [
    ['CNY', '人民币', '¥'],
    ['USD', '美元', '$'],
    ['EUR', '欧元', '€'],
  ].map(([c, n, s], i) => ({
    uuid: uuid(),
    attributes: { code: c, name: n, symbol: s },
    status: 1,
    creator: 'mock_admin',
    create_time: now - 86400000 * (10 - i),
    update_time: now - 86400000 * (10 - i),
  }));

  const products: MockRecord[] = [
    ['M-1000', '标准型组件', 120.5],
    ['M-2000', '增强型组件', 260],
    ['M-3000', '旗舰型组件', 880.88],
  ].map(([m, n, p], i) => ({
    uuid: uuid(),
    attributes: { model_no: m, name: n, price: p },
    status: 1,
    creator: 'mock_admin',
    create_time: now - 86400000 * (9 - i),
    update_time: now - 86400000 * (9 - i),
  }));

  const customers: MockRecord[] = [
    ['华东贸易有限公司', '金牌', '张三', currencies[0].uuid, true],
    ['南方制造集团', '普通', '李四', currencies[0].uuid, false],
    ['Global Tech Ltd.', '金牌', 'Tom', currencies[1].uuid, true],
    ['欧陆进出口公司', '普通', '王五', currencies[2].uuid, false],
  ].map(([n, l, c, cur, vip], i) => ({
    uuid: uuid(),
    attributes: { name: n, level: l, contact: c, currency: cur, is_vip: vip },
    status: 1,
    creator: 'mock_biz',
    create_time: now - 86400000 * (8 - i),
    update_time: now - 86400000 * (8 - i),
  }));

  const orders: MockRecord[] = [
    { customer: customers[0], amount: 24100, status: 100 },
    { customer: customers[1], amount: 520, status: 10 },
    { customer: customers[2], amount: 88088, status: 20 },
    { customer: customers[3], amount: 68000, status: 100 },
  ].map((o, i) => {
    const createTime = now - 3600000 * (i + 2);
    const history: { from_status?: number; to_status: number; operator: string; time: number; remark?: string }[] = [{ to_status: 100, operator: 'mock_biz', time: createTime, remark: '创建暂存' }];
    if (o.status !== 100) {
      history.push({ from_status: 100, to_status: 10, operator: 'mock_biz', time: createTime + 1800000 });
    }
    if (o.status === 20) {
      history.push({ from_status: 10, to_status: 20, operator: 'mock_admin', time: createTime + 3600000 });
    }
    return {
      uuid: uuid(),
      attributes: {
        customer: o.customer.uuid,
        order_date: new Date(createTime).toISOString().slice(0, 10),
        total_amount: o.amount,
        remark: i === 2 ? '大额订单（mock 规则示例：>50000 提交需审核）' : '',
        lines: [
          { product: products[0].uuid, qty: 10, unit_price: 120.5, amount: 1205 },
          { product: products[1].uuid, qty: 5, unit_price: 260, amount: 1300 },
        ],
      },
      current_status: o.status,
      creator: 'mock_biz',
      create_time: createTime,
      update_time: createTime,
      status_history: history,
    };
  });

  records.set('currency', currencies);
  records.set('product_model', products);
  records.set('customer', customers);
  records.set('sales_order', orders);
}
seed();

export function newUuid(): string {
  return uuid();
}
