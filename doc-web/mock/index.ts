// vite dev mock 插件：拦截 /api/v1/**，按 prd/11 契约返回统一响应结构。
// 仅 dev 生效（configureServer），build 不打包；后端就绪后删除本目录即可切换真实网关。
import type { Plugin } from 'vite';
import type { ServerResponse, IncomingMessage } from 'node:http';
import {
  bumpSchemaVersion,
  docs,
  entities,
  newUuid,
  records,
  schemaVersion,
} from './db';
import type { MockDocSchema, MockEntitySchema, MockRecord } from './db';

interface DslFilter {
  field: string;
  op: string;
  value?: unknown;
}

interface DslBody {
  target: 'entity_data' | 'doc_data';
  filters: DslFilter[];
  sort?: { field: string; order: 'asc' | 'desc' }[];
  page: { offset?: number; limit: number; token?: string };
  aggregations?: { name: string; type: string; field: string }[];
}

let traceSeq = 0;
function traceId(): string {
  traceSeq += 1;
  return `mock-trace-${Date.now()}-${traceSeq}`;
}

function send(res: ServerResponse, http: number, body: unknown): void {
  res.statusCode = http;
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.end(JSON.stringify(body));
}

function ok(res: ServerResponse, data: unknown): void {
  send(res, 200, { code: 0, message: 'ok', data, trace_id: traceId() });
}

function fail(res: ServerResponse, http: number, code: number, message: string, data?: unknown): void {
  send(res, http, { code, message, data, trace_id: traceId() });
}

function readBody(req: IncomingMessage): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    let raw = '';
    req.on('data', (c: Buffer) => {
      raw += c.toString('utf8');
    });
    req.on('end', () => {
      if (!raw) {
        resolve({});
        return;
      }
      try {
        resolve(JSON.parse(raw) as Record<string, unknown>);
      } catch (e) {
        reject(e);
      }
    });
    req.on('error', reject);
  });
}

function isEmpty(v: unknown): boolean {
  return v === undefined || v === null || v === '';
}

/** 已挂载菜单（实体 + 单据，status=1 且 menu.enabled） */
function mountedMenus(): { items: unknown[]; schema_version: number } {
  const items: unknown[] = [];
  for (const s of entities.values()) {
    if (s.status === 1 && s.menu?.enabled) {
      items.push({
        code: s.entity_code,
        obj_type: 'entity',
        menu_name: s.menu.menu_name || s.entity_name,
        group_code: s.menu.group_code ?? '',
        sort_order: s.menu.sort_order ?? 0,
        icon: s.menu.icon,
      });
    }
  }
  for (const s of docs.values()) {
    if (s.status === 1 && s.menu?.enabled) {
      items.push({
        code: s.doc_code,
        obj_type: 'doc',
        menu_name: s.menu.menu_name || s.doc_name,
        group_code: s.menu.group_code ?? '',
        sort_order: s.menu.sort_order ?? 0,
        icon: s.menu.icon,
      });
    }
  }
  return { items, schema_version: schemaVersion };
}

// ---------- 定义（schema）相关 ----------
function handleEntityList(res: ServerResponse, q: URLSearchParams): void {
  let list = [...entities.values()];
  const keyword = q.get('keyword') ?? '';
  const type = q.get('entity_type') ?? '';
  const status = q.get('status') ?? '';
  if (keyword) {
    list = list.filter((s) => s.entity_name.includes(keyword) || s.entity_code.includes(keyword));
  }
  if (type) list = list.filter((s) => s.entity_type === type);
  if (status) list = list.filter((s) => String(s.status) === status);
  const offset = Number(q.get('offset') ?? 0);
  const limit = Number(q.get('limit') ?? 20);
  ok(res, { total: list.length, items: list.slice(offset, offset + limit) });
}

function checkUnique(code: string, schema: MockEntitySchema | MockDocSchema, attrs: Record<string, unknown>, excludeUuid?: string): { field: string; message: string } | null {
  const rows = records.get(code)?.filter((r) => r.status !== 0) ?? [];
  for (const a of schema.attributes) {
    if (!a.unique) continue;
    const v = attrs[a.code];
    if (isEmpty(v)) continue;
    const dup = rows.find((r) => r.attributes[a.code] === v && r.uuid !== excludeUuid);
    if (dup) return { field: a.code, message: `『${a.name}』的值已存在（唯一约束）` };
  }
  return null;
}

