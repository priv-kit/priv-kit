import { defineConfig } from 'vite';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [tailwindcss()],
  optimizeDeps: { exclude: ['priv-playground'] },
  server: {
    host: '127.0.0.1',
    port: 8020,
  },
});
