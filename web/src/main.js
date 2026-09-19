// 前端启动入口：注册路由、加载全局样式并挂载 Vue 应用。
import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import './style.css'
import './team.css'

createApp(App).use(router).mount('#app')
