<template>
  <div class="chat-container">
    <div class="chat-header">
      <div style="display:flex;align-items:center;gap:12px">
        <span style="font-size:18px;font-weight:600">心语 PsyChat</span>
        <span style="font-size:13px;opacity:0.8">{{ username }}</span>
      </div>
      <div>
        <el-button text @click="newSession" style="color:#fff">新对话</el-button>
        <el-button text @click="$router.push('/facts')" style="color:#fff">记忆</el-button>
        <el-button text @click="$router.push('/admin')" style="color:#fff">
          绑定管理
        </el-button>
        <el-button text @click="showPrefDialog = true" style="color:#fff">偏好</el-button>
        <el-button text @click="loadWeeklyReport" style="color:#fff" :loading="reportLoading">情绪周报</el-button>
        <el-popconfirm title="确定清除所有AI记忆？" @confirm="clearMemory">
          <template #reference>
            <el-button text style="color:#fff">清除记忆</el-button>
          </template>
        </el-popconfirm>
        <el-button text @click="logout" style="color:#fff">退出</el-button>
      </div>
    </div>

    <!-- session list bar -->
    <div v-if="sessions.length > 0" style="padding:8px 16px;border-bottom:1px solid #eee;display:flex;gap:6px;overflow-x:auto;white-space:nowrap;align-items:center">
      <div v-for="s in sessions" :key="s.sessionId"
           @click="switchSession(s.sessionId)"
           :style="{
             padding:'6px 12px', borderRadius:'16px', cursor:'pointer', fontSize:'13px',
             background: currentSession === s.sessionId ? '#667eea' : '#f0f0f0',
             color: currentSession === s.sessionId ? '#fff' : '#666',
             display:'inline-flex', alignItems:'center', gap:'4px'
           }"
           :title="s.preview">
        <span>{{ s.preview || s.createdAt }}</span>
        <el-popconfirm title="确定删除此会话？" @confirm="deleteSession(s.sessionId)" @click.stop>
          <template #reference>
            <span style="font-weight:bold;opacity:0.5;font-size:11px;line-height:1;cursor:pointer"
                  title="删除此会话">&#x2715;</span>
          </template>
        </el-popconfirm>
      </div>
    </div>

    <div class="chat-messages" ref="msgContainer">
      <div v-if="messages.length === 0" style="text-align:center;color:#aaa;margin-top:40%">
        <p style="font-size:48px;margin-bottom:16px">&#x1f31f;</p>
        <p>我是心语，你的AI心理陪伴助手</p>
        <p style="font-size:13px;margin-top:8px">有什么想聊的，都可以告诉我</p>
      </div>

      <div v-for="(msg, idx) in messages" :key="idx"
           :class="['message-bubble', msg.role === 'USER' ? 'message-user' : 'message-assistant']">
        <span v-if="msg.streaming" v-html="msg.content" class="typing-cursor"></span>
        <span v-else>{{ msg.content }}</span>
      </div>

      <div v-if="isStreaming && streamingText === ''" style="align-self:flex-start;color:#999;font-size:14px">
        心语正在输入...
      </div>
    </div>

    <div class="chat-input-area">
      <el-input
        v-model="inputText"
        placeholder="在这里倾诉你的心事..."
        @keyup.enter="sendMessage"
        :disabled="isStreaming"
        size="large"
      />
      <el-button type="primary" @click="sendMessage" :loading="isStreaming" size="large">
        发送
      </el-button>
    </div>

    <!-- preference dialog -->
    <el-dialog v-model="showPrefDialog" title="回复风格偏好" width="380px">
      <div style="display:flex;flex-direction:column;gap:16px">
        <div>
          <div style="margin-bottom:6px;font-size:14px;color:#666">语气风格</div>
          <el-radio-group v-model="prefForm.toneStyle" size="small">
            <el-radio-button value="warm">温柔共情</el-radio-button>
            <el-radio-button value="casual">轻松随意</el-radio-button>
            <el-radio-button value="professional">专业理性</el-radio-button>
            <el-radio-button value="concise">简洁直接</el-radio-button>
          </el-radio-group>
        </div>
        <div>
          <div style="margin-bottom:6px;font-size:14px;color:#666">回复长度</div>
          <el-radio-group v-model="prefForm.responseLength" size="small">
            <el-radio-button value="short">简短</el-radio-button>
            <el-radio-button value="medium">适中</el-radio-button>
            <el-radio-button value="long">展开</el-radio-button>
          </el-radio-group>
        </div>
        <div>
          <div style="margin-bottom:6px;font-size:14px;color:#666">主动问候</div>
          <el-switch v-model="prefForm.allowProactive" active-text="允许" inactive-text="关闭" />
        </div>
      </div>
      <template #footer>
        <el-button @click="showPrefDialog = false">取消</el-button>
        <el-button type="primary" @click="savePreferences">保存</el-button>
      </template>
    </el-dialog>

    <!-- weekly report dialog -->
    <el-dialog v-model="showReportDialog" title="本周情绪关怀周报" width="600px">
      <div v-if="reportData.empty" style="text-align:center;padding:24px;color:#999">
        <p style="font-size:36px;margin-bottom:12px">&#x1f338;</p>
        <p>{{ reportData.message }}</p>
      </div>
      <div v-else-if="reportData.summary" style="line-height:1.8;font-size:14px">
        <div style="margin-bottom:16px">
          <div style="font-weight:600;color:#667eea;margin-bottom:6px">&#x1f4ca; 情绪概览</div>
          <div style="color:#333">{{ reportData.summary }}</div>
        </div>
        <!-- emotion trend chart -->
        <div v-if="chartOption" style="margin-bottom:16px">
          <div style="font-weight:600;color:#667eea;margin-bottom:6px">&#x1f4c8; 情绪趋势</div>
          <v-chart :option="chartOption" style="height:200px" autoresize />
        </div>
        <div style="margin-bottom:16px">
          <div style="font-weight:600;color:#667eea;margin-bottom:6px">&#x1f4c8; 情绪趋势</div>
          <div style="color:#333">{{ reportData.trend }}</div>
        </div>
        <div style="margin-bottom:16px">
          <div style="font-weight:600;color:#667eea;margin-bottom:6px">&#x1f4dd; 关怀建议</div>
          <ul style="padding-left:16px;color:#333">
            <li v-for="(s, i) in reportData.suggestions" :key="i" style="margin-bottom:4px">{{ s }}</li>
          </ul>
        </div>
        <div style="margin-bottom:12px">
          <div style="font-weight:600;color:#667eea;margin-bottom:6px">&#x1f31f; 下周小目标</div>
          <div style="color:#333;font-style:italic">{{ reportData.weeklyGoal }}</div>
        </div>
        <div style="font-size:12px;color:#aaa;text-align:right">
          共 {{ reportData.eventCount }} 条情绪记录 · {{ reportData.generatedAt?.substring(0,10) }}
        </div>
      </div>
      <div v-else style="text-align:center;padding:24px;color:#999">
        <p>加载失败，请重试</p>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, nextTick, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const router = useRouter()

