<template>
  <div>
    <div class="bar">
      <el-select v-model="type" style="width:160px" @change="onTypeChange">
        <el-option v-for="t in types" :key="t" :label="t" :value="t" />
      </el-select>
      <el-input v-model="q" placeholder="搜索名称/ID" style="width:220px" @keyup.enter="reload" clearable />
      <el-button :loading="busy('list')" @click="reload">查询</el-button>
      <el-button v-if="auth.canWrite" @click="openNew">＋ 新增</el-button>
      <el-button @click="goHealth">内容体检</el-button>
      <div style="flex:1"></div>
      <!-- 内容版本只是"现在下发的是哪一版"这一个数（H6-6）：
           以前这里挂一个【发布】按钮，可服务端每一次写入自己就把版本顶上去并失效缓存，
           那个按钮点出来的效果和保存完全一样——多余的动作比没有动作更容易让人误判。 -->
      <el-tag v-if="version != null">v{{ version }}</el-tag>
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
      <el-table-column label="更新时间" width="150">
        <template #default="s">{{ fmt(s.row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="270">
        <template #default="s">
          <el-button link type="primary" :loading="busy('edit:' + s.row.itemId)" @click="openEdit(s.row)">编辑</el-button>
          <el-button link :loading="busy('hist:' + s.row.itemId)" @click="openHistory(s.row)">历史</el-button>
          <el-button v-if="auth.canWrite" link :loading="busy('toggle:' + s.row.itemId)"
                     @click="toggle(s.row)">{{ s.row.enabled ? '停用' : '启用' }}</el-button>
          <el-button v-if="auth.canWrite" link type="danger" :loading="busy('del:' + s.row.itemId)"
                     @click="del(s.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination style="margin-top:10px" background layout="prev, pager, next, total"
      :total="total" :page-size="size" :current-page="page" @current-change="onPage" />

    <el-drawer v-model="hist.open" :title="'历史 · ' + hist.type + ' / ' + hist.id" size="640px">
      <el-alert :closable="false" type="info" style="margin-bottom:10px"
        title="每一行是「被这次改动顶掉之前的那一版」。当前生效的内容不在列表里——它就是现在这一行。" />
      <el-table :data="hist.rows" border v-loading="hist.loading" max-height="560">
        <el-table-column label="时间" width="150">
          <template #default="s">{{ fmt(s.row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="顶掉它的动作" width="110">
          <template #default="s">
            <el-tag :type="s.row.source === 'delete' ? 'danger' : 'info'">{{ SOURCE_LABEL[s.row.source] || s.row.source }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="operator" label="操作人" width="110" />
        <el-table-column label="当时版本" width="90">
          <template #default="s">v{{ s.row.version }}</template>
        </el-table-column>
        <el-table-column label="大小" width="90">
          <template #default="s">{{ kb(s.row.bytes) }}</template>
        </el-table-column>
        <el-table-column prop="name" label="当时的名称" show-overflow-tooltip />
        <el-table-column label="" width="80">
          <template #default="s">
            <el-button v-if="auth.canWrite" link type="primary" :loading="busy('rollback:' + s.row.id)"
              @click="rollback(s.row)">回滚</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!hist.loading && !hist.rows.length"
        description="这一行还没被人改过（改一次才留下一版）" />
    </el-drawer>

    <el-dialog v-model="dlg" :title="editing ? '编辑内容 · ' + curId : '新增内容'" width="720px" top="6vh">
      <el-alert v-if="!editing" :closable="false" type="info" style="margin-bottom:8px"
        title="按字段填写；保存前后端会做类型化+引用校验，不通过会明确报错。" />
      <el-alert v-else :closable="false" :type="stamp ? 'info' : 'warning'" style="margin-bottom:8px"
        :title="'这一行最后由 ' + (stampBy || '未知') + ' 在 ' + (fmt(stamp) || '未知时间') + ' 改过。保存时面板会把这句话带回去当版本号：期间若有人动过它，服务端会拒绝这次写入并让你先载入最新，而不是被谁静默盖掉。'" />

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
        <el-button v-if="auth.canWrite" type="primary" :loading="busy('save')" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import http from "../api";
import { useBusy } from "../busy";
import { askReloadOnConflict, explainConflict } from "../conflict";
import { useAuth } from "../store";
import SchemaField from "../components/SchemaField.vue";

const router = useRouter();
const route = useRoute();
const auth = useAuth();
/* 防连点（H6-4）：启停/删除/回滚这类"再点一次就反了"的动作各带一个在途标记，键里带上那一行的 ID，
   所以 A 行在途不影响 B 行——见 ../busy.js。 */
const { busy, run } = useBusy();

const types = ref([]);
const type = ref("reaction");
const q = ref("");
const rows = ref([]);
const total = ref(0);
const page = ref(1);
const size = 50;
const loading = ref(false);
/** 现在下发的是哪一版；拿不到就整个不显示（宁缺毋假，H6-6）。 */
const version = ref(null);

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
const meta = ref({ name: "", sort: 0, enabled: 1 });
/**
 * 乐观锁的版本号（H6-2）：这一行最后改动的时间戳，来自列表接口回传的 updatedAt，
 * 由库的 ON UPDATE CURRENT_TIMESTAMP(3) 盖章。打开对话框时记下，保存时原样带回 expect；
 * 空串表示"新增，这行还不该存在"。
 */
const stamp = ref("");
const stampBy = ref("");        // 只给对话框那句说明用：让人看清自己在改谁的那一版

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
  await reload();
  refreshVersion();
  await locate(route.query);
});

