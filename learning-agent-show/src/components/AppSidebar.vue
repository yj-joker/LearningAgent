<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { BookOpenText, Bot, ChevronDown, ChevronLeft, FolderOpen, GraduationCap, LayoutDashboard, LogOut, ScrollText, Target, X } from 'lucide-vue-next'
import { useAuth } from '@/composables/useAuth'

defineProps<{ open: boolean; collapsed: boolean }>()
const emit = defineEmits<{ close: []; toggleCollapse: [] }>()

const primaryNavItems = [
  { label: '学习概览', to: '/', icon: LayoutDashboard },
  { label: '知识库', to: '/knowledge-bases', icon: FolderOpen },
]

const route = useRoute()
const router = useRouter()
const { currentUser, logout } = useAuth()
const assistantExpanded = ref(false)

const isCourseArea = computed(() => ['/courses', '/chapters', '/knowledge-points'].some((path) => route.path.startsWith(path)))
const isAssistantArea = computed(() => route.path.startsWith('/ai-assistant'))

function toggleAssistant() {
  assistantExpanded.value = !assistantExpanded.value
}

function closeNavigation() {
  emit('close')
}

watch(
  () => route.path,
  () => {
    assistantExpanded.value = isAssistantArea.value
  },
  { immediate: true },
)

function signOut() {
  logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <Transition name="fade">
    <button v-if="open" class="sidebar-backdrop" aria-label="关闭导航" @click="$emit('close')" />
  </Transition>
  <aside class="sidebar" :class="{ 'is-open': open, 'is-collapsed': collapsed && !open }">
    <div class="brand-row">
      <RouterLink class="brand" to="/" @click="$emit('close')">
        <span class="brand-mark"><GraduationCap :size="25" :stroke-width="2.2" /></span>
        <span>
          <strong>Learning</strong>
          <small>AGENT</small>
        </span>
      </RouterLink>
      <button class="icon-button sidebar-close" aria-label="关闭菜单" @click="$emit('close')">
        <X :size="20" />
      </button>
      <button class="sidebar-collapse" :aria-label="collapsed ? '展开侧栏' : '收起侧栏'" @click="$emit('toggleCollapse')">
        <ChevronLeft :size="16" :class="{ 'is-flipped': collapsed }" />
      </button>
    </div>

    <div v-if="!collapsed" class="sidebar-section-label">工作台</div>
    <nav class="sidebar-nav" aria-label="主导航">
      <RouterLink
        v-for="item in primaryNavItems"
        :key="item.to"
        :to="item.to"
        :exact-active-class="item.to === '/' ? 'router-link-active' : undefined"
        @click="closeNavigation"
      >
        <component :is="item.icon" :size="19" />
        <span v-if="!collapsed">{{ item.label }}</span>
      </RouterLink>

      <RouterLink to="/courses" :class="{ 'router-link-active': isCourseArea }" @click="closeNavigation"><BookOpenText :size="19" /><span v-if="!collapsed">课程管理</span></RouterLink>
      <RouterLink to="/learning-plans" @click="closeNavigation"><ScrollText :size="19" /><span v-if="!collapsed">学习计划</span></RouterLink>

      <div class="sidebar-nav-group" :class="{ 'is-open': assistantExpanded, 'is-active': isAssistantArea }">
        <RouterLink class="sidebar-nav-parent" to="/ai-assistant" @click="toggleAssistant(); closeNavigation()">
          <Bot :size="19" />
          <span v-if="!collapsed">AI 助教</span>
          <ChevronDown v-if="!collapsed" class="sidebar-nav-chevron" :class="{ 'is-flipped': assistantExpanded }" :size="15" />
        </RouterLink>
        <div v-if="!collapsed && assistantExpanded" class="sidebar-subnav">
          <RouterLink :to="{ name: 'agent-chat' }" @click="closeNavigation"><BookOpenText :size="17" /><span>课程学习</span></RouterLink>
          <RouterLink :to="{ name: 'agent-chat-standalone' }" @click="closeNavigation"><Bot :size="17" /><span>独立问答</span></RouterLink>
          <RouterLink :to="{ name: 'agent-focus' }" @click="closeNavigation"><Target :size="17" /><span>专注学习</span></RouterLink>
        </div>
      </div>
    </nav>

    <div class="sidebar-spacer" />
    <div v-if="!collapsed" class="sidebar-mascot" aria-hidden="true">
      <img src="/course-mascot.png" alt="" />
    </div>

    <div class="sidebar-footer">
      <span class="avatar">{{ currentUser?.username?.slice(0, 2).toUpperCase() || 'LA' }}</span>
      <span v-if="!collapsed">
        <strong>{{ currentUser?.username || '学习者' }}</strong>
        <small>专注成长的每一天</small>
      </span>
      <button class="logout-button" title="退出登录" aria-label="退出登录" @click="signOut"><LogOut :size="15" /></button>
    </div>
  </aside>
</template>
