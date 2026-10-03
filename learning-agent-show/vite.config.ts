import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      // Keep the alias configuration portable in environments that do not
      // install Node's ambient type declarations for the Vite config.
      // 使用 fileURLToPath 处理 Windows 盘符，开发服务器和生产构建共用同一别名。
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/learning-agent': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/createChapters': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/getChaptersByCourseId': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/updateChapters': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/deleteChaptersByIds': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/knowledgePoints': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/knowledgePointRelations': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/knowledgeBase': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/document': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/agent': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // 同一个代理同时转发审批 HTTP 和 WebSocket 升级请求。
        ws: true,
      },
    },
  },
})
