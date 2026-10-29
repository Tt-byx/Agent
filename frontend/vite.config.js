import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  server: {
    host: '0.0.0.0',
    // 2026-10-29：前端 dev server 用 5173（Vite 默认值），**后端是 10086**。
    // 原先这里是 8080 —— 那个数字太容易被误认成后端端口（Spring Boot 的默认值也是 8080），
    // 排查问题时第一反应会去 8080 找后端，实际后端在 10086、8080 只是前端。
    // 换回 Vite 默认端口，两个角色在数字上就分得开。
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:10086',
        changeOrigin: true,
      },
      // 2026-10-29 清理：
      //   /py-api  → Python 后端已退役（P2 阶段迁移到 JVM），前端也没有任何地方调用它
      //   /ws      → WebSocket 链路已整体删除（D30），对话只剩 /api/chat 与 /api/chat/stream
      // 死代理比没有代理更误导：看起来有个后端在 9000 端口，实际请求会 502。
    },
  },
})
