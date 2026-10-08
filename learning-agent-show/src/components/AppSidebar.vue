<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { BookOpenText, Bot, ChevronDown, ChevronLeft, FolderOpen, GraduationCap, LayoutDashboard, Lightbulb, ListTree, LogOut, MessageSquareText, Plus, ScrollText, Target, X } from 'lucide-vue-next'
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
const openGroup = ref<'courses' | 'assistant' | null>(null)
const chaptersExpanded = ref(false)

const isCourseArea = computed(() => ['/courses', '/chapters', '/knowledge-points'].some((path) => route.path.startsWith(path)))
const isAssistantArea = computed(() => route.path.startsWith('/ai-assistant') || route.path.startsWith('/sessions') || route.path.startsWith('/learning-plans'))

function toggleGroup(group: 'courses' | 'assistant') {
  openGroup.value = openGroup.value === group ? null : group
}

function toggleChapters() {
  chaptersExpanded.value = !chaptersExpanded.value
}

function closeNavigation() {
  emit('close')
}

watch(
  () => route.path,
  () => {
    if (isCourseArea.value) {
      openGroup.value = 'courses'
      chaptersExpanded.value = route.path.startsWith('/chapters') || route.path.startsWith('/knowledge-points')
    } else if (isAssistantArea.value) {
      openGroup.value = 'assistant'
      chaptersExpanded.value = false
    } else {
      openGroup.value = null
      chaptersExpanded.value = false
    }
  },
  { immediate: true },
)

function signOut() {
  logout()
  router.push({ name: 'login' })
}

function openCreateCourse() {
  closeNavigation()
  router.push({ name: 'courses', query: { create: '1' } })
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

      <div class="sidebar-nav-group" :class="{ 'is-open': openGroup === 'courses', 'is-active': isCourseArea }">
        <RouterLink class="sidebar-nav-parent" to="/courses" @click="toggleGroup('courses'); closeNavigation">
          <BookOpenText :size="19" />
          <span v-if="!collapsed">课程管理</span>
          <ChevronDown v-if="!collapsed" class="sidebar-nav-chevron" :class="{ 'is-flipped': openGroup === 'courses' }" :size="15" />
        </RouterLink>
        <div v-if="!collapsed && openGroup === 'courses'" class="sidebar-subnav">
          <div class="sidebar-nav-group sidebar-nav-nested" :class="{ 'is-open': chaptersExpanded, 'is-active': route.path.startsWith('/chapters') || route.path.startsWith('/knowledge-points') }">
            <RouterLink
              class="sidebar-nav-parent"
              :class="{ 'is-module-active': route.path.startsWith('/chapters') || route.path.startsWith('/knowledge-points') }"
              :to="{ path: '/chapters', query: { from: 'sidebar' } }"
              @click="toggleChapters(); closeNavigation"
            >
              <ListTree :size="17" />
              <span>章节编排</span>
              <ChevronDown class="sidebar-nav-chevron" :class="{ 'is-flipped': chaptersExpanded }" :size="14" />
            </RouterLink>
            <div v-if="chaptersExpanded" class="sidebar-subnav">
              <RouterLink
                :class="{ 'is-module-active': route.path.startsWith('/knowledge-points') }"
                :to="{ path: '/knowledge-points', query: { from: 'sidebar' } }"
                @click="closeNavigation"
              >
                <Lightbulb :size="16" />
                <span>知识点管理</span>
              </RouterLink>
            </div>
          </div>
        </div>
      </div>

      <div class="sidebar-nav-group" :class="{ 'is-open': openGroup === 'assistant', 'is-active': isAssistantArea }">
        <RouterLink class="sidebar-nav-parent" to="/ai-assistant" @click="toggleGroup('assistant'); closeNavigation">
          <Bot :size="19" />
          <span v-if="!collapsed">AI 助教</span>
          <ChevronDown v-if="!collapsed" class="sidebar-nav-chevron" :class="{ 'is-flipped': openGroup === 'assistant' }" :size="15" />
        </RouterLink>
        <div v-if="!collapsed && openGroup === 'assistant'" class="sidebar-subnav">
          <RouterLink :to="{ name: 'agent-chat' }" @click="closeNavigation"><BookOpenText :size="17" /><span>课程学习</span></RouterLink>
          <RouterLink :to="{ name: 'agent-chat-standalone' }" @click="closeNavigation"><Bot :size="17" /><span>独立问答</span></RouterLink>
          <RouterLink :to="{ name: 'agent-focus' }" @click="closeNavigation"><Target :size="17" /><span>专注学习</span></RouterLink>
          <RouterLink :to="{ path: '/sessions', query: { from: 'sidebar' } }" @click="closeNavigation">
            <MessageSquareText :size="17" />
            <span>学习会话</span>
          </RouterLink>
          <RouterLink :to="{ path: '/learning-plans', query: { from: 'sidebar' } }" @click="closeNavigation">
            <ScrollText :size="17" />
            <span>学习计划草案</span>
          </RouterLink>
        </div>
      </div>
    </nav>

    <div class="sidebar-spacer" />
    <div class="sidebar-create-zone" :class="{ 'is-collapsed': collapsed }">
      <template v-if="!collapsed">
        <span class="sidebar-zone-emoji sidebar-zone-emoji-lightbulb" aria-hidden="true">💡</span>
        <span class="sidebar-zone-emoji sidebar-zone-emoji-help" aria-hidden="true">❓️</span>
        <span class="sidebar-zone-spark sidebar-zone-spark-one" aria-hidden="true" />
        <span class="sidebar-zone-spark sidebar-zone-spark-two" aria-hidden="true" />
        <div class="sidebar-visual" aria-hidden="true">
          <img src="/course-mascot.png" alt="" />
          <span class="sidebar-visual-ring sidebar-visual-ring-one" />
          <span class="sidebar-visual-ring sidebar-visual-ring-two" />
          <span class="sidebar-visual-dot sidebar-visual-dot-one" />
          <span class="sidebar-visual-dot sidebar-visual-dot-two" />
        </div>
        <div class="sidebar-create-copy">
          <strong>需要帮助？</strong>
          <span>从第一步开始创建您的专属课程！</span>
        </div>
      </template>
      <button class="sidebar-create-link" type="button" title="创建课程" @click="openCreateCourse"><Plus :size="17" /><span v-if="!collapsed">创建课程</span></button>
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
