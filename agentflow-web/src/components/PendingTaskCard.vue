<script setup lang="ts">
import type { PendingTaskItem } from '../api'
import TaskDeadlineStatus from './TaskDeadlineStatus.vue'
import SubmissionRiskStatus from './SubmissionRiskStatus.vue'
defineProps<{ item: PendingTaskItem; selected: boolean; locked: boolean }>()
const emit = defineEmits<{ select: [item: PendingTaskItem] }>()
const dateLabel = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
</script>

<template>
  <button type="button" class="pending-row" :data-task-id="item.taskId" :class="{ chosen: selected }" :aria-pressed="selected" :disabled="locked" @click="emit('select', item)">
    <span class="pending-node">{{ item.taskName }}<span>{{ item.delegationState === 'PENDING' ? '待回交' : item.assignee ? '已指派' : '待领取' }}</span></span>
    <strong>{{ item.title }}</strong><span class="pending-number">{{ item.businessNo }}</span>
    <span class="pending-facts"><span>申请人 {{ item.applicant }}</span><span>{{ item.amount == null ? '无可用金额' : '金额 ' + item.amount }}</span></span>
    <span class="pending-process">{{ item.processKey }} · v{{ item.definitionVersion }} · 第 {{ item.roundNo }} 轮</span>
    <span class="pending-organization">{{ item.departmentName ? `本轮组织：${item.legalEntityName} / ${item.departmentName} / ${item.positionName}` : '未记录本轮组织' }}</span>
    <span v-if="item.delegationState === 'PENDING' && item.owner" class="pending-owner">回交给 {{ item.owner }}</span>
    <time :datetime="item.createdAt">{{ dateLabel(item.createdAt) }} 进入待办</time>
    <SubmissionRiskStatus :risk="item.risk" compact />
    <TaskDeadlineStatus :due-at="item.dueAt" />
  </button>
</template>

<style scoped>
.pending-row{display:flex;flex-direction:column;text-align:left;gap:7px;width:100%;padding:20px;border-bottom:1px solid var(--line);border-left:3px solid transparent;background:#fff;overflow-wrap:anywhere}
.pending-row:hover{background:#f7fbfa}.pending-row.chosen{background:var(--soft);border-left-color:var(--teal)}
.pending-row:focus-visible{outline:3px solid #20a18c80;outline-offset:-3px}
.pending-node{display:flex;justify-content:space-between;gap:8px;width:100%;font-size:11px;color:var(--deep)}.pending-node>span{background:var(--paper);padding:2px 7px;border-radius:4px;white-space:nowrap}
.pending-row>strong{font-size:14px;line-height:1.6}.pending-number{font:10px 'DM Mono',monospace;color:var(--muted)}
.pending-facts{display:flex;gap:10px;flex-wrap:wrap;justify-content:space-between;width:100%;font-size:12px;line-height:1.7}
.pending-process,.pending-organization,.pending-row time,.pending-owner{font-size:10px;color:var(--muted);line-height:1.6}.pending-owner{color:var(--purple)}
@media(max-width:650px){.pending-row{padding:18px 14px}.pending-facts{font-size:11px}}
</style>
