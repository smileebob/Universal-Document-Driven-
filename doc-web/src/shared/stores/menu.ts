// menuStore：动态菜单缓存与失效（design/10 §2.1；prd/06 第三节）
// 菜单由「菜单挂载配置」（EC-05）产生，GET /menu/mounted 拉取，按 group_code 分组。
import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { fetchMountedMenus } from '@/modules/config/api/schema';
import type { MenuGroupCode, MenuItem, MountedMenuResult } from '@/modules/config/types';
import { MENU_GROUP_LABELS } from '@/modules/config/types';

export interface MenuGroup {
  code: MenuGroupCode | '';
  label: string;
  items: MenuItem[];
}

const GROUP_ORDER: (MenuGroupCode | '')[] = ['basic_data', 'biz_doc', 'ops', ''];

export const useMenuStore = defineStore('menu', () => {
  const menus = ref<MenuItem[]>([]);
  /** 全局 schema_version（prd/11 §一，版本探针比对用） */
  const schemaVersion = ref(0);
  const loaded = ref(false);
  /** dirty=true 时下次进入重拉（配置侧发布/取消挂载成功后置位） */
  const dirty = ref(true);
  const loading = ref(false);
  /** 菜单接口加载失败标记：降级展示错误提示而非无限重定向 */
  const loadError = ref(false);

  async function fetchMenus(force = false): Promise<void> {
    if (loading.value) return;
    if (loaded.value && !dirty.value && !force) return;
    loading.value = true;
    loadError.value = false;
    try {
      const res = await fetchMountedMenus();
      applyMounted(res);
    } catch (err) {
      // 降级：置 loaded=true 打破守卫重入循环，由布局壳展示错误提示与重试
      loadError.value = true;
      loaded.value = true;
      dirty.value = false;
      console.error('[menu] 菜单加载失败', err);
    } finally {
      loading.value = false;
    }
  }

  /** 菜单加载失败后的重试入口 */
  function retryMenus(): Promise<void> {
    dirty.value = true;
    loadError.value = false;
    return fetchMenus(true);
  }

  /** 写入远端菜单数据（schemaStore 版本探针命中时亦复用） */
  function applyMounted(res: MountedMenuResult): void {
    menus.value = res.items;
    schemaVersion.value = res.schema_version;
    loaded.value = true;
    dirty.value = false;
  }

  /** 配置侧发布/取消挂载成功后调用（prd/06：下一次进入或刷新时更新） */
  function invalidate(): void {
    dirty.value = true;
  }

  /** 按 group_code 分组 + sort_order 排序，驱动侧边栏渲染 */
  const groupedMenus = computed<MenuGroup[]>(() => {
    const groups = new Map<MenuGroupCode | '', MenuItem[]>();
    for (const item of menus.value) {
      const key = item.group_code || '';
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key)!.push(item);
    }
    return GROUP_ORDER.filter((code) => groups.has(code)).map((code) => ({
      code,
      label: code ? MENU_GROUP_LABELS[code] : '其他',
      items: groups.get(code)!.slice().sort((a, b) => a.sort_order - b.sort_order),
    }));
  });

  /** 菜单项 to 地址：/biz/{objType}/{code}（design/10 §2.2） */
  function menuPath(item: MenuItem): string {
    return `/biz/${item.obj_type}/${item.code}`;
  }

  return {
    menus,
    schemaVersion,
    loaded,
    dirty,
    loading,
    loadError,
    groupedMenus,
    fetchMenus,
    retryMenus,
    applyMounted,
    invalidate,
    menuPath,
  };
});
