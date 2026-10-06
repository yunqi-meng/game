<template>
  <div>
    <el-tabs v-model="tab">
      <el-tab-pane label="举报处理" name="reports">
        <div class="bar">
          <el-select v-model="rStatus" style="width:150px" @change="filterReports">
            <el-option label="全部状态" value="" />
            <el-option label="待处理" value="open" />
            <el-option label="已处理" value="handled" />
            <el-option label="已驳回" value="dismissed" />
          </el-select>
          <el-button @click="reloadReports">刷新</el-button>
        </div>
        <el-table :data="reports" border height="calc(100vh - 260px)">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="reporter" label="举报人" width="90" />
          <el-table-column prop="targetUser" label="被举报" width="90" />
          <el-table-column prop="kind" label="类型" width="110" />
          <el-table-column prop="reason" label="理由" show-overflow-tooltip />
          <el-table-column label="状态" width="90">
            <template #default="s"><el-tag :type="statusType(s.row.status)">{{ statusText(s.row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="createdAt" label="时间" width="200" />
          <el-table-column label="操作" width="180">
            <template #default="s">
              <template v-if="auth.canWrite && s.row.status === 'open'">
                <el-button link type="success" @click="handle(s.row,'handled')">处理</el-button>
                <el-button link type="info" @click="handle(s.row,'dismissed')">驳回</el-button>
              </template>
              <span v-else-if="s.row.handledBy" class="dim">by {{ s.row.handledBy }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination background layout="prev, pager, next, total" :total="rTotal" :page-size="size" :current-page="rPage" @current-change="onRPage" />
      </el-tab-pane>

      <el-tab-pane label="敏感词" name="words">
        <div class="bar">
          <el-input v-model="wForm.word" placeholder="新增敏感词" style="width:220px" @keyup.enter="addWord" />
          <!-- 分级只有 1/2 两档由服务端说了算（AdminModerationController.upsertWord 拒其它值）。
               这里原来放的是 :max="9"：运营拨到 3 保存，得到的是一句"分级只有 1 与 2 两档"的红字
               和一个白填的词语框。控件形态跟着后端走，别让它选出一个后端不认的值。 -->
          <el-select v-model="wForm.level" style="width:150px">
            <el-option :value="1" label="1 · 提示（放行）" />
            <el-option :value="2" label="2 · 拦截" />
          </el-select>
          <el-button v-if="auth.canWrite" type="primary" @click="addWord">添加</el-button>
          <span class="dim">只有 2（拦截）真的会让注册/改昵称被拒</span>
        </div>
        <el-table :data="words" border height="calc(100vh - 300px)">
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column prop="word" label="词语" />
          <el-table-column label="级别" width="120">
            <template #default="s">{{ Number(s.row.level) >= 2 ? "2 · 拦截" : "1 · 提示" }}</template>
          </el-table-column>
          <el-table-column v-if="auth.canWrite" label="操作" width="100">
            <template #default="s"><el-button link type="danger" @click="delWord(s.row)">删除</el-button></template>
          </el-table-column>
        </el-table>
        <el-pagination background layout="prev, pager, next, total" :total="wTotal" :page-size="size" :current-page="wPage" @current-change="onWPage" />
      </el-tab-pane>

      <el-tab-pane label="操作日志" name="audit">
        <div class="bar"><el-button @click="reloadAudit">刷新</el-button></div>
        <el-table :data="audit" border height="calc(100vh - 300px)">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="admin" label="管理员" width="130" />
          <el-table-column prop="action" label="操作" width="150" />
          <el-table-column prop="target" label="对象" width="150" show-overflow-tooltip />
          <el-table-column label="明细" min-width="220" show-overflow-tooltip>
            <template #default="s"><span class="dim">{{ detail(s.row.detail) }}</span></template>
          </el-table-column>
          <el-table-column prop="ip" label="IP" width="140" />
          <el-table-column prop="created_at" label="时间" width="200" />
        </el-table>
        <el-pagination background layout="prev, pager, next, total" :total="aTotal" :page-size="size" :current-page="aPage" @current-change="onAPage" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import http from "../api";
import { useAuth } from "../store";

const auth = useAuth();
const tab = ref("reports");
const size = 50;

const reports = ref([]); const rStatus = ref(""); const rTotal = ref(0); const rPage = ref(1);
const words = ref([]); const wTotal = ref(0); const wPage = ref(1); const wForm = ref({ word: "", level: 1 });
const audit = ref([]); const aTotal = ref(0); const aPage = ref(1);

onMounted(function () { reloadReports(); reloadWords(); reloadAudit(); });
watch(tab, function (t) { if (t === "words") reloadWords(); if (t === "audit") reloadAudit(); });

/*
  三个列表的总数都读服务端给的 total（H6-3）。

  以前这两个面板回的是裸数组，前端只好拿本页长度<b>编</b>一个总数：
  满页时写"页码 × 每页 + 1"（永远说还有下一页，翻过去是空的），
  最后一页写"偏移 + 本页行数"（比真实条数多算一条，分页组件上明明白白摆着假数字）。
  现在服务端把 COUNT(*) 一起回过来，这里就只是转手。
*/
async function reloadReports() {
  const d = await http.get("/moderation/reports", { params: { status: rStatus.value, size, off: (rPage.value - 1) * size } });
  reports.value = d.rows; rTotal.value = d.total;
}
/** 换筛选条件要回到第 1 页：第 3 页的人筛"已处理"，若页数本来就掉到 1，他会停在一个空列表上。 */
function filterReports() { rPage.value = 1; reloadReports(); }
function onRPage(p) { rPage.value = p; reloadReports(); }

async function handle(row, status) {
  await http.post("/moderation/reports/handle", null, { params: { id: row.id, status } });
  ElMessage.success("已更新"); reloadReports();
}

async function reloadWords() {
  const d = await http.get("/moderation/words", { params: { size, off: (wPage.value - 1) * size } });
  words.value = d.rows; wTotal.value = d.total;
}
function onWPage(p) { wPage.value = p; reloadWords(); }
async function addWord() {
  if (!wForm.value.word.trim()) return ElMessage.warning("请输入词语");
  await http.post("/moderation/words", { word: wForm.value.word.trim(), level: wForm.value.level });
  wForm.value.word = ""; ElMessage.success("已添加");
  // 词表按 id 倒序（新的在第一页）：加完词停在原来那一页，人就看不到自己刚加的那条，以为没存进去
  wPage.value = 1; reloadWords();
}
async function delWord(row) {
  await ElMessageBox.confirm(`删除敏感词「${row.word}」？`, "确认");
  await http.delete("/moderation/words", { params: { id: row.id } }); ElMessage.success("已删除");
  // 删掉本页最后一条时往前退一页，否则会停在一张空表上（总数是服务端给的，页码还在原地）
  if (words.value.length === 1 && wPage.value > 1) wPage.value--;
  reloadWords();
}

async function reloadAudit() {
  const d = await http.get("/moderation/audit", { params: { size, off: (aPage.value - 1) * size } });
  audit.value = d.rows; aTotal.value = d.total;
}
function onAPage(p) { aPage.value = p; reloadAudit(); }

function statusType(s) { return s === "handled" ? "success" : s === "dismissed" ? "info" : "warning"; }
function statusText(s) { return s === "handled" ? "已处理" : s === "dismissed" ? "已驳回" : "待处理"; }

/** 入库时统一包了一层 {"d": ...}（JSON 列要放标量也得包），展示时剥回来，
 *  不然「调整资产」这类带前后值的记录在界面上只能看到一个对象哈希。 */
function detail(d) {
  if (!d) return "-";
  try {
    const o = typeof d === "string" ? JSON.parse(d) : d;
    const v = o && typeof o === "object" && !Array.isArray(o) && "d" in o ? o.d : o;
    return typeof v === "object" && v !== null ? JSON.stringify(v) : String(v);
  } catch (e) { return String(d); }
}
</script>

<style scoped>
.bar { margin-bottom:10px; display:flex; gap:8px; }
.dim { color:#94a3b8; font-size:12px; }
</style>
