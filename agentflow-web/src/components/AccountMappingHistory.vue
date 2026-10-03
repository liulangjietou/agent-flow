<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { mappingError, type MappingScope, type MappingHistory, type MappingDefinition } from '../accountMappings'
import { ConfigurationRead } from '../expenseConfigurationRead'
import AccountMappingEntries from './AccountMappingEntries.vue'

const props = defineProps<{ scopeKey: string; mode: 'versions' | 'activations'; scope: MappingScope; refreshVersion: number; mappingKey?: string; mappingId?: string; draftRevision?: number }>()
const emit = defineEmits<{ unavailable: [status: number] }>()
interface Entry { revision: number; key: string; version: number; title: string; by: string; at: string; comment: string }
interface Detail { title: string; definition: MappingDefinition; by: string; at: string; comment: string; categoryRevision?: number; draftRevision?: number }
const list = reactive(new ConfigurationRead<MappingHistory<Entry>>(mappingError)), detail = reactive(new ConfigurationRead<Detail>(mappingError))
const entries = ref<Entry[]>([]), nextVersion = ref<number | null>(null), draftNumber = ref<number | string>(1)
const title = computed(() => props.mode === 'versions' ? '发布与草稿历史' : '本范围生效记录')
const when = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
function clear() { list.clear(); detail.clear(); entries.value = []; nextVersion.value = null }
function failure(status: number) { if ([401, 403, 404].includes(status)) clear(); if ([401, 403].includes(status)) emit('unavailable', status) }
async function load(more = false) {
  if (!props.scopeKey || list.loading || props.mode === 'versions' && !props.mappingKey) return
  const cursor = more ? nextVersion.value ?? undefined : undefined
  if (!more) { entries.value = []; nextVersion.value = null; detail.clear() }
  const value = await list.load(async signal => {
    if (props.mode === 'versions') {
      const key = props.mappingKey!, result = await api.accountMappingVersions(key, cursor, signal)
      return { ...result, items: result.items.map(v => ({ revision: v.version, key, version: v.version, title: `${v.name} v${v.version} · 草稿 ${v.draftRevision} / 类别 ${v.categoryRevision}`, by: v.publishedBy, at: v.publishedAt, comment: v.comment })) }
    }
    const result = await api.accountMappingActivations(props.scope, cursor, signal)
    return { ...result, items: result.items.map(v => ({ revision: v.revision, key: v.key, version: v.mappingVersion, title: `生效修订 ${v.revision} · ${v.key} v${v.mappingVersion}`, by: v.activatedBy, at: v.activatedAt, comment: v.comment })) }
  })
  if (!value) { failure(list.status); return }
  entries.value = more ? [...entries.value, ...value.items] : value.items; nextVersion.value = value.nextBeforeVersion
}
async function open(entry: Entry) {
  const value = await detail.load(async signal => {
    const v = await api.accountMappingVersion(entry.key, entry.version, signal)
    if (v.definition.legalEntityId !== props.scope.legalEntityId || v.definition.currency !== props.scope.currency) throw { code: 'RESPONSE_UNREADABLE', message: '历史版本与当前查看的法人、币种不符。' }
    return { title: `${v.definition.name} v${v.version}`, definition: v.definition, by: v.publishedBy, at: v.publishedAt, comment: v.comment, categoryRevision: v.categoryRevision, draftRevision: v.draftRevision }
  })
  if (!value) failure(detail.status)
}
async function openDraft() {
  const number = Number(draftNumber.value), key = props.mappingKey, id = props.mappingId
  if (!key || !id || !Number.isSafeInteger(number) || number < 1 || number > (props.draftRevision ?? 0)) return
  const value = await detail.load(async signal => {
    const v = await api.accountMappingDraftVersion(key, id, number, signal)
    return { title: `草稿修订 ${v.revision}`, definition: v.definition, by: v.updatedBy, at: v.updatedAt, comment: v.comment }
  })
  if (!value) failure(detail.status)
}
watch(() => [props.scopeKey, props.mode, props.scope.legalEntityId, props.scope.currency, props.mappingKey, props.mappingId, props.refreshVersion], () => { clear(); draftNumber.value = props.draftRevision ?? 1; void load() }, { immediate: true, flush: 'sync' })
onUnmounted(clear)
</script>
<template>
  <section class="mapping-history" :aria-label="title">
    <div class="history-heading"><h3>{{ title }}</h3><button type="button" class="secondary" :disabled="list.loading" @click="load()">刷新历史</button></div>
    <p v-if="list.loading" role="status">正在读取历史…</p><p v-if="list.error" class="history-error" role="alert">{{ list.error }}</p><p v-if="!list.loading && !list.error && !entries.length">暂无记录。</p>
    <ol><li v-for="entry in entries" :key="entry.revision"><button type="button" :disabled="detail.loading" @click="open(entry)"><strong>{{ entry.title }}</strong><span>{{ entry.by }} · {{ when(entry.at) }}</span><span>{{ entry.comment }}</span><small>查看固定版本 →</small></button></li></ol>
    <button v-if="nextVersion" type="button" class="secondary" :disabled="list.loading" @click="load(true)">更早记录</button>
    <form v-if="mode === 'versions' && mappingId" class="draft-lookup" @submit.prevent="openDraft"><label>草稿修订<input v-model="draftNumber" type="number" min="1" :max="draftRevision" required step="1" /></label><button type="submit" class="secondary" :disabled="detail.loading">查看草稿</button><small>可查 1 至 {{ draftRevision }}</small></form>
    <p v-if="detail.loading" role="status">正在读取版本正文…</p><p v-if="detail.error" class="history-error" role="alert">{{ detail.error }}</p>
    <section v-if="detail.value" class="history-detail" aria-label="历史版本正文"><h4>{{ detail.value.title }}</h4><p>{{ detail.value.by }} · {{ when(detail.value.at) }}</p><p>{{ detail.value.comment }}</p><p>法人 {{ detail.value.definition.legalEntityId }} · {{ detail.value.definition.currency }}</p><p v-if="detail.value.draftRevision !== undefined">来源草稿 {{ detail.value.draftRevision }} · 类别修订 {{ detail.value.categoryRevision }}</p><AccountMappingEntries :definition="detail.value.definition" readonly /></section>
  </section>
</template>
<style scoped>
.mapping-history{padding:18px;border:1px solid var(--line);border-radius:12px;margin:14px 0;font-size:13px}.history-heading,.draft-lookup{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.history-heading{justify-content:space-between}h3,h4{margin:0;font-size:15px}ol{list-style:none;padding:0}li button{display:grid;text-align:left;gap:5px;width:100%;padding:13px;background:var(--paper);border:1px solid var(--line);margin-bottom:8px;border-radius:8px;overflow-wrap:anywhere}li span,small{font-size:12px;color:var(--muted)}.draft-lookup label{display:grid;gap:5px}.draft-lookup input{width:95px}.history-detail{padding:16px;margin-top:14px;background:var(--paper);border-radius:8px;overflow-wrap:anywhere}.history-error{color:var(--red,#c9564d)}button:focus-visible,input:focus-visible{outline:2px solid #087a76;outline-offset:3px}
</style>
