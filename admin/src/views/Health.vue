<template>
  <div>
    <div class="bar">
      <el-button :loading="loading" @click="run(true)">重新体检</el-button>
      <el-tag v-if="data" :type="data.ok ? 'success' : 'danger'" size="large">
        {{ data.ok ? '字段与引用全部通过' : (data.issueCount + ' 处问题') }}
      </el-tag>
      <el-tag v-if="data && data.warningCount" type="warning">
        {{ data.warningCount }} 条优化提示
      </el-tag>
      <el-tag v-if="data" type="info">已检查 {{ data.checked }} 行</el-tag>
      <el-tag v-if="data">内容版本 v{{ data.version }}</el-tag>
      <!-- 这份结论是重扫的还是复用的，得说出来：不说的话，"我刚改了内容怎么还报这个问题"就只能靠猜 -->
      <el-tag v-if="data" size="small" :type="data.fromCache ? 'info' : 'success'" effect="plain">
        {{ provenance }}
      </el-tag>
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

/** 这份结论是第几次扫描产出的、是不是复用来的（H6-3：整表扫描按 content_version 缓存了）。 */
const provenance = computed(() => {
  const d = data.value;
  if (!d) return "";
  const n = d.runId == null ? "?" : d.runId;
  return d.fromCache ? `复用第 ${n} 次扫描的结果` : `第 ${n} 次扫描，刚重算`;
});

const filtered = computed(() => {
  const list = data.value?.issues || [];
  if (!q.value) return list;
  const k = q.value.toLowerCase();
  return list.filter(r => (r.type + ":" + r.id).toLowerCase().includes(k));
});

// 打开面板这一次允许吃缓存（没人改过内容时结论本来就不可能变），
// 而【重新体检】必须绕过去 —— 一个只会回缓存的"重新体检"等于没有那个按钮。
onMounted(() => run(false));
async function run(fresh) {
  loading.value = true;
  try { data.value = await http.get("/content/health", { params: fresh ? { fresh: true } : {} }); }
  finally { loading.value = false; }
}
function fix(row) { router.push({ path: "/content", query: { type: row.type, id: row.id } }); }
</script>

<style scoped>
.bar { display:flex; align-items:center; gap:8px; margin-bottom:12px; }
.err { color:var(--el-color-danger); font-size:13px; line-height:1.6; }
</style>
