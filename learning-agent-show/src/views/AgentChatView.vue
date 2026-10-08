<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ArrowRight, Bot, BookOpenText, Check, MessageSquareText, RefreshCw, Send, ShieldCheck, Sparkles, UserRound, X } from 'lucide-vue-next'
import ProposalDetails from '@/components/ProposalDetails.vue'
import ChatContent from '@/components/ChatContent.vue'
import LearningProgressCard from '@/components/LearningProgressCard.vue'
import { chatWithAgent, getActiveAgentRun, getAgentRun, decideAgentTool, resumeAgentRun } from '@/api/agent'
import { getSessionLearningPlanBinding, listLearningPlanDrafts, updateSessionLearningPlanBinding } from '@/api/learningPlans'
import type { AgentMode, AgentRunResult, CourseLearningProgress, LearningPlanDraft, LearningSessionVO, SessionGoalProgress, ToolApprovalRequest } from '@/types/api'
import { ApiError } from '@/api/client'
import { createStandaloneSession, getCourseLearningProgress, getFocusProgress, getSessionMessages, listSessions, startCourseLearning } from '@/api/sessions'
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

interface ModeSession {
  id: string
  title: string
  course: string
  createdAt: string
  status: string
}

const props = defineProps<{ mode: AgentMode; standalone: boolean }>()

const MAX_MESSAGE_LENGTH = 10_000

const route = useRoute()
const router = useRouter()
const { currentUser } = useAuth()
const { showToast } = useToast()
const { approvalRevision } = useApprovalNotifications()
let runRefreshVersion = 0
let runRefreshTimer: ReturnType<typeof setTimeout> | undefined

const selectedSessionId = ref('')
const draft = ref('')
const messages = ref<ChatMessage[]>([])
const sending = ref(false)
// 页面路由决定模式，课程、问答和专注不会共用同一个聊天页面状态。
const agentMode = computed(() => props.mode)
const pageTitle = computed(() => ({ COURSE: '课程学习', CHAT: '独立问答', FOCUS: '专注学习' }[props.mode]))
const historyTitle = computed(() => ({ COURSE: '课程学习会话', CHAT: '问答历史会话', FOCUS: '专注历史会话' }[props.mode]))
const sessions = ref<LearningSessionVO[]>([])
const loadingSessions = ref(false)
const historyLoading = ref(false)
const loadError = ref('')
const focusProgress = ref<SessionGoalProgress | null>(null)
const courseProgress = ref<CourseLearningProgress | null>(null)
const progressLoading = ref(false)
const progressError = ref('')
let progressVersion = 0
let pageVersion = 0
let historyVersion = 0
let disposed = false
// 当前专注请求可选择一个 ACTIVE 学习计划；空值表示沿用会话绑定。
const learningPlanDraftRef = ref('')
const learningPlans = ref<LearningPlanDraft[]>([])
const learningPlanBindingVersion = ref(0)
const learningPlanBindingSaving = ref(false)
const bindingLoading = ref(false)
// 审批状态来自后端，等待期间没有挂起中的聊天请求。
const activeRun = ref<AgentRunResult | null>(null)
const approvalBusy = ref(false)
const loadingRun = ref(false)
const messageList = ref<HTMLElement | null>(null)
const composer = ref<HTMLTextAreaElement | null>(null)
const chatView = ref<HTMLElement | null>(null)
const conversationPanel = ref<HTMLElement | null>(null)
const conversationHeight = ref(0)
let layoutObserver: ResizeObserver | undefined
let layoutFrame = 0

// 按聊天框实际起点计算剩余屏幕高度，避免顶部通知和课程工具栏把输入框挤出首屏。
function measureConversationHeight() {
  layoutFrame = 0
  const panel = conversationPanel.value
  if (disposed || !panel) return
  const viewport = window.visualViewport
  const top = panel.getBoundingClientRect().top + window.scrollY
  const viewportBottom = (viewport?.height ?? window.innerHeight) + (viewport?.offsetTop ?? 0)
  const fixedContentHeight = Array.from(panel.children).reduce((height, child) => {
    return child.classList.contains('agent-message-list') ? height : height + child.getBoundingClientRect().height
  }, 0)
  // 很小的屏幕仍保留可读消息空间，此时允许页面滚动。
  const minimum = Math.max(300, fixedContentHeight + 100)
  const nextHeight = Math.round(Math.max(minimum, Math.min(850, viewportBottom - top - 12)))
  if (Math.abs(nextHeight - conversationHeight.value) > 1) conversationHeight.value = nextHeight
}

function scheduleConversationMeasure() {
  if (disposed || layoutFrame) return
  layoutFrame = window.requestAnimationFrame(measureConversationHeight)
}

watch(conversationPanel, panel => {
  if (panel) layoutObserver?.observe(panel)
  scheduleConversationMeasure()
}, { flush: 'post' })

