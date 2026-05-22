<template>
  <div class="chat-container">
    <div class="chat-header">
      <div>
        <span style="font-size:18px;font-weight:600">结构化记忆</span>
        <span style="margin-left:12px;font-size:13px;opacity:0.8">{{ username }}</span>
      </div>
      <div>
        <el-popconfirm title="确定清空所有结构化记忆？" @confirm="clearAll">
          <template #reference>
            <el-button text style="color:#fff">清空全部</el-button>
          </template>
        </el-popconfirm>
        <el-button text @click="$router.push('/chat')" style="color:#fff">返回聊天</el-button>
        <el-button text @click="logout" style="color:#fff">退出</el-button>
      </div>
    </div>

    <div style="padding:24px;flex:1;overflow-y:auto">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="结构化记忆" name="facts" />
        <el-tab-pane label="向量记忆" name="vectors" />
      </el-tabs>

      <div v-if="activeTab === 'facts'">
      <el-table :data="facts" v-loading="loading" empty-text="暂无结构化记忆（多聊几轮后 AI 会自动学习）">
        <el-table-column prop="category" label="分类" width="120">
          <template #default="{ row }">
            <el-tag>{{ row.category }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="factContent" label="内容" min-width="300" />
        <el-table-column prop="createdAt" label="时间" width="180" />
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-popconfirm title="确定删除此条？" @confirm="deleteFact(row.id)">
              <template #reference>
                <el-button size="small" type="danger" text>删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
      </div>

      <div v-if="activeTab === 'vectors'">
        <div v-loading="vecLoading" style="display:flex;flex-direction:column;gap:10px">
          <div v-if="vectors.length === 0 && !vecLoading" style="color:#999;text-align:center;margin-top:40px">
            暂无向量记忆
          </div>
          <div v-for="(v, i) in vectors" :key="i"
               style="padding:12px 16px;background:#f9f9f9;border-radius:8px;font-size:14px;line-height:1.6;color:#555">
            {{ v }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '../api/request.js'

const router = useRouter()
const username = localStorage.getItem('username') || ''

const activeTab = ref('facts')
const facts = ref([])
const vectors = ref([])
const loading = ref(false)
const vecLoading = ref(false)

function logout() {
  localStorage.clear()
  router.push('/login')
}

async function loadFacts() {
  loading.value = true
  try {
    const res = await request.get('/fact/list')
    facts.value = res.data || []
  } catch (e) {
    // ignore
  } finally {
    loading.value = false
  }
}

async function loadVectors() {
  vecLoading.value = true
  try {
    const res = await request.get('/chat/memory/list?limit=50')
    vectors.value = res.data || []
  } catch (e) { /* ignore */ }
  finally { vecLoading.value = false }
}

async function deleteFact(id) {
  try {
    await request.delete(`/fact/${id}`)
    facts.value = facts.value.filter(f => f.id !== id)
    ElMessage.success('已删除')
  } catch (e) {
    ElMessage.error('删除失败')
  }
}

async function clearAll() {
  try {
    await request.delete('/fact/clear')
    facts.value = []
    ElMessage.success('已清空')
  } catch (e) {
    ElMessage.error('清空失败')
  }
}

onMounted(() => { loadFacts(); loadVectors() })
</script>
