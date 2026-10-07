<template>
  <!-- 语言包（H6-5 的连带）：以前中文是靠 app.use(ElementPlus, { locale: zhCn }) 挂上去的，
       改按需之后没有那个全局入口，不补这一层的话【分页的"共 N 条"、日期选择器、清空按钮】
       全都会安静地回英文——功能没坏，只是运营看不惯。 -->
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>

<script setup>
import zhCn from "element-plus/es/locale/lang/zh-cn";
</script>

<style>
html, body, #app { height: 100%; margin: 0; }
body { font-family: system-ui, "Microsoft YaHei", sans-serif; background: #f5f7fa; }
</style>
