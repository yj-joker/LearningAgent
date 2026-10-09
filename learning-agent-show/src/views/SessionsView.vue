<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { Bot, CheckCircle2, Play, RefreshCw, Search, Trash2 } from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import ModalDialog from '@/components/ModalDialog.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { ApiError } from '@/api/client'
import { completeSession, deleteLearningSession, listSessions } from '@/api/sessions'
import { useActivity } from '@/composables/useActivity'
import { useAuth } from '@/composables/useAuth'
import { useToast } from '@/composables/useToast'
import type { LearningSessionVO } from '@/types/api'

const route = useRoute()
const router = useRouter()
const { addActivity, removeSessionActivities } = useActivity()
const { currentUser } = useAuth()
const { showToast } = useToast()

const sessions = ref<LearningSessionVO[]>([])
const loading = ref(false)
const loadError = ref('')
const completingId = ref<string | null>(null)
const deletingSessionId = ref<string | null>(null)
const deleteTarget = ref<{ id: string; title: string } | null>(null)
const search = ref('')
const statusFilter = ref('ALL')
const modeFilter = ref('ALL')
const focusedSessionId = computed(() => typeof route.query.session === 'string' ? route.query.session : '')
let requestSequence = 0
let identityVersion = 0
let disposed = false

const activeCount = computed(() => sessions.value.filter(item => item.sessionStatus === 'ACTIVE').length)
const filteredSessions = computed(() => sessions.value.filter(item =>
  (!focusedSessionId.value || String(item.id) === focusedSessionId.value)
  && (statusFilter.value === 'ALL' || item.sessionStatus === statusFilter.value)
  && (modeFilter.value === 'ALL' || sessionMode(item) === modeFilter.value)
  && `${item.sessionTitle} ${item.courseName ?? ''}`.toLowerCase().includes(search.value.trim().toLowerCase())))
const mutationPending = computed(() => Boolean(completingId.value) || Boolean(deletingSessionId.value))

// 会话列表以数据库为准；较早的请求不能覆盖刷新结果或新账号的数据。
async function loadSessions() {
  const token = currentUser.value?.token
  if (!token || disposed) return
  const sequence = ++requestSequence
  const version = identityVersion
  loading.value = true
  loadError.value = ''
  try {
    const result = await listSessions()
    if (!isCurrentIdentity(version, token) || sequence !== requestSequence) return
    sessions.value = result
  } catch (error) {
    if (!isCurrentIdentity(version, token) || sequence !== requestSequence) return
    sessions.value = []
    loadError.value = error instanceof ApiError ? error.message : '会话加载失败，请稍后重试'
  } finally {
    if (isCurrentIdentity(version, token) && sequence === requestSequence) loading.value = false
  }
}

function isCurrentIdentity(version: number, token: string) {
  return !disposed && version === identityVersion && token === currentUser.value?.token
}

// 切换账号立即清除旧列表和弹窗，避免上一位用户的慢响应出现在新账号中。
watch(() => currentUser.value?.token, token => {
  identityVersion++
  requestSequence++
  useActivity()
  sessions.value = []
  loadError.value = ''
  loading.value = false
  completingId.value = null
  deletingSessionId.value = null
  deleteTarget.value = null
  search.value = ''
  statusFilter.value = 'ALL'
  modeFilter.value = 'ALL'
  if (token) void loadSessions()
}, { immediate: true })

watch(() => route.query.create, value => {
  if (value === '1') {
    void router.replace({ name: 'agent-chat', query: { create: '1' } })
  }
}, { immediate: true })

// 审批通知携带的是会话 ID；精确定位来源会话，不把 ID 当作标题搜索词。
watch(focusedSessionId, id => {
  if (!id) return
  search.value = ''
  statusFilter.value = 'ALL'
  modeFilter.value = 'ALL'
}, { immediate: true })

function clearSessionFocus() {
  const query = { ...route.query }
  delete query.session
  void router.replace({ query })
}

onBeforeUnmount(() => {
  disposed = true
  requestSequence++
})

