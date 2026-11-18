import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The backend runs on this PC at :8080. Proxying /api through Vite means a phone
// on the same Wi-Fi only needs to reach http://<PC-IP>:5173 and needs no CORS.
// The browser's Origin header (e.g. http://192.168.1.5:5173) is dropped, otherwise
// Spring's CORS check rejects every POST/PUT from anything but localhost.
//
// Dev-only slow mode for testing: `SLOW_API=1500 npm run dev` (PowerShell:
// `$env:SLOW_API=1500; npm run dev`) holds every API response for 1.5 s.
const slowMs = Number(process.env.SLOW_API) || 0;
const api = {
  '/api': {
    target: 'http://localhost:8080',
    changeOrigin: true,
    configure: (proxy) => {
      proxy.on('proxyReq', (proxyReq) => proxyReq.removeHeader('origin'));
      if (slowMs) {
        proxy.on('proxyRes', (proxyRes) => {
          proxyRes.pause();
          setTimeout(() => proxyRes.resume(), slowMs);
        });
      }
    },
  },
};

// Readable chunk names: customer / store route groups, and "shared" for code both use.
function chunkName(chunk) {
  const ids = (chunk.moduleIds || []).map((id) => id.split(String.fromCharCode(92)).join('/'));
  if (ids.some((id) => id.endsWith('/src/customer/index.jsx'))) return 'customer';
  if (ids.some((id) => id.endsWith('/src/store/index.jsx'))) return 'store';
  if (chunk.isDynamicEntry) return chunk.name;
  return 'shared';
}

export default defineConfig({
  plugins: [react()],
  // allowedHosts: let a Cloudflare quick tunnel (https://<random>.trycloudflare.com) through.
  server: { host: true, port: 5173, strictPort: true, proxy: api, allowedHosts: ['.trycloudflare.com'] },
  preview: { host: true, port: 4173, proxy: api, allowedHosts: ['.trycloudflare.com'] },
  build: {
    target: 'es2019',
    rollupOptions: {
      output: { chunkFileNames: (chunk) => `assets/${chunkName(chunk)}-[hash].js` },
    },
  },
});
