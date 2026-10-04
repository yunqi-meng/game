<template>
  <div>
    <div class="bar">
      <el-button :loading="loading" @click="run">重新体检</el-button>
      <el-tag v-if="data" :type="data.ok ? 'success' : 'danger'" size="large">
        {{ data.ok ? '字段与引用全部通过' : (data.issueCount + ' 处问题') }}
      </el-tag>
      <el-tag v-if="data && data.warningCount" type="warning">
        {{ data.warningCount }} 条优化提示
      </el-tag>
      <el-tag v-if="data" type="info">已检查 {{ data.checked }} 行</el-tag>
      <el-tag v-if="data">内容版本 v{{ data.version }}</el-tag>
      <el-input v-model="q" placeholder="筛选类型/ID" style="width:200px;margin-left:auto" clearable />
    </div>

    <el-alert v-if="data && data.ok" type="success" :closable="false" show-icon
      title="所有内容行均通过字段与引用校验，可安全下发客户端。" style="margin-bottom:12px" />
    <el-alert v-else-if="data" type="error" :closable="false" show-icon
      :title="'存在 ' + data.issueCount + ' 处会导致下发异常的问题，请修正后再发布。'" style="margin-bottom:12px" />

    <el-table :data="filtered" border v-loading="loading" height="calc(100vh - 230px)">
      <el-table-column prop="type" label="类型" width="140" />
      <el-table-column prop="id" label="ID" width="160" />
      <el-table-column label="问题">
        <template #default="s">
          <div v-for="(e,i) in s.row.errors" :key="i" class="err">{{ e }}</div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90">
        <template #default="s">
          <el-button link type="primary" @click="fix(s.row)">定位</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-collapse v-if="data && data.warningCount" style="margin-top:12px">
      <el-collapse-item :title="'优化提示（' + data.warningCount + '，非阻断）'">
        <div v-for="(w,i) in data.warnings" :key="i" class="warn">
          <b>{{ w.type }}:{{ w.id }}</b> — {{ w.errors.join("；") }}
          <el-button link type="primary" size="small" @click="fix(w)">定位</el-button>
        </div>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import http from "../api";

const router = useRouter();
const loading = ref(false);
const data = ref(null);
const q = ref("");

const filtered = computed(() => {
  const list = data.value?.issues || [];
  if (!q.value) return list;
  const k = q.value.toLowerCase();
  return list.filter(r => (r.type + ":" + r.id).toLowerCase().includes(k));
});

onMounted(run);
async function run() {
  loading.value = true;
  try { data.value = await http.get("/content/health"); }
  finally { loading.value = false; }
}
function fix(row) { router.push({ path: "/content", query: { type: row.type, id: row.id } }); }
</script>

<style scoped>
.bar { display:flex; align-items:center; gap:8px; margin-bottom:12px; }
.err { color:var(--el-color-danger); font-size:13px; line-height:1.6; }
</style>
