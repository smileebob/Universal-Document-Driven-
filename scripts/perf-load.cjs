// 任务 #20（步骤 42）：仿真环境基线压测脚本（正式化，docs/perf-report.md 证据来源）
// 用法：node scripts/perf-load.cjs [--concurrency 20 --duration 30 --write-concurrency 20 --write-docs 60]
// 模型：闭环压测（固定并发数持续打满），区别于开环目标 RPS；仿真内存在单机 JVM
// 依赖：三服务已启动（gateway 8080）且已预置演示数据（seed-stage4-export.cjs）
const BASE = 'http://localhost:8080';
const args = process.argv.slice(2);
const opt = (name, def) => {
  const i = args.indexOf('--' + name);
  return i >= 0 && args[i + 1] ? Number(args[i + 1]) : def;
};
const CONCURRENCY = opt('concurrency', 20);
const DURATION_S = opt('duration', 30);
const WRITE_CONCURRENCY = opt('write-concurrency', 20);
const WRITE_DOCS = opt('write-docs', 60);

const HEADERS = { 'Content-Type': 'application/json', 'X-User-Id': 'platform_admin' };

async function call(method, path, body) {
  const start = process.hrtime.bigint();
  try {
    const r = await fetch(BASE + path, {
      method, headers: HEADERS, body: body ? JSON.stringify(body) : undefined,
    });
    const text = await r.text();
    const ms = Number(process.hrtime.bigint() - start) / 1e6;
    let json = null;
    try { json = JSON.parse(text); } catch (e) { /* ignore */ }
    return { status: r.status, ms, ok: r.status === 200 && (!json || json.code === 0), json };
  } catch (e) {
    return { status: 0, ms: Number(process.hrtime.bigint() - start) / 1e6, ok: false, error: e.message };
  }
}

function percentile(sorted, p) {
  return sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))];
}

function stats(label, samples) {
  const sorted = samples.map(s => s.ms).sort((a, b) => a - b);
  const errors = samples.filter(s => !s.ok).length;
  return {
    label, count: samples.length, errors,
    avg_ms: +(sorted.reduce((a, b) => a + b, 0) / Math.max(1, sorted.length)).toFixed(1),
    p50_ms: sorted.length ? +percentile(sorted, 0.5).toFixed(1) : null,
    p95_ms: sorted.length ? +percentile(sorted, 0.95).toFixed(1) : null,
    p99_ms: sorted.length ? +percentile(sorted, 0.99).toFixed(1) : null,
    max_ms: sorted.length ? +sorted[sorted.length - 1].toFixed(1) : null,
  };
}

async function collectUuids() {
  const q = await call('POST', '/api/v1/query', {
    target: 'doc_data', filters: [{ field: 'doc_code', op: 'eq', value: 'export_order' }],
    page: { limit: 100 },
  });
  const items = (q.json && q.json.data && q.json.data.items) || [];
  return items.map(i => i.uuid).filter(Boolean);
}

async function phaseRead() {
  console.log(`\n===== Phase A 混合读：${CONCURRENCY} 并发闭环，持续 ${DURATION_S}s（列表:详情=1:1）=====`);
  const uuids = await collectUuids();
  if (uuids.length === 0) throw new Error('无演示数据 uuid，请先运行 seed');
  const listSamples = []; const detailSamples = [];
  const deadline = Date.now() + DURATION_S * 1000;
  let seq = 0;
  async function worker() {
    while (Date.now() < deadline) {
      const my = seq++;
      if (my % 2 === 0) {
        listSamples.push(await call('POST', '/api/v1/query',
          { target: 'doc_data', filters: [], page: { limit: 20 } }));
      } else {
        detailSamples.push(await call('GET',
          `/api/v1/data/doc/export_order/${uuids[my % uuids.length]}`));
      }
    }
  }
  const wall0 = Date.now();
  await Promise.all(Array.from({ length: CONCURRENCY }, worker));
  const wallSec = (Date.now() - wall0) / 1000;
  const total = listSamples.length + detailSamples.length;
  return {
    concurrency: CONCURRENCY, wall_s: +wallSec.toFixed(2),
    rps_total: +(total / wallSec).toFixed(1),
    list: Object.assign(stats('list(POST /query limit=20)', listSamples),
      { rps: +(listSamples.length / wallSec).toFixed(1) }),
    detail: Object.assign(stats('detail(GET 点查)', detailSamples),
      { rps: +(detailSamples.length / wallSec).toFixed(1) }),
  };
}

async function phaseWrite() {
  console.log(`\n===== Phase B 写路径：预建 ${WRITE_DOCS} 单 + ${WRITE_CONCURRENCY} 并发流转（100→10 触发 Saga）=====`);
  // 必填 ref：取演示数据 customer/currency 第一条 uuid
  const cu = await call('POST', '/api/v1/query', { target: 'entity_data',
    filters: [{ field: 'entity_code', op: 'eq', value: 'customer' }], page: { limit: 1 } });
  const cy = await call('POST', '/api/v1/query', { target: 'entity_data',
    filters: [{ field: 'entity_code', op: 'eq', value: 'currency' }], page: { limit: 1 } });
  const customerUuid = cu.json.data.items[0].uuid;
  const currencyUuid = cy.json.data.items[0].uuid;
  const uuids = [];
  for (let i = 0; i < WRITE_DOCS; i++) {
    const r = await call('POST', '/api/v1/data/doc/export_order', {
      attributes: { order_no: `PERF-${Date.now()}-${i}`, amount: 100 + i,
        customer: customerUuid, currency: currencyUuid, remark: 'perf' },
    });
    if (r.ok) uuids.push(r.json.data.uuid);
    else if (i === 0) console.log('建单失败样例: ' + JSON.stringify(r.json));
  }
  console.log(`预建单据: ${uuids.length}/${WRITE_DOCS}`);
  const samples = [];
  let idx = 0;
  async function worker() {
    while (idx < uuids.length) {
      const my = idx++;
      samples.push(await call('POST',
        `/api/v1/data/doc/export_order/${uuids[my]}/transition`,
        { target_status: 10, event: 'submit' }));
    }
  }
  const t0 = Date.now();
  await Promise.all(Array.from({ length: WRITE_CONCURRENCY }, worker));
  const wallSec = (Date.now() - t0) / 1000;
  return { docs: uuids.length, wall_s: +wallSec.toFixed(2), tps: +(uuids.length / wallSec).toFixed(1),
    transition: stats('transition(100→10 saga)', samples) };
}

(async () => {
  console.log(`基线压测开始: ${new Date().toISOString()} target=${BASE}`);
  const read = await phaseRead();
  const write = await phaseWrite();
  const report = { time: new Date().toISOString(), read, write };
  console.log(JSON.stringify(report, null, 2));
})().catch(e => { console.error('FATAL', e.message); process.exit(1); });
