// schemaStore：实体/单据定义元数据缓存与「三层失效」（design/10 §三 / §6.2）
// ① 操作即时失效（本会话）：写操作成功后调用 invalidate(code)
// ② 版本号校验（跨会话主手段）：GET /menu/mounted 全局 schema_version 探针比对
// ③ TTL 30s 兜底：缓存条目过期自动重拉；前台每 30s 静默比对全局版本
import { defineStore } from 'pinia';
import { fetchMountedMenus, getDoc, getEntity } from '@/modules/config/api/schema';
import type { ObjType, SchemaDef } from '@/modules/config/types';
import { useMenuStore } from '@/shared/stores/menu';

const TTL = 30_000;

interface CacheEntry {
  schema: SchemaDef;
  version: number;
  fetchedAt: number;
}

export const useSchemaStore = defineStore('schema', () => {
  const cache = new Map<string, CacheEntry>();
  let globalVersion = 0;
  /**
   * 探针基线标记：首次探测仅建立基线不参与比对。
   * 用 probed 而非 globalVersion > 0 判断，保证全新环境
   * schema_version 从 0→1 的首次配置变更也能触发缓存失效。
   */
  let probed = false;
  let probeTimer: ReturnType<typeof setInterval> | null = null;

  const key = (objType: ObjType, code: string) => `${objType}:${code}`;

  /** 读取定义（TTL 命中直接返回；force 跳过缓存） */
  async function getSchema(objType: ObjType, code: string, force = false): Promise<SchemaDef> {
    const k = key(objType, code);
    const hit = cache.get(k);
    if (!force && hit && Date.now() - hit.fetchedAt < TTL) {
      return hit.schema;
    }
    const schema: SchemaDef =
      objType === 'entity' ? await getEntity(code) : await getDoc(code);
    cache.set(k, { schema, version: schema.version ?? 0, fetchedAt: Date.now() });
    return schema;
  }

  /** ① 即时失效：传 code 删对应条目；不传则清空全部 */
  function invalidate(code?: string): void {
    if (!code) {
      cache.clear();
      return;
    }
    for (const k of [...cache.keys()]) {
      if (k.endsWith(`:${code}`)) cache.delete(k);
    }
  }

  /** ② 全局版本探针：schema_version 变化则清空缓存并通知菜单失效 */
  async function checkGlobalVersion(): Promise<void> {
    try {
      const res = await fetchMountedMenus();
      const changed = probed && res.schema_version !== globalVersion;
      probed = true;
      globalVersion = res.schema_version;
      const menuStore = useMenuStore();
      if (changed) {
        cache.clear();
        menuStore.invalidate();
      }
      menuStore.schemaVersion = res.schema_version;
    } catch {
      // 静默探针：失败不打扰用户，TTL 兜底仍有效
    }
  }

  /** ③ TTL 兜底轮询：应用前台停留期间每 30s 静默比对一次全局版本 */
  function startVersionProbe(): void {
    if (probeTimer) return;
    probeTimer = setInterval(() => {
      if (document.visibilityState === 'visible') {
        void checkGlobalVersion();
      }
    }, TTL);
  }

  return { getSchema, invalidate, checkGlobalVersion, startVersionProbe };
});
