import axios from "axios";
import { ElMessage } from "element-plus";

const client = axios.create({
  baseURL: (import.meta.env.VITE_API_BASE || "") + "/admin/api",
  timeout: 15000,
});

client.interceptors.request.use((cfg) => {
  const t = localStorage.getItem("chem-admin-token");
  if (t) cfg.headers.Authorization = "Bearer " + t;
  return cfg;
});

client.interceptors.response.use(
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
    // 调用方声明"这次失败我自己兜"（config.silent）时不弹红字：那一行可有可无的读数不该打扰运营。
    const silent = !!(err.config && err.config.silent);
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
      if (st === 409) err.isConflict = true;
      else if (!silent) ElMessage.error(msg || "网络错误");
    }
    return Promise.reject(err);
  }
);

/* ---------------- 防连点（H6-4）：同一处调用还在途时，第二次点返回的是同一次 ---------------- */

/**
 * 面板上 47 处 http.* 只有 5 处按钮带 :loading，剩下的全靠人别点第二下——这不够。
 * 逐个调用点补"置位—try—finally"要抄 47 遍，谁都会漏抄，所以这一层包在唯一的出口上：
 * key = 方法 + 路径 + 参数/请求体的原文，同一个调用点重入就直接复用那次没结束的 Promise。
 *
 * <p>它不改变任何一次调用的形状：视图里仍然是 http.put("/content/item", payload, { params: { expect } })，
 * 乐观锁的版本号照旧在调用点上传（test/admin-lock.js 判的就是这个），409 分流也照旧只在这一个文件里。
 */
const inflight = new Map();

/** 参数进 key 前要变成字符串：请求体里理论上有循环引用，那也不能让防连点这一层把请求发不出去。 */
function argText(v) {
  try { return JSON.stringify(v) ?? ""; }
  catch (e) { return String(v); }
}

function once(method, args) {
  const key = method + " " + args.map(argText).join("¶");
  const running = inflight.get(key);
  if (running) return running;
  // 结束后一定要摘掉：留着的话，翻页回到同一处会拿到上一次已经落定的结果（不再发请求）
  const p = client[method](...args).finally(() => inflight.delete(key));
  inflight.set(key, p);
  return p;
}

const http = {
  get: (url, config) => once("get", [url, config]),
  post: (url, data, config) => once("post", [url, data, config]),
  put: (url, data, config) => once("put", [url, data, config]),
  delete: (url, config) => once("delete", [url, config]),
};

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
