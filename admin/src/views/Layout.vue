<template>
  <el-container style="height:100vh">
    <el-aside width="210px">
      <div class="brand">⚗️ 化学纪元后台</div>
      <el-menu :default-active="route.path" router background-color="#1f2937" text-color="#cbd5e1" active-text-color="#38bdf8">
        <el-menu-item index="/dashboard">📊 数据看板</el-menu-item>
        <el-menu-item index="/content">🧪 内容管理</el-menu-item>
        <el-menu-item index="/health">🩺 内容体检</el-menu-item>
        <el-menu-item index="/config">⚙️ 运营配置</el-menu-item>
        <el-menu-item index="/users">👤 用户与存档</el-menu-item>
        <el-menu-item index="/moderation">
          <span>🛡️ 审核与审计</span>
          <!-- 真数：/admin/api/moderation/pending 读 status='open' 的计数。
               这个数字曾经在前端写死成 0，而"停在 0"比不显示更糟——它会让人以为确实没人举报。 -->
          <span v-if="pending > 0" class="pend">待处理 {{ pending > 99 ? "99+" : pending }}</span>
        </el-menu-item>
        <el-menu-item v-if="auth.isSuper" index="/admins">🔑 管理员账号</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="head">
        <span>{{ route.meta.title || "管理后台" }}</span>
        <span class="who">
          {{ auth.user }}
          <el-tag size="small" :type="auth.role==='super'?'danger':(auth.role==='editor'?'warning':'info')">{{ roleLabel }}</el-tag>
          <el-button link @click="openPass">修改口令</el-button>
          <el-button link @click="logout">退出</el-button>
        </span>
      </el-header>
      <el-main><router-view /></el-main>
    </el-container>

    <!-- 初始口令未换时不可关闭：服务端此时也只放行改密端点，关掉窗口等于什么都做不了 -->
    <el-dialog v-model="passBox" title="修改登录口令" width="420px"
               :close-on-click-modal="!auth.mustChange" :show-close="!auth.mustChange">
      <el-alert v-if="auth.mustChange" type="warning" :closable="false"
                title="当前使用的是初始口令，改密后才能使用后台其他功能。" style="margin-bottom:12px" />
      <el-form :model="pf" label-width="80px" @submit.prevent>
        <el-form-item label="原口令"><el-input v-model="pf.old" type="password" show-password autocomplete="current-password" /></el-form-item>
        <el-form-item label="新口令"><el-input v-model="pf.new" type="password" show-password autocomplete="new-password" /></el-form-item>
        <el-form-item label="确认"><el-input v-model="pf.repeat" type="password" show-password autocomplete="new-password" /></el-form-item>
      </el-form>
      <div class="tip">至少 8 位，且不能是常见弱口令或等于账号名。</div>
      <template #footer>
        <el-button @click="cancel">{{ auth.mustChange ? "退出登录" : "取消" }}</el-button>
        <el-button type="primary" :loading="busy" @click="submit">提交并重新登录</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import http from "../api";
import { useAuth } from "../store";

const route = useRoute();
const router = useRouter();
const auth = useAuth();
const roleLabel = computed(() => ({ super: "超级管理员", editor: "编辑", viewer: "只读" }[auth.role] || auth.role));
const passBox = ref(false);
const busy = ref(false);
const pf = reactive({ old: "", new: "", repeat: "" });

/* 待处理举报角标。轮询而不是事件：处理一条是在 Moderation.vue 里发生的，
   跨组件通知为一个角标不值当引入一层总线；60 秒的延迟也不会漏掉任何一条工单——它只是别停在上一轮。 */
const pending = ref(0);
let pendTimer = 0;
let pendQuiet = false;   // 后台轮询失败就停手：这条不是运营点的按钮，失败却每 60 秒弹一条红字，等于用噪声代替信息
async function reloadPending() {
  if (pendQuiet || auth.mustChange) return;   // 强制改密期间服务端只放行改密端点，问也是 403
  try {
    const d = await http.get("/moderation/pending");
    pending.value = Number(d && d.open) || 0;
  } catch (e) {
    pendQuiet = true;
    clearInterval(pendTimer);
  }
}
watch(() => route.path, () => { pendQuiet = false; reloadPending(); });
onMounted(() => { reloadPending(); pendTimer = setInterval(reloadPending, 60000); });
// 定时器必须跟着组件销毁一起停：退出登录后 Layout 会卸载，留下的是一个每 60 秒撞 401 的幽灵请求
onBeforeUnmount(() => clearInterval(pendTimer));

function openPass() { passBox.value = true; }
function logout() {
  auth.logout();
  router.push("/login");
}
function cancel() {
  if (!auth.mustChange) { passBox.value = false; return; }
  auth.logout();                       // 强制改密时唯一的出口是重新登录
  router.push("/login");
}
async function submit() {
  if (pf.new !== pf.repeat) { ElMessage.error("两次输入的新口令不一致"); return; }
  busy.value = true;
  try {
    await auth.changePass(pf.old, pf.new);   // store 里会清令牌
    passBox.value = false;
    ElMessage.success("口令已更新，请用新口令重新登录");
    router.push("/login");
  } finally {
    busy.value = false;
  }
}
onMounted(() => { if (auth.mustChange) passBox.value = true; });
</script>

<style scoped>
.brand { color:#fff; font-weight:700; padding:16px 14px; background:#111827; }
.el-aside { background:#1f2937; }
.head { display:flex; align-items:center; justify-content:space-between; background:#fff; border-bottom:1px solid #eee; }
.who { display:flex; align-items:center; gap:8px; }
.tip { color:#94a3b8; font-size:12px; }
.pend { margin-left:auto; background:#dc2626; color:#fff; font-size:11px; font-weight:700; border-radius:9px; padding:1px 7px; }
</style>
