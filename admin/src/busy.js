/* admin/src/busy.js —— 后台写操作防连点（H6-4）的那一份在途状态。
 *
 * 要防的那件事：面板上 47 处调用只有 5 个按钮带 :loading。运营连点两下【封禁】，
 * 服务端就会收到两次同一个写入（多一条审计、两次存档改动），而界面上一条提示都没有。
 * 以前补这个只能每个按钮手写一遍"置位—try—finally 复位"，47 处写点抄 47 遍，
 * 于是没人抄全——缺的那几处正好就是没人看过的那几个按钮。
 *
 * 所以这里只有一份登记表：key 是「动作 + 那一行的标识」（ban:12），
 * 点 A 行的封禁不会把 B 行的按钮一起灰掉；同一处第二次进来直接复用还在途的那次结果。
 * 展示层的禁用与 api.js 那层的"同一调用只发一遍"叠在一起用：前者让人看见，后者保证漏网的也发不出去。
 */
import { reactive } from "vue";

/** 全局一份在途表：key -> 那一次还没结束的 Promise。reactive 让模板里的 busy(key) 跟着收回。 */
const inflight = reactive({});

export function useBusy() {
  /** 这一处按钮此刻是不是正在跑（模板里直接调用，值跟着 inflight 变）。 */
  const busy = (key) => !!inflight[key];

  /**
   * 跑一次带在途标记的动作。同一个 key 重复触发不会跑第二遍——返回的还是那一次的结果，
   * 所以连点两次【删除】只会真的删一次，而不是"第二次撞在已删除的行上再报个错"。
   */
  async function run(key, fn) {
    if (inflight[key]) return inflight[key];
    const p = (async () => {
      try { return await fn(); }
      finally { delete inflight[key]; }
    })();
    inflight[key] = p;
    return p;
  }

  return { busy, run };
}
