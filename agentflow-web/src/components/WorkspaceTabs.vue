<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'

/** 两个详情入口共用键盘和语义，数据读取仍由当前面板的业务组件负责。 */
const props = defineProps<{ idBase: string; label: string; modelValue: string; tabs: Array<{ key: string; label: string }>; disabled?: boolean; variant?: 'task' | 'record' }>()
const emit = defineEmits<{ 'update:modelValue': [key: string] }>()
const tablist = ref<HTMLElement | null>(null)
const focused = ref(props.modelValue)
watch(() => [props.idBase, props.modelValue, props.disabled], () => { focused.value = props.modelValue })

/** 面板可能需要网络读取，方向键只移动焦点，原生 Enter/Space 点击才激活。 */
async function navigate(event: KeyboardEvent, index: number) {
  if (props.disabled || event.altKey || event.ctrlKey || event.metaKey || event.isComposing) return
  const scope = props.idBase, count = props.tabs.length
  const next = event.key === 'ArrowRight' ? (index + 1) % count : event.key === 'ArrowLeft' ? (index + count - 1) % count
    : event.key === 'Home' ? 0 : event.key === 'End' ? count - 1 : -1
  if (next < 0) return
  event.preventDefault(); focused.value = props.tabs[next]!.key
  await nextTick()
  if (!props.disabled && props.idBase === scope && focused.value === props.tabs[next]?.key) tablist.value?.querySelectorAll<HTMLButtonElement>('[role="tab"]')[next]?.focus()
}
function activate(key: string) { if (!props.disabled) emit('update:modelValue', key) }
/** 再次 Tab 进入时回到当前激活页签，不能停在上次未激活的候选页签。 */
function leave(event: FocusEvent) {
  if (!tablist.value?.contains(event.relatedTarget as Node | null)) focused.value = props.modelValue
}
</script>

<template>
  <div class="workspace-tabs" :class="variant">
    <div ref="tablist" class="tabs" role="tablist" :aria-label="label" @focusout="leave">
      <button v-for="(tab, index) in tabs" :id="`${idBase}-tab-${tab.key}`" :key="tab.key" type="button" role="tab"
        :aria-selected="modelValue === tab.key" :aria-controls="`${idBase}-panel-${tab.key}`" :class="{ active: modelValue === tab.key }"
        :tabindex="!disabled && focused === tab.key ? 0 : -1" :disabled="disabled" @focus="focused = tab.key"
        @keydown="navigate($event, index)" @click="activate(tab.key)">{{ tab.label }}</button>
    </div>
    <section v-for="tab in tabs" :id="`${idBase}-panel-${tab.key}`" :key="tab.key" role="tabpanel"
      :aria-labelledby="`${idBase}-tab-${tab.key}`" :hidden="modelValue !== tab.key" :tabindex="modelValue === tab.key ? 0 : -1">
      <slot v-if="modelValue === tab.key" />
    </section>
  </div>
</template>

<style scoped>
.tabs { flex-wrap: wrap; }
.record .tabs { gap: 6px; margin-top: 24px; padding: 0 0 9px; }
.record .tabs button { font-size: 12px; padding: 10px 13px; border-radius: 8px; }
.record .tabs button.active { background: var(--soft); color: var(--deep); font-weight: 600; }
.tabs button:focus-visible, [role="tabpanel"]:focus-visible { outline: 3px solid var(--teal); outline-offset: -3px; }
[hidden] { display: none; }
</style>
