// 任务 #19 步骤 9：阶段四冒烟（外销对象/关联反查/审计 before-after/权限抽样）
const BASE = 'http://localhost:8080/api/v1';
let failures = 0;
function check(name, ok, detail) {
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' | ' + detail : ''}`);
  if (!ok) failures++;
}
async function call(method, path, body, roles, userId) {
  const headers = { 'content-type': 'application/json; charset=utf-8' };
  if (roles != null) headers['X-User-Roles'] = roles;
  headers['X-User-Id'] = userId || 'smoke-19';
  const opts = { method, headers };
  if (body != null) opts.body = JSON.stringify(body);
  const resp = await fetch(BASE + path, opts);
  const text = await resp.text();
  let json = null; try { json = JSON.parse(text); } catch (e) {}
  return { http: resp.status, json, text };
}

async function main() {
  console.log('===== M1 外销对象可用 =====');
  const docDet = await call('GET', '/schema/doc/export_order');
  const defs = (docDet.json.data && docDet.json.data.status_defs) || [];
  check('export_order 已发布且五状态', docDet.json.code === 0 && docDet.json.data.status === 1 && defs.length === 5,
    `status=${docDet.json.data.status} defs=${defs.map(d => d.status).join('/')}`);
  const sm = await call('GET', '/schema/doc/export_order/state-machine');
  const trans = (sm.json.data && sm.json.data.transitions) || [];
  check('状态机 4 条流转（100→10→20→30→99）', sm.json.code === 0 && trans.length === 4,
    trans.map(t => t.start + '->' + (t.end || []).join('|')).join('; '));
  const txc = await call('GET', '/schema/doc/export_order/tx-config');
  check('事务配置 2 组计划', txc.json.code === 0 && txc.json.data.transitions.length === 2);
  for (const code of ['product_series', 'product_model', 'customer_level', 'currency', 'customer']) {
    const e = await call('GET', `/schema/entity/${code}`);
    check(`实体 ${code} 详情可查`, e.json.code === 0 && e.json.data.status != null,
      `status=${e.json.data.status} version=${e.json.data.version}`);
  }
  const menus = await call('GET', '/menu/mounted');
  const menuJson = JSON.stringify(menus.json);
  check('菜单挂载 6 项（basic_data 5 + biz_doc 1）', menus.json.code === 0
    && ['产品系列', '产品型号', '客户等级', '币种', '客户', '外销订单'].every(n => menuJson.includes(n)));

  console.log('===== M2 关联反查（RD-09/11）=====');
  const find = async (code, attrField, val) => {
    const q = await call('POST', '/query', { target: 'entity_data', filters: [
      { field: 'entity_code', op: 'eq', value: code },
      { field: attrField, op: 'eq', value: val }] }, 'platform_admin');
    return q.json.data.items[0].uuid;
  };
  const c1 = await find('customer', 'attributes.customer_code', 'C001');
  const c2 = await find('customer', 'attributes.customer_code', 'C002');
  const r1 = await call('POST', '/query', { target: 'doc_data', filters: [
    { field: 'doc_code', op: 'eq', value: 'export_order' },
    { field: 'relations[].uuid', op: 'nested_eq', value: c1 }] }, 'platform_admin');
  check('nested_eq 反查引用 C001 的单据=2', r1.json.code === 0 && r1.json.data.total === 2, 'total=' + r1.json.data.total);
  const r2 = await call('POST', '/query', { target: 'doc_data', filters: [
    { field: 'relations[].uuid', op: 'nested_in', value: [c1, c2] }] }, 'platform_admin');
  check('nested_in 多客户批量反查=3', r2.json.code === 0 && r2.json.data.total === 3, 'total=' + r2.json.data.total);
  const r3 = await call('POST', '/query', { target: 'doc_data', filters: [
    { field: 'relations[].code', op: 'nested_eq', value: 'currency' }] }, 'platform_admin');
  check('nested_eq relations[].code=currency 反查=3', r3.json.code === 0 && r3.json.data.total === 3, 'total=' + r3.json.data.total);

  console.log('===== M3 审计查询（before/after 供 JSON Diff）=====');
  const a1 = await call('GET', '/audit?target_code=export_order&limit=50', null, 'platform_admin');
  const items = (a1.json.data && a1.json.data.items) || [];
  const withSnap = items.filter(i => i.before_value || i.after_value);
  check('审计查询 target_code=export_order 命中且含快照', a1.json.code === 0 && items.length >= 3 && withSnap.length >= 3,
    `total=${a1.json.data.total} withSnap=${withSnap.length}`);
  const transAudits = items.filter(i => i.operation === 'transition' && i.before_value && i.after_value);
  check('流转审计双快照（before 100 / after 10）',
    transAudits.some(i => i.before_value.includes('"current_status":100') && i.after_value.includes('"current_status":10')),
    transAudits.length + ' 条');
  const a2 = await call('GET', '/audit?operation=create&target_type=doc', null, 'business_admin');
  check('operation+target_type 复合筛选（business_admin 可查审计）',
    a2.http === 200 && a2.json.code === 0 && (a2.json.data.items || []).length >= 1, 'items=' + ((a2.json.data && a2.json.data.items) || []).length);

  console.log('===== M4 权限抽样（X-User-Roles 透传）=====');
  const rl = await call('GET', '/rule', null, 'platform_admin');
  const ruleUuid = rl.json.data.items[0].rule_uuid;
  const p1 = await call('PUT', '/schema/doc/export_order/rules', { create_rules: [], status_rules: [] }, 'business_user');
  check('business_user 写规则绑定 → 403', p1.http === 403, p1.text.slice(0, 90));
  const p2 = await call('PUT', '/schema/doc/export_order/rules', { create_rules: [ruleUuid], status_rules: [] }, 'business_admin');
  check('business_admin 写规则绑定 → 放行（配置管理员以上）', p2.http === 200 && p2.json.code === 0, 'http=' + p2.http);
  const p3 = await call('GET', '/saga/dead-letter', null, 'business_user');
  check('business_user 访问运营死信 → 403', p3.http === 403, 'http=' + p3.http);
  const p4 = await call('GET', '/saga/dead-letter', null, 'platform_admin');
  check('platform_admin 访问运营死信 → 放行', p4.http === 200 && p4.json.code === 0);
  const p5 = await call('GET', '/saga/dead-letter', null, null);
  check('无角色头（dev deny-unauthenticated=false）放行', p5.http === 200, 'http=' + p5.http);

  console.log(failures === 0 ? '===== SMOKE-19 ALL PASS =====' : `===== SMOKE-19 FAILURES=${failures} =====`);
  process.exit(failures === 0 ? 0 : 1);
}
main().catch(e => { console.log('FATAL ' + e.stack); process.exit(2); });
