/// <reference types="vite/client" />

// UnoCSS 虚拟模块声明
declare module 'virtual:uno.css';

interface ImportMetaEnv {
  /** API 基础路径（经网关统一入口 /api/v1） */
  readonly VITE_API_BASE_URL: string;
  /** 演示登录开关：显式 'false' 关闭；缺省/其他值仅用于本地演示 */
  readonly VITE_DEMO_LOGIN?: string;
}
