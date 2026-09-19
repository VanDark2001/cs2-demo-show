// Vite 构建配置：启用 Vue，并在本地开发时把 /api 转发给 Spring Boot。
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': 'http://localhost:8090'
    }
  }
})
