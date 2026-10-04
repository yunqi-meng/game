<template>
  <div>
    <div class="bar">
      <el-select v-model="type" style="width:160px" @change="onTypeChange">
        <el-option v-for="t in types" :key="t" :label="t" :value="t" />
      </el-select>
      <el-input v-model="q" placeholder="搜索名称/ID" style="width:220px" @keyup.enter="reload" clearable />
      <el-button @click="reload">查询</el-button>
      <el-button v-if="auth.canWrite" @click="openNew">＋ 新增</el-button>
      <el-button @click="goHealth">内容体检</el-button>
      <div style="flex:1"></div>
      <el-tag>v{{ version }}</el-tag>
      <el-button v-if="auth.canWrite" type="primary" @click="publish">发布（刷新客户端缓存）</el-button>
    </div>

    <el-table :data="rows" border v-loading="loading" height="calc(100vh - 190px)">
      <el-table-column prop="itemId" label="ID" width="150" />
      <el-table-column prop="name" label="名称" />
      <el-table-column prop="sort" label="排序" width="80" />
      <el-table-column label="状态" width="90">
        <template #default="s">
          <el-tag :type="s.row.enabled ? 'success' : 'info'">{{ s.row.enabled ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="updatedBy" label="修改人" width="120" />
      <el-table-column label="操作" width="220">
        <template #default="s">
          <el-button link type="primary" @click="openEdit(s.row)">编辑</el-button>
          <el-button v-if="auth.canWrite" link @click="toggle(s.row)">{{ s.row.enabled ? '停用' : '启用' }}</el-button>
          <el-button v-if="auth.canWrite" link type="danger" @click="del(s.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination style="margin-top:10px" background layout="prev, pager, next, total"
      :total="total" :page-size="size" :current-page="page" @current-change="onPage" />

    <el-dialog v-model="dlg" :title="editing ? '编辑内容 · ' + curId : '新增内容'" width="720px" top="6vh">
      <el-alert v-if="!editing" :closable="false" type="info" style="margin-bottom:8px"
        title="按字段填写；保存前后端会做类型化+引用校验，不通过会明确报错。" />

      <el-tabs v-model="tab" @tab-change="onTab">
        <el-tab-pane label="表单" name="form">
          <el-form label-width="120px" label-position="right">
            <el-form-item v-if="!editing" label="业务ID" required>
              <el-input v-model="idInput" placeholder="如 R200 / H2SO4 / 自定义" />
            </el-form-item>
            <el-form-item v-else label="业务ID">
              <el-input :model-value="curId" readonly />
            </el-form-item>

            <SchemaField v-for="f in editFields" :key="f.key" :field="f" :model="model" :options="options"
              @update:model="(v) => (model = v)" />

            <el-divider content-position="left">元信息</el-divider>
            <el-form-item label="名称(列表显示)">
              <el-input v-model="meta.name" :placeholder="autoName || '留空自动取'" />
            </el-form-item>
            <el-form-item label="排序">
              <el-input-number v-model="meta.sort" :min="0" :precision="0" controls-position="right" />
            </el-form-item>
            <el-form-item label="启用">
              <el-switch v-model="meta.enabled" :active-value="1" :inactive-value="0" />
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="JSON" name="json">
          <el-input v-model="jsonText" type="textarea" :rows="18" style="font-family:monospace" />
        </el-tab-pane>
      </el-tabs>

      <template #footer>
        <el-button @click="dlg=false">取消</el-button>
        <el-button v-if="auth.canWrite" type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import http from "../api";
import { useAuth } from "../store";
import SchemaField from "../components/SchemaField.vue";

const router = useRouter();
const route = useRoute();
const auth = useAuth();

const types = ref([]);
const type = ref("reaction");
const q = ref("");
const rows = ref([]);
const total = ref(0);
const page = ref(1);
const size = 50;
const loading = ref(false);
const version = ref(0);

// schema：{ type: [Field] }；options：{ refs, enums }
const allFields = ref({});
const options = ref({ refs: {}, enums: {} });

const dlg = ref(false);
const tab = ref("form");
const jsonText = ref("");
const model = ref({});          // 表单绑定的数据对象（含 id）
const idInput = ref("");        // 新增时的业务 ID
const curId = ref("");          // 编辑时的只读 ID
const editing = ref(false);
const saving = ref(false);
const meta = ref({ name: "", sort: 0, enabled: 1 });

// 表单里不重复渲染 id 字段（id 由业务ID输入/只读框管理）
const editFields = computed(() =>
  (allFields.value[type.value] || []).filter(f => f.key !== "id"));
const autoName = computed(() => {
  const d = model.value;
  return d.zh || d.q || d.name || "";
});

onMounted(async () => {
  const [sch, opt] = await Promise.all([
    http.get("/content/schema"),
    http.get("/content/options"),
  ]);
  allFields.value = sch;
  options.value = opt;
  types.value = await http.get("/content/types");
  if (types.value.length && !types.value.includes(type.value)) type.value = types.value[0];
  if (route.query.type && types.value.includes(route.query.type)) type.value = route.query.type;
  await reload();
  version.value = (await http.get("/dashboard/overview")).contentVersion;
  if (route.query.id) {
    const row = rows.value.find(r => r.itemId === route.query.id);
    if (row) openEdit(row);
  }
});

function onTypeChange() { reload(); }
async function reload() {
  loading.value = true;
  try {
    const d = await http.get("/content/items", { params: { type: type.value, q: q.value, size, off: (page.value - 1) * size } });
    rows.value = d.rows; total.value = d.total;
  } finally { loading.value = false; }
}
function onPage(p) { page.value = p; reload(); }
function goHealth() { router.push("/health"); }

async function openEdit(row) {
  editing.value = true; curId.value = row.itemId; idInput.value = "";
  const data = await http.get("/content/item", { params: { type: type.value, id: row.itemId } });
  model.value = (data && typeof data === "object") ? data : {};
  meta.value = { name: row.name || "", sort: row.sort || 0, enabled: row.enabled ? 1 : 0 };
  tab.value = "form"; jsonText.value = "";
  dlg.value = true;
}
function openNew() {
  editing.value = false; curId.value = ""; idInput.value = "";
  model.value = blankFromSchema(type.value);
  meta.value = { name: "", sort: 0, enabled: 1 };
  tab.value = "form"; jsonText.value = "";
  dlg.value = true;
}

function blankFromSchema(t) {
  const out = {};
  for (const f of allFields.value[t] || []) out[f.key] = sampleOf(f);
  return out;
}
function sampleOf(f) {
  switch (f.kind) {
    case "int": case "num": return 0;
    case "bool": return false;
    case "enum": return f.options && f.options.length ? f.options[0] : "";
    case "enums": case "refs": case "stringList": return [];
    case "subMap": return {};
    case "obj": { const o = {}; (f.sub || []).forEach(sf => o[sf.key] = sampleOf(sf)); return o; }
    default: return "";
  }
}

// Tab 切换：保证表单与 JSON 两个视图数据一致
function onTab(name) {
  if (name === "json") {
    const bizId = editing.value ? curId.value : idInput.value;
    const snapshot = bizId ? { ...model.value, id: bizId } : { ...model.value };
    jsonText.value = JSON.stringify(snapshot, null, 2);
  } else {
    try { model.value = JSON.parse(jsonText.value || "{}"); }
    catch (e) { ElMessage.error("JSON 解析失败：" + e.message); }
  }
}

async function save() {
  let data;
  if (tab.value === "json") {
    try { data = JSON.parse(jsonText.value || "{}"); }
    catch (e) { return ElMessage.error("JSON 解析失败：" + e.message); }
  } else {
    data = { ...model.value };
  }
  const bizId = editing.value ? curId.value : idInput.value;
  if (!bizId) return ElMessage.error("请填写业务ID");
  data.id = bizId;

  const payload = {
    type: type.value,
    data,
    name: meta.value.name || data.zh || data.q || data.name || data.id,
    sort: meta.value.sort ?? 0,
    enabled: meta.value.enabled ?? 1,
  };
  saving.value = true;
  try {
    await http.put("/content/item", payload, { params: { strict: true } });
    ElMessage.success("已保存并通过校验（点发布通知客户端）");
    dlg.value = false; reload();
  } catch (e) {
    // 拦截器已弹出后端校验错误信息，这里保持弹窗不关闭供用户修正
  } finally { saving.value = false; }
}

async function toggle(row) {
  await http.post("/content/toggle", {}, { params: { type: type.value, id: row.itemId, enabled: row.enabled ? 0 : 1 } });
  reload();
}
async function del(row) {
  await ElMessageBox.confirm(`确认删除 ${type.value}:${row.itemId}？`, "提示", { type: "warning" });
  await http.delete("/content/item", { params: { type: type.value, id: row.itemId } });
  ElMessage.success("已删除，记得发布");
  reload();
}
async function publish() {
  const d = await http.post("/content/publish", {});
  version.value = d.version;
  ElMessage.success("已发布，新版本 v" + d.version);
}
</script>

<style scoped>
.bar { display:flex; align-items:center; gap:8px; margin-bottom:10px; }
</style>
