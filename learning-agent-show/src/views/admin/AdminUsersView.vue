<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  ChevronLeft,
  ChevronRight,
  CircleAlert,
  FilterX,
  RefreshCw,
  Search,
  ShieldCheck,
  UserRound,
  UsersRound,
} from 'lucide-vue-next'
import { queryUsers } from '@/api/admin'
import { ApiError } from '@/api/client'
import { useToast } from '@/composables/useToast'
import type { PageResult, UserPageItem, UserRole } from '@/types/api'

const { showToast } = useToast()
const loading = ref(false)
const errorMessage = ref('')
const pageSize = ref(20)
const result = ref<PageResult<UserPageItem>>({
  page: 1,
  size: 20,
  totalElements: 0,
  totalPages: 0,
  hasPrevious: false,
  hasNext: false,
  items: [],
})
const filters = reactive({
  username: '',
  role: '' as '' | UserRole,
  createdAtStart: '',
  createdAtEnd: '',
})

const adminCount = computed(() => result.value.items.filter((user) => user.role === 'ADMIN').length)
const normalUserCount = computed(() => result.value.items.filter((user) => user.role === 'USER').length)
const activeFilterCount = computed(() => [filters.username, filters.role, filters.createdAtStart, filters.createdAtEnd].filter(Boolean).length)

function toStartDate(value: string) {
  return value ? `${value}T00:00:00` : undefined
}

function toEndDate(value: string) {
  return value ? `${value}T23:59:59` : undefined
}

async function loadUsers(page = result.value.page || 1) {
  loading.value = true
  errorMessage.value = ''
  try {
    result.value = await queryUsers({
      page,
      size: pageSize.value,
      username: filters.username || undefined,
      role: filters.role || undefined,
      createdAtStart: toStartDate(filters.createdAtStart),
      createdAtEnd: toEndDate(filters.createdAtEnd),
    })
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '获取用户列表失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function searchUsers() {
  if (filters.createdAtStart && filters.createdAtEnd && filters.createdAtStart > filters.createdAtEnd) {
    showToast('error', '日期范围不正确', '注册开始日期不能晚于结束日期')
    return
  }
  loadUsers(1)
}

function resetFilters() {
  filters.username = ''
  filters.role = ''
  filters.createdAtStart = ''
  filters.createdAtEnd = ''
  loadUsers(1)
}

function changePageSize() {
  loadUsers(1)
}

function formatDate(value: string) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value))
}

function initials(username: string) {
  return username.slice(0, 2).toUpperCase()
}

onMounted(() => loadUsers(1))
</script>

