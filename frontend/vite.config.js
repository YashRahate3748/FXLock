import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { backendProxy } from "./proxy-config.js";
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: { host: "127.0.0.1", port: 5174, strictPort: true, open: "/",
    proxy: backendProxy(process.env) },
});
