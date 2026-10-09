<script setup lang="ts">
import { computed, KeepAlive, ref } from 'vue'
import { RouterView } from 'vue-router'
import { useRoute } from 'vue-router'
import AppHeader from '@/components/AppHeader.vue'
import AppSidebar from '@/components/AppSidebar.vue'
import AppToast from '@/components/AppToast.vue'
import AdminLayout from '@/components/admin/AdminLayout.vue'
import ApprovalInbox from '@/components/ApprovalInbox.vue'
import { startApprovalNotifications } from '@/composables/useApprovalNotifications'

// 全应用复用一条通知连接，离开聊天页仍能收到后台整理申请。
startApprovalNotifications()

const sidebarOpen = ref(false)
const sidebarCollapsed = ref(false)
const route = useRoute()
const isAuthPage = computed(() => route.name === 'login' || route.name === 'register' || route.name === 'admin-login')
const isAdminPage = computed(() => route.path.startsWith('/admin'))
</script>

<template>
  <div v-if="isAuthPage" class="auth-app-shell">
    <RouterView />
    <AppToast />
  </div>
  <div v-else-if="isAdminPage" class="admin-app-shell">
    <AdminLayout>
      <RouterView />
    </AdminLayout>
    <AppToast />
  </div>
  <div v-else class="app-shell" :class="{ 'sidebar-collapsed': sidebarCollapsed }">
    <AppSidebar
      :open="sidebarOpen"
      :collapsed="sidebarCollapsed"
      @close="sidebarOpen = false"
      @toggle-collapse="sidebarCollapsed = !sidebarCollapsed"
    />
    <div class="app-main">
      <AppHeader @open-menu="sidebarOpen = true" />
      <ApprovalInbox />
      <main class="page-container">
        <RouterView v-slot="{ Component }">
          <Transition name="page" mode="out-in">
            <!-- 只缓存三个聊天页面，让离开页面后的请求继续更新原对话。 -->
            <KeepAlive include="AgentChatView" :max="3">
              <component :is="Component" :key="String(route.name)" />
            </KeepAlive>
          </Transition>
        </RouterView>
      </main>
    </div>
    <AppToast />
  </div>
</template>
