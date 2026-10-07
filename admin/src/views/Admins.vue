<template>
  <div>
    <div class="bar">
      <span class="hint">后台账号即游戏数据的最高权限；建号与重置口令都会写入审计日志。</span>
      <el-button type="primary" @click="openCreate">新建管理员</el-button>
      <el-button :loading="busy('load')" @click="load">刷新</el-button>
    </div>
    <el-table :data="rows" border height="calc(100vh - 200px)">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column label="账号" width="180">
        <template #default="s">{{ s.row.user }}<span v-if="isSelf(s.row)" class="hint">（自己）</span></template>
      </el-table-column>
      <el-table-column label="角色" width="150">
        <template #default="s">
          <!-- 防连点（H6-4）：这一档改的是"谁能写库"，在途期间再选一次会把上一次的选择也送出去 -->
          <el-select v-model="s.row.role" size="small" :disabled="isSelf(s.row) || busy('role:' + s.row.id)"
                     :loading="busy('role:' + s.row.id)" @change="setRole(s.row)">
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
            <el-button v-if="!s.row.status" link type="warning"
                       :loading="busy('status:' + s.row.id)" @click="setStatus(s.row, 1)">停用</el-button>
            <el-button v-else link type="success"
                       :loading="busy('status:' + s.row.id)" @click="setStatus(s.row, 0)">启用</el-button>
            <el-button link type="danger" :loading="busy('del:' + s.row.id)" @click="remove(s.row)">删除</el-button>
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
        <el-button type="primary" :loading="busy('create')" @click="create">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resetDlg" :title="'重置口令：' + (target?.user || '')" width="400px">
      <el-form label-width="80px" @submit.prevent>
        <el-form-item label="新口令"><el-input v-model="newPass" type="password" show-password placeholder="至少 8 位" /></el-form-item>
      </el-form>
      <div class="hint">重置后该账号需先用新口令登录并自行改密。</div>
      <template #footer>
        <el-button @click="resetDlg = false">取消</el-button>
        <el-button type="primary" :loading="busy('reset')" @click="reset">确认重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import http from "../api";
import { useBusy } from "../busy";
import { useAuth } from "../store";

const auth = useAuth();
const { busy, run } = useBusy();
const rows = ref([]);
const createDlg = ref(false);
const cf = reactive({ user: "", pass: "", role: "editor" });
const resetDlg = ref(false);
const target = ref(null);
const newPass = ref("");

async function load() {
  await run("load", async () => { rows.value = await http.get("/admins"); });
}
function isSelf(row) { return row.user === auth.user; }

function openCreate() {
  cf.user = ""; cf.pass = ""; cf.role = "editor";
  createDlg.value = true;
}
async function create() {
  await run("create", async () => {
    await http.post("/admins/create", { user: cf.user, pass: cf.pass, role: cf.role });
    createDlg.value = false;
    ElMessage.success("已创建，初始口令需由本人首次登录时修改");
    await load();
  });
}

function openReset(row) { target.value = row; newPass.value = ""; resetDlg.value = true; }
async function reset() {
  await run("reset", async () => {
    await http.post("/admins/password", { pass: newPass.value }, { params: { id: target.value.id } });
    resetDlg.value = false;
    ElMessage.success("口令已重置");
    await load();
  });
}

/**
 * 参数一律走 axios 的 params（H6-4 附带）：手拼 `"?id=" + row.id` 拼的是"看起来像 URL 的字符串"，
 * 值里但凡有一个需要转义的东西（角色名将来可能是用户输入）就是静默改参数；
 * 服务端读的是 @RequestParam，交给 axios 序列化才是它的对口写法。
 */
async function setRole(row) {
  await run("role:" + row.id, async () => {
    try {
      await http.post("/admins/role", null, { params: { id: row.id, role: row.role } });
      ElMessage.success("角色已更新");
    } catch (e) {
      await load();                       // 服务端拒绝（如最后一个超管）时回滚下拉框
    }
  });
}

async function setStatus(row, status) {
  await run("status:" + row.id, async () => {
    await http.post("/admins/status", null, { params: { id: row.id, status } });
    ElMessage.success(status ? "已停用" : "已启用");
    await load();
  });
}

async function remove(row) {
  await run("del:" + row.id, async () => {
    await ElMessageBox.confirm(`删除管理员 ${row.user}？该操作不可撤销。`, "确认删除", { type: "warning" });
    await http.post("/admins/remove", null, { params: { id: row.id } });
    ElMessage.success("已删除");
    await load();
  });
}

// 这里原来还有一句"非超管给出警告"：router.js 的守卫已经先把非超管弹去 /dashboard 了，
// 这个视图只有超管进得来，那段判断永远不会被跑到（H6-6 的死逻辑）。
onMounted(load);
</script>

<style scoped>
.bar { display:flex; align-items:center; gap:10px; margin-bottom:10px; }
.bar .hint { flex:1; }
.hint { color:#94a3b8; font-size:12px; }
</style>
