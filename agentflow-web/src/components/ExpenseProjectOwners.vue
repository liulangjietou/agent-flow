<script setup lang="ts">
import type { ExpenseProjectOwners, ProjectResponsibility } from '../expenseProjectApproval'
const props = defineProps<{ source: ExpenseProjectOwners; responsibility?: ProjectResponsibility | null }>()
function effective(owner: string) { const escalation = props.responsibility?.escalation; return escalation?.originalSubject === owner ? escalation.replacementSubject : owner }
</script>

<template>
  <div class="project-owners">
    <p>来源目录 {{ source.catalogVersion }}</p>
    <p v-if="!source.projects.length">本轮未使用项目分摊。</p>
    <div v-else class="project-table"><table><caption>项目分摊负责人</caption><thead><tr><th>项目</th><th>原负责人</th><th v-if="responsibility">本轮审批责任人</th></tr></thead>
      <tbody><tr v-for="project in source.projects" :key="project.code"><td><strong>{{ project.name }}</strong><small>{{ project.code }}</small></td><td>{{ project.ownerSubject }}</td><td v-if="responsibility">{{ effective(project.ownerSubject) }}<small v-if="effective(project.ownerSubject) !== project.ownerSubject">按本次任职上溯直接主管</small></td></tr></tbody>
    </table></div>
    <p v-if="responsibility">本轮必要审批人 {{ responsibility.candidateSubjects.length }} 人：{{ responsibility.candidateSubjects.join('、') }}。同人多项目保留全部映射，合并为一份会签责任。</p>
  </div>
</template>

<style scoped>
.project-owners{font-size:12px;line-height:1.8}.project-owners p{color:var(--muted);overflow-wrap:anywhere}.project-table{overflow:auto}table{width:100%;border-collapse:collapse;text-align:left}caption{text-align:left;font-weight:600;color:var(--ink)}th,td{padding:9px 8px;border-bottom:1px solid var(--line);vertical-align:top;overflow-wrap:anywhere}th{color:var(--muted);font-weight:500}small{display:block;color:var(--muted)}
</style>
