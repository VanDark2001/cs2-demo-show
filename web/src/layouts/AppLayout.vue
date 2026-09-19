<!-- 主布局：组合固定页头、跨页面解析进度和当前子路由页面。 -->
<script setup>
import { RouterView } from 'vue-router'
import AppHeader from '../components/AppHeader.vue'
import ParserProgress from '../components/ParserProgress.vue'
</script>

<template>
  <div class="app">
    <AppHeader />
    <ParserProgress />
    <RouterView />
  </div>
</template>
