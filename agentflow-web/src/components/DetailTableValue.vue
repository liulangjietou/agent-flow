<script setup lang="ts">
import { computed } from 'vue'
import AttachmentField from './AttachmentField.vue'
import type { AttachmentContext } from '../attachments'
import { displayFields, isDetailRow, rawValueLabel, type FormField } from '../formSchema'

const props = defineProps<{ field: FormField; value: unknown; attachmentContext?: AttachmentContext }>()
const columns = computed(() => (props.field.columns ?? []).filter(column => column.type !== 'TABLE'))
const rows = computed(() => Array.isArray(props.value) ? props.value.map(row => {
  const cells = isDetailRow(row) ? displayFields({ schemaVersion: 1, fields: columns.value }, row) : null
  return { cells: cells?.filter(cell => !cell.extra), extras: cells?.filter(cell => cell.extra), raw: row }
}) : [])
</script>

<template>
  <div v-if="rows.length" class="detail-value">
    <div class="detail-scroll" role="region" :aria-label="`${field.label}数据表`" tabindex="0">
      <table><caption>{{ field.label }} · {{ rows.length }} 行</caption><thead><tr><th scope="col">序号</th><th v-for="column in columns" :key="column.key" scope="col">{{ column.label }}</th></tr></thead>
        <tbody><tr v-for="(row, index) in rows" :key="index"><th scope="row">{{ index + 1 }}</th><template v-if="row.cells"><td v-for="cell in row.cells" :key="cell.key"><AttachmentField v-if="columns.find(column => column.key === cell.key)?.type === 'ATTACHMENT' && isDetailRow(row.raw)" :model-value="row.raw[cell.key]" :field-path="field.key + '.' + cell.key" :context="attachmentContext" readonly /><template v-else>{{ cell.value }}</template></td></template><td v-else :colspan="columns.length">{{ rawValueLabel(row.raw) }}</td></tr></tbody>
      </table>
    </div>
    <template v-for="(row, index) in rows" :key="index"><p v-if="row.extras?.length" class="detail-extra">第 {{ index + 1 }} 行其他已保存字段：{{ row.extras.map(cell => `${cell.label}：${cell.value}`).join('；') }}</p></template>
  </div>
  <span v-else>{{ rawValueLabel(Array.isArray(value) ? null : value) }}</span>
</template>

<style scoped>
.detail-value{max-width:100%;min-width:0}.detail-scroll{overflow:auto;border:1px solid var(--line);border-radius:9px;background:white}.detail-scroll:focus-visible{outline:3px solid rgba(33,173,159,.35);outline-offset:2px}table{border-collapse:collapse;min-width:100%;font-size:12px;text-align:left}caption{text-align:left;padding:11px 12px;color:var(--muted);font-size:11px;background:var(--paper)}th,td{padding:10px 12px;border-top:1px solid var(--line);vertical-align:top;white-space:pre-wrap;min-width:105px;max-width:280px;overflow-wrap:anywhere}thead th{background:var(--soft);color:var(--deep);font-size:11px;white-space:nowrap}tbody th{font-weight:normal;color:var(--muted)}tr>:first-child{min-width:48px}.detail-extra{font-size:11px;color:var(--muted);white-space:pre-wrap;overflow-wrap:anywhere}
</style>