const username = localStorage.getItem('username') || ''
const role = localStorage.getItem('role') || 'USER'

const inputText = ref('')
const isStreaming = ref(false)
const streamingText = ref('')
const msgContainer = ref(null)
const currentSession = ref('')
const sessions = ref([])

const messages = reactive([])

const showPrefDialog = ref(false)
const showReportDialog = ref(false)
const reportLoading = ref(false)
const reportData = ref({})

const chartOption = computed(() => {
  const chart = reportData.value?.chart
  if (!chart || !chart.length) return null
  const emotionColors = {
    '焦虑': '#f59e0b', '低落': '#94a3b8', '疲惫': '#a8a29e',
    '难过': '#6366f1', '悲伤': '#6366f1', '绝望': '#dc2626',
    '愤怒': '#ef4444', '委屈': '#f97316', '内耗': '#8b5cf6',
    '恐惧': '#7c3aed', '压力': '#eab308', '平静': '#22c55e',
    '开心': '#10b981', '孤独': '#64748b', '困惑': '#06b6d4'
  }
  return {
    tooltip: { trigger: 'axis' },
    legend: { show: false },
    grid: { left: 40, right: 20, top: 10, bottom: 30 },
    xAxis: {
      type: 'category',
      data: chart.map(p => p.day),
      axisLabel: { fontSize: 11 }
    },
    yAxis: {
      type: 'value', min: 0, max: 1,
      axisLabel: { formatter: v => v.toFixed(1), fontSize: 11 },
      splitLine: { lineStyle: { type: 'dashed', color: '#eee' } }
    },
    series: [{
      type: 'line',
      data: chart.map(p => ({
        value: p.intensity,
        symbol: 'circle',
        symbolSize: 8,
        itemStyle: { color: emotionColors[p.label] || '#667eea' }
      })),
      smooth: true,
      lineStyle: { color: '#667eea', width: 2 },
      areaStyle: { color: 'rgba(102,126,234,0.08)' },
      markLine: {
        silent: true,
        data: [
          { yAxis: 0.3, lineStyle: { color: '#22c55e', type: 'dashed' }, label: { formatter: '平静' } },
          { yAxis: 0.7, lineStyle: { color: '#f59e0b', type: 'dashed' }, label: { formatter: '预警' } }
        ]
      }
    }]
  }
})

