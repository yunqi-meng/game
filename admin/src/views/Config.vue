<template>
  <div>
    <div class="bar">
      <el-input v-model="newKey" placeholder="新增配置键（如 daily_gift_coins）" style="width:240px" />
      <el-button v-if="auth.canWrite" @click="addNew">＋ 新增配置</el-button>
      <div style="flex:1"></div>
      <span class="tip">「生效范围」以服务端引擎实际读取为准；标为<el-tag size="small" type="info" effect="plain">仅前端</el-tag>的键改了不会改变结算</span>
    </div>

    <el-table :data="rows" border v-loading="loading" height="calc(100vh - 160px)">
      <el-table-column prop="cfgKey" label="键" width="150" />
      <el-table-column label="名称" width="140">
        <template #default="s">
          <span v-if="specOf(s.row)">{{ specOf(s.row).zh }}</span>
          <el-tag v-else size="small" type="warning" effect="plain">未收录</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="生效范围" width="104">
        <template #default="s">
          <el-tag v-if="specOf(s.row)" size="small" :type="scopeType(specOf(s.row).scope)">{{ specOf(s.row).scope }}</el-tag>
          <span v-else class="tip">—</span>
        </template>
      </el-table-column>
      <el-table-column label="作用（悬停看全文与当前值）" min-width="200">
        <template #default="s">
          <el-tooltip v-if="specOf(s.row)" placement="top" effect="dark" :show-after="120">
            <template #content>
              <div style="max-width:440px;line-height:1.7">
                <div>{{ specOf(s.row).effect }}</div>
                <div style="color:#fbbf24;margin-top:4px">{{ specOf(s.row).note }}</div>
                <div style="color:#cbd5e1;margin-top:4px">当前值：{{ fmt(s.row.cfgValue, 200) }}</div>
                <div style="color:#94a3b8;margin-top:4px">出处：{{ specOf(s.row).refs }}</div>
              </div>
            </template>
            <span class="eff">{{ headOf(specOf(s.row).effect) }}</span>
          </el-tooltip>
          <span v-else class="tip">引擎的 Content.Config 里没有这个字段：只会原样下发给前端，改了不影响服务端结算。</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="96">
        <template #default="s">
          <el-button link type="primary" @click="edit(s.row)">编辑</el-button>
          <el-button v-if="auth.canWrite" link type="danger" @click="del(s.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dlg" :title="'编辑配置 · ' + cur" width="680px" top="6vh">
      <!-- 保存前就把"改了会怎样"摆在编辑区上方，避免调崩经济却以为是下发没生效 -->
      <el-alert v-if="curSpec" :closable="false" :type="curSpec.scope === '仅前端' ? 'info' : (curSpec.scope === '部分生效' ? 'warning' : 'success')"
                style="margin-bottom:14px">
        <template #title><b>{{ curSpec.zh }}</b> · {{ curSpec.scope }}</template>
        <div class="spec">{{ curSpec.effect }}</div>
        <div class="spec warn">{{ curSpec.note }}</div>
        <div class="spec ref">出处：{{ curSpec.refs }}</div>
      </el-alert>
      <el-alert v-else :closable="false" type="warning" style="margin-bottom:14px"
                title="未收录的配置键"
                description="服务端 Content.Config 里没有这个字段，保存后只会原样下发给前端，不会参与任何结算。" />
      <el-form label-width="90px" label-position="right">
        <el-form-item label="键"><el-input :model-value="cur" readonly /></el-form-item>
        <el-form-item label="分类"><el-input v-model="meta.category" placeholder="general / economy / ..." /></el-form-item>
        <el-form-item label="备注"><el-input v-model="meta.remark" placeholder="来源常量名或自定义备注（作用说明看上方提示条）" /></el-form-item>
        <el-form-item label="值">
          <div style="width:100%">

            <!-- number -->
            <el-input-number v-if="kind === 'number'" v-model="val" :step="kindMeta.step || 1"
                             :precision="kindMeta.precision" controls-position="right" />

            <!-- string list -->
            <div v-else-if="kind === 'strList'" style="width:100%">
              <div v-for="(s,i) in val" :key="i" class="row">
                <el-input v-model="val[i]" style="flex:1" />
                <el-button link type="danger" @click="val.splice(i,1)">删除</el-button>
              </div>
              <el-button link type="primary" @click="val.push('')">＋ 添加</el-button>
            </div>

            <!-- number list -->
            <div v-else-if="kind === 'numList'" style="width:100%">
              <div v-for="(s,i) in val" :key="i" class="row">
                <el-input-number v-model="val[i]" :min="0" :precision="0" controls-position="right" />
                <el-button link type="danger" @click="val.splice(i,1)">删除</el-button>
              </div>
              <el-button link type="primary" @click="val.push(0)">＋ 添加</el-button>
            </div>

            <!-- discover_bonus: {级别: [下限,上限]} -->
            <div v-else-if="kind === 'bonusMap'" style="width:100%">
              <div v-for="k in Object.keys(val)" :key="k" class="row">
                <span class="lbl">Lv.{{ k }}</span>
                <el-input-number v-model="val[k][0]" :min="0" :precision="0" controls-position="right" />
                <span class="lbl">~</span>
                <el-input-number v-model="val[k][1]" :min="0" :precision="0" controls-position="right" />
              </div>
            </div>

            <!-- quality: [{q,zh,mult}] -->
            <div v-else-if="kind === 'quality'" style="width:100%">
              <div class="hdr"><span class="c1">等级q</span><span class="c2">名称</span><span class="c3">倍率</span><span class="c1"></span></div>
              <div v-for="(it,i) in val" :key="i" class="row">
                <el-input-number v-model="it.q" :min="0" :precision="0" controls-position="right" class="c1" />
                <el-input v-model="it.zh" class="c2" />
                <el-input-number v-model="it.mult" :step="0.1" :precision="2" controls-position="right" class="c3" />
                <el-button link type="danger" class="c1" @click="val.splice(i,1)">删除</el-button>
              </div>
              <el-button link type="primary" @click="val.push({q:val.length, zh:'', mult:1})">＋ 添加品质</el-button>
            </div>

            <!-- recharge: [{c,d}] -->
            <div v-else-if="kind === 'recharge'" style="width:100%">
              <div class="hdr"><span class="c2">充值(元)</span><span class="c2">钻石</span><span class="c1"></span></div>
              <div v-for="(it,i) in val" :key="i" class="row">
                <el-input-number v-model="it.c" :min="0" :precision="0" controls-position="right" class="c2" />
                <el-input-number v-model="it.d" :min="0" :precision="0" controls-position="right" class="c2" />
                <el-button link type="danger" class="c1" @click="val.splice(i,1)">删除</el-button>
              </div>
              <el-button link type="primary" @click="val.push({c:0,d:0})">＋ 添加档位</el-button>
            </div>

            <!-- lab_upgrades: {key:{zh,desc,step,baseCost,growth,max}} -->
            <div v-else-if="kind === 'labMap'" style="width:100%">
              <div v-for="k in Object.keys(val)" :key="k" class="lab">
                <div class="lab-h">
                  <b>{{ k }}</b>
                  <el-button link type="danger" @click="delete val[k]">删除</el-button>
                </div>
                <div class="row"><span class="lbl">名称</span><el-input v-model="val[k].zh" style="flex:1" /></div>
                <div class="row"><span class="lbl">说明</span><el-input v-model="val[k].desc" style="flex:1" /></div>
                <div class="row">
                  <span class="lbl">step</span><el-input-number v-model="val[k].step" :precision="0" controls-position="right" />
                  <span class="lbl">baseCost</span><el-input-number v-model="val[k].baseCost" :precision="0" controls-position="right" />
                </div>
                <div class="row">
                  <span class="lbl">growth</span><el-input-number v-model="val[k].growth" :step="0.1" :precision="2" controls-position="right" />
                  <span class="lbl">max</span><el-input-number v-model="val[k].max" :precision="0" controls-position="right" />
                </div>
              </div>
              <el-button link type="primary" @click="addLab">＋ 添加升级项</el-button>
            </div>

            <!-- 兜底：JSON -->
            <el-input v-else v-model="jsonText" type="textarea" :rows="8" style="font-family:monospace" />
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dlg=false">取消</el-button>
        <el-button v-if="auth.canWrite" type="primary" @click="save">保存并下发</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import http from "../api";