function sessionMode(item: LearningSessionVO) {
  // 只使用服务器保存的模式；缺失模式不能猜成问答或课程学习。
  return item.mode === 'COURSE' || item.mode === 'CHAT' || item.mode === 'FOCUS' ? item.mode : 'UNKNOWN'
}

function modeLabel(item: LearningSessionVO) {
  const mode = sessionMode(item)
  return mode === 'COURSE' ? '课程学习' : mode === 'FOCUS' ? '专注学习' : mode === 'CHAT' ? '自由问答' : '类型待更新'
}

function conversationRoute(item: LearningSessionVO) {
  const mode = sessionMode(item)
  return {
    name: mode === 'COURSE' ? 'agent-chat' : mode === 'FOCUS' ? 'agent-focus' : mode === 'CHAT' ? 'agent-chat-standalone' : 'sessions',
    query: { session: String(item.id) },
  }
}

function formatTime(item: LearningSessionVO) {
  const value = item.updatedAt ?? item.updateAt ?? item.createdAt ?? item.createAt
  if (!value) return '时间未记录'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '时间未记录' : date.toLocaleString('zh-CN')
}

async function finishSession(item: LearningSessionVO) {
  if (mutationPending.value || !currentUser.value?.token) return
  const id = String(item.id)
  const token = currentUser.value.token
  const version = identityVersion
  completingId.value = id
  try {
    const result = await completeSession(id)
    if (!isCurrentIdentity(version, token)) return
    addActivity({ kind: 'session-completed', title: result.sessionTitle || item.sessionTitle, description: '已完成本次学习目标', status: result.sessionStatus ?? 'COMPLETED', resourceId: id })
    showToast('success', '学习会话已完成', result.sessionTitle || item.sessionTitle)
    await loadSessions()
  } catch (error) {
    if (isCurrentIdentity(version, token)) showToast('error', '完成失败', error instanceof ApiError ? error.message : '学习会话完成失败，请稍后重试')
  } finally {
    if (isCurrentIdentity(version, token)) completingId.value = null
  }
}

function closeDeleteDialog() {
  if (!deletingSessionId.value) deleteTarget.value = null
}

async function confirmDeleteSession() {
  const target = deleteTarget.value
  if (!target || mutationPending.value || !currentUser.value?.token) return
  const token = currentUser.value.token
  const version = identityVersion
  deletingSessionId.value = target.id
  try {
    await deleteLearningSession(target.id)
    if (!isCurrentIdentity(version, token)) return
    removeSessionActivities(target.id)
    deleteTarget.value = null
    showToast('success', '学习会话已删除', target.title)
    await loadSessions()
  } catch (error) {
    if (isCurrentIdentity(version, token)) showToast('error', '删除失败', error instanceof ApiError ? error.message : '学习会话删除失败，请稍后重试')
  } finally {
    if (isCurrentIdentity(version, token)) deletingSessionId.value = null
  }
}
</script>

