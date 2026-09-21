<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ArrowRight, Bot, BookOpenText, MessageSquareText, Send, Sparkles, UserRound } from 'lucide-vue-next'
import { chatWithAgent } from '@/api/agent'
import { ApiError } from '@/api/client'
import { useActivity } from '@/composables/useActivity'
import { useAuth } from '@/composables/useAuth'
import { useToast } from '@/composables/useToast'

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

const selectedSessionId = ref('')
const draft = ref('')
const messages = ref<ChatMessage[]>([])
const sending = ref(false)
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
const canSend = computed(() => Boolean(selectedSession.value && draft.value.trim() && !sending.value && remainingCharacters.value >= 0))

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
  if (sending.value || sessionId === selectedSessionId.value) return
  await router.replace({ name: 'agent-chat', query: { session: sessionId } })
}

async function sendMessage() {
  const session = selectedSession.value
  const userMessage = draft.value.trim()
  if (!session || !userMessage || sending.value) return
  if (userMessage.length > MAX_MESSAGE_LENGTH) {
    showToast('error', '内容过长', `每次最多输入 ${MAX_MESSAGE_LENGTH} 个字符`)
    return
  }

  const pendingMessage = createMessage('user', userMessage)
  messages.value.push(pendingMessage)
  persistConversation()
  draft.value = ''
  sending.value = true
  await scrollToLatest()

  try {
    const answer = await chatWithAgent({ sessionId: session.id, userMessage })
    messages.value.push(createMessage('assistant', answer || '本次没有生成回复，请稍后再试。'))
    persistConversation()
    await scrollToLatest()
  } catch (error) {
    messages.value = messages.value.filter((message) => message.id !== pendingMessage.id)
    persistConversation()
    draft.value = userMessage
    showToast('error', '消息发送失败', error instanceof ApiError ? error.message : 'AI 助教暂时无法回复，请稍后重试')
  } finally {
    sending.value = false
    await nextTick()
    composer.value?.focus()
  }
}

function handleComposerKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return
  event.preventDefault()
  void sendMessage()
}

watch(
  [() => route.query.session, activeSessions],
  ([querySession]) => {
    const requestedId = typeof querySession === 'string' ? querySession : ''
    const nextId = activeSessions.value.some((item) => item.id === requestedId)
      ? requestedId
      : activeSessions.value[0]?.id ?? ''
    if (nextId !== selectedSessionId.value) {
      selectedSessionId.value = nextId
      messages.value = []
      if (nextId) loadConversation(nextId)
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