const prefForm = reactive({
  toneStyle: 'warm',
  responseLength: 'medium',
  allowProactive: false
})

async function loadPreferences() {
  try {
    const token = localStorage.getItem('token')
    const resp = await fetch('/api/preference', {
      headers: { 'Authorization': `Bearer ${token}` }
    })
    const body = await resp.json()
    if (body.code === 200 && body.data) {
      prefForm.toneStyle = body.data.toneStyle || 'warm'
      prefForm.responseLength = body.data.responseLength || 'medium'
      prefForm.allowProactive = body.data.allowProactive || false
    }
  } catch (e) { /* ignore */ }
}

async function savePreferences() {
  try {
    const token = localStorage.getItem('token')
    const resp = await fetch('/api/preference', {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify(prefForm)
    })
    const body = await resp.json()
    if (body.code === 200) {
      ElMessage.success('偏好已保存')
      showPrefDialog.value = false
    }
  } catch (e) {
    ElMessage.error('保存失败')
  }
}

async function loadWeeklyReport() {
  reportLoading.value = true
  try {
    const token = localStorage.getItem('token')
    const resp = await fetch('/api/emotion/weekly-report', {
      headers: { 'Authorization': `Bearer ${token}` }
    })
    const body = await resp.json()
    if (body.code === 200) {
      reportData.value = body.data
      showReportDialog.value = true
    } else {
      ElMessage.error('加载失败')
    }
  } catch (e) {
    ElMessage.error('加载失败')
  } finally {
    reportLoading.value = false
  }
}

function newSession() {
  currentSession.value = crypto.randomUUID()
  messages.splice(0)
  loadSessions()
}

function switchSession(sid) {
  currentSession.value = sid
  loadHistory(sid)
}

async function deleteSession(sid) {
  try {
    const token = localStorage.getItem('token')
    await fetch(`/api/chat/session/${encodeURIComponent(sid)}`, {
      method: 'DELETE',
      headers: { 'Authorization': `Bearer ${token}` }
    })
    sessions.value = sessions.value.filter(s => s.sessionId !== sid)
    if (currentSession.value === sid) {
      messages.splice(0)
      currentSession.value = crypto.randomUUID()
    }
    ElMessage.success('会话已删除')
  } catch (e) {
    ElMessage.error('删除失败')
  }
}