// 每个页面只接收已保存的同模式会话，课程关联不能代替会话模式。
const modeSessions = computed<ModeSession[]>(() => {
  return sessions.value.filter(item => item.mode === props.mode).map(item => ({
      id: String(item.id), title: item.sessionTitle || '未命名会话', course: item.courseName ?? '',
      createdAt: item.createdAt ?? item.createAt ?? '', status: item.sessionStatus ?? 'ACTIVE',
    }))
})

const selectedSession = computed(() => modeSessions.value.find((item) => item.id === selectedSessionId.value) ?? null)
const remainingCharacters = computed(() => MAX_MESSAGE_LENGTH - draft.value.length)
const canSend = computed(() => Boolean((selectedSession.value?.status === 'ACTIVE' || (props.standalone && !selectedSessionId.value)) && draft.value.trim() && !loadError.value && !sending.value && !approvalBusy.value && !loadingSessions.value && !historyLoading.value && !loadingRun.value && !bindingLoading.value && !learningPlanBindingSaving.value && !activeRun.value && remainingCharacters.value >= 0))
const runStatusText = computed(() => activeRun.value ? ({ WAITING_APPROVAL: '等待审批', APPROVAL_RESOLVED: '等待继续执行', RUNNING: '任务正在执行', COMPLETED: '任务已完成', FAILED: '任务失败' }[activeRun.value.status]) : sending.value ? '正在思考' : loadingRun.value ? '同步任务中' : '可以开始学习')
const toolNames: Record<string, string> = { create_memory: '新增记忆', update_memory: '修改记忆', delete_memory: '删除记忆', create_learning_plan_draft: '创建学习计划草案', update_learning_plan_draft: '修改学习计划', activate_learning_plan_draft: '确认学习计划生效', create_session_goal: '创建学习目标', update_task_plan: '更新任务计划', update_task_progress: '更新任务进度', propose_learning_progress: '记录学习进度', propose_course_learning_progress: '记录课程进度', switch_session_goal: '切换学习目标' }

// 独立会话按模式展示；课程旧会话保留已有问答历史，新的请求仍按 COURSE 执行。
async function loadConversation(sessionId: string) {
  const version = ++historyVersion
  const token = currentUser.value?.token
  historyLoading.value = true
  try {
    const result = await getSessionMessages(sessionId, props.standalone ? props.mode : undefined)
    if (version !== historyVersion || sessionId !== selectedSessionId.value || token !== currentUser.value?.token) return
    messages.value = result.filter(item => item.content && (item.role === 'USER' || item.role === 'ASSISTANT')).map(item => ({
      id: 'history-' + item.id, role: item.role === 'USER' ? 'user' : 'assistant',
      content: item.content ?? '', createdAt: item.createdAt,
    }))
    loadError.value = ''
    // 辅助进度单独读取，失败或慢响应不能挡住已加载的历史和待审批任务。
    if (props.mode !== 'CHAT') void loadSavedProgress(sessionId)
    if (disposed || version !== historyVersion || sessionId !== selectedSessionId.value || token !== currentUser.value?.token) return
    if (selectedSession.value?.status === 'ACTIVE') await restorePendingRun(sessionId)
    await scrollToLatest()
  } catch (error) {
    if (version === historyVersion && token === currentUser.value?.token) loadError.value = error instanceof Error ? error.message : '历史消息读取失败'
  } finally {
    if (version === historyVersion) historyLoading.value = false
  }
}

// 查看历史仅加载已保存快照；GET 失败也不能通过初始化接口补造进度。
async function loadSavedProgress(sessionId: string) {
  if (props.mode === 'CHAT' || !sessionId) return
  const version = ++progressVersion
  const token = currentUser.value?.token
  progressLoading.value = true
  progressError.value = ''
  try {
    if (props.mode === 'COURSE') {
      const result = await getCourseLearningProgress(sessionId)
      if (disposed || version !== progressVersion || sessionId !== selectedSessionId.value || token !== currentUser.value?.token) return
      if (String(result.sessionId) !== sessionId) {
        progressError.value = '返回的课程进度与当前会话不一致，请重试'
        return
      }
      courseProgress.value = result
    } else {
      const result = await getFocusProgress(sessionId)
      if (disposed || version !== progressVersion || sessionId !== selectedSessionId.value || token !== currentUser.value?.token) return
      focusProgress.value = result
    }
  } catch (error) {
    if (disposed || version !== progressVersion || sessionId !== selectedSessionId.value || token !== currentUser.value?.token) return
    // 课程查询的明确未初始化响应是正常空态，其他权限或服务错误仍保留失败提示。
    if (props.mode === 'COURSE' && error instanceof ApiError
        && error.message === '尚未开始课程学习，请先初始化课程进度') {
      courseProgress.value = null
      return
    }
    progressError.value = error instanceof ApiError ? error.message : '已保存的学习进度暂时无法读取'
  } finally {
    if (version === progressVersion && token === currentUser.value?.token) progressLoading.value = false
  }
}

