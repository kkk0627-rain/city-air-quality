import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 开发服务器：5173 端口。
 * /api 代理到后端 8080，这样前端代码里可以用相对路径发请求，
 * 不依赖后端 CORS 配置也能联调；CORS 配置作为直连场景的兜底仍然保留。
 */
export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
        // 后端接口路径即为 /api/**，无需 rewrite
      }
    }
  },
  build: {
    outDir: 'dist',
    // ECharts 体积较大，单独拆包便于浏览器缓存
    rollupOptions: {
      output: {
        manualChunks: {
          echarts: ['echarts'],
          vue: ['vue']
        }
      }
    }
  }
})
