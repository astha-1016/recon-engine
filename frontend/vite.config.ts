import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// In dev the browser talks to Vite, which forwards /api to the Spring Boot backend.
export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy: { '/api': 'http://localhost:8080' } },
})
