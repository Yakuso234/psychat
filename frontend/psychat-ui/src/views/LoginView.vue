<template>
  <div class="login-container">
    <div class="login-card">
      <div class="login-title">心语 PsyChat</div>
      <div class="login-subtitle">AI 心理陪伴倾诉助手</div>

      <el-tabs v-model="activeTab" class="login-tabs">
        <el-tab-pane label="登录" name="login" />
        <el-tab-pane label="注册" name="register" />
      </el-tabs>

      <el-form :model="form" :rules="rules" ref="formRef" label-position="top">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" placeholder="请输入密码" show-password />
        </el-form-item>

        <el-form-item v-if="activeTab === 'register'" label="角色">
          <el-radio-group v-model="form.role">
            <el-radio value="USER">普通用户</el-radio>
            <el-radio value="ADMIN">管理员</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" size="large" style="width:100%" @click="handleSubmit" :loading="loading">
            {{ activeTab === 'login' ? '登录' : '注册' }}
          </el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import axios from 'axios'

const router = useRouter()
const activeTab = ref('login')
const loading = ref(false)
const formRef = ref(null)

const form = reactive({
  username: '',
  password: '',
  role: 'USER',
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, min: 6, message: '密码至少6位', trigger: 'blur' }],
}

async function handleSubmit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    const endpoint = activeTab.value === 'login' ? '/api/auth/login' : '/api/auth/register'
    const resp = await axios.post(endpoint, {
      username: form.username,
      password: form.password,
      role: form.role,
    })
    console.log('API response:', resp.data)
    if (resp.data.code === 200 && resp.data.data) {
      localStorage.setItem('token', resp.data.data.token)
      localStorage.setItem('userId', resp.data.data.userId)
      localStorage.setItem('username', resp.data.data.username)
      localStorage.setItem('role', resp.data.data.role)
      ElMessage.success('登录成功')
      router.push('/chat')
    } else {
      ElMessage.error(resp.data.message || '返回数据异常')
    }
  } catch (e) {
    console.error('Login error:', e)
    ElMessage.error(e.response?.data?.message || '操作失败')
  } finally {
    loading.value = false
  }
}
</script>
