<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue'
import { X } from 'lucide-vue-next'

const props = defineProps<{
  open: boolean
  title: string
  description?: string
  width?: 'default' | 'wide'
}>()
const emit = defineEmits<{ close: [] }>()

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') emit('close')
}

watch(() => props.open, (open) => {
  document.body.classList.toggle('modal-open', open)
  if (open) window.addEventListener('keydown', onKeydown)
  else window.removeEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  document.body.classList.remove('modal-open')
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="open" class="modal-layer" role="dialog" aria-modal="true" :aria-label="title">
        <button class="modal-backdrop" aria-label="关闭弹窗" @click="$emit('close')" />
        <section class="modal-card" :class="{ 'modal-wide': width === 'wide' }">
          <header class="modal-header">
            <div>
              <span class="section-kicker">NEW ITEM</span>
              <h2>{{ title }}</h2>
              <p v-if="description">{{ description }}</p>
            </div>
            <button class="icon-button" aria-label="关闭" @click="$emit('close')"><X :size="20" /></button>
          </header>
          <div class="modal-content"><slot /></div>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>
