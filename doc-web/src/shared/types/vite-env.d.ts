/// <reference types="vite/client" />

// UnoCSS 虚拟模块声明
declare module 'virtual:uno.css';

interface ImportMetaEnv {
  /** API 基础路径（经网关统一入口 /api/v1） */
  readonly VITE_API_BASE_URL: string;
}
