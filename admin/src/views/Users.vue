<template>
  <div>
    <div class="bar"><el-input v-model="q" placeholder="搜索用户名" style="width:220px" @keyup.enter="reload" clearable />
      <el-button @click="reload">查询</el-button></div>
    <el-table :data="rows" border height="calc(100vh - 160px)">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="username" label="用户名" />
      <el-table-column prop="nickname" label="昵称" />
      <el-table-column label="状态" width="90">
        <template #default="s"><el-tag :type="s.row.status ? 'danger' : 'success'">{{ s.row.status ? '封禁' : '正常' }}</el-tag></template>
      </el-table-column>
      <el-table-column label="类型" width="90">
        <template #default="s"><el-tag v-if="s.row.is_guest" type="info" effect="plain">游客</el-tag>
          <el-tag v-else-if="s.row.taptap_open_id" type="success" effect="plain">TapTap</el-tag>
          <span v-else>正式</span></template>
      </el-table-column>
      <el-table-column label="青少年" width="150">
        <template #default="s">
          <el-tooltip placement="top" effect="dark" :show-after="120">
            <template #content>
              <div style="max-width:320px;line-height:1.7">
                <div>打上这个标记后，该账号只在【运营配置 · 防沉迷时段】放行的日子与窗口内能进游戏；服务端在每次玩法结算前拦，前端绕不过去。</div>
                <div style="color:#fbbf24;margin-top:4px">关掉总开关则对所有账号放行——那是合规开关，不是给单个玩家开的口子。</div>
                <div style="color:#94a3b8;margin-top:4px">改动写审计日志（user.minor）</div>
              </div>
            </template>
            <el-switch :model-value="!!s.row.minor" :disabled="!auth.canWrite" @change="setMinor(s.row, $event)" />
          </el-tooltip>
          <span class="tip">{{ s.row.minor ? '限时段' : '不限' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="🪙 金币" width="110" align="right">
        <template #default="s"><span class="num">{{ n(s.row.coins) }}</span></template>
      </el-table-column>
      <el-table-column label="💎 钻石" width="100" align="right">
        <template #default="s"><span class="num">{{ n(s.row.diamonds) }}</span></template>
      </el-table-column>
      <el-table-column prop="last_login_at" label="最近登录" width="200" />
      <el-table-column label="操作" width="380">
        <template #default="s">
          <el-button link type="primary" @click="viewSave(s.row)">存档</el-button>
          <el-button link @click="viewRevs(s.row)">历史</el-button>
          <el-button v-if="auth.isSuper" link type="primary" @click="openAssets(s.row)">调整资产</el-button>
          <el-button v-if="auth.canWrite && !s.row.status" link type="warning" @click="ban(s.row)">封禁</el-button>
          <el-button v-if="auth.canWrite && s.row.status" link type="success" @click="unban(s.row)">解封</el-button>
          <el-button v-if="auth.isSuper && !s.row.is_guest" link @click="resetPass(s.row)">重置口令</el-button>
          <el-button v-if="auth.canWrite" link type="danger" @click="del(s.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination style="margin-top:10px" background layout="prev, pager, next, total" :total="total" :page-size="size" :current-page="page" @current-change="onPage" />

    <el-dialog v-model="saveDlg" title="云端存档" width="640px">
      <div v-if="saveInfo.exists === false">该用户暂无云端存档。</div>
      <div v-else>
        <p>版本：<b>{{ saveInfo.revision }}</b> · 更新：{{ fmt(saveInfo.updatedAt) }}</p>
        <pre class="json">{{ pretty(saveInfo.payload) }}</pre>
      </div>
    </el-dialog>

    <el-dialog v-model="revDlg" title="存档历史（可回滚）" width="600px">
      <el-table :data="revs" height="360">
        <el-table-column prop="revision" label="版本" width="80" />
        <el-table-column prop="source" label="来源" width="100" />
        <el-table-column prop="createdAt" label="时间" />
        <el-table-column prop="bytes" label="大小" width="90" />
        <el-table-column label="操作" width="100">
          <template #default="s"><el-button v-if="auth.canWrite" link type="primary" @click="rollback(s.row)">回滚</el-button></template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="assetDlg" :title="'调整资产 · ' + (curUser?.username || '')" width="520px">
      <el-alert :closable="false" type="warning" style="margin-bottom:14px"
                title="填的是增减量（可为负），不是目标值。保存后立即生效：玩家下一次操作读到的就是新余额。" />
      <el-form label-width="88px">
        <el-form-item label="当前">
          <span class="num">🪙 {{ n(curUser?.coins) }} · 💎 {{ n(curUser?.diamonds) }}</span>
          <span v-if="curUser && curUser.coins == null" class="tip">（该玩家还没有云端存档，需先在游戏里进一次实验室）</span>
        </el-form-item>
        <el-form-item label="金币增减">
          <el-input-number v-model="assetForm.coins" :step="1000" :precision="0" controls-position="right" style="width:200px" />
        </el-form-item>
        <el-form-item label="钻石增减">
          <el-input-number v-model="assetForm.diamonds" :step="10" :precision="0" controls-position="right" style="width:200px" />
        </el-form-item>
        <el-form-item label="调整后">
          <span class="num strong">🪙 {{ preview(curUser?.coins, assetForm.coins) }} · 💎 {{ preview(curUser?.diamonds, assetForm.diamonds) }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assetDlg=false">取消</el-button>
        <el-button type="primary" :disabled="!assetDirty" @click="saveAssets">确认调整</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import http from "../api";
import { useAuth } from "../store";

const auth = useAuth();
const q = ref("");
const rows = ref([]);
const total = ref(0);
const page = ref(1);
const size = 30;
const curUser = ref(null);
const saveDlg = ref(false);
const revDlg = ref(false);
const saveInfo = ref({});
const revs = ref([]);
const assetDlg = ref(false);
const assetForm = ref({ coins: 0, diamonds: 0 });
const assetDirty = computed(() => !!assetForm.value.coins || !!assetForm.value.diamonds);

onMounted(reload);
async function reload() {
  const d = await http.get("/users", { params: { q: q.value, size, off: (page.value - 1) * size } });
  rows.value = d.rows; total.value = d.total;
}
function onPage(p) { page.value = p; reload(); }

async function viewSave(row) { curUser.value = row; saveInfo.value = await http.get("/users/save", { params: { id: row.id } }); saveDlg.value = true; }
async function viewRevs(row) { curUser.value = row; revs.value = await http.get("/users/save/revisions", { params: { id: row.id } }); revDlg.value = true; }

function openAssets(row) {
  curUser.value = row;
  assetForm.value = { coins: 0, diamonds: 0 };
  assetDlg.value = true;
}
function n(v) { return v == null ? "-" : Number(v).toLocaleString("zh-CN"); }
function preview(now, delta) {
  if (now == null) return "-";
  const t = Number(now) + Number(delta || 0);
  return t < 0 ? `会成负数(${t})` : n(t);
}
async function saveAssets() {
  const c = Number(assetForm.value.coins || 0), d = Number(assetForm.value.diamonds || 0);
  if (!c && !d) return ElMessage.warning("至少填一项非零的增减量");
  await ElMessageBox.confirm(
    `将 ${curUser.value.username} 的金币 ${signed(c)}、钻石 ${signed(d)}。这一步会写进审计日志与玩家的存档历史（来源标记为 admin）。`,
    "确认调整玩家资产", { type: "error", confirmButtonText: "确认调整" });
  const r = await http.post("/users/assets", { coins: c, diamonds: d }, { params: { id: curUser.value.id } });
  ElMessage.success(`已调整：🪙 ${n(r.coinsBefore)} → ${n(r.coinsAfter)} · 💎 ${n(r.diamondsBefore)} → ${n(r.diamondsAfter)}`);
  assetDlg.value = false; reload();
}
function signed(v) { return (v > 0 ? "+" : "") + n(v); }

/**
 * 青少年模式标记（防沉迷闸门的唯一运营入口）。
 *
 * <p>这里不自己判断"现在能不能玩"：接口返回的就是服务端闸门给出的权威状态视图，
 * 窗口、放行日、下次可玩时刻都以它为准，运营点完当场能看到生效没有。
 */
async function setMinor(row, on) {
  if (on) {
    await ElMessageBox.confirm(
      `将 ${row.username} 标记为青少年账号：此后只在【运营配置 · 防沉迷时段】放行的日子与窗口内能进游戏，其余时间每一次操作都会被服务端挡下（游戏内会显示下次可玩时刻，不是白屏）。`,
      "确认开启青少年模式", { type: "warning", confirmButtonText: "标记" });
  }
  const v = await http.post("/users/minor", null, { params: { id: row.id, on: !!on } });
  row.minor = on ? 1 : 0;   // 用返回视图回写行状态，失败时开关保持原样
  ElMessage.success(minorSummary(v));
}
function minorSummary(v) {
  if (!v) return "已更新";
  if (!v.enforced) return "已更新：防沉迷总开关当前是关的，这个标记此刻不影响游玩";
  if (v.allowed) return "已更新：该账号当前时段可以游玩";
  return "已更新：下次可玩 " + fmt(v.nextOpenAt);
}

async function ban(row) {
  const { value } = await ElMessageBox.prompt("封禁天数", "封禁用户", { inputValue: "7", inputPattern: /^\d+$/, inputErrorMessage: "请输入数字" });
  await http.post("/users/ban", null, { params: { id: row.id, days: value } });
  ElMessage.success("已封禁"); reload();
}
async function unban(row) { await http.post("/users/unban", null, { params: { id: row.id } }); ElMessage.success("已解封"); reload(); }
async function resetPass(row) {
  const { value } = await ElMessageBox.prompt(
    `为 ${row.username} 设置新口令（至少 6 位，不能与用户名相同）。重置后该玩家的刷新令牌全部作废，已打开的页面最长还能用到访问令牌自然过期（约 2 小时）。`,
    "重置玩家口令",
    { inputPlaceholder: "新口令", inputValidator: v => (v && v.length >= 6) ? true : "至少 6 位" });
  await http.post("/users/reset-password", { pass: value }, { params: { id: row.id } });
  ElMessage.success("已重置，请把新口令告知玩家");
}
async function del(row) {
  await ElMessageBox.confirm(`删除用户 ${row.username} 及其存档？不可恢复`, "危险操作", { type: "error" });
  await http.delete("/users", { params: { id: row.id } }); ElMessage.success("已删除"); reload();
}
async function rollback(r) {
  await ElMessageBox.confirm(`回滚到版本 ${r.revision}？`, "确认");
  await http.post("/users/save/rollback", null, { params: { id: curUser.value.id, revision: r.revision } });
  ElMessage.success("已回滚"); revDlg.value = false;
}
function pretty(p) { try { return JSON.stringify(typeof p === "string" ? JSON.parse(p) : p, null, 2); } catch (e) { return p; } }
function fmt(t) { return t ? new Date(t).toLocaleString() : "-"; }
</script>

<style scoped>
.bar { margin-bottom:10px; }
.json { max-height:360px; overflow:auto; background:#0b1220; color:#a5f3fc; padding:10px; border-radius:6px; font-size:12px; }
.num { font-variant-numeric:tabular-nums; }
.strong { font-weight:600; }
.tip { color:var(--el-text-color-secondary); font-size:12px; }
</style>
