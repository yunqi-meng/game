<template>
  <div>
    <el-tabs v-model="tab">
      <el-tab-pane label="举报处理" name="reports">
        <div class="bar">
          <el-select v-model="rStatus" style="width:150px" @change="reloadReports">
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
        <el-pagination background layout="prev, pager, next" :total="rTotal" :page-size="size" :current-page="rPage" @current-change="onRPage" />
      </el-tab-pane>

      <el-tab-pane label="敏感词" name="words">
        <div class="bar">
          <el-input v-model="wForm.word" placeholder="新增敏感词" style="width:220px" @keyup.enter="addWord" />
          <el-input-number v-model="wForm.level" :min="1" :max="9" controls-position="right" style="width:110px" />
          <el-button v-if="auth.canWrite" type="primary" @click="addWord">添加</el-button>
        </div>
        <el-table :data="words" border height="calc(100vh - 250px)">
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column prop="word" label="词语" />
          <el-table-column prop="level" label="级别" width="120" />
          <el-table-column v-if="auth.canWrite" label="操作" width="100">
            <template #default="s"><el-button link type="danger" @click="delWord(s.row)">删除</el-button></template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="操作日志" name="audit">
        <div class="bar"><el-button @click="reloadAudit">刷新</el-button></div>
        <el-table :data="audit" border height="calc(100vh - 250px)">
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
        <el-pagination background layout="prev, pager, next" :total="aTotal" :page-size="size" :current-page="aPage" @current-change="onAPage" />
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
const words = ref([]); const wForm = ref({ word: "", level: 1 });
const audit = ref([]); const aTotal = ref(0); const aPage = ref(1);

onMounted(function () { reloadReports(); reloadWords(); reloadAudit(); });
watch(tab, function (t) { if (t === "words") reloadWords(); if (t === "audit") reloadAudit(); });

async function reloadReports() {
  const d = await http.get("/moderation/reports", { params: { status: rStatus.value, size, off: (rPage.value - 1) * size } });
  reports.value = d; rTotal.value = d.length < size ? (rPage.value - 1) * size + d.length : rPage.value * size + 1;
}
function onRPage(p) { rPage.value = p; reloadReports(); }

async function handle(row, status) {
  await http.post("/moderation/reports/handle", null, { params: { id: row.id, status } });
  ElMessage.success("已更新"); reloadReports();
}

async function reloadWords() { words.value = await http.get("/moderation/words"); }
async function addWord() {
  if (!wForm.value.word.trim()) return ElMessage.warning("请输入词语");
  await http.post("/moderation/words", { word: wForm.value.word.trim(), level: wForm.value.level });
  wForm.value.word = ""; ElMessage.success("已添加"); reloadWords();
}
async function delWord(row) {
  await ElMessageBox.confirm(`删除敏感词「${row.word}」？`, "确认");
  await http.delete("/moderation/words", { params: { id: row.id } }); ElMessage.success("已删除"); reloadWords();
}

async function reloadAudit() {
  const d = await http.get("/moderation/audit", { params: { size, off: (aPage.value - 1) * size } });
  audit.value = d; aTotal.value = d.length < size ? (aPage.value - 1) * size + d.length : aPage.value * size + 1;
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
