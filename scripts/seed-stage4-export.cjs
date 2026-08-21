// 任务 #19 步骤 37：阶段四外销全套对象预置（经标准 API，含既有演示数据重建）
// 仿真存储重启即空，本脚本幂等全量重建：实体 5 个 + export_order 升级 + 状态机/规则/事务配置 + 菜单 + 样例数据
const BASE = 'http://localhost:8080/api/v1';
const HDR = { 'content-type': 'application/json; charset=utf-8', 'X-User-Id': 'seed-37', 'X-User-Roles': 'platform_admin' };
let failures = 0;

async function call(method, path, body) {
  const resp = await fetch(BASE + path, {
    method, headers: HDR,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await resp.text();
  let json = null;
  try { json = JSON.parse(text); } catch (e) { /* keep raw */ }
  if (!resp.ok || (json && json.code !== 0 && json.code !== undefined)) {
    failures++;
    console.log(`FAIL ${method} ${path} http=${resp.status} body=${text.slice(0, 300)}`);
    return null;
  }
  return json;
}

function str(name, code, extra = {}) {
  return { name, code, data_type: 'string', required: true, visible: true, editable: true, is_list_column: true, is_searchable: true, ...extra };
}
function ref(name, code, target, display, extra = {}) {
  return { name, code, data_type: 'ref', required: true, visible: true, editable: true, ref_entity_code: target, display_field: display, is_list_column: true, ...extra };
}

async function main() {
  console.log('===== S1 实体定义（外销基础数据五件套）=====');
  const entities = [
    { entity_code: 'product_series', entity_name: '产品系列', entity_type: 'base_data', description: '产品系列基础数据', attributes: [
        str('系列编码', 'series_code', { unique: true }), str('系列名称', 'series_name')] },
    { entity_code: 'product_model', entity_name: '产品型号', entity_type: 'base_data', description: '产品型号基础数据', attributes: [
        str('型号编码', 'model_code', { unique: true }), str('型号名称', 'model_name'),
        ref('所属系列', 'series', 'product_series', 'series_name', { required: false })] },
    { entity_code: 'customer_level', entity_name: '客户等级', entity_type: 'base_data', description: '客户等级（外销）', attributes: [
        str('等级编码', 'level_code', { unique: true }), str('等级名称', 'level_name')] },
    { entity_code: 'currency', entity_name: '币种', entity_type: 'base_data', description: '交易币种（外销）', attributes: [
        str('币种编码', 'currency_code', { unique: true }), str('币种名称', 'currency_name')] },
    { entity_code: 'customer', entity_name: '客户', entity_type: 'base_data', description: '外销客户主数据', attributes: [
        str('客户编码', 'customer_code', { unique: true }), str('客户名称', 'customer_name'),
        ref('客户等级', 'level', 'customer_level', 'level_name'),
        ref('结算币种', 'currency', 'currency', 'currency_name')] },
  ];
  for (const e of entities) {
    const r = await call('POST', '/schema/entity', e);
    console.log(`create ${e.entity_code} -> ${r ? 'ok v' + r.data.version : 'FAILED'}`);
    const p = await call('POST', `/schema/entity/${e.entity_code}/publish`, {});
    console.log(`publish ${e.entity_code} -> ${p ? 'ok v' + p.data.version : 'FAILED'}`);
  }

  console.log('===== S2 实体样例数据 =====');
  const data = {};
  async function seedRow(code, attrs, label) {
    const r = await call('POST', `/data/${code}`, { attributes: attrs });
    if (r) { data[label] = r.data.uuid; console.log(`data ${label} -> uuid=${r.data.uuid}`); }
  }
  await seedRow('product_series', { series_code: 'S001', series_name: '旗舰系列' }, 'S001');
  await seedRow('product_series', { series_code: 'S002', series_name: '经济系列' }, 'S002');
  await seedRow('product_model', { model_code: 'M001', model_name: 'X1 Pro', series: data.S001 }, 'M001');
  await seedRow('product_model', { model_code: 'M002', model_name: 'E1 Lite', series: data.S002 }, 'M002');
  await seedRow('customer_level', { level_code: 'VIP', level_name: '战略客户' }, 'VIP');
  await seedRow('customer_level', { level_code: 'NORMAL', level_name: '普通客户' }, 'NORMAL');
  await seedRow('currency', { currency_code: 'USD', currency_name: '美元' }, 'USD');
  await seedRow('currency', { currency_code: 'EUR', currency_name: '欧元' }, 'EUR');
  await seedRow('customer', { customer_code: 'C001', customer_name: 'Global Trading GmbH', level: data.VIP, currency: data.USD }, 'C001');
  await seedRow('customer', { customer_code: 'C002', customer_name: 'Ocean Import LLC', level: data.NORMAL, currency: data.EUR }, 'C002');

  console.log('===== S3 外销订单 export_order（五状态 + 客户/币种 ref）=====');
  const doc = {
    doc_code: 'export_order', doc_name: '外销订单', doc_type: 'business',
    description: '阶段四外销订单（客户/币种关联，五状态生命周期）',
    attributes: [
      { name: '订单编号', code: 'order_no', data_type: 'string', required: true, visible: true, editable: true, is_list_column: true, is_searchable: true },
      { name: '客户', code: 'customer', data_type: 'ref', required: true, visible: true, editable: true, ref_entity_code: 'customer', display_field: 'customer_name', is_list_column: true },
      { name: '结算币种', code: 'currency', data_type: 'ref', required: true, visible: true, editable: true, ref_entity_code: 'currency', display_field: 'currency_name', is_list_column: true },
      { name: '订单金额', code: 'amount', data_type: 'decimal', required: true, visible: true, editable: true, is_list_column: true, precision: 2 },
      { name: '备注', code: 'remark', data_type: 'string', required: false, visible: true, editable: true, is_list_column: false },
    ],
    status_defs: [
      { status: 100, name: 'draft' }, { status: 10, name: 'issued' }, { status: 20, name: 'audited' },
      { status: 30, name: 'shipping' }, { status: 99, name: 'closed' },
    ],
  };
  const dc = await call('POST', '/schema/doc', doc);
  console.log(`create export_order -> ${dc ? 'ok' : 'FAILED'}`);
  const dp = await call('POST', '/schema/doc/export_order/publish', {});
  console.log(`publish export_order -> ${dp ? 'ok v' + dp.data.version : 'FAILED'}`);

  console.log('===== S4 状态机（100→10→20→30→99）=====');
  const sm = await call('PUT', '/schema/doc/export_order/state-machine', {
    states: [
      { status: 100, name: 'draft', editable: true, terminal: false },
      { status: 10, name: 'issued', editable: false, terminal: false },
      { status: 20, name: 'audited', editable: false, terminal: false },
      { status: 30, name: 'shipping', editable: false, terminal: false },
      { status: 99, name: 'closed', editable: false, terminal: true },
    ],
    transitions: [
      { start: 100, end: [10] }, { start: 10, end: [20] }, { start: 20, end: [30] }, { start: 30, end: [99] },
    ],
  });
  console.log(`state-machine -> ${sm ? 'ok' : 'FAILED'}`);

  console.log('===== S5 规则库 + 绑定 =====');
  const rule = await call('POST', '/rule', { rule_name: '订单金额必须为正', rule_type: 'expression', expression: "#attributes['amount'] > 0", enabled: true, description: '外销订单创建校验' });
  console.log(`rule create -> ${rule ? 'uuid=' + rule.data.rule_uuid : 'FAILED'}`);
  const bind = await call('PUT', '/schema/doc/export_order/rules', { create_rules: rule ? [rule.data.rule_uuid] : [], status_rules: [] });
  console.log(`rules bind -> ${bind ? 'ok' : 'FAILED'}`);

  console.log('===== S6 事务配置（mock SAP/APS/NOTIFY）=====');
  const mock = 'http://127.0.0.1:8082/api/v1/mock/tx';
  const steps = (sapComp) => [
    { seq: 1, name: 'SAP', action_url: `${mock}/sap/action`, compensate_url: sapComp, timeout_ms: 5000, retry: 1 },
    { seq: 2, name: 'APS', action_url: `${mock}/aps/action`, compensate_url: `${mock}/aps/compensate`, timeout_ms: 5000, retry: 1 },
    { seq: 3, name: 'NOTIFY', action_url: `${mock}/notify/action`, compensate_url: null, timeout_ms: 5000, retry: 0 },
  ];
  const txc = await call('PUT', '/schema/doc/export_order/tx-config', { transitions: [
    { from_status: 100, to_status: 10, steps: steps(`${mock}/sap/compensate`) },
    { from_status: 10, to_status: 20, steps: steps(`${mock}/sapdead/compensate`) },
  ] });
  console.log(`tx-config -> ${txc ? 'ok' : 'FAILED'}`);

  console.log('===== S7 菜单挂载 =====');
  const menus = [
    ['entity', 'product_series', '产品系列', 'basic_data', 1],
    ['entity', 'product_model', '产品型号', 'basic_data', 2],
    ['entity', 'customer_level', '客户等级', 'basic_data', 3],
    ['entity', 'currency', '币种', 'basic_data', 4],
    ['entity', 'customer', '客户', 'basic_data', 5],
    ['doc', 'export_order', '外销订单', 'biz_doc', 1],
  ];
  for (const [type, code, name, group, order] of menus) {
    const m = await call('PUT', `/schema/${type}/${code}/menu`, { mounted: true, menu_name: name, group_code: group, sort_order: order });
    console.log(`menu ${group}/${code} -> ${m ? 'ok' : 'FAILED'}`);
  }

  console.log('===== S8 外销订单样例数据（含流转演示）=====');
  const mkDoc = async (attrs, label) => {
    const r = await call('POST', '/data/doc/export_order', { attributes: attrs });
    if (r) { data[label] = r.data.uuid; console.log(`doc ${label} -> uuid=${r.data.uuid}`); }
  };
  await mkDoc({ order_no: 'EO-2026-001', customer: data.C001, currency: data.USD, amount: 12800.00, remark: '汉堡港 FOB' }, 'EO1');
  await mkDoc({ order_no: 'EO-2026-002', customer: data.C001, currency: data.USD, amount: 5600.50 }, 'EO2');
  await mkDoc({ order_no: 'EO-2026-003', customer: data.C002, currency: data.EUR, amount: 3200.00, remark: '鹿特丹 CIF' }, 'EO3');
  // EO-001 提交（100→10，触发 saga）
  const tr = await call('POST', `/data/doc/export_order/${data.EO1}/transition`, { target_status: 10, event: 'submit' });
  console.log(`transition EO1->issued -> ${tr ? 'saga_triggered=' + tr.data.saga_triggered : 'FAILED'}`);

  console.log('===== S9 ref_display 端到端核验（仅含 .ref. 过滤条件的查询装配回显）=====');
  const q = await call('POST', '/query', { target: 'doc_data', filters: [
    { field: 'doc_code', op: 'eq', value: 'export_order' },
    { field: 'attributes.customer.ref.customer_name', op: 'eq', value: 'Global Trading GmbH' },
  ] });
  if (q) {
    const items = q.data.items || [];
    const first = items.find(i => (i.attributes || {}).order_no === 'EO-2026-001');
    const refDisp = first && first.ref_display;
    const custName = refDisp && refDisp.customer && refDisp.customer.customer_name;
    console.log(`query doc_data total=${q.data.total}; EO1 ref_display=${JSON.stringify(refDisp)}`);
    if (!custName || !String(custName).startsWith('Global Trading')) { failures++; console.log('FAIL ref_display 未回显客户展示字段'); }
  }
  // 关联反查：引用了 C001 的所有单据
  const rel = await call('POST', '/query', { target: 'doc_data', filters: [{ field: 'relations[].uuid', op: 'nested_eq', value: data.C001 }] });
  console.log(`relation back-query C001 -> total=${rel ? rel.data.total : 'FAILED'} (expect 2)`);

  console.log(failures === 0 ? '===== SEED-37 ALL OK =====' : `===== SEED-37 FAILURES=${failures} =====`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch(e => { console.log('FATAL ' + e.message); process.exit(2); });
