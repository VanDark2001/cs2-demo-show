<!-- 全局页头：提供主要业务页面导航和 Demo 解析入口。 -->
<script setup>
import { RouterLink } from 'vue-router'
</script>

<template>
  <header>
    <div class="brand"><span class="logo">◆</span><div><b>CS2 DEMO</b><small>战绩分析中心</small></div></div>
    <nav>
      <RouterLink to="/matches">比赛</RouterLink>
      <RouterLink to="/players">玩家</RouterLink>
      <RouterLink to="/weapons">武器</RouterLink>
      <RouterLink to="/maps">地图</RouterLink>
      <RouterLink to="/stats">统计</RouterLink>
    </nav>
    <RouterLink class="upload" to="/parser">＋ 解析 Demo</RouterLink>
  </header>
</template>
