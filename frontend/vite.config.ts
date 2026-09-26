import { defineConfig } from "vitest/config";
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
    // Font subsets stay separate files: inlined into the CSS they would be downloaded on every visit instead of only
    // when a page needs their unicode-range (ADR-0019).
    assetsInlineLimit: (file) => (/\.woff2?$/.test(file) ? false : undefined),
  },
  // The scan worker is started as a module worker and loads OpenCV.js (about 10 MB) itself, so the app bundle
  // stays small; members download it only when they scan a page.
  worker: {
    format: "es",
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
