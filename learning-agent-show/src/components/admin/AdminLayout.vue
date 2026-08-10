<script setup lang="ts">
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { GraduationCap, LogOut, PanelLeftClose, ShieldCheck, UsersRound } from 'lucide-vue-next'
import { ref } from 'vue'
import { useAuth } from '@/composables/useAuth'

const route = useRoute()
const router = useRouter()
const menuOpen = ref(false)
const { currentUser, logout } = useAuth()

function signOut() {
  logout()
  router.replace({ name: 'admin-login' })
}
</script>

<template>
  <div class="admin-layout">
    <button v-if="menuOpen" class="admin-menu-backdrop" aria-label="关闭管理端菜单" @click="menuOpen = false" />
    <aside class="admin-sidebar" :class="{ open: menuOpen }">
      <div class="admin-brand">
        <span><GraduationCap :size="23" /></span>
        <div><strong>Learning Agent</strong><small>ADMIN CONSOLE</small></div>
        <button class="admin-sidebar-close" aria-label="关闭菜单" @click="menuOpen = false"><PanelLeftClose :size="18" /></button>
      </div>

      <div class="admin-nav-label">管理中心</div>
      <nav class="admin-nav" aria-label="管理端导航">
        <RouterLink to="/admin/users" @click="menuOpen = false">
          <UsersRound :size="18" /><span>用户管理</span><i v-if="route.path === '/admin/users'" />
        </RouterLink>
      </nav>

      <div class="admin-sidebar-spacer" />
      <div class="admin-profile">
        <span class="admin-avatar"><ShieldCheck :size="18" /></span>
        <div><strong>{{ currentUser?.username || '管理员' }}</strong><small>系统管理员</small></div>
        <button aria-label="退出管理端" title="退出登录" @click="signOut"><LogOut :size="16" /></button>
      </div>
    </aside>

    <section class="admin-main">
      <header class="admin-mobile-header">
        <button aria-label="打开管理端菜单" @click="menuOpen = true"><UsersRound :size="20" /></button>
        <strong>Learning Agent 管理端</strong>
      </header>
      <main class="admin-content"><slot /></main>
    </section>
  </div>
</template>