import { useAuth } from "../store";

const auth = useAuth();
const rows = ref([]);
const loading = ref(false);
const newKey = ref("");
const specs = ref([]);          // 后端 ConfigSpec：作用/生效范围/风险/代码出处

function specOf(row) { return specs.value.find(s => s.key === row.cfgKey) || null; }
function scopeType(scope) {
  return { "服务端结算": "danger", "服务端+前端": "warning", "部分生效": "warning", "仅前端": "info" }[scope] || "info";
}
function headOf(text) { return text.length > 26 ? text.slice(0, 26) + "…" : text; }

const KIND = {
  buy_rate: { kind: "number", step: 0.1, precision: 2 },
  sell_rate: { kind: "number", step: 0.1, precision: 2 },
  first_sell_bonus: { kind: "number", step: 0.1, precision: 2 },
  start_coins: { kind: "number", step: 100, precision: 0 },
  tutorial_coins: { kind: "number", step: 100, precision: 0 },
  quiz_reward: { kind: "number", step: 10, precision: 0 },
  tier_names: { kind: "strList" },
  tier_up_cost: { kind: "numList" },
  milestones: { kind: "numList" },
  discover_bonus: { kind: "bonusMap" },
  quality: { kind: "quality" },
  recharge: { kind: "recharge" },
  lab_upgrades: { kind: "labMap" },
};
const LAB_DEFAULT = { zh: "", desc: "", step: 1, baseCost: 1000, growth: 1.6, max: 5 };