function checkRequired(schema: MockEntitySchema | MockDocSchema, attrs: Record<string, unknown>): { field: string; message: string }[] {
  return schema.attributes
    .filter((a) => a.required && a.data_type !== 'table' && isEmpty(attrs[a.code]))
    .map((a) => ({ field: a.code, message: `『${a.name}』为必填项` }));
}

// ---------- 数据（data）相关 ----------
function fieldOf(r: MockRecord, field: string): unknown {
  if (field.startsWith('attributes.')) return r.attributes[field.slice(11)];
  return (r as unknown as Record<string, unknown>)[field];
}

function matchFilter(r: MockRecord, f: DslFilter): boolean {
  const v = fieldOf(r, f.field);
  switch (f.op) {
    case 'eq':
      return v === f.value;
    case 'in':
      return Array.isArray(f.value) && (f.value as unknown[]).includes(v);
    case 'gte':
      return Number(v) >= Number(f.value);
    case 'lte':
      return Number(v) <= Number(f.value);
    case 'gt':
      return Number(v) > Number(f.value);
    case 'lt':
      return Number(v) < Number(f.value);
    case 'contains':
      return !isEmpty(v) && String(v).includes(String(f.value));
    case 'prefix':
      return !isEmpty(v) && String(v).startsWith(String(f.value));
    case 'exists':
      return !isEmpty(v);
    default:
      return true;
  }
}

function handleQuery(res: ServerResponse, body: Record<string, unknown>): void {
  const dsl = body as unknown as DslBody;
  const isDoc = dsl.target === 'doc_data';
  const codeFilter = dsl.filters.find((f) => f.field === (isDoc ? 'doc_code' : 'entity_code'));
  const code = String(codeFilter?.value ?? '');
  let rows = records.get(code) ?? [];
  if (!isDoc) rows = rows.filter((r) => r.status === 1);

  // 聚合在「除状态筛选外」的过滤结果上计算，保证状态分布条完整（design/10 §4.1）
  const nonStatusFilters = dsl.filters.filter(
    (f) => f.field !== 'entity_code' && f.field !== 'doc_code' && f.field !== 'current_status',
  );
  const baseRows = rows.filter((r) => nonStatusFilters.every((f) => matchFilter(r, f)));
  const filtered = baseRows.filter((r) =>
    dsl.filters.filter((f) => f.field === 'current_status').every((f) => matchFilter(r, f)),
  );

  // 排序（默认 create_time 倒序）
  const sort = dsl.sort?.[0] ?? { field: 'create_time', order: 'desc' as const };
  const sorted = filtered.slice().sort((a, b) => {
    const av = fieldOf(a, sort.field);
    const bv = fieldOf(b, sort.field);
    const cmp = Number(av ?? 0) - Number(bv ?? 0) || String(av ?? '').localeCompare(String(bv ?? ''));
    return sort.order === 'asc' ? cmp : -cmp;
  });

  // 分页：offset/token 互斥（prd/11 §一）
  const limit = Math.min(dsl.page.limit, 100);
  const start = dsl.page.token ? Number(dsl.page.token.replace('mock:', '')) : (dsl.page.offset ?? 0);
  const items = sorted.slice(start, start + limit);
  const result: Record<string, unknown> = { total: sorted.length, items };
  if (start + limit < sorted.length) result.next_token = `mock:${start + limit}`;

  if (dsl.aggregations?.length) {
    const agg: Record<string, { key: string | number; count: number }[]> = {};
    for (const a of dsl.aggregations) {
      if (a.type === 'group_by') {
        const counter = new Map<string | number, number>();
        for (const r of baseRows) {
          const key = fieldOf(r, a.field) as string | number;
          counter.set(key, (counter.get(key) ?? 0) + 1);
        }
        agg[a.name] = [...counter.entries()].map(([key, count]) => ({ key, count }));
      }
    }
    result.aggregations = agg;
  }
  ok(res, result);
}

function docDefaultStatus(schema: MockDocSchema): number {
  return schema.states.find((s) => s.is_default)?.value ?? schema.states[0]?.value ?? 100;
}

function findDocState(schema: MockDocSchema, value: number): MockDocSchema['states'][number] | undefined {
  return schema.states.find((s) => s.value === value);
}

// ---------- 路由分发 ----------
export function mockApiPlugin(): Plugin {
  return {
    name: 'doc-web-mock-api',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use(async (req, res, next) => {
        const url = new URL(req.url ?? '/', 'http://mock.local');
        if (!url.pathname.startsWith('/api/v1/')) {
          next();
          return;
        }
        const seg = url.pathname.slice('/api/v1/'.length).split('/').filter(Boolean);
        const method = req.method ?? 'GET';
        try {
          await dispatch(req, res, method, seg, url.searchParams);
        } catch {
          fail(res, 500, 50000, 'mock 服务内部错误');
        }
      });
    },
  };
}

