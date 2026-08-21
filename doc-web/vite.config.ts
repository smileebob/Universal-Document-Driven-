import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';
import AutoImport from 'unplugin-auto-import/vite';
import Components from 'unplugin-vue-components/vite';
import { AntDesignVueResolver } from 'unplugin-vue-components/resolvers';
import UnoCSS from 'unocss/vite';
import { fileURLToPath, URL } from 'node:url';
import { mockApiPlugin } from './mock';

export default defineConfig(({ mode }) => {
  // mock 开关：仅 dev 且 VITE_USE_MOCK=true 时启用（默认关，请求经 /api 代理直达网关）
  const useMock = loadEnv(mode, process.cwd(), '').VITE_USE_MOCK === 'true';
  return {
  plugins: [
    vue(),
    AutoImport({
      resolvers: [AntDesignVueResolver({ importStyle: false, resolveIcons: true })],
    }),
    Components({
      resolvers: [AntDesignVueResolver({ importStyle: false, resolveIcons: true })],
    }),
    UnoCSS(),
    // 后端未就绪期间：dev 本地 mock（仅 serve 生效，apply:'serve'）；
    // E2E 联调时置 VITE_USE_MOCK=true 以外默认关闭，请求直达网关
    ...(useMock ? [mockApiPlugin()] : []),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    proxy: {
      // 测试辅助：Saga 事务 Mock 桩位于 runtime-service（8082），网关未暴露 /mock/tx 路由，
      // dev 联调直连 runtime（P-TX-01 Mock 桩故障注入面板）
      '/api/v1/mock': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
      // Spring Cloud Gateway 网关端口（mock 中间件先于代理拦截 /api/v1，
      // 后端就绪后可删除 mock 插件，请求经此代理直达网关）
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    // 规范 N-08：单 chunk ≤500KB
    chunkSizeWarningLimit: 500,
    rollupOptions: {
      output: {
        manualChunks: {
          'vendor-vue': ['vue', 'vue-router', 'pinia'],
          'vendor-antd': ['ant-design-vue', '@ant-design/icons-vue', 'dayjs'],
          'vendor-axios': ['axios'],
          // G6 状态机图独立分包（阶段二 P-DC-SM；路由级懒加载按需拉取）
          'vendor-g6': ['@antv/g6'],
        },
      },
    },
  },
  };
});
