<script setup lang="ts">
import { computed } from 'vue'
import { organizationLabels, type OrganizationRecord } from '../organization'
import type { SyncFact } from '../organizationSync'

const props = defineProps<{ value?: OrganizationRecord | SyncFact | null }>()
const labels: Record<string, string> = { id: '本地编号', key: '来源标识', kind: '类别', name: '名称', subject: '稳定主体', displayName: '姓名',
  active: '在用', approvalEligible: '本地审批资格', revision: '修订', legalEntityId: '所属法人', parentDepartmentId: '上级部门', headAppointmentId: '负责人任职',
  personId: '人员', departmentId: '部门', positionId: '岗位', supervisorAppointmentId: '主管任职', legalEntity: '法人来源', parentDepartment: '上级部门来源',
  headAppointment: '负责人来源', person: '人员来源', department: '部门来源', position: '岗位来源', supervisorAppointment: '主管来源' }
const fields = computed(() => Object.entries(props.value ?? {}).filter(([key]) => key in labels))
function display(field: string, value: unknown): string {
  if (value == null) return '未设置'
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (typeof value === 'object' && 'kind' in value && 'externalId' in value) return `${organizationLabels[value.kind as keyof typeof organizationLabels]} · ${value.externalId}`
  // 只翻译类别字段，姓名、主体及其他业务原值必须逐字保留。
  if (field === 'kind' && typeof value === 'string' && value in organizationLabels) return organizationLabels[value as keyof typeof organizationLabels]
  return String(value)
}
</script>

<template>
  <dl v-if="value" class="sync-values"><template v-for="[key, item] in fields" :key="key"><dt>{{ labels[key] }}</dt><dd>{{ display(key, item) }}</dd></template></dl>
  <p v-else class="sync-absent">尚无本地记录</p>
</template>

<style scoped>
.sync-values{display:grid;grid-template-columns:max-content minmax(0,1fr);gap:6px 12px;margin:0;font-size:12px;line-height:1.7}.sync-values dt{color:var(--muted)}.sync-values dd{margin:0;overflow-wrap:anywhere}.sync-absent{color:var(--muted);font-size:12px}
@media(max-width:600px){.sync-values{grid-template-columns:1fr;gap:2px}.sync-values dd{margin-bottom:8px}}
</style>
