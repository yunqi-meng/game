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
            <el-button v-if="auth.canWrite" size="small" :loading="rolling" @click="roll(0)">重算今天</el-button>
            <el-button v-if="auth.canWrite" size="small" text @click="roll(-1)">补算昨天</el-button>
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
import { onMounted, ref, nextTick } from "vue";
import * as echarts from "echarts";
import { ElMessage } from "element-plus";
import http from "../api";
import { useAuth } from "../store";

const auth = useAuth();
const ov = ref({});
const daily = ref([]);
const trendEl = ref(null);
const rolling = ref(false);

onMounted(async () => {
  ov.value = await http.get("/dashboard/overview");
  const tr = await http.get("/dashboard/trend", { params: { days: 14 } });
  daily.value = (tr && tr.recentDaily) || [];
  await nextTick();
  if (trendEl.value) {
    const chart = echarts.init(trendEl.value);
    const rows = tr.logins || [];
    chart.setOption({
      tooltip: { trigger: "axis" },
      xAxis: { type: "category", data: rows.map((r) => fmt(r.d)) },
      yAxis: { type: "value" },
      series: [{ name: "登录用户", type: "line", smooth: true, areaStyle: {}, data: rows.map((r) => r.n) }],
    });
  }
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
  const day = dayStr(off);
  rolling.value = true;
  try {
    const row = await http.post("/dashboard/roll", null, { params: { day } });
    // 重算是覆盖式的：同一天的数点几次都是同一个值，不会把 DAU 刷成两倍
    ElMessage.success(`${day} 已重算：活跃 ${row.dau}、新增 ${row.newUsers}、成功反应 ${row.reactions}`);
    const tr = await http.get("/dashboard/trend", { params: { days: 14 } });
    daily.value = (tr && tr.recentDaily) || [];
  } catch (e) {
    // 失败由 api.js 的拦截器统一 toast（含 403"当前角色无写权限"），这里只把按钮放回来
  } finally {
    rolling.value = false;
  }
}
</script>

<style scoped>
.kpi { display:flex; flex-direction:column; }
.kpi b { font-size:28px; color:#2563eb; }
.kpi span { color:#888; margin-top:6px; }
.hd { display:flex; align-items:center; }
.hdropt { margin-left:auto; }
</style>
