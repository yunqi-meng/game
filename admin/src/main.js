import { createApp } from "vue";
import { createPinia } from "pinia";
import App from "./App.vue";
import router from "./router";

/* H6-5：这里不再有 app.use(ElementPlus) 与 element-plus/dist/index.css。
 * 模板里的 el-* 由 vite.config.js 的 ElementPlusResolver 就地引入，样式跟着组件走。
 *
 * 剩下三行是"插件够不着"的那三个：ElMessage / ElMessageBox 是代码里显式 import 的函数式组件，
 * v-loading 是指令，都不在模板组件的按需替换范围内。它们的样式不在这里点名的话，
 * toast、409 冲突弹窗与表格遮罩会退化成没有样式的裸文本——正是这一轮最容易漏的那类"看着还能跑"。
 * （每个 style/css 都会把 theme-chalk/base.css 一起带进来，CSS 变量与 reset 因此仍然齐全。）
 */
import "element-plus/es/components/message/style/css";
import "element-plus/es/components/message-box/style/css";
import "element-plus/es/components/loading/style/css";

const app = createApp(App);
app.use(createPinia());
app.use(router);
app.mount("#app");
