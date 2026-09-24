<script setup lang="ts">
import type { ConditionGroup, ConditionGroupEdit } from '../conditionGroups'
import type { FormField } from '../formSchema'
import ConditionRuleFields from './ConditionRuleFields.vue'
const props = defineProps<{ group: ConditionGroup; fields: FormField[]; disabled: boolean; path?: string; depth?: number }>()
const emit = defineEmits<{ edit: [edit: ConditionGroupEdit]; invalid: [message: string] }>()
function edit(command: ConditionGroupEdit) { if (!props.disabled) emit('edit', command) }
</script>
<template>
  <fieldset class="condition-group" :class="{ 'condition-group-deep': (depth ?? 0) >= 3 }" :disabled="disabled">
    <legend>{{ path ? `条件组 ${path}` : '根条件组' }}</legend>
    <div class="group-combination"><label>组合方式<select :value="group.join" @change="edit({kind:'join', id:group.id, join:($event.target as HTMLSelectElement).value as 'AND'|'OR'})"><option value="AND">全部满足（且）</option><option value="OR">任一满足（或）</option></select></label><label class="condition-negation"><input type="checkbox" :checked="group.negated" @change="edit({kind:'negate',id:group.id,negated:($event.target as HTMLInputElement).checked})" />不满足本组结果</label></div>
    <p v-if="group.negated" class="group-hint">整组结果取反，可能包含未填写字段的情况。</p>
    <template v-for="(child,index) in group.children" :key="child.id">
      <div v-if="index > 0" class="group-join" aria-hidden="true">{{ group.join === 'AND' ? '且' : '或' }}</div>
      <div v-if="child.kind === 'group'" class="group-child">
        <ConditionGroupFields :group="child" :fields="fields" :disabled="disabled" :path="[path,index+1].filter(Boolean).join('.')" :depth="(depth ?? 0)+1" @edit="edit" @invalid="emit('invalid',$event)" />
        <button type="button" class="condition-remove" :disabled="group.children.length < 2" @click="edit({kind:'remove',id:child.id})">删除条件组 {{ [path,index+1].filter(Boolean).join('.') }}（含组内条件）</button>
      </div>
      <fieldset v-else class="group-rule"><legend>条件 {{ [path,index+1].filter(Boolean).join('.') }}</legend><ConditionRuleFields :row="child.row" :fields="fields" :version="2" :disabled="disabled" @change="edit({kind:'row',id:child.id,row:$event})" @invalid="emit('invalid',$event)" /><label class="condition-negation"><input type="checkbox" :checked="child.negated" @change="edit({kind:'negate',id:child.id,negated:($event.target as HTMLInputElement).checked})" />不满足此条件</label><button type="button" class="condition-remove" :disabled="group.children.length < 2" @click="edit({kind:'remove',id:child.id})">删除条件 {{ [path,index+1].filter(Boolean).join('.') }}</button></fieldset>
    </template>
    <div class="group-add"><button type="button" class="secondary" @click="edit({kind:'addRule',id:group.id})">＋ 添加条件</button><button type="button" class="secondary" @click="edit({kind:'addGroup',id:group.id})">＋ 添加条件组</button></div>
  </fieldset>
</template>
<style scoped>
.condition-group{border:1px solid var(--deep);border-left:3px solid var(--deep);border-radius:9px;padding:12px;margin:10px 0;min-width:0}.condition-group legend{font-size:11px;color:var(--deep);font-weight:600;padding:0 5px;overflow-wrap:anywhere}.group-combination{display:flex;flex-wrap:wrap;gap:8px;align-items:center}.group-combination>label:first-child{flex:1;min-width:135px;font-size:11px}.group-combination select{width:100%;font-size:12px}.condition-negation{display:flex!important;align-items:center;gap:7px;font-size:11px!important;line-height:1.6;margin:8px 0!important}.condition-negation input{width:14px!important;margin:0!important;flex-shrink:0}.group-rule{min-width:0;border:1px solid var(--line);padding:10px;border-radius:7px;margin:10px 0;background:var(--paper)}.group-rule legend{color:var(--muted);font-weight:400}.group-child{min-width:0}.group-join{font-size:10px;font-weight:600;color:var(--deep);text-align:center;line-height:1.6}.condition-remove{border:0;background:transparent;color:var(--red);font-size:10px;padding:4px 0;max-width:100%;text-align:left;overflow-wrap:anywhere}.group-add{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}.group-add button{font-size:11px;flex:1;min-width:95px;padding:8px}.group-hint{font-size:11px;color:var(--muted);line-height:1.8}.condition-group-deep{padding-left:6px;padding-right:6px;border-left-width:1px}@media(max-width:650px){.condition-group{padding:9px}.condition-group-deep{padding:4px}.group-rule{padding:8px}}
</style>
