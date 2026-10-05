import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: Object.fromEntries(['/bff', '/oauth2', '/login', '/logout'].map((path) => [path, {
      target: process.env.VITE_CORE_ORIGIN ?? 'http://localhost:8080',
      changeOrigin: false,
    }])),
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    restoreMocks: true,
  },
});