async function dispatch(
  req: IncomingMessage,
  res: ServerResponse,
  method: string,
  seg: string[],
  q: URLSearchParams,
): Promise<void> {
  // ---- 菜单 ----
  if (seg[0] === 'menu' && seg[1] === 'mounted' && method === 'GET') {
    ok(res, mountedMenus());
    return;
  }

  // ---- 统一查询 ----
  if (seg[0] === 'query' && method === 'POST') {
    handleQuery(res, await readBody(req));
    return;
  }

  // ---- 定义（schema） ----
  if (seg[0] === 'schema') {
    const kind = seg[1]; // entity | doc
    const code = seg[2];

    // GET/PUT /schema/{objType}/{code}/menu（独立挂载接口，snake_case）
    if (seg[3] === 'menu' && kind && code && (kind === 'entity' || kind === 'doc')) {
      const store = kind === 'doc' ? docs : entities;
      const schema = store.get(code);
      if (!schema) return fail(res, 404, 40401, '定义不存在');
      if (method === 'GET') {
        const menu = schema.menu;
        return ok(res, {
          target_type: kind,
          target_code: code,
          menu_name: menu?.menu_name ?? null,
          icon: menu?.icon ?? null,
          group_code: menu?.group_code ?? null,
          sort_order: menu?.sort_order ?? 0,
          enabled: menu?.enabled ?? false,
        });
      }
      if (method === 'PUT') {
        const body = await readBody(req);
        const menuName = String(body.menu_name ?? '').trim();
        if (!menuName) return fail(res, 400, 40001, 'menu_name 不能为空');
        schema.menu = {
          enabled: !!body.enabled,
          menu_name: menuName,
          icon: body.icon as string | undefined,
          group_code: body.group_code as string | undefined,
          sort_order: Number(body.sort_order ?? 0),
        };
        schema.update_time = Date.now();
        bumpSchemaVersion();
        return ok(res, null);
      }
    }

    if (kind === 'doc') {
      if (method === 'GET' && code) {
        const s = docs.get(code);
        return s ? ok(res, s) : fail(res, 404, 40401, '单据定义不存在');
      }
      return fail(res, 404, 40404, '接口不存在');
    }

    if (kind === 'entity') {
      // GET /schema/entity（列表）
      if (!code && method === 'GET') return handleEntityList(res, q);
      // POST /schema/entity（新建）
      if (!code && method === 'POST') {
        const body = (await readBody(req)) as unknown as MockEntitySchema;
        if (entities.has(body.entity_code)) {
          return fail(res, 400, 40005, '实体编码已存在', {
            field_errors: [{ field: 'entity_code', message: '实体编码已存在' }],
          });
        }
        body.status = 100;
        body.version = 1;
        body.creator = 'mock_admin';
        body.create_time = Date.now();
        body.update_time = Date.now();
        entities.set(body.entity_code, body);
        records.set(body.entity_code, []);
        bumpSchemaVersion();
        return ok(res, body);
      }
      const schema = entities.get(code);
      if (!schema) return fail(res, 404, 40401, '实体定义不存在');

      if (method === 'GET' && !seg[3]) return ok(res, schema);
      if (method === 'PUT' && !seg[3]) {
        const body = (await readBody(req)) as unknown as MockEntitySchema;
        Object.assign(schema, body, { entity_code: code, version: schema.version + 1, update_time: Date.now() });
        bumpSchemaVersion();
        return ok(res, schema);
      }
      if (method === 'DELETE' && !seg[3]) {
        entities.delete(code);
        records.delete(code);
        bumpSchemaVersion();
        return ok(res, null);
      }
      // POST /schema/entity/{code}/publish
      if (method === 'POST' && seg[3] === 'publish') {
        const reasons: string[] = [];
        if (schema.attributes.length === 0) reasons.push('至少需要定义一个属性');
        if (!schema.attributes.some((a) => a.is_list_column)) reasons.push('至少需要一个属性勾选「列表列」');
        if (reasons.length) return fail(res, 400, 40011, '发布校验未通过', { reasons });
        schema.status = 1;
        schema.version += 1;
        schema.update_time = Date.now();
        bumpSchemaVersion();
        return ok(res, schema);
      }
      // POST /schema/entity/{code}/disable
      if (method === 'POST' && seg[3] === 'disable') {
        schema.status = 0;
        schema.update_time = Date.now();
        bumpSchemaVersion();
        return ok(res, schema);
      }
    }
    return fail(res, 404, 40404, '接口不存在');
  }

  // ---- 运行时数据（data） ----
  if (seg[0] === 'data') {
    const code = seg[1];
    const uuid = seg[2];
    const isDoc = docs.has(code);
    const schema = isDoc ? docs.get(code) : entities.get(code);
    if (!schema) return fail(res, 404, 40401, '定义不存在或未发布');
    const rows = records.get(code) ?? [];

    // POST /data/{code}（新增）
    if (!uuid && method === 'POST') {
      const body = await readBody(req);
      const attrs = (body.attributes ?? {}) as Record<string, unknown>;
      const requiredErrors = checkRequired(schema, attrs);
      if (requiredErrors.length) return fail(res, 400, 40001, '必填项缺失', { field_errors: requiredErrors });
      const uniqueError = checkUnique(code, schema, attrs);
      if (uniqueError) return fail(res, 400, 40005, '唯一约束冲突', { field_errors: [uniqueError] });
      const record: MockRecord = {
        uuid: newUuid(),
        attributes: attrs,
        creator: 'mock_user',
        create_time: Date.now(),
        update_time: Date.now(),
      };
      if (isDoc) {
        const initStatus = docDefaultStatus(schema as MockDocSchema);
        record.current_status = initStatus;
        record.status_history = [
          { to_status: initStatus, operator: record.creator ?? 'mock_user', time: record.create_time ?? Date.now(), remark: '创建暂存' },
        ];
      } else {
        record.status = 1;
      }
      rows.push(record);
      records.set(code, rows);
      return ok(res, record);
    }

    const record = rows.find((r) => r.uuid === uuid);
    if (!record || (!isDoc && record.status === 0)) {
      return fail(res, 404, 40401, '数据不存在或已被删除');
    }

    // GET /data/{code}/{uuid}（点查，写后读一致性）
    if (method === 'GET' && !seg[3]) return ok(res, record);

    // PUT /data/{code}/{uuid}（修改）
    if (method === 'PUT' && !seg[3]) {
      if (isDoc) {
        const state = findDocState(schema as MockDocSchema, record.current_status ?? 0);
        if (!state?.is_editable) return fail(res, 409, 40901, '当前状态不允许修改');
      }
      const body = await readBody(req);
      const attrs = (body.attributes ?? {}) as Record<string, unknown>;
      const uniqueError = checkUnique(code, schema, attrs, record.uuid);
      if (uniqueError) return fail(res, 400, 40005, '唯一约束冲突', { field_errors: [uniqueError] });
      record.attributes = attrs;
      record.update_time = Date.now();
      return ok(res, record);
    }

    // DELETE /data/{code}/{uuid}
    if (method === 'DELETE' && !seg[3]) {
      if (isDoc && record.current_status !== 100) {
        return fail(res, 409, 40901, '仅暂存状态的单据可删除');
      }
      if (isDoc) {
        records.set(code, rows.filter((r) => r.uuid !== uuid));
      } else {
        record.status = 0; // 实体数据逻辑删除
      }
      return ok(res, null);
    }

    // GET /data/{code}/{uuid}/history
    if (method === 'GET' && seg[3] === 'history') {
      return ok(res, record.status_history ?? []);
    }

    // POST /data/{code}/{uuid}/transition
    if (method === 'POST' && seg[3] === 'transition') {
      if (!isDoc) return fail(res, 400, 40002, '实体数据不支持状态流转');
      const body = await readBody(req);
      const target = Number(body.target_status);
      const docSchema = schema as MockDocSchema;
      if (!findDocState(docSchema, target)) return fail(res, 400, 40002, '目标状态不存在');
      // mock 规则示例（对齐 prd 规则失败样例）：销售订单金额 > 50000 提交触发规则拦截
      if (code === 'sales_order' && target === 10 && Number(record.attributes.total_amount ?? 0) > 50000) {
        return fail(res, 422, 42201, '规则校验未通过', {
          failed_rules: [{ rule_name: '大额订单审核规则', reason: '订单总额超过 50,000，须先经主管审核' }],
        });
      }
      const from = record.current_status ?? 0;
      record.current_status = target;
      record.update_time = Date.now();
      record.status_history = [
        ...(record.status_history ?? []),
        { from_status: from, to_status: target, operator: 'mock_user', time: Date.now() },
      ];
      return ok(res, { uuid: record.uuid, from_status: from, to_status: target });
    }
    return fail(res, 404, 40404, '接口不存在');
  }

  fail(res, 404, 40404, '接口不存在');
}
