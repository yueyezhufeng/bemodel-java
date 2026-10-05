<template>
  <el-container class="layout">
    <el-aside width="240px" class="aside">
      <div class="logo">
        <div class="logo-icon">Bm</div>
        <div class="logo-texts">
          <div class="logo-text">BeModel</div>
          <div class="logo-sub">本体平台</div>
        </div>
      </div>
      <el-menu :default-active="activeMenu" router class="bm-menu">
        <el-menu-item index="/architecture">
          <el-icon><Platform /></el-icon>
          <span>架构全貌</span>
        </el-menu-item>
        <el-menu-item-group>
          <template #title>核心功能</template>
          <el-menu-item index="/ontology">
            <el-icon><Collection /></el-icon>
            <span>本体管理</span>
          </el-menu-item>
          <el-menu-item index="/datasource">
            <el-icon><Connection /></el-icon>
            <span>数据源绑定</span>
          </el-menu-item>
          <el-menu-item index="/glossary">
            <el-icon><Notebook /></el-icon>
            <span>统一口径</span>
          </el-menu-item>
          <el-menu-item index="/knowledge">
            <el-icon><Reading /></el-icon>
            <span>知识库</span>
          </el-menu-item>
          <el-menu-item index="/ask">
            <el-icon><ChatDotRound /></el-icon>
            <span>智能问数</span>
          </el-menu-item>
          <el-menu-item index="/link">
            <el-icon><Share /></el-icon>
            <span>链路追溯</span>
          </el-menu-item>
          <el-menu-item index="/trace">
            <el-icon><Connection /></el-icon>
            <span>证据链</span>
          </el-menu-item>
          <el-menu-item index="/gov">
            <el-icon><Stamp /></el-icon>
            <span>数据治理</span>
          </el-menu-item>
        </el-menu-item-group>
        <el-menu-item-group>
          <template #title>应用场景</template>
          <el-menu-item index="/flow">
            <el-icon><Tickets /></el-icon>
            <span>医嘱闭环</span>
          </el-menu-item>
          <el-menu-item index="/clinical">
            <el-icon><FirstAidKit /></el-icon>
            <span>临床决策</span>
          </el-menu-item>
          <el-menu-item index="/cs">
            <el-icon><Service /></el-icon>
            <span>AI客服</span>
          </el-menu-item>
          <el-menu-item index="/value">
            <el-icon><DataAnalysis /></el-icon>
            <span>价值实证</span>
          </el-menu-item>
          <el-menu-item index="/lab">
            <el-icon><DataAnalysis /></el-icon>
            <span>AI查询比对</span>
          </el-menu-item>
        </el-menu-item-group>
        <el-menu-item-group>
          <template #title>本体演化</template>
          <el-menu-item index="/evolve">
            <el-icon><Opportunity /></el-icon>
            <span class="evolve-item">
              概念缺口
              <el-badge
                :value="pendingMissCount"
                :hidden="!pendingMissCount"
                :max="99"
                class="miss-badge"
              />
            </span>
          </el-menu-item>
          <el-menu-item index="/drift">
            <el-icon><Aim /></el-icon>
            <span>语义漂移</span>
          </el-menu-item>
        </el-menu-item-group>
      </el-menu>
      <div class="aside-footer">
        <div>版本 v{{ appVersion }}</div>
        <div class="copyright">© 2026 BeModel Team</div>
      </div>
    </el-aside>
    <el-container>
      <el-header class="header" height="60px">
        <div class="header-left">
          <div class="header-row">
            <span class="topbar-title">{{ route.meta.title || '' }}</span>
            <span class="topbar-crumb">
              BeModel <span class="crumb-sep">/</span>
              <span class="crumb-page">{{ route.meta.title || '' }}</span>
            </span>
          </div>
          <div v-if="route.meta.subtitle" class="topbar-subtitle">
            {{ route.meta.subtitle }}
          </div>
        </div>
        <div class="header-right">
          <div class="sys-status">
            <span class="status-dot"></span>
            <span>系统运行正常</span>
          </div>
          <el-badge
            :value="unreadCount"
            :hidden="!unreadCount"
            :max="99"
            class="notice-badge"
          >
            <el-icon class="bell-icon" :size="18" @click="openNoticeDrawer"><Bell /></el-icon>
          </el-badge>
          <el-tag :type="llmTagType" effect="plain">{{ llmTagText }}</el-tag>
          <el-dropdown @command="onUserCommand">
            <span class="user-info">
              <span class="bm-avatar">{{ avatarText }}</span>
              {{ userStore.user?.displayName }}
              <el-tag size="small" :type="userStore.roleTagType" class="role-tag">
                {{ userStore.roleText }}
              </el-tag>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>

    <!-- 巡检告警 -->
    <el-drawer v-model="noticeVisible" title="巡检告警" size="480px">
      <div class="notice-toolbar">
        <el-button
          v-if="!userStore.isViewer"
          size="small"
          type="primary"
          :loading="inspecting"
          @click="doInspect"
        >立即巡检</el-button>
        <el-button
          v-if="!userStore.isViewer"
          size="small"
          :disabled="!unreadCount"
          @click="doReadAll"
        >全部已读</el-button>
        <span class="notice-tip">指标巡检每 5 分钟自动执行，也可手动触发</span>
      </div>
      <div v-loading="loadingNotices">
        <div
          v-for="n in notices"
          :key="n.id"
          class="notice-item"
          :class="{ unread: n.status === '未读' }"
        >
          <div class="notice-head">
            <span class="notice-metric">{{ n.metricName }}<span v-if="!n.metricCode?.startsWith('RECON:')">（{{ n.metricCode }}）</span></span>
            <el-tag size="small" :type="n.status === '未读' ? 'danger' : 'info'" effect="plain">
              {{ n.status }}
            </el-tag>
          </div>
          <div class="notice-message">{{ n.message }}</div>
          <div class="notice-foot">
            <span v-if="n.metricCode?.startsWith('RECON:')">差额 {{ n.actualValue }} ｜ {{ n.createdAt?.replace('T', ' ') }}</span>
            <span v-else>实测 {{ n.actualValue }} / 阈值 {{ n.threshold }} ｜ {{ n.createdAt?.replace('T', ' ') }}</span>
            <el-button
              v-if="n.status === '未读' && !userStore.isViewer"
              size="small"
              link
              type="primary"
              @click="doRead(n)"
            >已读</el-button>
          </div>
        </div>
        <el-empty v-if="!notices.length" description="暂无告警" :image-size="60" />
      </div>
      <el-pagination
        v-if="noticeTotal > noticePageSize"
        class="notice-pager"
        small
        layout="total, prev, pager, next"
        :current-page="noticePage"
        :page-size="noticePageSize"
        :total="noticeTotal"
        @current-change="loadNotices"
      />
    </el-drawer>
  </el-container>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Platform, Collection, Connection, Notebook, ChatDotRound, Share, Tickets, FirstAidKit, Stamp, Service, DataAnalysis, Opportunity, Reading, Aim, ArrowDown, Bell } from '@element-plus/icons-vue'
