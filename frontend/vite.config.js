import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: { outDir: 'dist' },
  server: {
    proxy: {
      // TODO: M5a 后端联调时改为真实后端地址
      '/api': 'http://localhost:8080'
    }
  }
})
