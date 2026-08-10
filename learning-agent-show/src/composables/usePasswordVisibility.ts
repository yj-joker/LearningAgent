import { nextTick, ref } from 'vue'

export function usePasswordVisibility() {
  const passwordVisible = ref(false)

  async function togglePasswordVisibility(event: MouseEvent) {
    const button = event.currentTarget as HTMLElement | null
    const input = button?.parentElement?.querySelector<HTMLInputElement>('input') ?? null
    const selectionStart = input?.selectionStart ?? null
    const selectionEnd = input?.selectionEnd ?? null
    passwordVisible.value = !passwordVisible.value
    await nextTick()
    const nextInput = button?.parentElement?.querySelector<HTMLInputElement>('input') ?? null
    nextInput?.focus()
    if (selectionStart !== null && selectionEnd !== null) {
      nextInput?.setSelectionRange(selectionStart, selectionEnd)
    }
  }

  return { passwordVisible, togglePasswordVisibility }
}