import { useUserStore } from '../store/user'
import { version as appVersion } from '../../package.json'
import { getLlmRoutes } from '../api/release'
import { listOntologyMisses } from '../api/ontology'
import {
  unreadNoticeCount,
  listNotices,
  readNotice,
  readAllNotices,
  runInspect
} from '../api/notice'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const activeMenu = computed(() => route.path)
const avatarText = computed(() => (userStore.user?.displayName || '用').charAt(0))

const onUserCommand = (cmd) => {
  if (cmd === 'logout') {
    userStore.logout()
    router.push('/login')
  }
}

// ---------- 巡检告警铃铛 ----------
const unreadCount = ref(0)
const noticeVisible = ref(false)
const loadingNotices = ref(false)
const inspecting = ref(false)
const notices = ref([])
const noticePage = ref(1)
const noticeTotal = ref(0)
const noticePageSize = 20

const loadUnreadCount = async () => {
  try {
    const res = await unreadNoticeCount()
    unreadCount.value = res.count
  } catch {
    // 计数失败不打断页面
  }
}

const loadNotices = async (page = 1) => {
  loadingNotices.value = true
  try {
    const res = await listNotices(page, noticePageSize)
    notices.value = res.list
    noticeTotal.value = res.total
    noticePage.value = res.pageNum
  } finally {
    loadingNotices.value = false
  }
}

const openNoticeDrawer = () => {
  noticeVisible.value = true
  loadNotices(1)
}

const doRead = async (n) => {
  await readNotice(n.id)
  loadNotices(noticePage.value)
  loadUnreadCount()
}

const doReadAll = async () => {
  await readAllNotices()
  ElMessage.success('已全部标记为已读')
  loadNotices(1)
  loadUnreadCount()
}

const doInspect = async () => {
  inspecting.value = true
  try {
    const res = await runInspect()
    ElMessage.success(
      `巡检完成：评估 ${res.evaluated} 项指标，告警 ${res.alarmed} 项，新增通知 ${res.noticesCreated} 条`
    )
    loadNotices(1)
    loadUnreadCount()
  } finally {
    inspecting.value = false
  }
}

// ---------- 概念缺口角标（本体增长回路待处理数） ----------
const pendingMissCount = ref(0)

// ---------- LLM 主备健康（借鉴 4）：读 /api/llm/routes 说真话，接口不可达静默兜底 ----------
const llmTagText = ref('AI 提供方检测中…')
const llmTagType = ref('info')
const loadLlmRoutes = async () => {
  try {
    const data = await getLlmRoutes()
    const routes = Array.isArray(data) ? data : []
    if (!routes.length) return
    const primary = routes.find((r) => r.name === 'primary')
    const backup = routes.find((r) => r.name === 'backup')
    const mark = (r) => (r && r.healthy ? '正常' : '未接通')
    llmTagText.value = `AI: ${primary?.model || '状态未知'}（主${mark(primary)}${backup ? `，备${mark(backup)}` : ''}）`
    llmTagType.value = primary && primary.healthy === false ? 'danger' : 'success'
  } catch (_) { /* 保留兜底文案，不弹错 */ }
}