// 新会话、账号切换和模式切换都清除旧进度，旧请求不能再次填回卡片。
function clearProgress() {
  progressVersion++
  focusProgress.value = null
  courseProgress.value = null
  progressLoading.value = false
  progressError.value = ''
}

function updateProgress(result: AgentRunResult) {
  // 等待、执行中和失败结果可能携带暂停时的旧快照，只让最终结果临时更新卡片。
  if (result.status !== 'COMPLETED') return
  if (props.mode === 'FOCUS' && result.progress) {
    progressVersion++
    progressLoading.value = false
    focusProgress.value = result.progress
    progressError.value = ''
  } else if (props.mode === 'COURSE' && result.courseProgress
      && String(result.courseProgress.sessionId) === selectedSessionId.value) {
    progressVersion++
    progressLoading.value = false
    courseProgress.value = result.courseProgress
    progressError.value = ''
  }
}

// 通知查询当前数据库进度，任务返回的更新会使更早的进度查询失效。
async function refreshSessionState(sessionId: string) {
  if (!sessionId) return
  await Promise.all([
    loadSavedProgress(sessionId),
    selectedSession.value?.status === 'ACTIVE' ? restorePendingRun(sessionId) : Promise.resolve(),
  ])
}

// 服务器列表也是会话归属依据；读取失败时不使用旧账号或本地记录替代。
async function loadSessionList() {
  const version = ++pageVersion
  const token = currentUser.value?.token
  loadingSessions.value = true
  try {
    const result = await listSessions()
    if (version === pageVersion && token === currentUser.value?.token) {
      sessions.value = result
      loadError.value = ''
    }
  } catch (error) {
    if (version === pageVersion && token === currentUser.value?.token) loadError.value = error instanceof Error ? error.message : '会话加载失败'
  } finally {
    if (version === pageVersion) loadingSessions.value = false
  }
}

async function retryLoad() {
  await loadSessionList()
  if (selectedSessionId.value) await loadConversation(selectedSessionId.value)
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
  if (!sessionId && props.standalone) {
    await newConversation()
    return
  }
  // 移动选择器和历史侧栏使用同一列表，禁止把其他模式的 ID 带入页面。
  if (!modeSessions.value.some(item => item.id === sessionId)) return
  await router.replace({ name: route.name as string, query: { session: sessionId } })
}

function sessionStatusLabel(status: string) {
  const labels: Record<string, string> = { ACTIVE: '进行中', COMPLETED: '已完成', CANCELED: '已取消' }
  return labels[status] ?? '未知状态'
}

function sessionSubtitle(session: ModeSession) {
  const date = new Date(session.createdAt)
  const dateLabel = session.createdAt && !Number.isNaN(date.getTime())
    ? date.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' }) : ''
  return [props.mode === 'COURSE' ? session.course || '课程学习' : dateLabel, sessionStatusLabel(session.status)].filter(Boolean).join(' · ')
}

// 新会话只清除页面选择，首条消息才创建数据库记录；旧对话仍在会话列表。
async function newConversation() {
  if (sending.value || approvalBusy.value) return
  historyVersion++
  runRefreshVersion++
  selectedSessionId.value = ''
  messages.value = []
  activeRun.value = null
  clearProgress()
  draft.value = ''
  historyLoading.value = false
  loadingRun.value = false
  learningPlanDraftRef.value = ''
  bindingLoading.value = false
  await router.replace({ name: route.name as string })
}

async function switchPage(mode: 'CHAT' | 'FOCUS') {
  if (sending.value || approvalBusy.value || mode === props.mode) return
  // 切换不携带 session 参数，切回来仍是一份新对话。
  await router.push({ name: mode === 'CHAT' ? 'agent-chat-standalone' : 'agent-focus' })
}

// 会话切换后读取当前绑定，避免把上一会话的计划误显示到新会话。
async function loadLearningPlanBinding(sessionId: string) {
  bindingLoading.value = true
  learningPlanDraftRef.value = ''
  learningPlanBindingVersion.value = 0
  try {
    const binding = await getSessionLearningPlanBinding(sessionId)
    if (sessionId === selectedSessionId.value) {
      learningPlanDraftRef.value = binding.draftRef ?? ''
      learningPlanBindingVersion.value = binding.bindingVersion
    }
  } catch {
    if (sessionId === selectedSessionId.value) learningPlanDraftRef.value = ''
  } finally {
    if (sessionId === selectedSessionId.value) bindingLoading.value = false
  }
}

