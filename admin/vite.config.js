import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

// base=/admin/ 且产物直出到后端静态目录，Spring Boot 在 /admin/ 托管整套 SPA。
export default defineConfig({
  base: "/admin/",
  plugins: [vue()],
  build: {
    outDir: "../server/src/main/resources/static/admin",
    emptyOutDir: true,
    chunkSizeWarningLimit: 1500,
  },
  server: {
    port: 5273,
    proxy: {
      "/api": { target: "http://localhost:8080", changeOrigin: true },
      "/admin/api": { target: "http://localhost:8080", changeOrigin: true },
    },
  },
});
