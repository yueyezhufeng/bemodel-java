import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
const env = loadEnv(mode, process.cwd(), '')
return {
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: Number(env.VITE_PORT) || 18082,
    proxy: {
      '/api': {
        target: env.VITE_API_TARGET || 'http://127.0.0.1:18081',
        changeOrigin: true
      }
    }
  }
}
})
