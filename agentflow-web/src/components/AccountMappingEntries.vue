<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { mappingRoles, type MappingDefinition, type MappingEntry } from '../accountMappings'

const props = defineProps<{ definition: MappingDefinition; readonly?: boolean; disabled?: boolean; categories?: { code: string; name: string; active: boolean }[] }>()
const page = ref(0), pageSize = 50
const pages = computed(() => Math.max(1, Math.ceil(props.definition.entries.length / pageSize)))
const visible = computed(() => props.definition.entries.slice(page.value * pageSize, (page.value + 1) * pageSize))
watch(pages, value => { page.value = Math.min(page.value, value - 1) })
watch(() => props.definition, () => { page.value = 0 })
function add() {
  if (props.readonly || props.disabled || props.definition.entries.length >= 4096) return
  props.definition.entries.push({ key: { role: 'EXPENSE', selector: '' }, accountCode: '' }); page.value = pages.value - 1
}
function remove(index: number) { if (!props.readonly && !props.disabled) props.definition.entries.splice(page.value * pageSize + index, 1) }
function changeRole(entry: MappingEntry) { entry.key.selector = '' }
const categoryLabel = (code: string) => props.categories?.find(value => value.code === code)?.name
</script>
<template>
  <section class="mapping-entries" aria-label="用途与科目对应表">
    <div class="entries-heading"><h3>用途与科目 <small>{{ definition.entries.length }} 项</small></h3><button v-if="!readonly" type="button" class="secondary" :disabled="disabled || definition.entries.length >= 4096" @click="add">添加科目</button></div>
    <p v-if="!definition.entries.length" class="entries-help">尚未配置科目。草稿可以暂存，发布前至少需要一项。</p>
    <p v-if="!readonly" class="entries-help">费用用途按类别匹配；付款银行按扣款账户引用匹配。科目代码由企业会计系统校验。</p>
    <datalist v-if="!readonly" id="mapping-category-options"><option v-for="item in categories?.filter(value => value.active)" :key="item.code" :value="item.code">{{ item.name }}</option></datalist>
    <ol :start="page * pageSize + 1">
      <li v-for="(entry, index) in visible" :key="page * pageSize + index">
        <template v-if="readonly"><span><strong>{{ mappingRoles[entry.key.role] }}</strong><small>{{ entry.key.selector ? (categoryLabel(entry.key.selector) ? categoryLabel(entry.key.selector) + ' · ' : '') + entry.key.selector : '本范围通用' }}</small></span><span class="entry-arrow" aria-hidden="true">→</span><code>{{ entry.accountCode }}</code></template>
        <template v-else>
          <label>用途<select v-model="entry.key.role" :disabled="disabled" @change="changeRole(entry)"><option v-for="(label, role) in mappingRoles" :key="role" :value="role">{{ label }}</option></select></label>
          <label v-if="entry.key.role === 'EXPENSE' || entry.key.role === 'BANK'">{{ entry.key.role === 'EXPENSE' ? '费用类别代码' : '扣款账户引用' }}<input v-model.trim="entry.key.selector" :list="entry.key.role === 'EXPENSE' ? 'mapping-category-options' : undefined" :disabled="disabled" maxlength="128" required autocomplete="off" /></label>
          <p v-else class="entry-scope">本法人、币种内通用</p>
          <label>ERP 科目代码<input v-model.trim="entry.accountCode" :disabled="disabled" maxlength="128" required autocomplete="off" /></label>
          <button type="button" class="quiet" :disabled="disabled" :aria-label="'移除第 ' + (page * pageSize + index + 1) + ' 项科目'" @click="remove(index)">移除</button>
        </template>
      </li>
    </ol>
    <nav v-if="pages > 1" class="entry-pages" aria-label="科目明细分页"><button type="button" class="secondary" :disabled="page === 0" @click="page--">上一页</button><span>第 {{ page + 1 }} / {{ pages }} 页 · 每页 {{ pageSize }} 项</span><button type="button" class="secondary" :disabled="page + 1 >= pages" @click="page++">下一页</button></nav>
  </section>
</template>
<style scoped>
.entries-heading,.entry-pages{display:flex;align-items:center;justify-content:space-between;gap:12px}.entries-heading h3{font-size:15px;margin:12px 0}.entries-heading small{font-weight:400;color:var(--muted);margin-left:8px}.entries-help,.entry-scope{font-size:12px;line-height:1.65;color:var(--muted)}ol{padding:0;list-style:none;margin:8px 0}li{display:flex;align-items:end;gap:14px;padding:14px 0;border-top:1px solid var(--line)}li>label{flex:1;min-width:0;font-size:12px;display:grid;gap:6px}li>span:first-child{flex:1}li small{display:block;color:var(--muted);margin-top:4px;overflow-wrap:anywhere}input,select{width:100%;min-width:0}code{font-size:13px;overflow-wrap:anywhere;max-width:45%;color:var(--teal-deep,#087a76)}.entry-arrow{color:var(--muted);align-self:center}.entry-scope{flex:1;align-self:center}.entry-pages{font-size:12px;justify-content:center;margin-top:16px}button:focus-visible,input:focus-visible,select:focus-visible{outline:2px solid #087a76;outline-offset:3px}@media(max-width:650px){li{flex-wrap:wrap}li>label{flex:1 1 100%}.entry-scope{flex-basis:100%}.entry-pages{flex-wrap:wrap}}

input,select{font:inherit;color:var(--ink);background:#fff;border:1px solid #cfdbd9;border-radius:7px;padding:10px;min-height:40px;box-sizing:border-box}
</style>
