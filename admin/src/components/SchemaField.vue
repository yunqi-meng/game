<template>
  <el-form-item :label="field.label" :required="field.required">
    <!-- 文本 -->
    <el-input v-if="kind === 'text'" :model-value="str" @update:model-value="set"
              :placeholder="hint" clearable style="width:100%" />

    <!-- 多行文本 -->
    <el-input v-else-if="kind === 'textarea'" :model-value="str" @update:model-value="set"
              type="textarea" :rows="3" style="width:100%" />

    <!-- 整数 / 数字 -->
    <el-input-number v-else-if="kind === 'int' || kind === 'num'" :model-value="num"
                     @update:model-value="setNum" :precision="kind === 'int' ? 0 : undefined"
                     :step="kind === 'int' ? 1 : 0.01" controls-position="right" />

    <!-- 布尔 -->
    <el-switch v-else-if="kind === 'bool'" :model-value="bool" @update:model-value="set" />

    <!-- 闭集枚举：单选（标签带中文口径，取值仍是机器名，见 enumLabel） -->
    <el-select v-else-if="kind === 'enum'" :model-value="str" @update:model-value="set"
               clearable style="width:100%">
      <el-option v-for="o in field.options" :key="o" :label="enumLabel(o)" :value="o" />
    </el-select>

    <!-- 闭集枚举：多选 -->
    <el-select v-else-if="kind === 'enums'" :model-value="arr" @update:model-value="set"
               multiple clearable style="width:100%">
      <el-option v-for="o in field.options" :key="o" :label="enumLabel(o)" :value="o" />
    </el-select>

    <!-- 引用：单选 -->
    <el-select v-else-if="kind === 'ref'" :model-value="str" @update:model-value="set"
               filterable clearable style="width:100%">
      <el-option v-for="o in refOptions" :key="o.value" :label="o.label" :value="o.value" />
    </el-select>

    <!-- 引用：多选 -->
    <el-select v-else-if="kind === 'refs'" :model-value="arr" @update:model-value="set"
               multiple filterable clearable style="width:100%">
      <el-option v-for="o in refOptions" :key="o.value" :label="o.label" :value="o.value" />
    </el-select>

    <!-- 字符串数组：可增删 -->
    <div v-else-if="kind === 'stringList'" style="width:100%">
      <div v-for="(s, i) in arr" :key="i" style="display:flex;gap:6px;margin-bottom:6px">
        <el-input :model-value="s" @update:model-value="(v)=>setList(i,v)" style="flex:1" />
        <el-button link type="danger" @click="removeItem(i)">删除</el-button>
      </div>
      <el-button link type="primary" @click="addItem">＋ 添加一项</el-button>
    </div>

    <!-- 子物质映射：{物质ID: 份数} -->
    <div v-else-if="kind === 'subMap'" style="width:100%">
      <div v-for="k in mapKeys" :key="k" style="display:flex;gap:6px;margin-bottom:6px">
        <el-select :model-value="k" @update:model-value="(nk)=>renameKey(k,nk)"
                   filterable style="flex:1" placeholder="物质">
          <el-option v-for="o in refOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-input-number :model-value="mapObj[k]" @update:model-value="(v)=>setKey(k,v)"
                         :min="1" :precision="0" controls-position="right" style="width:120px" />
        <el-button link type="danger" @click="removeKey(k)">删除</el-button>
      </div>
      <el-button link type="primary" @click="addKey">＋ 添加物质</el-button>
    </div>

    <!-- 嵌套对象：递归渲染子字段 -->
    <div v-else-if="kind === 'obj'"
         style="width:100%;padding:8px 12px;border:1px dashed var(--el-border-color);border-radius:6px">
      <div v-for="sf in field.sub" :key="sf.key">
        <SchemaField :field="sf" :model="objModel" :options="options"
                     @update:model="(patched)=>set(patched)" />
      </div>
    </div>

    <span v-else class="na">不支持的字段类型：{{ field.kind }}</span>
  </el-form-item>
</template>

<script setup>
import { computed } from "vue";

const props = defineProps({
  field: { type: Object, required: true },
  model: { type: Object, required: true },   // 该字段所在层级的父对象
  options: { type: Object, default: () => ({ refs: {}, enums: {} }) },
});
const emit = defineEmits(["update:model"]);

const kind = computed(() => props.field.kind);
const cur = computed(() => props.model[props.field.key]);

function set(v) { emit("update:model", patchKey(props.field.key, v)); }
function setNum(v) { set(v == null ? null : (kind.value === "int" ? Math.round(v) : Number(v))); }

// 生成替换后的父对象（保持不可变，便于 Vue 侦测）
function patchKey(key, v) { return { ...props.model, [key]: v }; }

const str = computed(() => (cur.value == null ? "" : String(cur.value)));
const num = computed(() => (typeof cur.value === "number" ? cur.value : undefined));
const bool = computed(() => cur.value === true);
const arr = computed(() => (Array.isArray(cur.value) ? cur.value : []));

/* ref / refs / subMap 的候选来源：后端下发的 [{value,label}] 列表 */
const refOptions = computed(() => props.options.refs?.[props.field.ref] || []);

/* 枚举下拉的显示文案：后端 enumsZh 里带中文口径时显示「中文 · 机器名」，否则原样。
   提交出去的永远是 field.options 里那个机器名——闭集校验和引擎读的都是它，
   翻译只发生在这一层，所以面板好看与判定正确不会互相牺牲。 */
function enumLabel(o) {
  const zh = props.options.enumsZh?.[o];
  return zh ? `${zh} · ${o}` : o;
}

/* stringList */
function setList(i, v) { const a = [...arr.value]; a[i] = v; set(a); }
function addItem() { set([...arr.value, ""]); }
function removeItem(i) { const a = [...arr.value]; a.splice(i, 1); set(a); }

/* subMap */
const mapObj = computed(() => (cur.value && typeof cur.value === "object" && !Array.isArray(cur.value) ? cur.value : {}));
const mapKeys = computed(() => Object.keys(mapObj.value));
function setKey(k, v) { emit("update:model", patchKey(props.field.key, { ...mapObj.value, [k]: v })); }
function renameKey(oldK, newK) {
  const next = {};
  for (const k of mapKeys.value) if (k !== oldK) next[k] = mapObj.value[k];
  if (newK) next[newK] = mapObj.value[oldK];
  emit("update:model", patchKey(props.field.key, next));
}
function addKey() {
  const pool = refOptions.value.map(o => o.value);
  let pick = "";
  for (const id of pool) if (!mapKeys.value.includes(id)) { pick = id; break; }
  emit("update:model", patchKey(props.field.key, { ...mapObj.value, [pick || "NEW"]: 1 }));
}
function removeKey(k) {
  const next = { ...mapObj.value }; delete next[k];
  emit("update:model", patchKey(props.field.key, next));
}

/* obj */
const objModel = computed(() => (cur.value && typeof cur.value === "object" && !Array.isArray(cur.value) ? cur.value : {}));
</script>

<style scoped>
.na { color: var(--el-text-color-secondary); font-size: 12px; }
</style>
