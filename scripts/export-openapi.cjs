// 任务 #20（步骤 6）：导出三服务 springdoc openapi.json 快照归档 docs/openapi/
// 用法：三服务运行中时执行 node scripts/export-openapi.cjs
const fs = require('fs');
const path = require('path');
const OUT = path.join('d:', 'work', '自有项目', 'Document-Driven', 'doc-platform-parent', 'docs', 'openapi');
fs.mkdirSync(OUT, { recursive: true });

const services = [
  ['gateway', 'http://localhost:8080'],
  ['meta-service', 'http://localhost:8081'],
  ['runtime-service', 'http://localhost:8082'],
];

(async () => {
  let failed = 0;
  for (const [name, base] of services) {
    try {
      const r = await fetch(base + '/v3/api-docs');
      if (!r.ok) throw new Error('HTTP ' + r.status);
      const json = await r.json();
      const file = path.join(OUT, name + '-openapi.json');
      fs.writeFileSync(file, JSON.stringify(json, null, 2));
      console.log(`OK ${name}: paths=${Object.keys(json.paths || {}).length} -> ${file}`);
    } catch (e) {
      failed++;
      console.error(`FAIL ${name}: ${e.message}`);
    }
  }
  process.exit(failed === 0 ? 0 : 1);
})();
