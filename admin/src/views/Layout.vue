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
        <el-menu-item index="/moderation">🛡️ 审核与审计</el-menu-item>
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
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useAuth } from "../store";

const route = useRoute();
const router = useRouter();
const auth = useAuth();
const roleLabel = computed(() => ({ super: "超级管理员", editor: "编辑", viewer: "只读" }[auth.role] || auth.role));
const passBox = ref(false);
const busy = ref(false);
const pf = reactive({ old: "", new: "", repeat: "" });

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
</style>
