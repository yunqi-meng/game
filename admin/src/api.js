import axios from "axios";
import { ElMessage } from "element-plus";

const http = axios.create({
  baseURL: (import.meta.env.VITE_API_BASE || "") + "/admin/api",
  timeout: 15000,
});

http.interceptors.request.use((cfg) => {
  const t = localStorage.getItem("chem-admin-token");
  if (t) cfg.headers.Authorization = "Bearer " + t;
  return cfg;
});

http.interceptors.response.use(
  (res) => {
    const b = res.data;
    if (b && typeof b === "object" && "ok" in b) {
      if (!b.ok) { ElMessage.error(b.msg || "请求失败"); return Promise.reject(b); }
      return b.data;
    }
    return b;
  },
  (err) => {
    const st = err.response && err.response.status;
    if (st === 401) {
      // 只清 localStorage 是不够的：Pinia 里的 token 还活着，守卫据此认为仍然登录，
      // 于是 /login 被弹回 /dashboard——运营停在一堆 "-" 的空白看板上出不去。
      // 所以走 store 的 logout()（内存与 localStorage 一起清）。动态 import 是为了
      // 不跟 store.js 形成模块期循环（它本来就 import 了这个文件）。
      ElMessage.error("登录状态失效，请重新登录");
      import("./store").then(({ useAuth }) => {
        useAuth().logout();
        location.hash = "#/login";
      });
    } else {
      const msg = err.response && err.response.data && err.response.data.msg;
      ElMessage.error(msg || "网络错误");
    }
    return Promise.reject(err);
  }
);

export default http;
