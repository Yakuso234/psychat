<template>
  <div class="chat-container">
    <div class="chat-header">
      <div>
        <span style="font-size:18px;font-weight:600">绑定管理</span>
        <span style="margin-left:12px;font-size:13px;opacity:0.8">
          {{ username }} ({{ role === 'ADMIN' ? '管理员' : '用户' }})
        </span>
      </div>
      <div>
        <el-badge :value="unreadCount" :hidden="unreadCount === 0" :max="99">
          <el-button text @click="toggleNotifPanel" style="color:#fff;font-size:18px">
            🔔
          </el-button>
        </el-badge>
        <el-button text @click="$router.push('/chat')" style="color:#fff">返回聊天</el-button>
        <el-button text @click="logout" style="color:#fff">退出</el-button>
      </div>
    </div>

    <!-- 通知面板 -->
    <div v-if="showNotifPanel" style="position:absolute;top:56px;right:24px;width:400px;max-height:500px;overflow-y:auto;background:#fff;border-radius:8px;box-shadow:0 4px 24px rgba(0,0,0,0.15);z-index:100;padding:16px">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
        <span style="font-weight:600">危机告警</span>
        <el-button size="small" text @click="markAllRead" :disabled="unreadCount === 0">全部已读</el-button>
      </div>
      <div v-if="notifications.length === 0" style="color:#999;text-align:center;padding:24px">暂无告警</div>
      <div v-for="n in notifications" :key="n.id"
           style="padding:10px 0;border-bottom:1px solid #f0f0f0;cursor:pointer"
           :style="{ opacity: n.isRead ? 0.5 : 1 }"
           @click="markRead(n.id)">
        <div style="display:flex;justify-content:space-between">
          <span style="font-weight:600" :style="{ color: n.isRead ? '#999' : '#e6a23c' }">
            {{ n.username }} · {{ n.riskLevel }}
          </span>
          <span style="font-size:12px;color:#999">{{ n.createdAt?.substring(0,16) }}</span>
        </div>
        <div style="font-size:13px;margin-top:4px;color:#666">{{ n.summary }}</div>
        <div v-if="n.evidence" style="font-size:12px;margin-top:2px;color:#999">触发词: {{ n.evidence }}</div>
      </div>
    </div>

    <div style="padding:24px;flex:1;overflow-y:auto">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="发起绑定" name="request" />
        <el-tab-pane label="已建立的绑定" name="accepted" />
        <el-tab-pane :label="'待处理 (' + pendingForMe.length + ')'" name="pending" />
      </el-tabs>

      <!-- 发起绑定 -->
      <div v-if="activeTab === 'request'" style="margin-top:20px">
        <p style="color:#999;margin-bottom:12px;font-size:13px">
          {{ role === 'ADMIN' ? '输入普通用户的用户名发起绑定' : '输入管理员的用户名发起绑定' }}
        </p>
        <el-input v-model="bindUsername" placeholder="请输入对方用户名" style="width:300px;margin-right:12px" />
        <el-button type="primary" @click="requestBind" :loading="bindLoading">发起绑定</el-button>
      </div>

      <!-- 已建立的绑定 -->
      <div v-if="activeTab === 'accepted'" style="margin-top:20px">
        <el-table :data="acceptedList" v-loading="loading" empty-text="暂无已建立的绑定关系">
          <el-table-column label="对方信息" min-width="200">
            <template #default="{ row }">
              {{ role === 'ADMIN' ? row.userName : row.adminName }}
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="绑定时间" width="180" />
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-popconfirm title="确定解除绑定？" @confirm="cancelBind(row.id)">
                <template #reference>
                  <el-button size="small" type="danger" text>解除绑定</el-button>
                </template>
              </el-popconfirm>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 待处理请求 -->
      <div v-if="activeTab === 'pending'" style="margin-top:20px">
        <!-- 需要我处理的 -->
        <h4 style="margin-bottom:12px;color:#e6a23c">需要我处理</h4>
        <el-table :data="pendingForMe" v-loading="loading" empty-text="暂无">
          <el-table-column label="发起方" min-width="200">
            <template #default="{ row }">
              {{ row.initiator === 'ADMIN' ? row.adminName : row.userName }}
            </template>
          </el-table-column>
          <el-table-column label="对方" min-width="200">
            <template #default="{ row }">
              {{ row.initiator === 'ADMIN' ? row.userName : row.adminName }}
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="时间" width="180" />
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button size="small" type="success" @click="respondBind(row.id, 'accept')">接受</el-button>
              <el-button size="small" type="danger" @click="respondBind(row.id, 'reject')">拒绝</el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- 我发起的 -->
        <h4 style="margin:24px 0 12px;color:#909399">我发起的（等待对方回应）</h4>
        <el-table :data="pendingByMe" v-loading="loading" empty-text="暂无">
          <el-table-column label="对方" min-width="200">
            <template #default="{ row }">
              {{ role === 'ADMIN' ? row.userName : row.adminName }}
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="发起时间" width="180" />
        </el-table>

        <!-- 已拒绝的 -->
        <h4 style="margin:24px 0 12px;color:#f56c6c">已拒绝</h4>
        <el-table :data="rejectedList" v-loading="loading" empty-text="暂无">
          <el-table-column label="对方" min-width="200">
            <template #default="{ row }">
              {{ role === 'ADMIN' ? row.userName : row.adminName }}
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="时间" width="180" />
        </el-table>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../api/request.js'

