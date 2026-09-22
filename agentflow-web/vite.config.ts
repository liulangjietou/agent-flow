import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const backendTarget = 'http://127.0.0.1:8080'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: { '/api': { target: backendTarget, changeOrigin: false } }
  },
  preview: {
    port: 4173,
    proxy: { '/api': { target: backendTarget, changeOrigin: false } }
  }
})
