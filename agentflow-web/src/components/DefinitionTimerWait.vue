<script setup lang="ts">
const props = defineProps<{ modelValue?: string; disabled: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; beforeChange: [] }>()
/** 保留未填写和非法原文，由同一发布校验明确报告，不补默认时长。 */
function change(event: Event) { if (!props.disabled) emit('update:modelValue', (event.target as HTMLInputElement).value) }
</script>

<template>
  <section class="timer-definition" aria-label="定时等待配置">
    <label>等待时长（秒）<input :value="modelValue ?? ''" inputmode="numeric" :disabled="disabled" placeholder="填写 1 至 31536000 的整数" @focus="emit('beforeChange')" @input="change" /></label>
    <p>60 秒为 1 分钟，3600 秒为 1 小时。从实际到达节点时开始等待，到期后继续下一步；服务重启不重新计时。</p>
    <p>等待不会代替人工审批。若后续推进失败，将保留此等待，供管理员核对原因后重试。</p>
  </section>
</template>

<style scoped>
.timer-definition p{font-size:12px;color:var(--muted);line-height:1.7;overflow-wrap:anywhere}.timer-definition label{display:block}.timer-definition input{width:100%;min-width:0;box-sizing:border-box}
</style>
