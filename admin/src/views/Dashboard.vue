<template>
  <div>
    <el-row :gutter="12">
      <el-col :span="6"><el-card shadow="hover"><div class="kpi"><b>{{ ov.totalUsers ?? '-' }}</b><span>累计用户</span></div></el-card></el-col>
      <el-col :span="6"><el-card shadow="hover"><div class="kpi"><b>{{ ov.dauToday ?? '-' }}</b><span>今日活跃</span></div></el-card></el-col>
      <el-col :span="6"><el-card shadow="hover"><div class="kpi"><b>{{ ov.newUsersToday ?? '-' }}</b><span>今日新增</span></div></el-card></el-col>
      <el-col :span="6"><el-card shadow="hover"><div class="kpi"><b>{{ ov.contentVersion ?? '-' }}</b><span>内容版本</span></div></el-card></el-col>
    </el-row>
    <el-card style="margin-top:12px" header="近 14 日登录趋势">
      <div ref="trendEl" style="height:300px"></div>
    </el-card>
    <el-card style="margin-top:12px">
      <template #header>
        <div class="hd">
          <span>日报（聚合表 daily_stats）</span>
          <span class="hdropt">
            <el-button v-if="auth.canWrite" size="small" :loading="busy('roll:0')" @click="roll(0)">重算今天</el-button>
            <el-button v-if="auth.canWrite" size="small" text :loading="busy('roll:-1')" @click="roll(-1)">补算昨天</el-button>
          </span>
        </div>
      </template>
      <!-- 这张表以前谁都不写，面板上永远是空——G1 之后凌晨的作业会结清昨天，
           而运营遇到"服务那天正停着"或"想立刻看到今天"时，上面两个按钮就是那个入口。 -->
      <el-table :data="daily" height="240" empty-text="还没有聚合行：日报作业每天凌晨写昨天，等不及就点上面【重算今天】">
        <el-table-column label="日期" width="130">
          <template #default="s">{{ fmt(s.row.day) }}</template>
        </el-table-column>
        <el-table-column prop="dau" label="活跃" width="90" />
        <el-table-column prop="new_users" label="新增" width="90" />
        <el-table-column prop="reactions" label="成功反应" width="110" />
        <el-table-column prop="booms" label="事故" width="90" />
        <el-table-column prop="trades" label="成交" width="90" />
      </el-table>
    </el-card>
    <el-card style="margin-top:12px" header="事件分布（近 7 日）">
      <el-table :data="ov.events7d || []" height="240">
        <el-table-column prop="event" label="事件" />
        <el-table-column prop="n" label="次数" width="120" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { nextTick, onMounted, onUnmounted, ref } from "vue";
/* echarts 按需注册（H6-5）：以前这里 import * as echarts from "echarts"，把整套发行版
   （地图、关系图、3D、词云、SVG 渲染器、全部图表类型）一起打进了 Dashboard-*.js——1.04 MB，
   而这个面板一辈子只画一条带面积的近 14 日折线。现在只装用得上的那四件：
   line 图 + 直角坐标系（grid）+ 轴触发 tooltip + canvas 渲染器。
   以后这张面板要加图表类型，得往下面的 use([...]) 里补一件；少一件的表现为"那张图安静地画不出来"，
   所以清单就写在这张图的旁边，改图的人一眼就看得见。 */
import * as echarts from "echarts/core";
import { LineChart } from "echarts/charts";
import { GridComponent, TooltipComponent } from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
import { ElMessage } from "element-plus";
import http from "../api";
import { useBusy } from "../busy";
import { useAuth } from "../store";

echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer]);

const auth = useAuth();
const { busy, run } = useBusy();
const ov = ref({});
const daily = ref([]);
const trendEl = ref(null);
/** 图表实例留在模块里（不是 ref：它不是响应式数据，包一层只会让 Vue 去深遍历 echarts 的内部状态）。 */
let chart = null;

function onWindowResize() { if (chart && !chart.isDisposed()) chart.resize(); }

onMounted(async () => {
  ov.value = await http.get("/dashboard/overview");
  const tr = await http.get("/dashboard/trend", { params: { days: 14 } });
  daily.value = (tr && tr.recentDaily) || [];
  await nextTick();
  if (trendEl.value) {
    chart = echarts.init(trendEl.value);
    const rows = tr.logins || [];
    chart.setOption({
      tooltip: { trigger: "axis" },
      xAxis: { type: "category", data: rows.map((r) => fmt(r.d)) },
      yAxis: { type: "value" },
      series: [{ name: "登录用户", type: "line", smooth: true, areaStyle: {}, data: rows.map((r) => r.n) }],
    });
    // 后台是嵌在侧栏里的，窗口一窄这张图就糊在右边：resize 得挂上
    window.addEventListener("resize", onWindowResize);
  }
});

// 离开看板时 echarts 的实例不会自己散架：canvas、事件与 DOM 引用会跟着这个已卸载的组件一直留着
onUnmounted(() => {
  window.removeEventListener("resize", onWindowResize);
  if (chart) { chart.dispose(); chart = null; }
});
function fmt(d) { return d ? String(d).slice(0, 10) : ""; }

/**
 * 本地日期，不用 toISOString()：那个给的是 UTC，东八区每天 0–8 点会算成"昨天"，
 * 而服务端按 LocalDate.now()（服务器本地日）落库，两边口径差一天就白补。
 */
function dayStr(off) {
  const d = new Date();
  d.setDate(d.getDate() + off);
  const p = (x) => String(x).padStart(2, "0");
  return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate());
}

async function roll(off) {
  await run("roll:" + off, async () => {
    const day = dayStr(off);
    try {
      const row = await http.post("/dashboard/roll", null, { params: { day } });
      // 重算是覆盖式的：同一天的数点几次都是同一个值，不会把 DAU 刷成两倍
      ElMessage.success(`${day} 已重算：活跃 ${row.dau}、新增 ${row.newUsers}、成功反应 ${row.reactions}`);
      const tr = await http.get("/dashboard/trend", { params: { days: 14 } });
      daily.value = (tr && tr.recentDaily) || [];
    } catch (e) {
      // 失败由 api.js 的拦截器统一 toast（含 403"当前角色无写权限"），这里只把按钮放回来
    }
  });
}
</script>

<style scoped>
.kpi { display:flex; flex-direction:column; }
.kpi b { font-size:28px; color:#2563eb; }
.kpi span { color:#888; margin-top:6px; }
.hd { display:flex; align-items:center; }
.hdropt { margin-left:auto; }
</style>