<template>
  <div class="resource-view">
    <section class="page-heading">
      <div><span class="section-kicker">学习记录</span><h2>全部历史</h2><p class="session-heading-copy">课程学习、独立问答和专注学习的会话记录。</p></div>
    </section>

    <section class="panel session-history-panel" :aria-busy="loading">
      <div class="panel-header"><div><h3>我的会话</h3></div><div class="session-header-actions"><span class="count-pill">{{ sessions.length }} 个会话 · {{ activeCount }} 进行中</span><button class="icon-button" type="button" title="刷新会话" aria-label="刷新会话" :disabled="loading || mutationPending" @click="loadSessions"><RefreshCw :size="17" :class="{ spinning: loading }" /></button></div></div>
      <div v-if="focusedSessionId" class="session-focus-banner" role="status"><span>正在查看通知对应的会话</span><button type="button" class="button button-secondary" @click="clearSessionFocus">清除定位，查看全部会话</button></div>
      <div class="session-tools">
        <div class="session-filter" role="group" aria-label="会话状态"><button v-for="filter in [{ value: 'ALL', label: '全部状态' }, { value: 'ACTIVE', label: '进行中' }, { value: 'COMPLETED', label: '已完成' }]" :key="filter.value" type="button" :disabled="Boolean(focusedSessionId)" :class="{ active: statusFilter === filter.value }" :aria-pressed="statusFilter === filter.value" @click="statusFilter = filter.value">{{ filter.label }}</button></div>
        <label class="session-mode-filter"><span class="visually-hidden">会话类型</span><select v-model="modeFilter" :disabled="Boolean(focusedSessionId)" aria-label="会话类型"><option value="ALL">全部类型</option><option value="COURSE">课程学习</option><option value="CHAT">自由问答</option><option value="FOCUS">专注学习</option><option value="UNKNOWN">类型待更新</option></select></label>
        <label class="session-search"><Search :size="16" /><input v-model="search" :disabled="Boolean(focusedSessionId)" placeholder="搜索会话或课程" aria-label="搜索会话或课程" /></label>
      </div>
      <div v-if="loadError" class="session-load-error" role="alert"><EmptyState title="会话暂时无法加载" :description="loadError" /><button class="button button-secondary" :disabled="loading" @click="loadSessions"><RefreshCw :size="16" /> 重新加载</button></div>
      <div v-else-if="loading && !sessions.length" class="session-loading" role="status"><RefreshCw class="spinning" :size="22" /> 正在加载会话…</div>
      <div v-else-if="filteredSessions.length" class="session-timeline">
        <article v-for="item in filteredSessions" :key="String(item.id)" class="session-record" :class="{ 'session-record-focused': focusedSessionId === String(item.id) }">
          <span class="timeline-mark" :class="item.sessionStatus === 'ACTIVE' ? 'active' : 'done'"><Play v-if="item.sessionStatus === 'ACTIVE'" :size="16" /><CheckCircle2 v-else :size="18" /></span>
          <div class="session-record-copy">
            <div><h4>{{ item.sessionTitle || '未命名会话' }}</h4><StatusBadge :status="item.sessionStatus" /><span class="session-mode-tag">{{ modeLabel(item) }}</span></div>
            <p v-if="sessionMode(item) === 'COURSE'">课程：{{ item.courseName || '未命名课程' }}</p>
            <p v-else>{{ sessionMode(item) === 'FOCUS' ? '围绕一个目标逐步学习' : sessionMode(item) === 'CHAT' ? '随时提问，讨论你感兴趣的问题' : '会话类型尚未更新，暂时无法打开对话。' }}</p>
            <time>{{ formatTime(item) }}</time>
            <div class="session-record-actions">
              <RouterLink v-if="sessionMode(item) !== 'UNKNOWN'" class="button button-primary session-chat-button" :to="conversationRoute(item)"><Bot :size="14" /> {{ item.sessionStatus === 'ACTIVE' ? '继续对话' : '查看对话' }}</RouterLink>
              <button v-else class="button button-secondary session-chat-button" disabled><Bot :size="14" /> 类型待更新</button>
              <button v-if="item.sessionStatus === 'ACTIVE'" class="button button-secondary session-complete-button" :disabled="mutationPending" @click="finishSession(item)"><CheckCircle2 :size="14" />{{ completingId === String(item.id) ? '完成中…' : '完成会话' }}</button>
              <button class="icon-button session-delete-button" title="删除会话" aria-label="删除会话" :disabled="mutationPending" @click="deleteTarget = { id: String(item.id), title: item.sessionTitle || '未命名会话' }"><Trash2 :size="15" /></button>
            </div>
          </div>
        </article>
      </div>
      <EmptyState v-else-if="focusedSessionId" title="未找到通知对应的会话" description="这个会话可能已被删除，或不属于当前账号。可以清除定位查看其他会话。" />
      <EmptyState v-else-if="sessions.length" title="没有匹配的会话" description="试试其他类型、状态或搜索词。" />
      <div v-else class="session-empty-action"><EmptyState title="还没有学习会话" description="开始课程学习，或前往 AI 助教发起自由问答和专注学习。" /><RouterLink class="button button-secondary" :to="{ name: 'agent-chat-standalone' }"><Bot :size="16" /> 前往 AI 助教</RouterLink></div>
    </section>

    <ModalDialog :open="Boolean(deleteTarget)" title="删除学习会话" :description="deleteTarget ? `确认删除“${deleteTarget.title}”？` : ''" @close="closeDeleteDialog">
      <div class="delete-chapter-confirm"><span><Trash2 :size="23" /></span><p>删除后，这个会话将不再显示在学习会话列表中。</p></div>
      <footer class="form-actions"><button type="button" class="button button-secondary" :disabled="Boolean(deletingSessionId)" @click="closeDeleteDialog">取消</button><button class="button chapter-delete-button" :disabled="Boolean(deletingSessionId)" @click="confirmDeleteSession">{{ deletingSessionId ? '删除中…' : '确认删除' }}</button></footer>
    </ModalDialog>
  </div>
