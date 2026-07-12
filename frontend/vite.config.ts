import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

/**
 * The SPA always calls the API same-origin under /api (07b: same-origin beats
 * CORS wildcards; no Access-Control-Allow-* needed anywhere).
 *
 * - Production: static files are served behind the same Traefik host that
 *   routes /api/* to the backend services (see deploy/nginx.conf).
 * - Development: this proxy forwards /api to a real backend. The target is a
 *   public URL, not a secret — VITE_* vars are inlined into the JS bundle and
 *   must never hold credentials (07b: frontend env vars are public).
 */
const STRICT_CSP = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self'",
  "connect-src 'self'",
  "img-src 'self' data:",
  "font-src 'self'",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
].join('; ')

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const proxyTarget = env.VITE_DEV_PROXY_TARGET ?? 'https://eurotransit.vojtechn.dev'
  const proxy = {
    '/api': { target: proxyTarget, changeOrigin: true },
  }

  return {
    plugins: [react(), tailwindcss()],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    server: {
      proxy,
      headers: {
        // Dev server needs inline scripts/styles for HMR, so the strict policy
        // runs report-only here (07b rollout strategy). nginx enforces it in
        // production; `vite preview` enforces it on the real build below.
        'Content-Security-Policy-Report-Only': STRICT_CSP,
        'X-Content-Type-Options': 'nosniff',
      },
    },
    preview: {
      proxy,
      headers: {
        'Content-Security-Policy': STRICT_CSP,
        'X-Content-Type-Options': 'nosniff',
        'Referrer-Policy': 'strict-origin-when-cross-origin',
      },
    },
  }
})
