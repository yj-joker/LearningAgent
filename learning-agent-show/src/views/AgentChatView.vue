<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ArrowRight, Bot, BookOpenText, MessageSquareText, Send, Sparkles, UserRound } from 'lucide-vue-next'
import { chatWithAgent, getActiveAgentRun, getAgentRun, decideAgentTool, resumeAgentRun } from '@/api/agent'
import type { AgentRunResult, ToolApprovalRequest } from '@/types/api'
import { ApiError } from '@/api/client'
import { useActivity } from '@/composables/useActivity'
import { useAuth } from '@/composables/useAuth'
import { useToast } from '@/composables/useToast'
import { useApprovalNotifications } from '@/composables/useApprovalNotifications'

type ChatRole = 'user' | 'assistant'

interface ChatMessage {
  id: string
  role: ChatRole
  content: string
  createdAt: string
}

interface ActiveSession {
  id: string
  title: string
  course: string
  createdAt: string
}

const MAX_MESSAGE_LENGTH = 10_000
const STORAGE_PREFIX = 'learning-agent.agent-chat.v1'

const route = useRoute()
const router = useRouter()
const { activities } = useActivity()
const { currentUser } = useAuth()
const { showToast } = useToast()
const { approvalRevision } = useApprovalNotifications()
let runRefreshVersion = 0
let runRefreshTimer: ReturnType<typeof setTimeout> | undefined

const selectedSessionId = ref('')
const draft = ref('')
const messages = ref<ChatMessage[]>([])
const sending = ref(false)
// 当前发送模式只影响新请求，审批恢复仍由后端检查点决定。
const agentMode = ref<'CHAT' | 'FOCUS'>('CHAT')
// 审批状态来自后端，等待期间没有挂起中的聊天请求。
const activeRun = ref<AgentRunResult | null>(null)
const approvalBusy = ref(false)
const loadingRun = ref(false)
const messageList = ref<HTMLElement | null>(null)
const composer = ref<HTMLTextAreaElement | null>(null)

const activeSessions = computed<ActiveSession[]>(() => {
  const completedIds = new Set(
    activities.value
      .filter((item) => item.kind === 'session-completed' && item.resourceId)
      .map((item) => String(item.resourceId)),
  )
  const sessions = new Map<string, ActiveSession>()
  for (const item of activities.value) {
    if (item.kind !== 'session-created' || !item.resourceId) continue
    const id = String(item.resourceId)
    if (completedIds.has(id) || sessions.has(id)) continue
    sessions.set(id, {
      id,
      title: item.title,
      course: item.description.replace(/^课程[：:]\s*/, ''),
      createdAt: item.createdAt,
    })
  }
  return [...sessions.values()]
})

const selectedSession = computed(() => activeSessions.value.find((item) => item.id === selectedSessionId.value) ?? null)
const remainingCharacters = computed(() => MAX_MESSAGE_LENGTH - draft.value.length)
const canSend = computed(() => Boolean(selectedSession.value && draft.value.trim() && !sending.value && !approvalBusy.value && !loadingRun.value && !activeRun.value && remainingCharacters.value >= 0))

function conversationStorageKey(sessionId: string) {
  const owner = encodeURIComponent(currentUser.value?.username || 'guest')
  return `${STORAGE_PREFIX}.${owner}.${encodeURIComponent(sessionId)}`
}

function loadConversation(sessionId: string) {
  try {
    const stored = localStorage.getItem(conversationStorageKey(sessionId))
    messages.value = stored ? JSON.parse(stored) as ChatMessage[] : []
  } catch {
    messages.value = []
  }
}

function persistConversation() {
  if (!selectedSessionId.value) return
  try {
    localStorage.setItem(conversationStorageKey(selectedSessionId.value), JSON.stringify(messages.value.slice(-60)))
  } catch {
    // 浏览器存储不可用时仍允许继续当前对话。
  }
}