// 页面选择立即保存为会话绑定，空选项表示解除关联。
async function saveLearningPlanBinding() {
  const sessionId = selectedSessionId.value
  if (!sessionId || learningPlanBindingSaving.value) return
  learningPlanBindingSaving.value = true
  try {
    const binding = await updateSessionLearningPlanBinding(
      sessionId, learningPlanDraftRef.value || null, learningPlanBindingVersion.value,
    )
    if (sessionId !== selectedSessionId.value) return
    learningPlanDraftRef.value = binding.draftRef ?? ''
    learningPlanBindingVersion.value = binding.bindingVersion
    showToast('success', '学习计划关联已保存', binding.title ?? '当前会话不关联长期计划')
  } catch (error) {
    showToast('error', '保存学习计划关联失败', error instanceof Error ? error.message : '请刷新后重试')
    await loadLearningPlanBinding(sessionId)
  } finally {
    learningPlanBindingSaving.value = false
  }
}

async function sendMessage() {
  let session = selectedSession.value
  const ownerToken = currentUser.value?.token
  const userMessage = draft.value.trim()
  if (!userMessage || !canSend.value) return
  if (userMessage.length > MAX_MESSAGE_LENGTH) {
    showToast('error', '内容过长', `每次最多输入 ${MAX_MESSAGE_LENGTH} 个字符`)
    return
  }

  sending.value = true
  // 正在发送时作废旧查询，不能让旧审批状态覆盖这次响应。
  runRefreshVersion++
  progressVersion++
  progressLoading.value = false
  let pendingMessage: ChatMessage | undefined
  try {
    // 问答/专注的空页面首次发送时创建新会话，而不是绑定一门虚构的课程。
    if (!session && props.standalone) {
      const created = await createStandaloneSession(userMessage.slice(0, 100), props.mode as 'CHAT' | 'FOCUS')
      if (disposed || ownerToken !== currentUser.value?.token) return
      await router.replace({ name: route.name as string, query: { session: String(created.id) } })
      sessions.value = [created, ...sessions.value]
      selectedSessionId.value = String(created.id)
      session = selectedSession.value
    }
    if (!session) return
    // 课程快照初始化幂等；课程页的发送是用户明确开始本次课程学习的动作。
    if (props.mode === 'COURSE') {
      const initialized = await startCourseLearning(session.id)
      if (disposed || ownerToken !== currentUser.value?.token || selectedSessionId.value !== session.id) return
      progressVersion++
      progressLoading.value = false
      if (String(initialized.sessionId) === session.id) courseProgress.value = initialized
      progressError.value = ''
    }
    if (disposed || ownerToken !== currentUser.value?.token || selectedSessionId.value !== session.id) return
    pendingMessage = createMessage('user', userMessage)
    messages.value.push(pendingMessage)
    draft.value = ''
    await scrollToLatest()
    const result = await chatWithAgent({
      sessionId: session.id,
      userMessage,
      mode: agentMode.value,
    })
    if (disposed || ownerToken !== currentUser.value?.token || selectedSessionId.value !== session.id) return
    // 后端直接返回暂停状态；展示原工具参数，不把它误报为执行成功。
    acceptRunResult(result)
    await scrollToLatest()
  } catch (error) {
    if (disposed || ownerToken !== currentUser.value?.token || (session && selectedSessionId.value !== session.id)) return
    if (pendingMessage) messages.value = messages.value.filter((message) => message.id !== pendingMessage!.id)
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
  updateProgress(result)
  // 审批快照用于恢复原任务；页面进度另外查询当前数据库，避免覆盖后续已保存的结果。
  if (selectedSessionId.value) void loadSavedProgress(selectedSessionId.value)
  activeRun.value = result.status === 'COMPLETED' || result.status === 'FAILED' ? null : result
  const id = result.status === 'COMPLETED' ? 'run-' + result.runId + '-completed' : 'run-' + result.runId + '-' + result.batchNumber
  const message = { ...createMessage('assistant', result.answer), id }
  const index = messages.value.findIndex(item => item.id === id)
  if (index < 0) messages.value.push(message)
  else messages.value[index] = message
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
  progressVersion++
  progressLoading.value = false
  loadingRun.value = false
  try {
    const result = await decideAgentTool(request.runId, request.batchNumber, request.toolCallId, approved)
    if (ownerToken === currentUser.value?.token && sessionId === selectedSessionId.value) {
      updateProgress(result)
      activeRun.value = result
      void loadSavedProgress(sessionId)
    }
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
  progressVersion++
  progressLoading.value = false
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
  progressVersion++
  progressLoading.value = false
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
  pageVersion++
  historyVersion++
  runRefreshVersion++
  messages.value = []
  activeRun.value = null
  clearProgress()
  sending.value = false
  approvalBusy.value = false
  loadingRun.value = false
  draft.value = ''
  sessions.value = []
  selectedSessionId.value = ''
  historyLoading.value = false
  bindingLoading.value = false
  learningPlanBindingSaving.value = false
  learningPlanDraftRef.value = ''
  learningPlanBindingVersion.value = 0
  loadError.value = ''
  if (currentUser.value?.token) void loadSessionList()
})

// 通知只安排查询，不自动提交决定或恢复任务。
watch([approvalRevision, sending, approvalBusy], () => {
  clearTimeout(runRefreshTimer)
  if (currentUser.value?.token && !sending.value && !approvalBusy.value && !historyLoading.value && selectedSessionId.value) {
    runRefreshTimer = setTimeout(() => void refreshSessionState(selectedSessionId.value), 120)
  }
})

// 离开页面后取消排队的刷新，正在返回的旧响应也会失效。
onBeforeUnmount(() => {
  disposed = true
  pageVersion++
  historyVersion++
  runRefreshVersion++
  progressVersion++
  clearTimeout(runRefreshTimer)
  layoutObserver?.disconnect()
  window.cancelAnimationFrame(layoutFrame)
  window.removeEventListener('resize', scheduleConversationMeasure)
  window.visualViewport?.removeEventListener('resize', scheduleConversationMeasure)
  window.visualViewport?.removeEventListener('scroll', scheduleConversationMeasure)
})

watch(
  [() => route.query.session, modeSessions],
  ([querySession]) => {
    const requestedId = typeof querySession === 'string' ? querySession : ''
    const nextId = modeSessions.value.some((item) => item.id === requestedId)
      ? requestedId
      : props.standalone ? '' : modeSessions.value.find(item => item.status === 'ACTIVE')?.id ?? modeSessions.value[0]?.id ?? ''
    if (nextId !== selectedSessionId.value) {
      runRefreshVersion++
      loadingRun.value = false
      selectedSessionId.value = nextId
      historyVersion++
      historyLoading.value = false
      messages.value = []
      activeRun.value = null
      clearProgress()
      draft.value = ''
      learningPlanDraftRef.value = ''
      bindingLoading.value = false
      if (nextId) {
        // 首次创建时正在发送首条消息，不让空历史的慢响应覆盖正在显示的问题。
        if (!sending.value) void loadConversation(nextId)
        if (props.mode === 'FOCUS' && selectedSession.value?.status === 'ACTIVE') void loadLearningPlanBinding(nextId)
      }
      void scrollToLatest()
    }
  },
  { immediate: true },
)

onMounted(async () => {
  layoutObserver = new ResizeObserver(scheduleConversationMeasure)
  if (chatView.value) layoutObserver.observe(chatView.value)
  if (conversationPanel.value) layoutObserver.observe(conversationPanel.value)
  if (chatView.value?.parentElement?.parentElement) layoutObserver.observe(chatView.value.parentElement.parentElement)
  window.addEventListener('resize', scheduleConversationMeasure)
  window.visualViewport?.addEventListener('resize', scheduleConversationMeasure)
  window.visualViewport?.addEventListener('scroll', scheduleConversationMeasure)
  scheduleConversationMeasure()
  composer.value?.focus()
  void loadSessionList()
  try {
    learningPlans.value = await listLearningPlanDrafts()
  } catch {
    learningPlans.value = []
  }
})
</script>

<template>
  <div ref="chatView" class="agent-chat-view" :style="conversationHeight ? { '--conversation-height': `${conversationHeight}px` } : undefined">
    <section class="agent-chat-heading">
      <div>
        <span class="section-kicker"><Sparkles :size="13" /> 智能学习</span>
        <h2>{{ pageTitle }}</h2>
      </div>
      <div class="agent-heading-actions"><button v-if="standalone" class="button button-primary" type="button" :disabled="sending || approvalBusy" @click="newConversation">新对话</button><RouterLink class="button button-secondary" to="/sessions"><BookOpenText :size="16" /> 全部会话</RouterLink></div>
    </section>

    <p v-if="loadError" class="agent-load-error" role="alert">{{ loadError }} <button class="button button-secondary" type="button" @click="retryLoad">重试</button></p>
    <div v-if="loadingSessions" class="agent-loading">正在读取会话…</div>
    <div v-else-if="modeSessions.length || standalone" class="agent-chat-layout">
      <aside class="agent-session-panel" :aria-label="historyTitle">
        <header>
          <span class="section-kicker">{{ historyTitle }}</span>
          <strong>{{ modeSessions.length }}</strong>
        </header>
        <div class="agent-session-select-wrap">
          <label for="agent-session-select">{{ historyTitle }}</label>
          <select id="agent-session-select" :value="selectedSessionId" :disabled="sending || approvalBusy" @change="selectSession(($event.target as HTMLSelectElement).value)">
            <option v-if="standalone" value="">新对话 · 从这里开始</option>
            <option v-for="session in modeSessions" :key="session.id" :value="session.id">{{ session.title }} · {{ sessionStatusLabel(session.status) }}</option>
          </select>
        </div>
        <nav class="agent-session-list" :aria-label="historyTitle">
          <p v-if="!modeSessions.length" class="agent-history-empty">还没有{{ mode === 'FOCUS' ? '专注' : '问答' }}会话记录。<span>开始对话后，可在这里再次打开。</span></p>
          <button
            v-for="session in modeSessions"
            :key="session.id"
            type="button"
            :class="{ active: session.id === selectedSessionId }"
            :aria-current="session.id === selectedSessionId ? 'true' : undefined"
            :disabled="sending || approvalBusy"
            @click="selectSession(session.id)"
          >
            <span><MessageSquareText :size="17" /></span>
            <span>
              <strong>{{ session.title }}</strong>
              <small>{{ sessionSubtitle(session) }}</small>
            </span>
            <ArrowRight :size="15" />
          </button>
        </nav>
      </aside>

      <section ref="conversationPanel" class="agent-conversation-panel">
        <header class="agent-conversation-header">
          <span class="agent-avatar"><Bot :size="21" /></span>
          <div>
            <strong>{{ selectedSession?.title ?? (mode === 'FOCUS' ? '一个目标，分步完成' : '从一个问题开始') }}</strong>
            <small><i /> {{ selectedSession?.status === 'COMPLETED' ? '已完成 · 查看历史' : standalone && !selectedSessionId ? '新对话 · 输入后开始' : runStatusText }}</small>
          </div>
          <div v-if="standalone" class="agent-mode-switch" role="group" aria-label="学习页面"><button v-for="target in (['CHAT', 'FOCUS'] as const)" :key="target" type="button" :aria-pressed="mode === target" :class="{ active: mode === target }" :disabled="sending || approvalBusy" @click="switchPage(target)">{{ target === 'CHAT' ? '问答' : '专注' }}</button></div>
        </header>
        <div v-if="mode === 'FOCUS' && selectedSessionId && selectedSession?.status === 'ACTIVE'" class="agent-plan-toolbar">
          <label for="learning-plan-select">学习计划</label>
            <select id="learning-plan-select" v-model="learningPlanDraftRef" :disabled="sending || approvalBusy || loadingRun || Boolean(activeRun) || learningPlanBindingSaving || bindingLoading" @change="saveLearningPlanBinding">
              <option value="">不关联学习计划</option>
              <option v-for="plan in learningPlans.filter(item => item.status === 'ACTIVE')" :key="plan.draftRef" :value="plan.draftRef">
                {{ plan.title }} · v{{ plan.version }}
              </option>
            </select>
          <RouterLink :to="{ name: 'learning-plans' }">管理计划 <ArrowRight :size="13" /></RouterLink>
        </div>

        <LearningProgressCard
          v-if="mode !== 'CHAT' && selectedSessionId"
          :key="selectedSessionId"
          :mode="mode"
          :focus="focusProgress"
          :course="courseProgress"
          :loading="progressLoading || sending || approvalBusy"
          :error="progressError"
          :read-only="selectedSession?.status === 'COMPLETED'"
          @retry="loadSavedProgress(selectedSessionId)"
        />
        <div ref="messageList" class="agent-message-list" aria-live="polite">
          <div v-if="historyLoading" class="agent-loading">正在读取历史消息…</div>
          <div v-else-if="!messages.length" class="agent-welcome">
            <span><Sparkles :size="23" /></span>
            <h3>{{ selectedSession?.status === 'COMPLETED' ? '这份会话还没有可展示的消息' : mode === 'FOCUS' ? '把一个目标拆成可完成的步骤' : mode === 'COURSE' ? '围绕课程继续学习' : '从一个具体问题开始' }}</h3>
            <p>{{ selectedSession?.status === 'COMPLETED' ? '会话已完成，仅供查看历史记录。' : mode === 'FOCUS' ? '说清你想完成什么，助教会先规划，再逐步推进。' : mode === 'COURSE' ? `当前课程：${selectedSession?.course ?? ''}` : '可以直接提问，无需选择课程。' }}</p>
            <div v-if="selectedSession?.status !== 'COMPLETED'" class="agent-prompt-suggestions">
              <button type="button" @click="draft = '请帮我梳理这个学习目标涉及的核心知识点。'; composer?.focus()">梳理核心知识点</button>
              <button type="button" @click="draft = '请用一个简单的例子帮我理解当前学习内容。'; composer?.focus()">用例子解释</button>
              <button type="button" @click="draft = '请出一道题检查我是否真正理解了。'; composer?.focus()">检查我的理解</button>
            </div>
          </div>

          <article v-for="message in messages" :key="message.id" class="agent-message" :class="`is-${message.role}`">
            <span class="agent-message-avatar"><UserRound v-if="message.role === 'user'" :size="16" /><Bot v-else :size="16" /></span>
            <div>
              <strong>{{ message.role === 'user' ? '我' : 'AI 助教' }}</strong>
              <ChatContent v-if="message.role === 'assistant'" :content="message.content" />
              <p v-else>{{ message.content }}</p>
              <time>{{ new Date(message.createdAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}</time>
            </div>
          </article>

          <article v-if="sending" class="agent-message is-assistant is-loading" aria-label="AI 助教正在思考">
            <span class="agent-message-avatar"><Bot :size="16" /></span>
            <div><strong>AI 助教</strong><p><i /><i /><i /></p></div>
          </article>
        <section v-if="activeRun" class="agent-approval-panel" aria-label="工具审批">
          <header><ShieldCheck :size="18" /><strong>{{ runStatusText }}</strong><span>{{ activeRun.approvals.filter(item => item.status === 'PENDING').length }} 项待确认</span></header>
          <article v-for="approval in activeRun.approvals" :key="approval.batchNumber + ':' + approval.toolCallId">
            <div class="tool-approval-heading"><strong>{{ toolNames[approval.toolName] ?? approval.toolName }}</strong><span :class="approval.status.toLowerCase()">{{ { PENDING: '待确认', APPROVED: '已同意，待执行', REJECTED: '已拒绝' }[approval.status] }}</span></div>
            <p>{{ approval.reason }}</p>
            <ProposalDetails :value="approval.arguments" />
            <details class="tool-raw"><summary>原始参数</summary><pre>{{ approval.arguments }}</pre></details>
            <p v-if="approval.decisionReason">{{ approval.decisionReason }}</p>
            <div v-if="approval.status === 'PENDING'" class="tool-approval-actions">
              <button class="button button-secondary" :disabled="approvalBusy" @click="decideTool(approval, false)"><X :size="15" /> 拒绝</button>
              <button class="button button-primary" :disabled="approvalBusy" @click="decideTool(approval, true)"><Check :size="15" /> 同意</button>
            </div>
          </article>
          <footer><button class="button button-secondary" :disabled="approvalBusy" @click="refreshRun"><RefreshCw :size="15" /> 刷新状态</button><button v-if="activeRun.status === 'APPROVAL_RESOLVED'" class="button button-primary" :disabled="approvalBusy" @click="continueRun">继续执行 <ArrowRight :size="15" /></button></footer>
          <span v-if="approvalBusy">正在处理，请勿重复提交…</span>
        </section>
        </div>

        <form class="agent-composer" @submit.prevent="sendMessage">
          <div>
            <textarea
              ref="composer"
              v-model="draft"
              rows="3"
              :maxlength="MAX_MESSAGE_LENGTH + 1"
              :disabled="sending || Boolean(activeRun) || selectedSession?.status === 'COMPLETED'"
              :placeholder="selectedSession?.status === 'COMPLETED' ? '已完成的会话仅供查看' : activeRun ? '当前任务等待处理审批' : mode === 'FOCUS' ? '描述本次想完成的目标…' : '输入你想讨论的问题…'"
              aria-label="发送给 AI 助教的消息"
              @keydown="handleComposerKeydown"
            />
            <span :class="{ warning: remainingCharacters < 200 }">{{ remainingCharacters }} 字</span>
          </div>
          <button class="agent-send-button" :disabled="!canSend" aria-label="发送消息" title="发送消息">
            <Send :size="18" />
          </button>
        </form>
      </section>
    </div>

    <section v-else class="agent-no-session panel">
      <span><MessageSquareText :size="27" /></span>
      <h3>先创建一份课程学习会话</h3>
      <p>选择课程后，助教会按课程知识点继续教学。</p>
      <RouterLink class="button button-primary" to="/sessions?create=1">开始学习 <ArrowRight :size="16" /></RouterLink>
    </section>
  </div>
</template>

<style scoped>
.agent-chat-layout { height: var(--conversation-height, min(850px, calc(100dvh - 300px))); min-height: 0; grid-template-columns: 230px minmax(0, 1fr); }
.agent-heading-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.agent-load-error { color: #b34747; font-size: 13px; }
.agent-loading { padding: 24px; color: #71857c; font-size: 13px; }
.agent-conversation-panel { display: flex; flex-direction: column; height: var(--conversation-height, min(850px, calc(100dvh - 300px))); min-height: 0; }
.agent-conversation-header { flex: 0 0 auto; padding: 14px 18px; }
.agent-conversation-header > div:first-of-type { flex: 1; }
.agent-conversation-header strong { white-space: normal; overflow-wrap: anywhere; }
.agent-conversation-header > .agent-mode-switch { display: flex; flex: 0 0 auto; flex-direction: row; padding: 3px; gap: 3px; background: #eff4f2; border-radius: 6px; }
.agent-mode-switch button { padding: 7px 12px; border: 0; border-radius: 4px; background: transparent; color: #71857c; font-size: 12px; cursor: pointer; }
.agent-mode-switch button.active { background: #fff; color: #287774; box-shadow: 0 1px 4px #d8e4df; }
.agent-plan-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; padding: 10px 18px; border-bottom: 1px solid #e2e8e6; font-size: 12px; }
.agent-plan-toolbar select { min-width: 0; flex: 1; width: 100%; max-width: 400px; padding: 7px; border: 1px solid #d7e3de; border-radius: 4px; background: white; }
.agent-plan-toolbar a { display: flex; align-items: center; gap: 3px; color: #287774; }
.agent-message-list { min-height: 0; flex: 1; }
.agent-message > div { max-width: calc(100% - 45px); }
.agent-message.is-assistant > div { flex: 1; }
.agent-message.is-assistant .chat-content { padding: 14px 16px; background: #fff; border: 1px solid #e1e8e4; border-radius: 6px; }
.agent-session-list strong { font-size: 12px; }
.agent-session-list small { font-size: 11px; }
.agent-history-empty { margin: 0; padding: 20px 12px; color: #657b71; font-size: 12px; line-height: 1.8; }
.agent-history-empty span { display: block; margin-top: 6px; color: #87928e; font-size: 11px; }
.agent-composer { flex: 0 0 auto; }
.agent-approval-panel { max-width: 760px; margin: 18px auto; padding: 18px; border: 1px solid #e3d6ad; background: #fffefa; border-radius: 6px; }
.agent-approval-panel > header { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; color: #8c6b24; font-size: 13px; }
.agent-approval-panel > header span { margin-left: auto; font-size: 11px; }
.agent-approval-panel article { margin-block: 16px; padding-block: 14px; border-top: 1px solid #e8e6dd; }
.tool-approval-heading { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 8px; font-size: 13px; }
.tool-approval-heading span { font-size: 11px; color: #946c20; } .tool-approval-heading .approved { color: #287774; } .tool-approval-heading .rejected { color: #b84d4d; }
.agent-approval-panel article > p { color: #76857f; font-size: 12px; line-height: 1.6; }
.tool-raw { margin-top: 12px; color: #7a8885; font-size: 11px; } .tool-raw summary { cursor: pointer; }
.tool-approval-actions, .agent-approval-panel footer { display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; margin-top: 14px; }
.agent-approval-panel pre { max-height: 12rem; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; }
@media (max-width: 1000px) { .agent-chat-layout { grid-template-columns: 1fr; height: auto; min-height: 0; } .agent-session-list, .agent-session-panel > header { display: none; } .agent-session-select-wrap { display: grid; gap: 6px; padding: 12px; } .agent-session-select-wrap select { min-width: 0; width: 100%; padding: 10px; border: 1px solid #dce3df; border-radius: 6px; } .agent-conversation-panel { height: var(--conversation-height, calc(100dvh - 330px)); min-height: 0; } }
@media (max-width: 620px) {
  .agent-chat-heading { flex-direction: row; align-items: center; min-height: 0; padding-bottom: 0; gap: 8px; margin-bottom: 10px; }
  .agent-chat-heading h2 { margin-block: 4px 0; font-size: 21px; }
  .agent-chat-heading .section-kicker { font-size: 10px; }
  .agent-heading-actions { gap: 5px; flex-wrap: nowrap; }
  .agent-heading-actions .button { width: auto; min-height: 34px; padding: 7px 9px; gap: 5px; font-size: 11px; white-space: nowrap; }
  .agent-heading-actions svg { width: 13px; height: 13px; }
  .agent-chat-layout { gap: 8px; }
  .agent-session-select-wrap { padding: 6px 10px; gap: 3px; }
  .agent-session-select-wrap select { min-height: 34px; padding: 6px 8px; font-size: 12px; }
  .agent-conversation-panel { height: var(--conversation-height, calc(100dvh - 330px)); min-height: 0; }
  .agent-conversation-header { min-height: 58px; flex-wrap: wrap; padding: 9px 10px; gap: 7px; }
  .agent-conversation-header .agent-avatar { width: 30px; height: 30px; }
  .agent-conversation-header strong { font-size: 12px; }
  .agent-conversation-header small { font-size: 10px; }
  .agent-mode-switch { margin-left: auto; }
  .agent-mode-switch button { padding: 6px 9px; font-size: 11px; }
  .agent-plan-toolbar { gap: 7px; padding: 7px 10px; font-size: 11px; }
  .agent-plan-toolbar select { flex-basis: calc(100% - 80px); padding: 5px; }
  .agent-message-list { padding: 14px 12px; }
  .agent-composer { gap: 8px; padding: 10px; }
  .agent-composer textarea { min-height: 82px; font-size: 13px; }
  .agent-approval-panel { padding: 12px; }
}
</style>