/* 体检页那句【定位】是把 type/id 塞进 query 再跳到 /content（H6-6）。
   以前这份 query 只在 onMounted 读一次：人已经停在 /content 时点【定位】，路由变了、
   组件却是复用的那一个，mounted 不会再跑第二遍，于是"改完回去看一眼"这条路的最后一跳什么都不做。
   现在盯着 query 本身：每次带着新的 type/id 进来都照着走一遍。 */
watch(() => route.query, (qq) => { locate(qq); });

async function locate(qq) {
  if (!qq) return;
  if (qq.type && types.value.includes(qq.type) && qq.type !== type.value) {
    type.value = qq.type;
    await reload();
  }
  if (!qq.id) return;
  const row = rows.value.find(r => r.itemId === qq.id);
  if (row) await openEdit(row);
  else ElMessage.info(`当前这一页里没有 ${qq.id}（可能不在这一页），请按 ID 查询后再编辑`);
}

function onTypeChange() { reload(); }
async function reload() {
  await run("list", async () => {
    loading.value = true;
    try {
      const d = await http.get("/content/items", { params: { type: type.value, q: q.value, size, off: (page.value - 1) * size } });
      rows.value = d.rows; total.value = d.total;
    } finally { loading.value = false; }
  });
}
/**
 * 内容版本号（H6-6）：这一个数以前是把整张看板的聚合查询（/dashboard/overview 要算 DAU、
 * 近 7 日事件分布）拉回来只为了读它的 contentVersion 字段。现在走 /content/version 那个轻接口，
 * 拿不到就什么都不显示——绝不许"退化回去拉 overview"，那样这条改动等于没做，只是多了一条隐蔽路径。
 */
async function refreshVersion() {
  try {
    const d = await http.get("/content/version", { silent: true });
    version.value = d && d.contentVersion != null ? d.contentVersion : null;
  } catch (e) { version.value = null; }
}
function onPage(p) { page.value = p; reload(); }
function goHealth() { router.push("/health"); }

async function openEdit(row) {
  await run("edit:" + row.itemId, async () => {
    editing.value = true; curId.value = row.itemId; idInput.value = "";
    // 版本号取列表那一行的 updatedAt：它由库的 ON UPDATE 盖章，所以我们抄回来的就是服务端认的那一份
    stamp.value = row.updatedAt || ""; stampBy.value = row.updatedBy || "";
    const data = await http.get("/content/item", { params: { type: type.value, id: row.itemId } });
    model.value = (data && typeof data === "object") ? data : {};
    meta.value = { name: row.name || "", sort: row.sort || 0, enabled: row.enabled ? 1 : 0 };
    tab.value = "form"; jsonText.value = "";
    dlg.value = true;
  });
}
function openNew() {
  editing.value = false; curId.value = ""; idInput.value = "";
  stamp.value = ""; stampBy.value = "";      // 没有上一版：保存时带 expect=none，撞上已有键就该被拒
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
  await run("save", async () => {
    try {
      // expect：把打开对话框那一刻的版本号带回去（空串=新增，服务端按「这行还不该存在」判）
      await http.put("/content/item", payload,
        { params: { strict: true, expect: stamp.value || "none" } });
      ElMessage.success("已保存并通过校验，客户端下一次取内容就是这一版");
      dlg.value = false; refreshVersion(); reload();
    } catch (e) {
      if (await askReloadOnConflict(e, "「载入最新」会把你这份没保存的改动就地换成别人那一版；"
        + "想留着它就先别关这个弹窗，把字段抄到别处。")) await reopenWith(bizId);
      // 其余错误（校验不通过等）拦截器已提示，弹窗保持打开供修正
    }
  });
}

