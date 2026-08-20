import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // 5173 is Vite's default. Pinned with strictPort because the backend's WebSocket
    // allowed-origins list names this exact port (WebSocketConfig). If 5173 is busy,
    // Vite's normal behaviour is to move to 5174 and say so in one line of startup
    // output - and the app would then load fine and fail only at the handshake, which
    // reads as a broken backend rather than a changed port. Fail loudly instead.
    port: 5173,
    strictPort: true,

    // Every call the browser makes is same-origin against this dev server, which is
    // forwarded to Spring on 8080. That is what makes CORS a non-problem rather than a
    // configured one: there is no cross-origin request to permit, so the backend needs
    // no addCorsMappings, no preflight handling, and no second origin list to keep in
    // sync with the WebSocket one. See docs/frontend.md for the full argument.
    //
    // This does NOT remove the WebSocket allowed-origins entry. The proxy forwards the
    // browser's Origin header on the upgrade, so Spring still sees an origin and still
    // checks it. Both possible values are already listed: http://localhost:5173 if the
    // header is forwarded untouched, http://localhost:8080 if the proxy rewrites it to
    // the target. Whichever Vite does, the handshake is allowed.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
      },
      '/ws': {
        target: 'http://localhost:8080',
        // Without this the upgrade is served as a plain HTTP request and the socket
        // never opens. It is the whole reason the WebSocket can share the proxy.
        ws: true,
      },
    },
  },
});