<template>
  <div class="admin-users-view">
    <header class="admin-page-heading">
      <div>
        <span class="admin-eyebrow"><ShieldCheck :size="14" /> USER DIRECTORY</span>
        <h1>用户管理</h1>
        <p>查看平台中的全部用户，按用户名、角色和注册时间快速筛选。</p>
      </div>
      <button class="admin-refresh-button" :disabled="loading" @click="loadUsers(result.page)"><RefreshCw :size="17" :class="{ spin: loading }" />刷新数据</button>
    </header>

    <section class="admin-stats-grid">
      <article><span class="stat-icon stat-total"><UsersRound :size="21" /></span><div><small>全部用户</small><strong>{{ result.totalElements }}</strong></div><i>平台用户总数</i></article>
      <article><span class="stat-icon stat-user"><UserRound :size="21" /></span><div><small>本页普通用户</small><strong>{{ normalUserCount }}</strong></div><i>学习者</i></article>
      <article><span class="stat-icon stat-admin"><ShieldCheck :size="21" /></span><div><small>本页管理员</small><strong>{{ adminCount }}</strong></div><i>管理员</i></article>
    </section>

    <section class="admin-filter-card">
      <form class="admin-filter-form" @submit.prevent="searchUsers">
        <div class="admin-search-field">
          <label for="admin-search-username">用户名</label>
          <div><Search :size="17" /><input id="admin-search-username" v-model="filters.username" maxlength="64" placeholder="输入完整或部分用户名"></div>
        </div>
        <div class="admin-filter-field">
          <label for="admin-search-role">用户角色</label>
          <select id="admin-search-role" v-model="filters.role"><option value="">全部角色</option><option value="USER">普通用户</option><option value="ADMIN">管理员</option></select>
        </div>
        <div class="admin-filter-field">
          <label for="admin-created-start">注册开始日期</label>
          <input id="admin-created-start" v-model="filters.createdAtStart" type="date">
        </div>
        <div class="admin-filter-field">
          <label for="admin-created-end">注册结束日期</label>
          <input id="admin-created-end" v-model="filters.createdAtEnd" type="date" :min="filters.createdAtStart || undefined">
        </div>
        <button class="admin-search-button"><Search :size="16" />查询用户</button>
        <button v-if="activeFilterCount" type="button" class="admin-reset-button" @click="resetFilters"><FilterX :size="15" />清除 {{ activeFilterCount }} 项</button>
      </form>
    </section>

    <section class="admin-table-card">
      <div class="admin-table-header">
        <div><h2>用户列表</h2><p>共 {{ result.totalElements }} 位用户，当前第 {{ result.page }} 页</p></div>
        <label>每页<select v-model.number="pageSize" @change="changePageSize"><option :value="10">10</option><option :value="20">20</option><option :value="50">50</option><option :value="100">100</option></select>条</label>
      </div>

      <div v-if="errorMessage" class="admin-table-error"><CircleAlert :size="22" /><div><strong>暂时无法获取用户</strong><p>{{ errorMessage }}</p></div><button @click="loadUsers(result.page)">重新加载</button></div>

      <div v-else class="admin-table-wrap">
        <table class="admin-user-table">
          <thead><tr><th>用户</th><th>角色</th><th>注册时间</th><th>最近更新</th></tr></thead>
          <tbody v-if="loading">
            <tr v-for="index in 5" :key="index" class="admin-skeleton-row"><td><i /><span /></td><td><span /></td><td><span /></td><td><span /></td></tr>
          </tbody>
          <tbody v-else-if="result.items.length">
            <tr v-for="user in result.items" :key="user.id">
              <td><div class="admin-user-cell"><span v-if="!user.avatarUrl" class="admin-user-avatar">{{ initials(user.username) }}</span><img v-else :src="user.avatarUrl" :alt="`${user.username} 的头像`"><div><strong>{{ user.username }}</strong><small>{{ user.role === 'ADMIN' ? '管理员账号' : '普通学习者' }}</small></div></div></td>
              <td><span class="admin-role-badge" :class="user.role === 'ADMIN' ? 'role-admin' : 'role-user'"><i />{{ user.role === 'ADMIN' ? '管理员' : '普通用户' }}</span></td>
              <td><time>{{ formatDate(user.createdAt) }}</time></td>
              <td><time>{{ formatDate(user.updatedAt) }}</time></td>
            </tr>
          </tbody>
        </table>
        <div v-if="!loading && !result.items.length" class="admin-empty-users"><span><UsersRound :size="27" /></span><strong>没有找到符合条件的用户</strong><p>尝试清除筛选条件或换一个用户名关键词。</p><button v-if="activeFilterCount" @click="resetFilters">清除筛选条件</button></div>
      </div>

      <footer v-if="!errorMessage && result.totalPages > 0" class="admin-pagination">
        <span>第 {{ result.page }} / {{ result.totalPages }} 页</span>
        <div><button :disabled="!result.hasPrevious || loading" aria-label="上一页" @click="loadUsers(result.page - 1)"><ChevronLeft :size="17" /></button><button :disabled="!result.hasNext || loading" aria-label="下一页" @click="loadUsers(result.page + 1)"><ChevronRight :size="17" /></button></div>
      </footer>
    </section>
  </div>
</template>
