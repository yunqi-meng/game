<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <h2>化学纪元 · 管理后台</h2>
      <el-form @submit.prevent="submit">
        <el-form-item><el-input v-model="user" placeholder="管理员账号" /></el-form-item>
        <el-form-item><el-input v-model="pass" type="password" show-password placeholder="密码" @keyup.enter="submit" /></el-form-item>
        <el-button type="primary" :loading="loading" style="width:100%" @click="submit">登录</el-button>
      </el-form>
      <p class="tip">默认超级管理员见后端启动日志（可通过环境变量覆盖）</p>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useAuth } from "../store";

const auth = useAuth();
const router = useRouter();
const user = ref("admin");
const pass = ref("");
const loading = ref(false);

async function submit() {
  if (!user.value || !pass.value) return ElMessage.warning("请输入账号和密码");
  loading.value = true;
  try {
    await auth.login(user.value, pass.value);
    ElMessage.success("登录成功");
    router.push("/dashboard");
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.login-wrap { height: 100vh; display: flex; align-items: center; justify-content: center; }
.login-card { width: 360px; }
.tip { color: #999; font-size: 12px; }
</style>
