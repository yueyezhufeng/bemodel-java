import { createRouter, createWebHistory } from 'vue-router'
import IndustrialLayout from '../layout/IndustrialLayout.vue'
import { useUserStore } from '../store/user'

const routes = [
  { path:'/login', name:'login', component:()=>import('../views/login/index.vue'), meta:{title:'登录'} },
  {
    path:'/', component:IndustrialLayout, redirect:'/industrial',
    children:[
      {path:'industrial',name:'industrial',component:()=>import('../views/industrial/index.vue'),meta:{title:'工业本体全景',subtitle:'工业供应链模型基础、来源映射与接入缺口'}},
      {path:'ontology',name:'ontology',component:()=>import('../views/ontology/index.vue'),meta:{title:'本体管理',subtitle:'工业对象 · 属性 · 关系 · 规则 · 版本'}},
      {path:'datasource',name:'datasource',component:()=>import('../views/datasource/index.vue'),meta:{title:'数据源与字段映射',subtitle:'连接器工厂 · 工业数据接入契约 · 字段语义映射'}},
      {path:'glossary',name:'glossary',component:()=>import('../views/glossary/index.vue'),meta:{title:'术语与指标口径',subtitle:'统一工业业务语言与可核验的指标定义'}},
      {path:'knowledge',name:'knowledge',component:()=>import('../views/knowledge/index.vue'),meta:{title:'工业制度知识'}}
    ]
  },
  {path:'/:pathMatch(.*)*',redirect:'/industrial'}
]
const router=createRouter({history:createWebHistory(),routes})
router.beforeEach(to=>{
  const user=useUserStore()
  if(to.path==='/login')return user.isLoggedIn?'/industrial':true
  if(!user.isLoggedIn)return {path:'/login',query:{redirect:to.fullPath}}
  return true
})
router.afterEach(()=>{document.title='ForgePilot · 工业本体管理'})
export default router
