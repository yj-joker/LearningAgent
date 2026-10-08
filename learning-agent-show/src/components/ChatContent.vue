<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
const props = defineProps<{ content: string }>()
// 助教回答可包含标题、代码和表格；渲染前过滤 HTML，避免回答内容执行脚本。
const html = computed(() => DOMPurify.sanitize(marked.parse(props.content, { async: false, breaks: true }), {
  USE_PROFILES: { html: true }, FORBID_TAGS: ['img', 'style', 'input', 'form', 'button'],
}))
</script>
<template><div class="chat-content" v-html="html" /></template>
<style scoped>
.chat-content { min-width: 0; font-size: 14px; line-height: 1.85; color: #293c36; overflow-wrap: anywhere; }
.chat-content :deep(p) { white-space: normal; width: auto; padding: 0; margin: 0 0 12px; border: 0; background: transparent; font-size: inherit; line-height: inherit; }
.chat-content :deep(h1), .chat-content :deep(h2), .chat-content :deep(h3) { font-size: 16px; margin: 18px 0 8px; line-height: 1.6; }
.chat-content :deep(pre) { max-width: 100%; padding: 14px; overflow-x: auto; background: #f1f5f4; border: 1px solid #dfe7e4; border-radius: 6px; font-size: 12px; white-space: pre; }
.chat-content :deep(code) { font-size: 12px; background: #edf2f0; padding: 2px 4px; border-radius: 3px; }
.chat-content :deep(pre code) { padding: 0; background: transparent; }
.chat-content :deep(ul), .chat-content :deep(ol) { padding-left: 24px; }
.chat-content :deep(blockquote) { margin: 12px 0; padding-left: 14px; border-left: 3px solid #8ac2b3; color: #61756d; }
.chat-content :deep(table) { display: block; max-width: 100%; overflow: auto; border-collapse: collapse; margin: 12px 0; }
.chat-content :deep(th), .chat-content :deep(td) { padding: 8px 12px; border: 1px solid #dce6e1; text-align: left; min-width: 90px; }
.chat-content :deep(a) { color: #287774; text-decoration: underline; }
.chat-content :deep(>:first-child) { margin-top: 0; } .chat-content :deep(>:last-child) { margin-bottom: 0; }
</style>