const router = useRouter()
const username = localStorage.getItem('username') || ''
const role = localStorage.getItem('role') || 'USER'
const myId = Number(localStorage.getItem('userId'))

const activeTab = ref('request')
const bindUsername = ref('')
const bindLoading = ref(false)
const allBinds = ref([])
const loading = ref(false)

// notification polling
const unreadCount = ref(0)
const notifications = ref([])
const showNotifPanel = ref(false)
let notifTimer = null

async function loadUnreadCount() {
  try {
    const res = await request.get('/notification/unread-count')
    unreadCount.value = res.data ?? 0
  } catch (e) {
    // ignore
  }
}

async function loadNotifications() {
  try {
    const res = await request.get('/notification/list')
    notifications.value = res.data || []
  } catch (e) {
    // ignore
  }
}

async function markRead(id) {
  try {
    await request.put('/notification/read/' + id)
    const n = notifications.value.find(x => x.id === id)
    if (n) n.isRead = true
    if (unreadCount.value > 0) unreadCount.value--
  } catch (e) {
    // ignore
  }
}

function toggleNotifPanel() {
  showNotifPanel.value = !showNotifPanel.value
  if (showNotifPanel.value) {
    loadNotifications()
  }
}

async function markAllRead() {
  try {
    await request.put('/notification/read-all')
    notifications.value.forEach(n => n.isRead = true)
    unreadCount.value = 0
  } catch (e) {
    // ignore
  }
}

// 已建立绑定
const acceptedList = computed(() =>
  allBinds.value.filter(r => r.status === 'ACCEPTED')
)

// 待处理：需要我处理的（我不是发起方）
const pendingForMe = computed(() =>
  allBinds.value.filter(r => {
    if (r.status !== 'PENDING') return false
    // 我是管理员且发起方是USER → 需要我处理
    if (role === 'ADMIN' && r.initiator === 'USER') return true
    // 我是用户且发起方是ADMIN → 需要我处理
    if (role === 'USER' && r.initiator === 'ADMIN') return true
    return false
  })
)

// 待处理：我发起的（等待对方）
const pendingByMe = computed(() =>
  allBinds.value.filter(r => {
    if (r.status !== 'PENDING') return false
    if (role === 'ADMIN' && r.initiator === 'ADMIN') return true
    if (role === 'USER' && r.initiator === 'USER') return true
    return false
  })
)

// 已拒绝
const rejectedList = computed(() =>
  allBinds.value.filter(r => r.status === 'REJECTED')
)

function logout() {
  localStorage.clear()
  router.push('/login')
}

async function loadBinds() {
  loading.value = true
  try {
    const res = await request.get('/bind/list')
    allBinds.value = res.data || []
  } catch (e) {
    // ignore
  } finally {
    loading.value = false
  }
}

async function requestBind() {
  if (!bindUsername.value.trim()) {
    ElMessage.warning('请输入用户名')
    return
  }
  bindLoading.value = true
  try {
    await request.post('/bind/request', { targetUsername: bindUsername.value.trim() })
    ElMessage.success('绑定请求已发送')
    bindUsername.value = ''
    loadBinds()
  } catch (e) {
    // error handled by interceptor
  } finally {
    bindLoading.value = false
  }
}

async function respondBind(bindId, action) {
  try {
    await request.post('/bind/respond', null, { params: { bindId, action } })
    ElMessage.success(action === 'accept' ? '已接受绑定' : '已拒绝')
    loadBinds()
  } catch (e) {
    // error handled by interceptor
  }
}

async function cancelBind(bindId) {
  try {
    await request.post('/bind/cancel', null, { params: { bindId } })
    ElMessage.success('已解除绑定')
    loadBinds()
  } catch (e) {
    // error handled by interceptor
  }
}

onMounted(() => {
  loadBinds()
  loadUnreadCount()
  notifTimer = setInterval(() => {
    loadUnreadCount()
    if (showNotifPanel.value) loadNotifications()
  }, 10000)
})

onBeforeUnmount(() => {
  if (notifTimer) clearInterval(notifTimer)
})
</script>
