<template>
  <div class="industrial-layout">
    <aside>
      <a class="industrial-brand" href="/">F/ <span>ForgePilot<small>工业本体管理</small></span></a>
      <nav aria-label="工业管理导航">
        <router-link v-for="item in items" :key="item.path" :to="item.path"><component :is="item.icon" />{{ item.name }}</router-link>
      </nav>
      <div class="industrial-footer"><small>工业配置 · 模型草稿</small><a href="http://127.0.0.1:8000/solution/demo-journey.html" target="_blank" rel="noopener">完整案例演示 ↗</a><a href="http://127.0.0.1:8000/solution/index.html" target="_blank" rel="noopener">一体化方案与汇报 ↗</a><a href="http://127.0.0.1:8000/" target="_blank" rel="noopener">业务工作台 ↗</a><a href="http://127.0.0.1:8000/ontology/index.html" target="_blank" rel="noopener">查看当前业务实例 ↗</a></div>
    </aside>
    <section class="industrial-body">
      <header><div><strong>{{ route.meta.title }}</strong><small>{{ route.meta.subtitle }}</small></div><div class="industrial-user">{{ userStore.user?.displayName }} / {{ userStore.roleText }}<button @click="logout">退出</button></div></header>
      <main><router-view /></main>
    </section>
  </div>
</template>
<script setup>
import { useRoute, useRouter } from 'vue-router'
import { Collection, Connection, Notebook, Reading, Share } from '@element-plus/icons-vue'
import { useUserStore } from '../store/user'
const route=useRoute(),router=useRouter(),userStore=useUserStore()
const items=[{path:'/industrial',name:'工业本体全景',icon:Share},{path:'/ontology',name:'对象 / 关系 / 规则',icon:Collection},{path:'/datasource',name:'数据源与字段映射',icon:Connection},{path:'/glossary',name:'术语与指标口径',icon:Notebook},{path:'/knowledge',name:'工业制度知识',icon:Reading}]
function logout(){userStore.logout();router.push('/login')}
</script>
<style scoped>
.industrial-layout{display:flex;height:100vh;background:#eeede8;color:#151515}.industrial-layout>aside{width:216px;flex-shrink:0;background:#151515;color:#eeede8;padding:25px 18px;display:flex;flex-direction:column}.industrial-brand{display:flex;align-items:center;gap:12px;color:#d65b35;font-size:29px;font-weight:700;text-decoration:none}.industrial-brand span{color:#eeede8;font-size:20px}.industrial-brand small{display:block;font-size:11px;font-weight:400;margin-top:5px;color:#92948b}nav{margin-top:40px}nav a{display:flex;align-items:center;gap:12px;padding:14px 8px;color:#a6a79f;text-decoration:none;font-size:12px}nav a.router-link-active{color:#eeede8;border-left:2px solid #d65b35;background:#ffffff0a}nav svg{width:17px}.industrial-footer{margin-top:auto;font-size:10px;line-height:2.5}.industrial-footer a{display:block;color:#a6a79f}.industrial-body{flex:1;min-width:0;overflow:auto}.industrial-body>header{padding:17px 28px;border-bottom:1px solid #cecec5;display:flex;justify-content:space-between;gap:15px;align-items:center;font-size:13px}.industrial-body>header small{display:block;margin-top:6px;color:#73746e;font-size:10px}.industrial-body>main{padding:22px;min-width:0}.industrial-user{font-size:11px;white-space:nowrap}.industrial-user button{background:transparent;border:1px solid #cecec5;padding:6px 12px;margin-left:14px;cursor:pointer}@media(max-width:800px){.industrial-layout>aside{width:65px;padding:20px 7px}.industrial-brand{font-size:24px}.industrial-brand span,.industrial-footer{display:none}nav a{font-size:0;padding:15px}nav svg{flex-shrink:0}.industrial-body>header{padding:15px}.industrial-body>header small{display:none}.industrial-body>main{padding:10px}}
</style>
