import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
      '@framework': path.resolve(__dirname, 'src/live2d/cubism-framework'),
    },
  },
  server: {
    host: '0.0.0.0',
    port: 8080,
    proxy: {
      '/api': {
        target: 'http://localhost:10086',
        changeOrigin: true,
      },
      '/py-api': {
        target: 'http://localhost:9000',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/py-api/, '/api'),
      },
      '/ws': {
        target: 'http://localhost:10086',
        ws: true,
        changeOrigin: true,
      },
    },
  },
})
