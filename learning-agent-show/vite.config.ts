import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
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
      '/v3': {
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
    },
  },
})
