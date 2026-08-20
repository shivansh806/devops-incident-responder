import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // 5173 is Vite's default. It is pinned with strictPort because the backend's
    // WebSocket allowed-origins list names this exact port (WebSocketConfig). If 5173
    // is busy, Vite's normal behaviour is to move to 5174 and say so in one line of
    // startup output - and the app would then load fine and fail only at the handshake,
    // which reads as a broken backend rather than a changed port. Fail loudly instead.
    port: 5173,
    strictPort: true,
  },
});
