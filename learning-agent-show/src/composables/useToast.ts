import { ref } from 'vue'

export type ToastType = 'success' | 'error' | 'info'

export interface ToastMessage {
  id: number
  type: ToastType
  title: string
  message: string
}

const toasts = ref<ToastMessage[]>([])
let nextId = 1

export function useToast() {
  function showToast(type: ToastType, title: string, message: string) {
    const id = nextId++
    toasts.value.push({ id, type, title, message })
    window.setTimeout(() => dismissToast(id), 4200)
  }

  function dismissToast(id: number) {
    toasts.value = toasts.value.filter((toast) => toast.id !== id)
  }

  return { toasts, showToast, dismissToast }
}
