// 可观测性闭环验证（任务 #20 步骤 41 正式化）：trace_id 抽样 + Prometheus ddp_* 指标
// 任务 #20（步骤 41）：trace_id 闭环抽样 + Prometheus 自定义指标验证
const BASE_GW = 'http://localhost:8080';

async function j(method, path, body, headers = {}) {
  const r = await fetch(BASE_GW + path, {
    method,
    headers: { 'Content-Type': 'application/json', 'X-User-Id': 'platform_admin', ...headers },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await r.text();
  let json = null;
  try { json = JSON.parse(text); } catch (e) { /* non-json */ }
  return { status: r.status, headers: r.headers, json, text };
}

(async () => {
  const report = { trace: {}, prometheus: {} };

  // 1) 网关入口：带/不带 X-Trace-Id 的响应头回写
  const withHeader = await j('POST', '/api/v1/query',
    { target: 'doc_data', filters: [{ field: 'doc_code', op: 'eq', value: 'export_order' }], page: { limit: 5 } },
    { 'X-Trace-Id': 'e2e-trace-observ-001' });
  report.trace.responseHeaderEcho = withHeader.headers.get('x-trace-id');
  report.trace.queryTotal = withHeader.json && withHeader.json.data && withHeader.json.data.total;
  report.trace.apiResponseTraceId = withHeader.json && withHeader.json.trace_id;

  const noHeader = await j('POST', '/api/v1/query',
    { target: 'doc_data', filters: [], page: { limit: 1 } });
  report.trace.generatedHeader = noHeader.headers.get('x-trace-id');

  // 2) 审计查询：AuditQueryController（GET /api/v1/audit）EO1 流转审计应带 trace_id
  const audit = await j('GET', '/api/v1/audit?operation=transition&limit=5');
  const items = (audit.json && audit.json.data && audit.json.data.items) || [];
  report.trace.auditRows = items.length;
  report.trace.auditTraceIdSample = items.slice(0, 3).map(it =>
    (it.attributes && (it.attributes.trace_id)) || it.trace_id || null);

  // 3) Prometheus：三服务 ddp_* 指标抽样
  for (const [name, port] of [['gateway', 8080], ['meta', 8081], ['runtime', 8082]]) {
    try {
      const r = await fetch('http://localhost:' + port + '/actuator/prometheus');
      const text = await r.text();
      const names = [...new Set((text.match(/^ddp_[a-z_]+/gm) || []).map(l => l.replace(/^(ddp_[a-z_]+).*/, '$1')))].sort();
      const sample = {};
      for (const key of ['ddp_tablestore_operation_seconds_count', 'ddp_tablestore_operation_total',
        'ddp_cache_get_total', 'ddp_outbox_pending', 'ddp_outbox_relay_total',
        'ddp_saga_execution_total', 'ddp_saga_dead_letter_total', 'ddp_transition_total',
        'http_server_requests_seconds_count']) {
        const lines = text.split('\n').filter(l => l.startsWith(key));
        sample[key] = lines.length ? lines.slice(0, 2) : null;
      }
      report.prometheus[name] = { metricNames: names, sample };
    } catch (e) {
      report.prometheus[name] = { error: e.message };
    }
  }
  console.log(JSON.stringify(report, null, 2));
})();
