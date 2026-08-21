// 任务 #19 步骤 37 补漏：规则绑定（rule_uuid 键名）+ ref_display 端到端核验（实体详情口径）
const BASE = 'http://localhost:8080/api/v1';
const HDR = { 'content-type': 'application/json; charset=utf-8', 'X-User-Id': 'seed-37', 'X-User-Roles': 'platform_admin' };
let failures = 0;
async function call(method, path, body) {
  const resp = await fetch(BASE + path, { method, headers: HDR, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await resp.text();
  let json = null; try { json = JSON.parse(text); } catch (e) {}
  if (!resp.ok || (json && json.code !== undefined && json.code !== 0)) {
    failures++; console.log(`FAIL ${method} ${path} http=${resp.status} body=${text.slice(0, 300)}`); return null;
  }
  return json;
}

async function main() {
  // 1) 规则绑定：取既有规则 uuid
  const rl = await call('GET', '/rule?keyword=' + encodeURIComponent('订单金额必须为正'));
  const items = rl && rl.data && rl.data.items || [];
  if (!items.length) { failures++; console.log('FAIL 规则列表未命中已创建规则'); }
  const ruleUuid = items[0] && items[0].rule_uuid;
  const bind = await call('PUT', '/schema/doc/export_order/rules', { create_rules: [ruleUuid], status_rules: [] });
  console.log(`rules bind -> ${bind ? 'ok (rule_uuid=' + ruleUuid + ')' : 'FAILED'}`);
  const getRules = await call('GET', '/schema/doc/export_order/rules');
  console.log(`rules GET -> create_rules=${JSON.stringify(getRules && getRules.data.create_rules)}`);

  // 2) ref_display 端到端：customer C001 详情（等级/币种 ref 展示）
  const qc = await call('POST', '/query', { target: 'entity_data', filters: [
    { field: 'entity_code', op: 'eq', value: 'customer' },
    { field: 'attributes.customer_code', op: 'eq', value: 'C001' }] });
  const cUuid = qc && qc.data.items && qc.data.items[0] && qc.data.items[0].uuid;
  if (!cUuid) { failures++; console.log('FAIL 未找到 C001 实体数据'); }
  const det = await call('GET', `/data/customer/${cUuid}`);
  const refDisp = det && det.data && det.data.ref_display;
  console.log(`customer C001 ref_display=${JSON.stringify(refDisp)}`);
  if (!refDisp || !JSON.stringify(refDisp).includes('战略客户') || !JSON.stringify(refDisp).includes('美元')) {
    failures++; console.log('FAIL ref_display 未回显等级/币种展示字段');
  }

  // 3) product_model M001 详情（series ref 展示）
  const qm = await call('POST', '/query', { target: 'entity_data', filters: [
    { field: 'entity_code', op: 'eq', value: 'product_model' },
    { field: 'attributes.model_code', op: 'eq', value: 'M001' }] });
  const mUuid = qm && qm.data.items && qm.data.items[0] && qm.data.items[0].uuid;
  const mDet = await call('GET', `/data/product_model/${mUuid}`);
  const mDisp = mDet && mDet.data && mDet.data.ref_display;
  console.log(`product_model M001 ref_display=${JSON.stringify(mDisp)}`);
  if (!mDisp || !JSON.stringify(mDisp).includes('旗舰系列')) { failures++; console.log('FAIL M001 ref_display 未回显系列展示字段'); }

  console.log(failures === 0 ? '===== SEED-37B ALL OK =====' : `===== SEED-37B FAILURES=${failures} =====`);
  process.exit(failures === 0 ? 0 : 1);
}
main().catch(e => { console.log('FATAL ' + e.message); process.exit(2); });
