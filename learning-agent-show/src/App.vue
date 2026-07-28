<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterView } from 'vue-router'
import { useRoute } from 'vue-router'
import AppHeader from '@/components/AppHeader.vue'
import AppSidebar from '@/components/AppSidebar.vue'
import AppToast from '@/components/AppToast.vue'
import AdminLayout from '@/components/admin/AdminLayout.vue'

const sidebarOpen = ref(false)
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
  <div v-else class="app-shell">
    <AppSidebar :open="sidebarOpen" @close="sidebarOpen = false" />
    <div class="app-main">
      <AppHeader @open-menu="sidebarOpen = true" />
      <main class="page-container">
        <RouterView v-slot="{ Component }">
          <Transition name="page" mode="out-in">
            <component :is="Component" />
          </Transition>
        </RouterView>
      </main>
    </div>
    <AppToast />
  </div>
</template>
