<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { BookOpenText, Bot, FolderOpen, GraduationCap, LayoutDashboard, Lightbulb, ListTree, LogOut, MessageSquareText, Plus, X } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { useAuth } from '@/composables/useAuth'

defineProps<{ open: boolean }>()
defineEmits<{ close: [] }>()

const navItems = [
  { label: '学习概览', to: '/', icon: LayoutDashboard },
  { label: '课程管理', to: '/courses', icon: BookOpenText },
  { label: '章节编排', to: '/chapters', icon: ListTree },
  { label: '知识点管理', to: '/knowledge-points', icon: Lightbulb },
  { label: '知识库', to: '/knowledge-bases', icon: FolderOpen },
  { label: '学习会话', to: '/sessions', icon: MessageSquareText },
  { label: 'AI 助教', to: '/ai-assistant', icon: Bot },
]

const router = useRouter()
const { currentUser, logout } = useAuth()

function signOut() {
  logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <Transition name="fade">
    <button v-if="open" class="sidebar-backdrop" aria-label="关闭导航" @click="$emit('close')" />
  </Transition>
  <aside class="sidebar" :class="{ 'is-open': open }">
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
    </div>

    <div class="sidebar-section-label">工作台</div>
    <nav class="sidebar-nav" aria-label="主导航">
      <RouterLink
        v-for="item in navItems"
        :key="item.to"
        :to="item.to"
        :exact-active-class="item.to === '/' ? 'router-link-active' : undefined"
        @click="$emit('close')"
      >
        <component :is="item.icon" :size="19" />
        <span>{{ item.label }}</span>
      </RouterLink>
    </nav>

    <div class="sidebar-spacer" />
    <RouterLink class="sidebar-create-link" to="/courses?create=1" @click="$emit('close')"><Plus :size="17" /> 创建课程</RouterLink>

    <div class="sidebar-footer">
      <span class="avatar">{{ currentUser?.username?.slice(0, 2).toUpperCase() || 'LA' }}</span>
      <span>
        <strong>{{ currentUser?.username || '学习者' }}</strong>
        <small>专注成长的每一天</small>
      </span>
      <button class="logout-button" title="退出登录" aria-label="退出登录" @click="signOut"><LogOut :size="15" /></button>
    </div>
  </aside>
</template>