function logout() {
  localStorage.clear()
  router.push('/login')
}

async function clearMemory() {
  try {
    const token = localStorage.getItem('token')
    const resp = await fetch('/api/chat/memory', {
      method: 'DELETE',
      headers: { 'Authorization': `Bearer ${token}` }
    })
    const body = await resp.json()
    ElMessage.success(body.message || '记忆已清除')
  } catch (e) {
    ElMessage.error('清除失败')
  }
}

async function loadSessions() {
  try {
    const token = localStorage.getItem('token')
    const resp = await fetch('/api/chat/sessions', {
      headers: { 'Authorization': `Bearer ${token}` }
    })
    const body = await resp.json()
    if (body.code === 200) sessions.value = body.data || []
  } catch (e) { /* ignore */ }
}

async function loadHistory(sessionId) {
  messages.splice(0)
  try {
    const token = localStorage.getItem('token')
    const resp = await fetch(`/api/chat/history?sessionId=${encodeURIComponent(sessionId)}`, {
      headers: { 'Authorization': `Bearer ${token}` }
    })
    const body = await resp.json()
    if (body.code === 200 && body.data) {
      body.data.forEach(msg => {
        messages.push({ role: msg.role, content: msg.content, streaming: false })
      })
    }
  } catch (e) { /* ignore */ }
  scrollToBottom()
}

onMounted(async () => {
  currentSession.value = crypto.randomUUID()
  loadPreferences()
  await loadSessions()
  // if there are existing sessions, load the latest
  if (sessions.value.length > 0) {
    currentSession.value = sessions.value[0].sessionId
    await loadHistory(currentSession.value)
  }
  scrollToBottom()
})

async function sendMessage() {
  const text = inputText.value.trim()
  if (!text || isStreaming.value) return

  const isFirst = messages.length === 0

  inputText.value = ''
  messages.push({ role: 'USER', content: text, streaming: false })
  scrollToBottom()

  isStreaming.value = true
  streamingText.value = ''

  const aiMsg = reactive({ role: 'ASSISTANT', content: '', streaming: true })
  messages.push(aiMsg)
  scrollToBottom()

  try {
    const token = localStorage.getItem('token')
    const response = await fetch('/api/chat/send', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`,
      },
      body: JSON.stringify({ message: text, sessionId: currentSession.value }),
    })

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let pendingEvent = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      const chunk = buffer + decoder.decode(value, { stream: true })
      buffer = ''
      const lines = chunk.split('\n')
      if (!chunk.endsWith('\n')) {
        buffer = lines.pop()
      }
      for (const line of lines) {
        if (line.startsWith('event:')) {
          pendingEvent = line.substring(6).trim()
        } else if (line.startsWith('data:')) {
          const content = line.substring(5).trim()
          // auto-popup weekly report
          if (pendingEvent === 'report') {
            pendingEvent = ''
            try {
              reportData.value = JSON.parse(content)
              showReportDialog.value = true
            } catch (e) { /* ignore */ }
            continue
          }
          pendingEvent = ''
          if (content.startsWith('[ERROR]')) {
            aiMsg.content = '抱歉，' + content.substring(7) + '，请稍后重试'
            ElMessage.error(content.substring(7))
          } else if (content) {
            streamingText.value += content
            aiMsg.content = streamingText.value
            scrollToBottom()
          }
        }
      }
    }
  } catch (e) {
    aiMsg.content = '抱歉，连接似乎出现了问题，请稍后再试。'
    ElMessage.error('请求失败')
  } finally {
    aiMsg.streaming = false
    isStreaming.value = false
    streamingText.value = ''
    if (isFirst) loadSessions() // refresh session list on first message
  }
}

function scrollToBottom() {
  nextTick(() => {
    const el = msgContainer.value
    if (el) el.scrollTop = el.scrollHeight
  })
}
</script>
