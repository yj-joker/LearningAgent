<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import {
  ArrowRight,
  BookMarked,
  BookOpenText,
  Braces,
  Check,
  CircleAlert,
  CircleDashed,
  Clock3,
  MessageSquareText,
  Plus,
  RefreshCw,
  Sparkles,
  TrendingUp,
  Wifi,
  WifiOff,
} from 'lucide-vue-next'
import StatusBadge from '@/components/StatusBadge.vue'
import EmptyState from '@/components/EmptyState.vue'
import { checkBackend, type BackendState } from '@/api/client'
import { useActivity } from '@/composables/useActivity'
import { useAuth } from '@/composables/useAuth'

const { recentActivities, courseCount, activeSessionCount } = useActivity()
const { isAdmin } = useAuth()
const connection = ref<BackendState | 'checking'>('checking')

const completionCount = computed(() => recentActivities.value.filter((item) => item.kind === 'session-completed').length)
const endpointCount = computed(() => isAdmin.value ? 9 : 6)

async function testConnection() {
  connection.value = 'checking'
  connection.value = await checkBackend()
}

function formatTime(value: string) {
  const date = new Date(value)
  const diff = Date.now() - date.getTime()
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  return new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric' }).format(date)
}

onMounted(testConnection)
</script>

<template>
  <div class="dashboard-view">
    <section class="hero-card">
      <div class="hero-copy">
        <span class="hero-kicker"><Sparkles :size="15" /> 保持好奇，持续构建</span>
        <h2>让每一次学习，<br><em>都有清晰的方向。</em></h2>
        <p>创建课程、拆解目标、开启学习会话。今天，向你的目标再靠近一点。</p>
        <div class="hero-actions">
          <RouterLink class="button button-primary" to="/courses?create=1">
            <Plus :size="18" /> 创建课程
          </RouterLink>
          <RouterLink class="button button-ghost-light" to="/sessions?create=1">
            开始学习 <ArrowRight :size="17" />
          </RouterLink>
        </div>
      </div>
      <div class="hero-art" aria-hidden="true">
        <div class="orbit orbit-one" />
        <div class="orbit orbit-two" />
        <div class="hero-book">
          <BookMarked :size="60" :stroke-width="1.4" />
          <span class="spark spark-one">✦</span>
          <span class="spark spark-two">✦</span>
        </div>
        <div class="floating-note note-one"><Check :size="13" /> 明确目标</div>
        <div class="floating-note note-two"><TrendingUp :size="13" /> 持续进步</div>
      </div>
    </section>

    <section class="metrics-grid" aria-label="学习数据">
      <article class="metric-card metric-sage">
        <span class="metric-icon"><BookOpenText :size="21" /></span>
        <div><strong>{{ courseCount }}</strong><span>已创建课程</span></div>
        <RouterLink to="/courses" aria-label="查看课程"><ArrowRight :size="18" /></RouterLink>
      </article>
      <article class="metric-card metric-sun">
        <span class="metric-icon"><CircleDashed :size="21" /></span>
        <div><strong>{{ activeSessionCount }}</strong><span>进行中会话</span></div>
        <RouterLink to="/sessions" aria-label="查看会话"><ArrowRight :size="18" /></RouterLink>
      </article>
      <article class="metric-card metric-lilac">
        <span class="metric-icon"><Check :size="21" /></span>
        <div><strong>{{ completionCount }}</strong><span>近期已完成</span></div>
        <RouterLink to="/sessions" aria-label="查看记录"><ArrowRight :size="18" /></RouterLink>
      </article>
      <article class="metric-card metric-cream">
        <span class="metric-icon"><Braces :size="21" /></span>
        <div><strong>{{ endpointCount }}</strong><span>当前可用接口</span></div>
        <RouterLink to="/api-docs" aria-label="查看接口"><ArrowRight :size="18" /></RouterLink>
      </article>
    </section>

    <div class="dashboard-columns">
      <section class="panel activity-panel">
        <div class="panel-header">
          <div>
            <span class="section-kicker">RECENT ACTIVITY</span>
            <h3>最近动态</h3>
          </div>
          <RouterLink class="text-link" to="/sessions">查看全部 <ArrowRight :size="15" /></RouterLink>
        </div>
        <div v-if="recentActivities.length" class="activity-list">
          <article v-for="item in recentActivities" :key="item.id" class="activity-item">
            <span class="activity-icon" :class="item.kind.includes('course') ? 'is-course' : 'is-session'">
              <BookOpenText v-if="item.kind.includes('course')" :size="18" />
              <MessageSquareText v-else :size="18" />
            </span>
            <div class="activity-copy">
              <strong>{{ item.title }}</strong>
              <p>{{ item.description }}</p>
            </div>
            <div class="activity-meta">
              <StatusBadge :status="item.status" />
              <span><Clock3 :size="13" /> {{ formatTime(item.createdAt) }}</span>
            </div>
          </article>
        </div>
        <EmptyState
          v-else
          title="还没有学习动态"
          description="创建第一门课程后，你的操作记录会出现在这里。"
        />
      </section>

      <aside class="panel connection-panel">
        <div class="panel-header compact">
          <div>
            <span class="section-kicker">BACKEND</span>
            <h3>服务连接</h3>
          </div>
          <button class="icon-button" :disabled="connection === 'checking'" aria-label="重新检测" @click="testConnection">
            <RefreshCw :size="17" :class="{ spin: connection === 'checking' }" />
          </button>
        </div>
        <div class="connection-state" :class="`connection-${connection}`">
          <span>
            <Wifi v-if="connection === 'online'" :size="25" />
            <CircleAlert v-else-if="connection === 'degraded'" :size="25" />
            <WifiOff v-else-if="connection === 'offline'" :size="25" />
            <RefreshCw v-else :size="25" class="spin" />
          </span>
          <div>
            <strong>{{ connection === 'online' ? '后端服务在线' : connection === 'degraded' ? '后端服务异常' : connection === 'offline' ? '未连接到后端' : '正在检测服务' }}</strong>
            <p>{{ connection === 'online' ? '接口文档可访问，可以开始联调。' : connection === 'degraded' ? '8080 端口可访问，但 OpenAPI 返回异常。' : connection === 'offline' ? '请确认 Spring Boot 已在 8080 端口启动。' : '正在访问 OpenAPI 文档…' }}</p>
          </div>
        </div>
        <dl class="connection-details">
          <div><dt>前端端口</dt><dd>5173</dd></div>
          <div><dt>后端端口</dt><dd>8080</dd></div>
          <div><dt>响应状态码</dt><dd>code: "200"</dd></div>
        </dl>
        <RouterLink class="button button-soft button-block" to="/api-docs">查看接口说明 <ArrowRight :size="16" /></RouterLink>
      </aside>
    </div>
  </div>
</template>
