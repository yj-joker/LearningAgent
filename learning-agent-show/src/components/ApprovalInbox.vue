<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { decideMemoryApproval, getMemoryApprovals } from '@/api/agent'
import { useApprovalNotifications } from '@/composables/useApprovalNotifications'
import { useAuth } from '@/composables/useAuth'
import { useToast } from '@/composables/useToast'
import type { MemoryApprovalView } from '@/types/api'

const { currentUser } = useAuth()
const { approvalRevision, connectionState } = useApprovalNotifications()
const { showToast } = useToast()
const approvals = ref<MemoryApprovalView[]>([])
const busy = ref(false)
const errorMessage = ref('')
let generation = 0
let ownerToken = currentUser.value?.token
let refreshTimer: ReturnType<typeof setTimeout> | undefined

// 按当前账号加载全部记忆提案，不能只看当前会话而漏掉后台整理申请。
async function refresh() {
  const version = ++generation
  const token = currentUser.value?.token
  if (!token) { approvals.value = []; return }
  try {
    const result = await getMemoryApprovals()
    if (version !== generation || token !== currentUser.value?.token) return
    const previous = new Set(approvals.value.map(item => String(item.id)))
    const added = result.filter(item => !previous.has(String(item.id))).length
    approvals.value = result
    errorMessage.value = ''
    if (added) showToast('info', '有记忆变更等待确认', '请展开审批栏查看内容，尚未修改记忆。')
  } catch {
    if (version === generation && token === currentUser.value?.token) errorMessage.value = '审批读取失败，请点击刷新'
  }
}

// 保留用户明确操作；审批返回 STALE 时说明旧方案失效，不能提示执行成功。
async function decide(item: MemoryApprovalView, approved: boolean) {
  if (busy.value) return
  const token = currentUser.value?.token
  busy.value = true
  generation++
  try {
    const result = await decideMemoryApproval(String(item.id), approved)
    if (token !== currentUser.value?.token) return
    showToast(result.status === 'STALE' ? 'info' : 'success',
      result.status === 'STALE' ? '方案已失效，未执行' : approved ? '已批准并执行' : '已拒绝', result.decisionReason ?? '')
  } catch (error) {
    if (token === currentUser.value?.token) showToast('error', '审批未完成', error instanceof Error ? error.message : '请刷新后重试')
  } finally {
    busy.value = false
    void refresh()
  }
}

// 格式化展示保存的原提案，不使用 v-html，也不允许前端改写后端的审批参数。
function proposalText(item: MemoryApprovalView) {
  try { return JSON.stringify(JSON.parse(item.candidateJson), null, 2) }
  catch { return item.candidateJson }
}

// 短时间多次变更合并为一次查询；旧账号的慢响应会被版本检查丢弃。
watch([approvalRevision, () => currentUser.value?.token, busy], () => {
  clearTimeout(refreshTimer)
  // 切换账号立刻清空旧账号正文，不能等新查询返回才隐藏。
  if (ownerToken !== currentUser.value?.token) {
    ownerToken = currentUser.value?.token
    generation++
    approvals.value = []
    errorMessage.value = ''
  }
  if (!currentUser.value?.token) { generation++; approvals.value = []; return }
  if (!busy.value) refreshTimer = setTimeout(() => void refresh(), 120)
}, { immediate: true })

// 切换到登录页后不继续查询，也不让旧响应回填已卸载的审批栏。
onBeforeUnmount(() => {
  generation++
  clearTimeout(refreshTimer)
})
</script>

<template>
  <section class="approval-inbox" aria-label="审批通知">
    <div class="approval-status">
      <span>{{ connectionState === 'connected' ? '审批实时通知已连接' : '审批通知重连中，可手动刷新' }}</span>
      <RouterLink :to="{ name: 'agent-chat' }">查看工具审批</RouterLink>
      <button type="button" :disabled="busy" @click="refresh">刷新记忆审批</button>
    </div>
    <p v-if="errorMessage" role="status">{{ errorMessage }}</p>
    <details v-if="approvals.length">
      <summary>待确认的记忆变更（{{ approvals.length }}）</summary>
      <article v-for="item in approvals" :key="String(item.id)">
        <strong>{{ item.approvalType === 'CONSOLIDATION' ? '记忆整理方案' : '记忆变更提案' }} · {{ item.scope === 'USER' ? '长期记忆' : '会话记忆' }}</strong>
        <p>来源会话：{{ item.sessionId }}。以下内容尚未执行，请确认后再批准。</p>
        <pre>{{ proposalText(item) }}</pre>
        <button type="button" :disabled="busy" @click="decide(item, true)">同意并执行</button>
        <button type="button" :disabled="busy" @click="decide(item, false)">拒绝</button>
      </article>
    </details>
  </section>
</template>

<style scoped>
.approval-inbox { margin: .75rem 1.5rem 0; padding: .75rem 1rem; border: 1px solid #cbd5e1; border-radius: 12px; font-size: .85rem; }
.approval-status { display: flex; flex-wrap: wrap; align-items: center; gap: .75rem; }
.approval-status span { flex: 1; }
.approval-inbox summary { cursor: pointer; margin-top: .75rem; font-weight: 600; }
.approval-inbox article { padding-top: .75rem; margin-top: .75rem; border-top: 1px solid #e2e8f0; }
.approval-inbox pre { max-height: 16rem; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; }
.approval-inbox button { margin-right: .5rem; padding: .3rem .6rem; border: 1px solid #94a3b8; border-radius: 6px; }
</style>