/** 冲突之后唯一有用的动作：刷新列表，用当前生效那一版重开表单（不拿旧草稿去覆盖）。 */
async function reopenWith(id) {
  await reload();
  const row = rows.value.find(r => r.itemId === id);
  if (!row) {
    dlg.value = false;
    ElMessage.info(`当前这一页里没有 ${type.value}:${id}（可能已被删除或不在这一页），请按 ID 查询后再编辑`);
    return;
  }
  await openEdit(row);
}

async function toggle(row) {
  await run("toggle:" + row.itemId, async () => {
    try {
      await http.post("/content/toggle", {}, {
        params: { type: type.value, id: row.itemId, enabled: row.enabled ? 0 : 1, expect: row.updatedAt || "none" },
      });
      refreshVersion();
    } catch (e) {
      // 开关没有"草稿"要保：说清楚是谁动过，然后把列表刷回真相，让运营自己再决定一次
      await explainConflict(e);
    }
    reload();
  });
}
async function del(row) {
  await run("del:" + row.itemId, async () => {
    await ElMessageBox.confirm(`确认删除 ${type.value}:${row.itemId}？删除前的内容会先存进历史，删错了能在【历史】里退回来。`, "提示", { type: "warning" });
    try {
      await http.delete("/content/item", {
        params: { type: type.value, id: row.itemId, expect: row.updatedAt || "none" },
      });
      ElMessage.success("已删除");
      refreshVersion();
    } catch (e) {
      if (await explainConflict(e)) { reload(); return; }
    }
    reload();
  });
}

/* ---------------- 历史与回滚（G3） ---------------- */

/** source 记的是"把那一版顶掉的动作"，不是"那一版当初怎么来的"——抽屉里要读的是前者。 */
const SOURCE_LABEL = { edit: "覆盖写", delete: "删除", toggle: "启停", rollback: "回滚" };
/**
 * stamp：打开抽屉那一刻 live 行的版本号。回滚写的就是 live 行，所以它得守和编辑同样的规矩（H6-2），
 * 否则"看见冲突，先回滚一版试试"会成了绕过乐观锁的那扇门。
 */
const hist = ref({ open: false, loading: false, rows: [], type: "", id: "", stamp: "" });

async function openHistory(row) {
  await run("hist:" + row.itemId, async () => {
    hist.value = { open: true, loading: true, rows: [], type: type.value, id: row.itemId, stamp: row.updatedAt || "" };
    await refreshHistory();
  });
}

/** 只重读历史列表，不动 stamp（回滚之后 stamp 来自服务端回体，比翻当前页可靠）。 */
async function refreshHistory() {
  hist.value.loading = true;
  try {
    hist.value.rows = (await http.get("/content/revisions",
      { params: { type: hist.value.type, id: hist.value.id } })) || [];
  } finally { hist.value.loading = false; }
}

async function rollback(r) {
  if (!hist.value.stamp)
    return ElMessage.warning("没拿到这一行的当前版本号（它已不在当前这一页）。请回列表按 ID 查询后再打开【历史】，"
      + "否则这次回滚会盖掉别人刚做的改动。");
  await run("rollback:" + r.id, async () => {
    await ElMessageBox.confirm(
      `把 ${hist.value.type}:${hist.value.id} 退回「${fmt(r.createdAt)} 被${SOURCE_LABEL[r.source] || r.source}顶掉的那一版」？\n`
      + "现在这一版会先存进历史，所以退回本身也能再退回来；这一版仍要过类型化校验，不通过会被拒。",
      "回滚确认", { type: "warning" });
    try {
      const d = await http.post("/content/rollback", {},
        { params: { rev: r.id, expect: hist.value.stamp } });
      ElMessage.success(`已退回，内容版本 v${d.version}`);
      version.value = d.version;
      // 回滚自己就改了 live 行：抽屉里手上的版本号换成服务端回体给的那一份，
      // 否则紧接着的第二次回滚会把自己刚才那次改动判成"别人改过"
      hist.value.stamp = d.updatedAt || "";
      await Promise.all([reload(), refreshHistory()]);
    } catch (e) {
      if (await explainConflict(e)) {
        hist.value.stamp = "";
        await reload();
      }
    }
  });
}

function fmt(t) { return t ? String(t).replace("T", " ").slice(0, 16) : ""; }
function kb(n) { const v = Number(n) || 0; return v > 1024 ? (Math.round(v / 102.4) / 10) + " KB" : v + " B"; }
</script>

<style scoped>
.bar { display:flex; align-items:center; gap:8px; margin-bottom:10px; }
</style>
