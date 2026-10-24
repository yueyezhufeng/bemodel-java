<template>
  <div class="login-page">
    <el-card class="login-card">
      <div class="login-title">ForgePilot</div>
      <div class="login-subtitle">工业供应链本体管理</div>
      <el-form :model="form" @keyup.enter="submit">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" :prefix-icon="User" size="large" />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            :prefix-icon="Lock"
            size="large"
            show-password
          />
        </el-form-item>
        <el-button
          type="primary"
          size="large"
          class="login-btn"
          :loading="loading"
          @click="submit"
        >登 录</el-button>
      </el-form>
      <div class="demo-accounts">
        演示账号：admin / admin123（管理员）· modeler / model123（建模员）· viewer / viewer123（只读）
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { login } from '../../api/auth'
import { useUserStore } from '../../store/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const form = reactive({ username: '', password: '' })
const loading = ref(false)

const submit = async () => {
  if (!form.username.trim() || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const data = await login({ username: form.username.trim(), password: form.password })
    userStore.loginSuccess(data)
    ElMessage.success(`欢迎，${data.displayName}`)
    router.push(String(route.query.redirect || '/'))
  } catch {
    // 失败 msg 由拦截器统一提示（用户名或密码错误）
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #001529 0%, #0b3050 100%);
}

.login-card {
  width: 400px;
  padding: 12px 16px 4px;
}

.login-title {
  font-size: 28px;
  font-weight: 700;
  text-align: center;
  color: #303133;
}

.login-subtitle {
  text-align: center;
  color: #909399;
  font-size: 14px;
  margin: 6px 0 24px;
}

.login-btn {
  width: 100%;
}

.demo-accounts {
  margin-top: 20px;
  text-align: center;
  font-size: 12px;
  color: #a8abb2;
  line-height: 1.8;
}
</style>
