import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import AutoImport from "unplugin-auto-import/vite";
import Components from "unplugin-vue-components/vite";
import { ElementPlusResolver } from "unplugin-vue-components/resolvers";

// base=/admin/ 且产物直出到后端静态目录，Spring Boot 在 /admin/ 托管整套 SPA。
//
// H6-5：Element Plus 从"整包 app.use"改成按需（unplugin-vue-components + unplugin-auto-import）。
// 整包时 index-*.js 里 1.1 MB、index-*.css 361 KB，装的是两百多个组件与全套主题——本项目一共用到十几个。
// 按需之后模板里的 el-* 由插件就地引，样式跟着组件走。
// 注意：代码里显式 import 的那几个（ElMessage / ElMessageBox / v-loading）不是模板组件，
// 插件替换不到它们的样式，那几行样式在 main.js 里点名补齐——别删，删了 toast 与冲突弹窗会变成裸文本。
export default defineConfig({
  base: "/admin/",
  plugins: [
    vue(),
    AutoImport({ resolvers: [ElementPlusResolver()], dts: false }),
    Components({ resolvers: [ElementPlusResolver()], dts: false }),
  ],
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