function createMessage(role: ChatRole, content: string): ChatMessage {
  return {
    id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    role,
    content,
    createdAt: new Date().toISOString(),
  }
}

async function scrollToLatest() {
  await nextTick()
  if (messageList.value) messageList.value.scrollTop = messageList.value.scrollHeight
}

async function selectSession(sessionId: string) {
  if (sending.value || approvalBusy.value || sessionId === selectedSessionId.value) return
  await router.replace({ name: 'agent-chat', query: { session: sessionId } })
}

async function sendMessage() {
  const session = selectedSession.value
  const ownerToken = currentUser.value?.token
  const userMessage = draft.value.trim()
  if (!session || !userMessage || !canSend.value) return
  if (userMessage.length > MAX_MESSAGE_LENGTH) {
    showToast('error', '内容过长', `每次最多输入 ${MAX_MESSAGE_LENGTH} 个字符`)
    return
  }

  const pendingMessage = createMessage('user', userMessage)
  messages.value.push(pendingMessage)
  persistConversation()
  draft.value = ''
  sending.value = true
  // 正在发送时作废旧查询，不能让旧审批状态覆盖这次响应。
  runRefreshVersion++
  await scrollToLatest()

  try {
    const result = await chatWithAgent({ sessionId: session.id, userMessage, mode: agentMode.value })
    if (ownerToken !== currentUser.value?.token || selectedSessionId.value !== session.id) return
    // 后端直接返回暂停状态；展示原工具参数，不把它误报为执行成功。
    acceptRunResult(result)
    persistConversation()
    await scrollToLatest()
  } catch (error) {
    if (ownerToken !== currentUser.value?.token || selectedSessionId.value !== session.id) return
    messages.value = messages.value.filter((message) => message.id !== pendingMessage.id)
    persistConversation()
    draft.value = userMessage
    showToast('error', '消息发送失败', error instanceof ApiError ? error.message : 'AI 助教暂时无法回复，请稍后重试')
  } finally {
    if (ownerToken === currentUser.value?.token) sending.value = false
    await nextTick()
    composer.value?.focus()
  }
}

// 同一任务的最终回答使用稳定编号，重复恢复响应不会重复显示答案。
function acceptRunResult(result: AgentRunResult) {
  activeRun.value = result.status === 'COMPLETED' || result.status === 'FAILED' ? null : result
  const id = result.status === 'COMPLETED' ? 'run-' + result.runId + '-completed' : 'run-' + result.runId + '-' + result.batchNumber
  const message = { ...createMessage('assistant', result.answer), id }
  const index = messages.value.findIndex(item => item.id === id)
  if (index < 0) messages.value.push(message)
  else messages.value[index] = message
  persistConversation()
}

// 切换或刷新页面后从数据库找回任务；旧会话的慢响应不能覆盖当前会话。
async function restorePendingRun(sessionId: string) {
  const version = ++runRefreshVersion
  const token = currentUser.value?.token
  const knownRunId = activeRun.value?.runId
  loadingRun.value = true
  try {
    // 已知任务按 runId 查询，才能接到另一个标签页恢复后的最终回答。
    const result = knownRunId ? await getAgentRun(knownRunId) : await getActiveAgentRun(sessionId)
    if (version !== runRefreshVersion || selectedSessionId.value !== sessionId
        || token !== currentUser.value?.token || sending.value || approvalBusy.value) return
    if (result) acceptRunResult(result)
    else activeRun.value = null
  } catch (error) {
    if (version === runRefreshVersion && selectedSessionId.value === sessionId) showToast('error', '读取审批状态失败', error instanceof Error ? error.message : '请刷新后重试')
  } finally {
    if (version === runRefreshVersion && selectedSessionId.value === sessionId
        && token === currentUser.value?.token) loadingRun.value = false
  }
}

