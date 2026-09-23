<script setup lang="ts">
import type { CalendarPeriod } from '../businessCalendars'
const props = defineProps<{ periods: CalendarPeriod[]; label: string; readonly?: boolean }>()
const emit = defineEmits<{ change: [periods: CalendarPeriod[]] }>()
function set(index: number, key: 'start' | 'end', event: Event) {
  emit('change', props.periods.map((period, i) => i === index ? { ...period, [key]: (event.target as HTMLInputElement).value } : { ...period }))
}
</script>
<template>
  <div class="calendar-hours">
    <span v-if="!periods.length" class="rest">休息</span>
    <div v-for="(period,index) in periods" :key="index" class="period">
      <template v-if="readonly"><span>{{ period.start }} — {{ period.end }}</span></template>
      <template v-else><input :value="period.start" :aria-label="`${label}时段${index + 1}开始`" placeholder="09:00" inputmode="numeric" maxlength="5" @input="set(index, 'start', $event)" /><span>—</span><input :value="period.end" :aria-label="`${label}时段${index + 1}结束`" placeholder="18:00" inputmode="numeric" maxlength="5" @input="set(index, 'end', $event)" /><button type="button" class="quiet remove-period" :aria-label="`删除${label}时段${index + 1}`" @click="emit('change', periods.filter((_,i) => i !== index))">×</button></template>
    </div>
    <button v-if="!readonly" type="button" class="quiet add-period" :disabled="periods.length >= 8" @click="emit('change', [...periods, { start: '', end: '' }])">＋ 时段</button>
  </div>
</template>
<style scoped>
.calendar-hours{display:flex;flex-wrap:wrap;gap:9px;align-items:center;min-height:35px}.period{display:flex;align-items:center;gap:7px;font-size:12px}.period input{width:66px;border:1px solid var(--line);border-radius:5px;padding:8px 6px;font:inherit;text-align:center;background:#fff;color:var(--ink)}.period>span,.rest{color:var(--muted);font-size:12px}.rest{padding-right:16px}.add-period{font-size:11px;color:var(--deep)}.remove-period{font-size:19px;color:var(--muted);padding:2px 4px}
</style>
