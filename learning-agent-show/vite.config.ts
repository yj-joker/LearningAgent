import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

declare const URL: {
  new (url: string, base?: string): { pathname: string }
}

declare global {
  interface ImportMeta {
    readonly url: string
  }
}

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      // Keep the alias configuration portable in environments that do not
      // install Node's ambient type declarations for the Vite config.
      '@': new URL('./src', import.meta.url).pathname,
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
      },
    },
  },
})