// 单项决定只保存用户选择；所有决定齐备后才显示“继续执行”。
async function decideTool(request: ToolApprovalRequest, approved: boolean) {
  if (approvalBusy.value || !activeRun.value) return
  const ownerToken = currentUser.value?.token
  const sessionId = selectedSessionId.value
  approvalBusy.value = true
  runRefreshVersion++
  loadingRun.value = false
  try {
    const result = await decideAgentTool(request.runId, request.batchNumber, request.toolCallId, approved)
    if (ownerToken === currentUser.value?.token && sessionId === selectedSessionId.value) activeRun.value = result
  } catch (error) {
    if (ownerToken !== currentUser.value?.token) return
    showToast('error', '审批未完成', error instanceof Error ? error.message : '请刷新后重试')
  } finally {
    if (ownerToken === currentUser.value?.token) approvalBusy.value = false
  }
}

// 继续同一个 runId，不再调用聊天接口重复提交原问题。
async function continueRun() {
  if (approvalBusy.value || activeRun.value?.status !== 'APPROVAL_RESOLVED') return
  const runId = activeRun.value.runId
  const ownerToken = currentUser.value?.token
  const sessionId = selectedSessionId.value
  approvalBusy.value = true
  runRefreshVersion++
  loadingRun.value = false
  try {
    const result = await resumeAgentRun(runId)
    if (ownerToken !== currentUser.value?.token || sessionId !== selectedSessionId.value) return
    acceptRunResult(result)
    await scrollToLatest()
  } catch (error) {
    if (ownerToken !== currentUser.value?.token || sessionId !== selectedSessionId.value) return
    showToast('error', '任务恢复未完成', error instanceof Error ? error.message : '请查询当前状态')
    // 请求超时不等于后端没执行；查询状态，而不是自动重发原工具请求。
    try {
      const result = await getAgentRun(runId)
      if (ownerToken === currentUser.value?.token && sessionId === selectedSessionId.value) acceptRunResult(result)
    } catch { /* 保留当前卡片，允许手动刷新。 */ }
  } finally {
    if (ownerToken === currentUser.value?.token) approvalBusy.value = false
  }
}

// 只查询状态；RUNNING 时不自动重跑可能已经执行过的工具。
async function refreshRun() {
  if (!activeRun.value || approvalBusy.value) return
  const ownerToken = currentUser.value?.token
  const sessionId = selectedSessionId.value
  approvalBusy.value = true
  runRefreshVersion++
  loadingRun.value = false
  try {
    const result = await getAgentRun(activeRun.value.runId)
    if (ownerToken === currentUser.value?.token && sessionId === selectedSessionId.value) acceptRunResult(result)
  }
  catch (error) { showToast('error', '刷新失败', error instanceof Error ? error.message : '请稍后重试') }
  finally { if (ownerToken === currentUser.value?.token) approvalBusy.value = false }
}

function handleComposerKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return
  event.preventDefault()
  void sendMessage()
}

// 跨标签页退出或换号时，也不能继续展示上一个账号的消息。
watch(() => currentUser.value?.token, () => {
  runRefreshVersion++
  messages.value = []
  activeRun.value = null
  sending.value = false
  approvalBusy.value = false
  loadingRun.value = false
  draft.value = ''
  if (currentUser.value?.token && selectedSessionId.value) loadConversation(selectedSessionId.value)
})

// 通知只安排查询，不自动提交决定或恢复任务。
watch([approvalRevision, sending, approvalBusy], () => {
  clearTimeout(runRefreshTimer)
  if (currentUser.value?.token && !sending.value && !approvalBusy.value && selectedSessionId.value) {
    runRefreshTimer = setTimeout(() => void restorePendingRun(selectedSessionId.value), 120)
  }
})

// 离开页面后取消排队的刷新，正在返回的旧响应也会失效。
onBeforeUnmount(() => {
  runRefreshVersion++
  clearTimeout(runRefreshTimer)
})

