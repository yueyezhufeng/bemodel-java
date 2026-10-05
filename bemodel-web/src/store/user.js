import { defineStore } from 'pinia'

const TOKEN_KEY = 'bemodel_token'
const USER_KEY = 'bemodel_user'

// 登录态：token/user 持久化到 localStorage，刷新不丢
export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
    isViewer: (s) => s.user?.role === 'VIEWER',
    // 评审动作可用（发布/停用/版本发布：与后端服务层 REVIEWER/ADMIN 门禁同口径）
    canReview: (s) => s.user?.role === 'REVIEWER' || s.user?.role === 'ADMIN',
    roleText: (s) =>
      ({ ADMIN: '管理员', EDITOR: '建模员', REVIEWER: '评审员', VIEWER: '只读' }[s.user?.role]
        || s.user?.role || ''),
    roleTagType: (s) =>
      ({ ADMIN: 'danger', EDITOR: 'success', REVIEWER: 'warning', VIEWER: 'info' }[s.user?.role] || 'info')
  },
  actions: {
    loginSuccess(data) {
      this.token = data.token
      this.user = { username: data.username, displayName: data.displayName, role: data.role }
      localStorage.setItem(TOKEN_KEY, this.token)
      localStorage.setItem(USER_KEY, JSON.stringify(this.user))
    },
    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
    }
  }
})