const loadMissCount = async () => {
  try {
    const res = await listOntologyMisses()
    pendingMissCount.value = res.pendingCount || 0
  } catch {
    // 计数失败不打断页面
  }
}

// 60s 轮询未读数 + 概念缺口数 + LLM 主备健康 + 页面重新激活时刷新
let pollTimer = null
const onVisible = () => {
  if (!document.hidden) {
    loadLlmRoutes()
    loadUnreadCount()
    loadMissCount()
  }
}

onMounted(() => {
  loadLlmRoutes()
  loadUnreadCount()
  loadMissCount()
  pollTimer = setInterval(() => {
    loadLlmRoutes()
    loadUnreadCount()
    loadMissCount()
  }, 60000)
  document.addEventListener('visibilitychange', onVisible)
})

onBeforeUnmount(() => {
  clearInterval(pollTimer)
  document.removeEventListener('visibilitychange', onVisible)
})
</script>

<style scoped>
.layout {
  height: 100vh;
}

/* ---------- 侧边栏 ---------- */
.aside {
  background: var(--sidebar-bg);
  display: flex;
  flex-direction: column;
}

.logo {
  height: var(--topbar-height);
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 20px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.logo-icon {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: linear-gradient(135deg, var(--primary), #4db6ac);
  color: #fff;
  font-weight: 700;
  font-size: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.logo-text {
  color: #fff;
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 0.5px;
  line-height: 1.2;
}

.logo-sub {
  color: var(--sidebar-text);
  opacity: 0.7;
  font-size: 11px;
  margin-top: 2px;
}

.bm-menu {
  flex: 1;
  overflow-y: auto;
  border-right: none;
  background: transparent;
  padding: 8px 0;
}

.bm-menu::-webkit-scrollbar {
  width: 4px;
}
.bm-menu::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.1);
  border-radius: 2px;
}

.aside :deep(.el-menu-item) {
  height: 42px;
  line-height: 42px;
  margin: 2px 0;
  color: var(--sidebar-text);
  border-left: 3px solid transparent;
  transition: var(--transition);
}

.aside :deep(.el-menu-item:hover) {
  background: var(--sidebar-hover);
  color: var(--sidebar-text-active);
}

.aside :deep(.el-menu-item.is-active) {
  background: var(--sidebar-hover);
  color: var(--sidebar-text-active);
  border-left-color: var(--primary);
}

.aside :deep(.el-menu-item-group__title) {
  padding: 14px 20px 6px;
  font-size: 10px;
  font-weight: 700;
  color: rgba(255, 255, 255, 0.3);
  text-transform: uppercase;
  letter-spacing: 1px;
  line-height: 1.4;
}

.evolve-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.miss-badge {
  height: auto;
}

.miss-badge :deep(.el-badge__content) {
  position: static;
  transform: none;
}

.aside-footer {
  padding: 12px 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  color: var(--sidebar-text);
  font-size: 12px;
  flex-shrink: 0;
}

.aside-footer .copyright {
  margin-top: 4px;
  opacity: 0.6;
}

/* ---------- 顶栏 ---------- */
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--card-bg);
  border-bottom: 1px solid var(--border-color);
  padding: 0 24px;
}

.header-left {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-width: 0;
}

.header-row {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.topbar-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}

.topbar-crumb {
  font-size: 12px;
  color: var(--text-muted);
}

.topbar-crumb .crumb-page {
  color: var(--text-secondary);
}

.topbar-subtitle {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-shrink: 0;
}

.sys-status {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-secondary);
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: var(--text-primary);
  font-size: 14px;
}

.role-tag {
  margin-left: 2px;
}

.notice-badge {
  display: flex;
  align-items: center;
}

.bell-icon {
  cursor: pointer;
  color: var(--text-secondary);
}

.bell-icon:hover {
  color: var(--primary);
}

/* ---------- 巡检告警抽屉 ---------- */
.notice-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.notice-tip {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-muted);
}

.notice-item {
  border: 1px solid var(--border-color);
  border-radius: 6px;
  padding: 10px 12px;
  margin-bottom: 10px;
}

.notice-item.unread {
  background: var(--danger-light);
  border-color: var(--el-color-danger-light-7);
}

.notice-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.notice-metric {
  font-weight: 600;
  font-size: 13px;
}

.notice-message {
  font-size: 13px;
  color: var(--text-secondary);
  margin: 6px 0;
}

.notice-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: var(--text-muted);
}

.notice-pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.main {
  padding: 24px;
  overflow-y: auto;
}
</style>
