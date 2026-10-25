import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    host: "127.0.0.1",
    port: 5173,
    strictPort: true,
    proxy: {
      "/api/v1": {
        target: process.env.RATE_BACKEND_URL || "http://localhost:8081",
        changeOrigin: true,
      },
      "/m2": {
        target: process.env.QUOTE_BACKEND_URL || "http://localhost:8082",
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/m2/, "") || "/",
        configure(proxy) {
          proxy.on("proxyRes", (response) => {
            const location = response.headers.location;
            if (location) {
              const target =
                process.env.QUOTE_BACKEND_URL || "http://localhost:8082";
              const url = new URL(location, target);
              if (url.origin === new URL(target).origin)
                response.headers.location = "/m2" + url.pathname + url.search;
            }
          });
        },
      },
    },
  },
});
