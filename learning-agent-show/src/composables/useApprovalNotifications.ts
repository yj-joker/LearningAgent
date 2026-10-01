import { onBeforeUnmount, readonly, ref, watch } from 'vue'
import { request } from '@/api/client'
import { useAuth } from '@/composables/useAuth'
import { useToast } from '@/composables/useToast'

// App 只建立一条连接，各页面共享刷新信号，避免每个审批组件各连一次。
const revision = ref(0)
const connectionState = ref<'offline' | 'connecting' | 'connected'>('offline')

// 页面只订阅信号，不负责建立连接，也不接收敏感审批正文。
export function useApprovalNotifications() {
  return { approvalRevision: readonly(revision), connectionState: readonly(connectionState) }
}

// 由 App 调用一次；退出登录、切换账号和卸载时关闭旧连接。
export function startApprovalNotifications() {
  const { currentUser } = useAuth()
  const { showToast } = useToast()
  let socket: WebSocket | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | undefined
  let heartbeatTimer: ReturnType<typeof setInterval> | undefined
  let handshakeTimer: ReturnType<typeof setTimeout> | undefined
  let generation = 0
  let attempts = 0
  let lastReceivedAt = 0
  let lastNoticeAt = 0

  // 回收定时器和连接；旧回调不能替新账号安排重连。
  function disconnect() {
    clearTimeout(reconnectTimer)
    clearTimeout(handshakeTimer)
    clearInterval(heartbeatTimer)
    if (socket) {
      socket.onclose = null
      socket.close()
      socket = null
    }
    connectionState.value = 'offline'
  }

  // 重连逐步延迟，最长 30 秒，并加少量随机抖动避免同时重连。
  function reconnect(version: number) {
    if (version !== generation || !currentUser.value?.token) return
    disconnect()
    const delay = Math.min(1000 * 2 ** Math.min(attempts++, 5), 30000) + Math.random() * 500
    reconnectTimer = setTimeout(() => void connect(version), delay)
  }

  // 先走带 JWT 的 HTTP 领票，再用一次性短期票据完成 WebSocket 握手。
  async function connect(version: number) {
    if (version !== generation || !currentUser.value?.token) return
    connectionState.value = 'connecting'
    try {
      const result = await request<{ ticket: string }>('/agent/approvals/socket-ticket', {
        method: 'POST', signal: AbortSignal.timeout(10000),
      })
      if (version !== generation) return
      const base = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')
      const url = new URL(base + '/agent/approvals/socket', window.location.href)
      url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
      url.searchParams.set('ticket', result.ticket)
      const connection = new WebSocket(url)
      socket = connection
      // 连接或 READY 超时就重新领票；避免网络黑洞让界面永远卡在连接中。
      handshakeTimer = setTimeout(() => {
        if (version === generation && socket === connection) reconnect(version)
      }, 10000)
      connection.onmessage = event => {
        if (version !== generation || socket !== connection) return
        try {
          const message = JSON.parse(String(event.data)) as { type?: string }
          lastReceivedAt = Date.now()
          if (message.type === 'READY') {
            clearTimeout(handshakeTimer)
            attempts = 0
            connectionState.value = 'connected'
            // 首次连接与每次重连都补查，断网期间丢失的通知不影响最终状态。
            revision.value++
            clearInterval(heartbeatTimer)
            heartbeatTimer = setInterval(() => {
              if (Date.now() - lastReceivedAt > 60000) reconnect(version)
              else if (connection.readyState === WebSocket.OPEN) connection.send('ping')
            }, 25000)
          } else if (message.type === 'APPROVALS_CHANGED') {
            revision.value++
            // 用户不在聊天页时也能看到提醒；连续变更不刷满提示条。
            if (Date.now() - lastNoticeAt > 1500) {
              lastNoticeAt = Date.now()
              showToast('info', '审批状态有更新', '可查看工具审批或展开记忆审批栏，确认具体内容。')
            }
          }
        } catch {
          // 不显示未经解析的推送内容，也不把异常载荷打印到控制台。
          console.warn('审批通知格式异常，等待重新连接或手动刷新')
        }
      }
      connection.onclose = () => {
        if (socket === connection) reconnect(version)
      }
      connection.onerror = () => connection.close()
    } catch {
      // 日志不包含 JWT、一次性票据或完整 WebSocket 地址。
      if (version === generation) {
        console.warn('审批通知暂未连接，将自动重试；仍可使用 HTTP 审批')
        reconnect(version)
      }
    }
  }

  // 页面回到前台时补查，后台休眠可能导致浏览器暂停心跳。
  function onVisible() {
    if (document.visibilityState === 'visible' && currentUser.value?.token) {
      revision.value++
      if (connectionState.value === 'offline') {
        clearTimeout(reconnectTimer)
        void connect(generation)
      }
    }
  }

  const stop = watch(() => currentUser.value?.token, () => {
    generation++
    disconnect()
    attempts = 0
    revision.value++
    if (currentUser.value?.token) void connect(generation)
  }, { immediate: true })
  document.addEventListener('visibilitychange', onVisible)
  onBeforeUnmount(() => {
    generation++
    stop()
    disconnect()
    document.removeEventListener('visibilitychange', onVisible)
  })
}
