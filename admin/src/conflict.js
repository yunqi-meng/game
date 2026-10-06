/* 乐观锁冲突（H6-2）的唯一一处收尾逻辑。
 *
 * 两个运营同时开同一条反应 / 同一个配置键时，后提交的那一位会收到 409：这一份没落库，
 * 也不会落库——服务端那句话里已经说清楚是谁在什么时候动了它。这里只负责给出那一条出路：
 * 「载入最新」而不是「再点一次保存」。后者正是要拦的那次覆盖，所以文案、按钮、返回值都只服务于
 * "让你看清当前版本" 这一件事，不做任何自动重试。
 *
 * 为什么两个面板共用一个文件：Content.vue 与 Config.vue 的冲突形状一模一样，各写一份的话
 * 总有一个会漂成"顺手把 list 刷一下就算处理过了"，而那样运营仍然看不见别人的改动。
 */
import { ElMessageBox } from "element-plus";
import { isConflict, conflictMsg } from "./api";

/**
 * 弹出冲突说明。返回 true 表示运营选了「载入最新」（调用方应当刷新列表并回填最新版本），
 * 返回 false 表示他选择先不覆盖——这时候调用方必须什么都不写，也不要把弹窗关掉，
 * 那份草稿是他唯一的凭证，静默丢弃等于替他做了决定。
 */
export async function askReloadOnConflict(err, draftHint) {
  if (!isConflict(err)) return false;
  const detail = conflictMsg(err) + (draftHint ? "\n\n" + draftHint : "");
  try {
    await ElMessageBox.confirm(detail, "这一项被人改过了", {
      type: "warning",
      confirmButtonText: "载入最新",
      cancelButtonText: "先不覆盖",
      distinguishCancelAndClose: true,
      customClass: "conflict-box",
    });
    return true;
  } catch (e) {
    return false;   // 取消或点右上角：留在原地，手上这份不落库
  }
}

/**
 * 只是刷新看一眼的场合（列表里那个开关、删除按钮）：没有"草稿"要保，
 * 所以不给「载入最新」这个动作，只把人家的改动说清楚，并让调用方刷新列表回到真相。
 */
export async function explainConflict(err) {
  if (!isConflict(err)) return false;
  await ElMessageBox.alert(conflictMsg(err) + "\n\n列表已刷新，请在最新版本上再决定一次。",
    "这一项被人改过了", { type: "warning", confirmButtonText: "知道了" }).catch(() => {});
  return true;
}
