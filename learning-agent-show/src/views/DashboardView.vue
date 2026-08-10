<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import {
  ArrowRight,
  BookOpenText,
  CheckCircle2,
  Clock3,
  ListTree,
  MessageSquareText,
  Plus,
  Play,
} from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { useActivity } from '@/composables/useActivity'
import { useAuth } from '@/composables/useAuth'

const { activities, recentActivities, courseCount, activeSessionCount } = useActivity()
const { currentUser } = useAuth()
const completionCount = computed(() => activities.value.filter((item) => item.kind === 'session-completed').length)
const displayName = computed(() => {
  const name = currentUser.value?.username || '学习者'
  return name.length > 18 ? `${name.slice(0, 18)}…` : name
})

function formatTime(value: string) {
  const date = new Date(value)
  const diff = Date.now() - date.getTime()
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  return new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric' }).format(date)
}
</script>

<template>
  <div class="dashboard-view">
    <section class="workspace-summary">
      <div>
        <span class="section-kicker">今日学习</span>
        <h2>{{ displayName }}，欢迎回来</h2>
        <p>从一门课程开始，继续整理你的学习内容。</p>
      </div>
      <div class="workspace-summary-actions">
        <RouterLink class="button button-secondary" to="/chapters"><ListTree :size="17" /> 编排章节</RouterLink>
        <RouterLink class="button button-primary" to="/courses?create=1"><Plus :size="17" /> 创建课程</RouterLink>
      </div>
    </section>

    <section class="metrics-grid" aria-label="学习数据">
      <article class="metric-card">
        <span class="metric-icon metric-icon-course"><BookOpenText :size="20" /></span>
        <div><strong>{{ courseCount }}</strong><span>我的课程</span></div>
        <RouterLink to="/courses" aria-label="查看课程"><ArrowRight :size="17" /></RouterLink>
      </article>
      <article class="metric-card">
        <span class="metric-icon metric-icon-active"><Play :size="20" /></span>
        <div><strong>{{ activeSessionCount }}</strong><span>进行中</span></div>
        <RouterLink to="/sessions" aria-label="查看学习会话"><ArrowRight :size="17" /></RouterLink>
      </article>
      <article class="metric-card">
        <span class="metric-icon metric-icon-complete"><CheckCircle2 :size="20" /></span>
        <div><strong>{{ completionCount }}</strong><span>已完成</span></div>
        <RouterLink to="/sessions" aria-label="查看完成记录"><ArrowRight :size="17" /></RouterLink>
      </article>
    </section>

    <div class="dashboard-workspace-grid">
      <section class="panel activity-panel">
        <div class="panel-header">
          <div><span class="section-kicker">最近动态</span><h3>学习记录</h3></div>
        </div>
        <div v-if="recentActivities.length" class="activity-list">
          <article v-for="item in recentActivities" :key="item.id" class="activity-item">
            <span class="activity-icon" :class="item.kind.includes('course') ? 'is-course' : 'is-session'">
              <BookOpenText v-if="item.kind.includes('course')" :size="18" />
              <MessageSquareText v-else :size="18" />
            </span>
            <div class="activity-copy"><strong>{{ item.title }}</strong><p>{{ item.description }}</p></div>
            <div class="activity-meta"><StatusBadge :status="item.status" /><span><Clock3 :size="13" /> {{ formatTime(item.createdAt) }}</span></div>
          </article>
        </div>
        <EmptyState v-else title="还没有学习记录" description="创建第一门课程后，最近操作会显示在这里。" />
      </section>

      <aside class="panel next-action-panel">
        <span class="section-kicker">快捷入口</span>
        <h3>继续学习</h3>
        <nav aria-label="快捷入口">
          <RouterLink to="/courses"><span><BookOpenText :size="18" /></span><div><strong>我的课程</strong><small>查看课程与审核状态</small></div><ArrowRight :size="16" /></RouterLink>
          <RouterLink to="/chapters"><span><ListTree :size="18" /></span><div><strong>章节编排</strong><small>维护课程章节顺序</small></div><ArrowRight :size="16" /></RouterLink>
          <RouterLink to="/sessions"><span><MessageSquareText :size="18" /></span><div><strong>学习会话</strong><small>开始一次专注学习</small></div><ArrowRight :size="16" /></RouterLink>
        </nav>
      </aside>
    </div>
  </div>
</template>
