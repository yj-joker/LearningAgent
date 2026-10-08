<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { Bell, Check, ChevronDown, RefreshCw, ShieldCheck, X } from 'lucide-vue-next'
import ProposalDetails from '@/components/ProposalDetails.vue'
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
const loading = ref(false)
const expanded = ref(false)
const pending = computed(() => approvals.value.filter(item => item.status === 'PENDING'))
const connectionText = computed(() => connectionState.value === 'connected'
  ? '实时同步中' : connectionState.value === 'connecting' ? '正在连接' : '连接待恢复')
const errorMessage = ref('')
let generation = 0
let ownerToken = currentUser.value?.token
let refreshTimer: ReturnType<typeof setTimeout> | undefined

// 按当前账号加载全部记忆提案，不能只看当前会话而漏掉后台整理申请。
async function refresh() {
  const version = ++generation
  const token = currentUser.value?.token
  if (!token) { approvals.value = []; return }
  loading.value = true
  try {
    const result = await getMemoryApprovals()
    if (version !== generation || token !== currentUser.value?.token) return
    const previous = new Set(pending.value.map(item => String(item.id)))
    const added = result.filter(item => item.status === 'PENDING' && !previous.has(String(item.id))).length
    approvals.value = result
    errorMessage.value = ''
    if (added) showToast('info', '有记忆变更等待确认', '请展开审批栏查看内容，尚未修改记忆。')
  } catch {
    if (version === generation && token === currentUser.value?.token) errorMessage.value = '审批读取失败，请点击刷新'
  } finally {
    if (version === generation) loading.value = false
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
    const title = { STALE: '方案已失效，未执行', APPROVED: '记忆变更已批准', REJECTED: '已拒绝', PENDING: '仍待确认，请刷新查看' }[result.status]
    showToast(result.status === 'APPROVED' || result.status === 'REJECTED' ? 'success' : 'info', title, result.decisionReason ?? '')
  } catch (error) {
    if (token === currentUser.value?.token) showToast('error', '审批未完成', error instanceof Error ? error.message : '请刷新后重试')
  } finally {
    busy.value = false
    void refresh()
  }
}

