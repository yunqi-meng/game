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
      // 409 是乐观锁冲突（H6-2）：要给的是一条出路（载入最新那一版再改），不是一行飘过去就消失的红字。
      // 所以这里不弹，交给调用方（Content.vue / Config.vue）弹确认框；其它状态照旧。
      if (st !== 409) ElMessage.error(msg || "网络错误");
      else err.isConflict = true;
    }
    return Promise.reject(err);
  }
);

export default http;

/** 这次失败是不是"你手上那份过期了"（H6-2）。按状态码判，不按文案匹配——文案随时会改字。 */
export function isConflict(e) {
  return !!(e && (e.isConflict || (e.response && e.response.status === 409)));
}

/** 服务端那句"是谁在什么时候动了它"，冲突弹窗里直接用它。 */
export function conflictMsg(e) {
  return (e && e.response && e.response.data && e.response.data.msg)
    || "这一项在你编辑期间已被他人改动，本次写入已取消。";
}
