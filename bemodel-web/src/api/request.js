import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../store/user'
import router from '../router'

const request = axios.create({
  baseURL: '/api',
  timeout: 60000
})

// 请求头自动带 token
request.interceptors.request.use((config) => {
  const userStore = useUserStore()
  if (userStore.token) {
    config.headers.Authorization = `Bearer ${userStore.token}`
  }
  return config
})

request.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) {
        return body.data
      }
      ElMessage.error(body.msg || '请求失败')
      return Promise.reject(new Error(body.msg || '请求失败'))
    }
    return body
  },
  (error) => {
    const status = error.response?.status
    const userStore = useUserStore()
    if (status === 401) {
      userStore.logout()
      if (router.currentRoute.value.path !== '/login') {
        ElMessage.error('登录已过期，请重新登录')
        router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
      }
      return Promise.reject(error)
    }
    if (status === 403) {
      ElMessage.error(error.response?.data?.msg || '权限不足：需要编辑或管理员角色')
      return Promise.reject(error)
    }
    const msg = error.response?.data?.msg || error.message || '网络异常'
    ElMessage.error(msg)
    return Promise.reject(error)
  }
)

export default request