watch(
  [() => route.query.session, activeSessions],
  ([querySession]) => {
    const requestedId = typeof querySession === 'string' ? querySession : ''
    const nextId = activeSessions.value.some((item) => item.id === requestedId)
      ? requestedId
      : activeSessions.value[0]?.id ?? ''
    if (nextId !== selectedSessionId.value) {
      runRefreshVersion++
      loadingRun.value = false
      selectedSessionId.value = nextId
      messages.value = []
      activeRun.value = null
      if (nextId) {
        loadConversation(nextId)
        void restorePendingRun(nextId)
      }
      void scrollToLatest()
    }
  },
  { immediate: true },
)

onMounted(() => composer.value?.focus())
</script>

<template>
  <div class="agent-chat-view">
    <section class="agent-chat-heading">
      <div>
        <span class="section-kicker"><Sparkles :size="13" /> 智能学习</span>
        <h2>AI 助教</h2>
        <p>围绕当前学习会话提问，让助教结合你的学习目标继续讲解。</p>
      </div>
      <RouterLink class="button button-secondary" to="/sessions"><BookOpenText :size="16" /> 管理学习会话</RouterLink>
    </section>

    <div v-if="activeSessions.length" class="agent-chat-layout">
      <aside class="agent-session-panel" aria-label="进行中的学习会话">
        <header>
          <span class="section-kicker">进行中的会话</span>
          <strong>{{ activeSessions.length }}</strong>
        </header>
        <div class="agent-session-select-wrap">
          <label for="agent-session-select">当前学习会话</label>
          <select id="agent-session-select" :value="selectedSessionId" :disabled="sending" @change="selectSession(($event.target as HTMLSelectElement).value)">
            <option v-for="session in activeSessions" :key="session.id" :value="session.id">{{ session.title }}</option>
          </select>
        </div>
        <nav class="agent-session-list">
          <button
            v-for="session in activeSessions"
            :key="session.id"
            type="button"
            :class="{ active: session.id === selectedSessionId }"
            :disabled="sending"
            @click="selectSession(session.id)"
          >
            <span><MessageSquareText :size="17" /></span>
            <span>
              <strong>{{ session.title }}</strong>
              <small>{{ session.course || '学习会话' }}</small>
            </span>
            <ArrowRight :size="15" />
          </button>
        </nav>
      </aside>

      <section class="agent-conversation-panel">
        <header class="agent-conversation-header">
          <span class="agent-avatar"><Bot :size="21" /></span>
          <div>
            <strong>{{ selectedSession?.title }}</strong>
            <small><i /> AI 助教已就绪</small>
          </div>
          <label class="agent-mode-select" for="agent-mode-select">
            新请求模式
            <select id="agent-mode-select" v-model="agentMode" :disabled="sending || approvalBusy || loadingRun || Boolean(activeRun)">
              <option value="CHAT">问答模式</option>
              <option value="FOCUS">专注模式</option>
            </select>
            <small>{{ activeRun ? '审批恢复保持原任务模式' : agentMode === 'FOCUS' ? '先规划，再分步完成一个目标' : '即时答疑，按需使用工具' }}</small>
          </label>
        </header>

        <div ref="messageList" class="agent-message-list" aria-live="polite">
          <div v-if="!messages.length" class="agent-welcome">
            <span><Sparkles :size="23" /></span>
            <h3>从一个具体问题开始</h3>
            <p>我会围绕“{{ selectedSession?.title }}”与你讨论。你可以描述不理解的概念、要求举例，或检查自己的理解。</p>
            <div class="agent-prompt-suggestions">
              <button type="button" @click="draft = '请帮我梳理这个学习目标涉及的核心知识点。'; composer?.focus()">梳理核心知识点</button>
              <button type="button" @click="draft = '请用一个简单的例子帮我理解当前学习内容。'; composer?.focus()">用例子解释</button>
              <button type="button" @click="draft = '请出一道题检查我是否真正理解了。'; composer?.focus()">检查我的理解</button>
            </div>
          </div>

          <article v-for="message in messages" :key="message.id" class="agent-message" :class="`is-${message.role}`">
            <span class="agent-message-avatar"><UserRound v-if="message.role === 'user'" :size="16" /><Bot v-else :size="16" /></span>
            <div>
              <strong>{{ message.role === 'user' ? '我' : 'AI 助教' }}</strong>
              <p>{{ message.content }}</p>
              <time>{{ new Date(message.createdAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}</time>
            </div>
          </article>

          <article v-if="sending" class="agent-message is-assistant is-loading" aria-label="AI 助教正在思考">
            <span class="agent-message-avatar"><Bot :size="16" /></span>
            <div><strong>AI 助教</strong><p><i /><i /><i /></p></div>
          </article>
        </div>

        <section v-if="activeRun" class="agent-approval-panel" aria-label="工具审批">
          <strong>{{ activeRun.answer }}</strong>
          <p>审批仅表示允许执行，工具的实际结果会在继续任务后显示。</p>
          <article v-for="approval in activeRun.approvals" :key="approval.batchNumber + ':' + approval.toolCallId">
            <strong>{{ approval.toolName }} · {{ approval.status }}</strong>
            <p>{{ approval.reason }}</p>
            <pre>{{ approval.arguments }}</pre>
            <div v-if="approval.status === 'PENDING'">
              <button :disabled="approvalBusy" @click="decideTool(approval, true)">同意</button>
              <button :disabled="approvalBusy" @click="decideTool(approval, false)">拒绝</button>
            </div>
          </article>
          <button v-if="activeRun.status === 'APPROVAL_RESOLVED'" :disabled="approvalBusy" @click="continueRun">继续执行原任务</button>
          <button :disabled="approvalBusy" @click="refreshRun">刷新状态</button>
          <span v-if="approvalBusy">正在处理，请勿重复提交…</span>
        </section>

        <form class="agent-composer" @submit.prevent="sendMessage">
          <div>
            <textarea
              ref="composer"
              v-model="draft"
              rows="3"
              :maxlength="MAX_MESSAGE_LENGTH + 1"
              :disabled="sending"
              placeholder="输入你想讨论的问题…"
              aria-label="发送给 AI 助教的消息"
              @keydown="handleComposerKeydown"
            />
            <span :class="{ warning: remainingCharacters < 200 }">{{ remainingCharacters }} 字</span>
          </div>
          <button class="agent-send-button" :disabled="!canSend" aria-label="发送消息">
            <Send :size="18" />
          </button>
        </form>
      </section>
    </div>

    <section v-else class="agent-no-session panel">
      <span><MessageSquareText :size="27" /></span>
      <h3>先开始一次学习会话</h3>
      <p>AI 助教需要依托进行中的学习会话理解你的目标和保存上下文。</p>
      <RouterLink class="button button-primary" to="/sessions?create=1">开始学习 <ArrowRight :size="16" /></RouterLink>
    </section>
  </div>
</template>

<style scoped>
.agent-mode-select { margin-left: auto; display: flex; flex-direction: column; gap: .25rem; font-size: .8rem; }
.agent-mode-select select { padding: .35rem .6rem; border: 1px solid #cbd5e1; border-radius: 6px; background: white; }
.agent-mode-select small { color: #64748b; }
.agent-approval-panel { margin: 1rem; padding: 1rem; border: 1px solid #cbd5e1; border-radius: 12px; }
.agent-approval-panel article { margin-block: 1rem; padding-block: .75rem; border-top: 1px solid #e2e8f0; }
.agent-approval-panel pre { max-height: 12rem; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; }
.agent-approval-panel button { margin: .4rem .6rem .4rem 0; padding: .4rem .8rem; border: 1px solid #94a3b8; border-radius: 6px; }
</style>
