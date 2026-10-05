import { createRouter, createWebHistory } from 'vue-router'
import MainLayout from '../layout/MainLayout.vue'
import { useUserStore } from '../store/user'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/login/index.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/',
    component: MainLayout,
    redirect: '/architecture',
    children: [
      {
        path: 'architecture',
        name: 'architecture',
        component: () => import('../views/architecture/index.vue'),
        meta: { title: '架构全貌', subtitle: '本体驱动的企业 AI 平台 · 分层语义架构 · 点击节点下钻详情' }
      },
      {
        path: 'ontology',
        name: 'ontology',
        component: () => import('../views/ontology/index.vue'),
        meta: { title: '本体管理', subtitle: '统一业务概念 · 构建语义模型 · 维护类、属性、公理与实例' }
      },
      {
        path: 'datasource',
        name: 'datasource',
        component: () => import('../views/datasource/index.vue'),
        meta: { title: '数据源绑定', subtitle: '把业务系统的数据对应到统一业务概念，让数据"说同一种语言"' }
      },
      {
        path: 'glossary',
        name: 'glossary',
        component: () => import('../views/glossary/index.vue'),
        meta: { title: '统一口径', subtitle: '统一术语与指标口径 · 一处定义、处处复用' }
      },
      {
        path: 'knowledge',
        name: 'knowledge',
        component: () => import('../views/knowledge/index.vue'),
        meta: { title: '知识库' }
      },
      {
        path: 'trace',
        name: 'trace',
        component: () => import('../views/trace/index.vue'),
        meta: { title: '证据链', subtitle: '每个结论都有链：问题→计划→校验→执行→作答，锚点全部平台内可达' }
      },
      {
        path: 'link',
        name: 'link',
        component: () => import('../views/link/index.vue'),
        meta: { title: '链路追溯', subtitle: '全生命周期链路追溯 · 变更影响透明可查' }
      },
      {
        path: 'flow',
        name: 'flow',
        component: () => import('../views/flow/index.vue'),
        meta: { title: '医嘱闭环', subtitle: '医嘱到结算全链路追踪 · 数据来自业务库实测' }
      },
      {
        path: 'clinical',
        name: 'clinical',
        component: () => import('../views/clinical/index.vue'),
        meta: { title: '临床决策', subtitle: '基于本体推理的临床辅助决策' }
      },
      {
        path: 'gov',
        name: 'gov',
        component: () => import('../views/gov/index.vue'),
        meta: { title: '数据治理', subtitle: '数据质量可视化管控 · 问题发现到整改闭环' }
      },
      {
        path: 'ask',
        name: 'ask',
        component: () => import('../views/ask/index.vue'),
        meta: { title: '智能问数', subtitle: '基于本体语义层的自然语言问答 · 解析过程可解释 · 数字来自真实查询' }
      },
      {
        path: 'cs',
        name: 'cs',
        component: () => import('../views/cs/index.vue'),
        meta: { title: 'AI客服', subtitle: '智能问答与工单处置 · 每个结论有据可查' }
      },
      {
        path: 'evolve',
        name: 'evolve',
        component: () => import('../views/evolve/index.vue'),
        meta: { title: '概念缺口', subtitle: '提问与搜索中本体未覆盖的说法 · 按热度生长本体' }
      },
      {
        path: 'drift',
        name: 'drift',
        component: () => import('../views/drift/index.vue'),
        meta: { title: '语义漂移', subtitle: '术语分叉与口径演进检测 · 全部来自真实数据，无推断占比' }
      },
      {
        path: 'value',
        name: 'value',
        component: () => import('../views/value/index.vue'),
        meta: { title: '价值实证', subtitle: '对照实验 · 用数据验证平台价值' }
      },
      {
        path: 'lab',
        name: 'lab',
        component: () => import('../views/lab/index.vue'),
        meta: { title: 'AI查询比对', subtitle: '同一个问题三组对比:AI+本体(规则约束) / AI 直接操作数据库 / 人工开发页面,看谁答得可靠' }
      },
      {
        path: 'simulation',
        name: 'simulation',
        component: () => import('../views/simulation/index.vue'),
        meta: { title: '推演沙盘', subtitle: '动手改一个数,沿本体看波及链 · 全程演示数据,不碰真实库' }
      },
      {
        path: 'rca',
        redirect: '/cs'
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 未登录一律去登录页（带 redirect）；已登录访问登录页则回首页
router.beforeEach((to) => {
  const userStore = useUserStore()
  if (to.path === '/login') {
    return userStore.isLoggedIn ? '/' : true
  }
  if (!userStore.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
