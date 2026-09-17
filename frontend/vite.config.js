import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // Dev: forward API calls to Spring Boot on 8080.
      '/api': 'http://localhost:8080'
    }
  }
})
