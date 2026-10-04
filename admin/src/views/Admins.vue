<template>
  <div>
    <div class="bar">
      <span class="hint">后台账号即游戏数据的最高权限；建号与重置口令都会写入审计日志。</span>
      <el-button type="primary" @click="openCreate">新建管理员</el-button>
      <el-button @click="load">刷新</el-button>
    </div>
    <el-table :data="rows" border height="calc(100vh - 200px)">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column label="账号" width="180">
        <template #default="s">{{ s.row.user }}<span v-if="isSelf(s.row)" class="hint">（自己）</span></template>
      </el-table-column>
      <el-table-column label="角色" width="150">
        <template #default="s">
          <el-select v-model="s.row.role" size="small" :disabled="isSelf(s.row)" @change="setRole(s.row)">
            <el-option label="超级管理员" value="super" />
            <el-option label="编辑" value="editor" />
            <el-option label="只读" value="viewer" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="s">
          <el-tag :type="s.row.status ? 'danger' : 'success'">{{ s.row.status ? "已停用" : "在岗" }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="口令" width="130">
        <template #default="s">
          <el-tag v-if="s.row.mustChange" type="warning" size="small">待首次改密</el-tag>
          <span v-else class="hint">已自设</span>
        </template>
      </el-table-column>
      <el-table-column prop="lastLoginAt" label="最近登录" width="200" />
      <el-table-column prop="createdAt" label="创建时间" width="200" />
      <el-table-column label="操作" width="240">
        <template #default="s">
          <el-button link type="primary" @click="openReset(s.row)">重置口令</el-button>
          <!-- 自己这行不给停用/删除：服务端一律拒绝，摆出来只是让人撞错误 -->
          <template v-if="!isSelf(s.row)">
            <el-button v-if="!s.row.status" link type="warning" @click="setStatus(s.row, 1)">停用</el-button>
            <el-button v-else link type="success" @click="setStatus(s.row, 0)">启用</el-button>
            <el-button link type="danger" @click="remove(s.row)">删除</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createDlg" title="新建管理员" width="440px">
      <el-form :model="cf" label-width="80px" @submit.prevent>
        <el-form-item label="账号"><el-input v-model="cf.user" placeholder="字母开头，3-32 位" /></el-form-item>
        <el-form-item label="初始口令"><el-input v-model="cf.pass" type="password" show-password placeholder="至少 8 位" /></el-form-item>
        <el-form-item label="角色">
          <el-select v-model="cf.role" style="width:100%">
            <el-option label="超级管理员" value="super" />
            <el-option label="编辑" value="editor" />
            <el-option label="只读" value="viewer" />
          </el-select>
        </el-form-item>
      </el-form>
      <div class="hint">初始口令请通过其他渠道转交：对方首次登录会被强制改密，在此之前用不了后台。</div>
      <template #footer>
        <el-button @click="createDlg = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="create">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resetDlg" :title="'重置口令：' + (target?.user || '')" width="400px">
      <el-form label-width="80px" @submit.prevent>
        <el-form-item label="新口令"><el-input v-model="newPass" type="password" show-password placeholder="至少 8 位" /></el-form-item>
      </el-form>
      <div class="hint">重置后该账号需先用新口令登录并自行改密。</div>
      <template #footer>
        <el-button @click="resetDlg = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="reset">确认重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import http from "../api";
import { useAuth } from "../store";

const auth = useAuth();
const rows = ref([]);
const busy = ref(false);
const createDlg = ref(false);
const cf = reactive({ user: "", pass: "", role: "editor" });
const resetDlg = ref(false);
const target = ref(null);
const newPass = ref("");

async function load() { rows.value = await http.get("/admins"); }
function isSelf(row) { return row.user === auth.user; }

function openCreate() {
  cf.user = ""; cf.pass = ""; cf.role = "editor";
  createDlg.value = true;
}
async function create() {
  busy.value = true;
  try {
    await http.post("/admins/create", { user: cf.user, pass: cf.pass, role: cf.role });
    createDlg.value = false;
    ElMessage.success("已创建，初始口令需由本人首次登录时修改");
    await load();
  } finally { busy.value = false; }
}

function openReset(row) { target.value = row; newPass.value = ""; resetDlg.value = true; }
async function reset() {
  busy.value = true;
  try {
    await http.post("/admins/password?id=" + target.value.id, { pass: newPass.value });
    resetDlg.value = false;
    ElMessage.success("口令已重置");
    await load();
  } finally { busy.value = false; }
}

async function setRole(row) {
  try {
    await http.post("/admins/role?id=" + row.id + "&role=" + row.role);
    ElMessage.success("角色已更新");
  } catch (e) {
    await load();                       // 服务端拒绝（如最后一个超管）时回滚下拉框
  }
}

async function setStatus(row, status) {
  await http.post("/admins/status?id=" + row.id + "&status=" + status);
  ElMessage.success(status ? "已停用" : "已启用");
  await load();
}

async function remove(row) {
  await ElMessageBox.confirm(`删除管理员 ${row.user}？该操作不可撤销。`, "确认删除", { type: "warning" });
  await http.post("/admins/remove?id=" + row.id);
  ElMessage.success("已删除");
  await load();
}

onMounted(() => {
  load();
  if (!auth.isSuper) ElMessage.warning("仅超级管理员可查看管理员账号");
});
</script>

<style scoped>
.bar { display:flex; align-items:center; gap:10px; margin-bottom:10px; }
.bar .hint { flex:1; }
.hint { color:#94a3b8; font-size:12px; }
</style>