</template>

<style scoped>
.session-history-panel { padding: 0; border: 0; border-radius: 0; box-shadow: none; background: transparent; }
.session-heading-copy { margin: 8px 0 0; color: #73837b; font-size: 13px; }
.session-header-actions { display: flex; align-items: center; gap: 12px; }
.session-focus-banner { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; margin-bottom: 16px; padding: 14px 16px; border: 1px solid #b6d7ca; border-radius: 6px; background: #edf7f1; color: #315f52; font-size: 13px; }
.session-focus-banner .button { min-height: 34px; font-size: 12px; }
.session-tools { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; padding-bottom: 18px; border-bottom: 1px solid #cfe1dc; }
.session-filter { display: flex; gap: 4px; padding: 3px; background: #eaf2ee; border-radius: 6px; }
.session-filter button { border: 0; border-radius: 4px; background: transparent; color: #73837b; padding: 8px 14px; font-size: 12px; cursor: pointer; }
.session-filter button.active { background: #fff; color: #287774; box-shadow: 0 1px 3px #d6e4dc; }
.session-filter button:disabled, .session-mode-filter select:disabled, .session-search input:disabled { opacity: .6; cursor: default; }
.session-mode-filter select { min-height: 36px; padding: 8px 12px; border: 1px solid #d7e4df; background: #fff; border-radius: 6px; color: #3d5a50; font-size: 12px; }
.session-search { display: flex; align-items: center; gap: 8px; padding: 10px 12px; margin-left: auto; color: #7a8885; border: 1px solid #d7e4df; background: white; border-radius: 6px; }
.session-search input { width: 210px; min-width: 0; outline: 0; border: 0; background: transparent; font-size: 12px; }
.session-record { padding: 22px 0; }
.session-record-focused { border-bottom: 1px solid #b6d7ca; }
.session-record-copy { min-width: 0; flex: 1; }
.session-record-copy h4 { font-size: 15px; overflow-wrap: anywhere; }
.session-record-copy > div:first-child { flex-wrap: wrap; gap: 8px; }
.session-record-copy p, .session-record-copy time { font-size: 12px; }
.session-mode-tag { padding: 4px 8px; border: 1px solid #d7e4df; border-radius: 4px; color: #517366; font-size: 11px; }
.session-record-actions { flex-wrap: wrap; }
.session-record-actions .button { min-height: 36px; font-size: 12px; }
.session-record-actions .session-delete-button { width: 36px; height: 36px; padding: 0; color: #b34d4d; margin-left: auto; }
.session-loading { display: flex; align-items: center; justify-content: center; gap: 10px; min-height: 200px; color: #73837b; font-size: 14px; }
.session-load-error { display: flex; align-items: center; flex-direction: column; padding-bottom: 24px; }
.spinning { animation: session-spin 1s linear infinite; }
.visually-hidden { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }
@keyframes session-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .spinning { animation: none; } }
@media (max-width: 620px) { .session-filter { width: 100%; } .session-filter button { flex: 1; padding: 8px 5px; } .session-mode-filter { width: 100%; } .session-mode-filter select { width: 100%; } .session-search { width: 100%; margin-left: 0; } .session-search input { width: 100%; } .session-record-actions .session-delete-button { margin-left: 0; } .session-header-actions { gap: 6px; } }
</style>
