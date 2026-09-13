import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// Same-origin deployment: the backend serves the built assets. In development, API calls are proxied.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      "/api": { target: process.env.AEGIS_DEV_API ?? "http://localhost:8080", changeOrigin: false },
      "/actuator": { target: process.env.AEGIS_DEV_API ?? "http://localhost:8080", changeOrigin: false },
    },
  },
  build: {
    outDir: "dist",
    sourcemap: false,
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
  },
});
