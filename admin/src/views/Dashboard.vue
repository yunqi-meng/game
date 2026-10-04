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
import http from "../api";

const ov = ref({});
const trendEl = ref(null);

onMounted(async () => {
  ov.value = await http.get("/dashboard/overview");
  const tr = await http.get("/dashboard/trend", { params: { days: 14 } });
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
</script>

<style scoped>
.kpi { display:flex; flex-direction:column; }
.kpi b { font-size:28px; color:#2563eb; }
.kpi span { color:#888; margin-top:6px; }
</style>
