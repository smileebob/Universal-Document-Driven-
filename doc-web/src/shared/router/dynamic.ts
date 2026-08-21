// 动态路由注册（design/10 §2.2）：运行时仅注册 4 条带参模式路由，
// 由菜单加载成功后调用；组件全部懒加载（N-04）
import type { Router, RouteRecordRaw } from 'vue-router';

const BIZ_ROUTES: RouteRecordRaw[] = [
  {
    // P-RT-01 通用列表
    path: 'biz/:objType(entity|doc)/:code',
    name: 'biz-list',
    component: () => import('@/modules/runtime/views/GenericList.vue'),
    meta: { title: '数据列表' },
  },
  {
    // P-RT-02 通用表单（无 uuid=新增）
    path: 'biz/:objType(entity|doc)/:code/form/:uuid?',
    name: 'biz-form',
    component: () => import('@/modules/runtime/views/GenericForm.vue'),
    meta: { title: '数据表单' },
  },
  {
    // P-RT-03 单据详情（阶段二 DocDetail：状态时间线 + 流转按钮）
    path: 'biz/doc/:code/detail/:uuid',
    name: 'biz-doc-detail',
    component: () => import('@/modules/runtime/views/DocDetail.vue'),
    meta: { title: '单据详情' },
  },
  {
    // P-RT-04 实体详情
    path: 'biz/entity/:code/detail/:uuid',
    name: 'biz-entity-detail',
    component: () => import('@/modules/runtime/views/EntityDetail.vue'),
    meta: { title: '实体详情' },
  },
];

/** 幂等注册：挂载到布局壳（name=layout）之下 */
export function registerBizRoutes(router: Router): void {
  for (const route of BIZ_ROUTES) {
    if (route.name && !router.hasRoute(route.name)) {
      router.addRoute('layout', route);
    }
  }
}
