<script setup lang="ts">
import { CheckCircle2, CircleAlert, Info, X } from 'lucide-vue-next'
import { useToast } from '@/composables/useToast'

const { toasts, dismissToast } = useToast()
const icons = { success: CheckCircle2, error: CircleAlert, info: Info }
</script>

<template>
  <div class="toast-stack" aria-live="polite">
    <TransitionGroup name="toast">
      <div v-for="toast in toasts" :key="toast.id" class="toast" :class="`toast-${toast.type}`">
        <component :is="icons[toast.type]" :size="20" />
        <div>
          <strong>{{ toast.title }}</strong>
          <p>{{ toast.message }}</p>
        </div>
        <button aria-label="关闭提示" @click="dismissToast(toast.id)"><X :size="16" /></button>
      </div>
    </TransitionGroup>
  </div>
</template>