const dlg = ref(false);
const cur = ref("");
const val = ref(null);         // 类型化模型
const jsonText = ref("");      // 兜底 JSON
const meta = ref({ category: "general", remark: "" });

const kind = computed(() => (KIND[cur.value]?.kind) || "json");
const kindMeta = computed(() => KIND[cur.value] || {});
const curSpec = computed(() => specs.value.find(s => s.key === cur.value) || null);

onMounted(() => { load(); loadSpec(); });
async function loadSpec() { specs.value = await http.get("/config/spec"); }
async function load() {
  loading.value = true;
  try { rows.value = await http.get("/config"); } finally { loading.value = false; }
}
function fmt(raw, max = 120) {
  try { const s = JSON.stringify(JSON.parse(raw)); return s.length > max ? s.slice(0, max) + "…" : s; }
  catch (e) { return raw; }
}

function edit(row) {
  cur.value = row.cfgKey;
  meta.value = { category: row.category || "general", remark: row.remark || "" };
  let parsed;
  try { parsed = JSON.parse(row.cfgValue); } catch (e) { parsed = row.cfgValue; }
  if (KIND[row.cfgKey]) {
    val.value = (typeof parsed === "object" && parsed !== null)
      ? (Array.isArray(parsed) ? parsed.map(o => ({ ...o })) : cloneDeep(parsed))
      : parsed;
    jsonText.value = "";
  } else {
    jsonText.value = JSON.stringify(parsed, null, 2);
    val.value = null;
  }
  dlg.value = true;
}
function cloneDeep(o) {
  if (Array.isArray(o)) return o.map(cloneDeep);
  if (o && typeof o === "object") { const r = {}; for (const k in o) r[k] = cloneDeep(o[k]); return r; }
  return o;
}
function addNew() {
  if (!newKey.value) return ElMessage.error("请输入键名");
  cur.value = newKey.value;
  meta.value = { category: "general", remark: "" };
  jsonText.value = "0"; val.value = null;
  dlg.value = true;
}
function addLab() {
  let key = "custom"; let i = 1;
  while (val.value && val.value[key]) key = "custom" + (++i);
  val.value = { ...val.value, [key]: { ...LAB_DEFAULT } };
}

function currentValue() {
  if (kind.value === "json") {
    try { return JSON.parse(jsonText.value || "null"); }
    catch (e) { throw new Error("JSON 解析失败：" + e.message); }
  }
  return val.value;
}

async function save() {
  let v;
  try { v = currentValue(); } catch (e) { return ElMessage.error(e.message); }
  await http.put("/config", { key: cur.value, value: v, category: meta.value.category || "general", remark: meta.value.remark || null });
  ElMessage.success("已保存并下发"); dlg.value = false; newKey.value = ""; load();
}
async function del(row) {
  await ElMessageBox.confirm(`删除配置 ${row.cfgKey}？删除后客户端将回退到代码内置默认值。`, "提示", { type: "warning" });
  await http.delete("/config", { params: { key: row.cfgKey } });
  ElMessage.success("已删除"); load();
}
</script>

<style scoped>
.bar { display:flex; align-items:center; gap:8px; margin-bottom:10px; }
.tip { color:var(--el-text-color-secondary); font-size:12px; }
.eff { display:inline-block; max-width:100%; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; color:#334155; cursor:default; border-bottom:1px dotted #cbd5e1; }
.spec { font-size:12px; line-height:1.7; font-weight:400; }
.spec.warn { color:#b45309; }
.spec.ref { color:#94a3b8; font-family:ui-monospace,Consolas,monospace; }
.row { display:flex; align-items:center; gap:6px; margin-bottom:6px; }
.hdr { display:flex; gap:6px; font-size:12px; color:var(--el-text-color-secondary); margin-bottom:4px; }
.lbl { min-width:56px; color:var(--el-text-color-secondary); font-size:13px; }
.c1 { width:110px; flex:none; } .c2 { flex:1; } .c3 { width:120px; flex:none; }
.lab { border:1px dashed var(--el-border-color); border-radius:6px; padding:8px 10px; margin-bottom:8px; }
.lab-h { display:flex; justify-content:space-between; margin-bottom:6px; }
</style>
