import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    host: true, // listen on LAN so the app is reachable from the phone
    proxy: {
      '/api': 'http://localhost:8081',
    },
  },
})
