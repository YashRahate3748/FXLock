import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  base: '/dashboard/',
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api/dashboard': { target: `http://127.0.0.1:${process.env.FXLOCK_SETTLEMENT_PORT || 8083}` },
      '/dashboard/login': { target: `http://127.0.0.1:${process.env.FXLOCK_SETTLEMENT_PORT || 8083}` },
    },
  },
})
