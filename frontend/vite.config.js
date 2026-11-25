import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  // Electron 用 VITE_API_BASE 指向 http://127.0.0.1:<port>；
  // Web 形态留空，走相对路径 /personal，由 Spring Boot 同源托管。
  const apiBase = env.VITE_API_BASE || ''
  // 构建产物直接写进后端 static，Spring Boot 启动即可托管；
  // Electron 形态可改成 outDir: '../dist-electron' 再交给主进程 loadFile。
  const outDir = env.VITE_OUT_DIR || '../src/main/resources/static'

  return {
    base: './',
    plugins: [vue()],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
    },
    server: {
      port: 5173,
      proxy: {
        '/personal': {
          target: 'http://localhost:8080',
          changeOrigin: true
        }
      }
    },
    define: {
      __API_BASE__: JSON.stringify(apiBase)
    },
    build: {
      outDir,
      emptyOutDir: true
    }
  }
})