// 短时间多次变更合并为一次查询；旧账号的慢响应会被版本检查丢弃。
watch([approvalRevision, () => currentUser.value?.token, busy], () => {
  clearTimeout(refreshTimer)
  // 切换账号立刻清空旧账号正文，不能等新查询返回才隐藏。
  if (ownerToken !== currentUser.value?.token) {
    ownerToken = currentUser.value?.token
    generation++
    approvals.value = []
    expanded.value = false
    loading.value = false
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
  <section class="approval-inbox" :class="{ 'is-empty': !pending.length, 'is-expanded': expanded }" aria-label="记忆变更通知">
    <div class="approval-status">
      <Bell :size="17" />
      <button class="approval-toggle" type="button" :aria-expanded="expanded" aria-controls="memory-approval-body" @click="expanded = !expanded">
        记忆变更 <span class="approval-count" :class="{ 'is-clear': !pending.length }">{{ pending.length ? `${pending.length} 项待确认` : loading ? '读取中…' : '暂无待办' }}</span><ChevronDown :size="15" :class="{ flipped: expanded }" />
      </button>
      <span class="connection-label" :class="{ 'is-connected': connectionState === 'connected' }" role="status"><i :class="{ connected: connectionState === 'connected' }" />{{ connectionText }}</span>
      <div class="approval-header-actions">
        <RouterLink class="approval-tool-link" :to="{ name: 'sessions' }"><ShieldCheck :size="15" /> 查看会话审批</RouterLink>
        <button class="icon-button" type="button" title="刷新记忆变更" aria-label="刷新记忆变更" :disabled="busy || loading" @click="refresh"><RefreshCw :size="16" :class="{ spinning: loading }" /></button>
      </div>
    </div>
    <p v-if="errorMessage" class="approval-read-error" role="status">{{ errorMessage }}</p>
    <div v-if="expanded" id="memory-approval-body" class="approval-body">
      <div class="approval-body-actions">
        <span v-if="connectionState === 'connected'" class="connection-label is-connected"><i class="connected" />{{ connectionText }}</span>
        <RouterLink class="approval-tool-link" :to="{ name: 'sessions' }"><ShieldCheck :size="15" /> 查看会话审批</RouterLink>
        <button class="icon-button" type="button" title="刷新记忆变更" aria-label="刷新记忆变更" :disabled="busy || loading" @click="refresh"><RefreshCw :size="16" :class="{ spinning: loading }" /></button>
      </div>
      <p v-if="!pending.length" class="approval-empty">{{ loading ? '正在读取审批…' : '暂无待确认的记忆变更' }}</p>
      <article v-for="item in pending" :key="String(item.id)">
        <header><strong>{{ item.approvalType === 'CONSOLIDATION' ? '整理重复记忆' : ({ CREATE: '新增记忆', UPDATE: '修改记忆', DELETE: '删除记忆' }[item.operation ?? 'CREATE']) }}</strong><span>{{ item.scope === 'USER' ? '长期记忆' : '会话记忆' }} · 待确认</span></header>
        <RouterLink class="approval-source" :to="{ name: 'sessions', query: { session: String(item.sessionId) } }">查看来源会话</RouterLink>
        <ProposalDetails :value="item.candidateJson" />
        <details class="raw-proposal"><summary>原始参数</summary><pre>{{ item.candidateJson }}</pre></details>
        <footer><button class="button button-secondary" type="button" :disabled="busy" @click="decide(item, false)"><X :size="15" /> 拒绝</button><button class="button button-primary" type="button" :disabled="busy" @click="decide(item, true)"><Check :size="15" /> 同意并执行</button></footer>
      </article>
    </div>
  </section>
</template>

<style scoped>
.approval-inbox { margin: 14px 24px 0; font-size: 13px; background: #fff; border: 1px solid #dce8e7; border-radius: 8px; }
.approval-status { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 12px 16px; }
.approval-inbox.is-empty:not(.is-expanded) .approval-status { padding-block: 8px; }
.approval-status > svg { color: #319897; }
.approval-toggle { display: inline-flex; padding: 0; align-items: center; gap: 9px; border: 0; background: transparent; font-weight: 600; cursor: pointer; }
.approval-count { padding: 3px 8px; font-size: 11px; color: #946c20; background: #fff5d4; border-radius: 4px; }
.approval-count.is-clear { color: #71847b; background: #f0f5f2; }
.flipped { transform: rotate(180deg); }
.connection-label { display: flex; align-items: center; gap: 6px; margin-left: auto; color: #7a8885; font-size: 11px; }
.connection-label i { width: 6px; height: 6px; border-radius: 50%; background: #caa45b; }
.connection-label i.connected { background: #319897; }
.approval-header-actions { display: flex; align-items: center; gap: 12px; }
.approval-header-actions .icon-button, .approval-body-actions .icon-button { width: 30px; height: 30px; padding: 0; }
.approval-body-actions { display: none; }
.approval-tool-link { display: flex; align-items: center; gap: 5px; color: #287774; font-size: 12px; }
.approval-body { padding: 0 16px 16px; max-height: 65vh; overflow: auto; }
.approval-empty { color: #7a8885; padding-top: 12px; border-top: 1px solid #e5edeb; }
.approval-read-error { margin: 0; padding: 0 16px 12px; color: #a96a23; }
.approval-inbox article { padding: 18px 0; border-top: 1px solid #e2e8e6; }
.approval-inbox article header { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 8px; margin-bottom: 6px; }
.approval-inbox article header span, .approval-source { color: #7a8885; font-size: 11px; }
.approval-source { display: inline-block; margin-bottom: 16px; }
.approval-inbox footer { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
.raw-proposal { margin-top: 14px; color: #7a8885; font-size: 11px; }
summary { cursor: pointer; }
pre { max-height: 200px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; }
.spinning { animation: spin 1s linear infinite; } @keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 620px) {
  .approval-inbox { margin: 8px 14px 0; }
  .approval-status { gap: 8px; padding: 10px 12px; }
  .approval-toggle { flex: 1; min-width: 0; gap: 7px; font-size: 12px; }
  .approval-toggle > svg { margin-left: auto; }
  .approval-count { white-space: nowrap; }
  .approval-header-actions, .approval-status > .connection-label.is-connected { display: none; }
  .approval-status > .connection-label { flex-basis: 100%; margin-left: 25px; }
  .approval-body { padding: 0 12px 12px; }
  .approval-body-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 10px; padding-block: 8px; border-top: 1px solid #e5edeb; }
  .approval-body-actions .connection-label { margin: 0 auto 0 0; }
  .approval-read-error { padding: 0 12px 10px; }
}
@media (prefers-reduced-motion: reduce) { .spinning { animation: none; } }
</style>
