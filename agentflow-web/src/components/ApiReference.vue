<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { ApiReferenceQuery, entries, filterEntries, curlExample, schemaName, resolveSchema, type ApiSchema } from '../apiReference'
const props = defineProps<{ scopeKey: string; refreshVersion: number }>()
const query = reactive(new ApiReferenceQuery(api.openApi))
const search = ref(''), group = ref(''), selectedId = ref(''), selectedSchema = ref('')
const downloadUrl = ref('')
const modelPanel = ref<HTMLElement | null>(null)
async function openSchema(name: string) {
  selectedSchema.value = name
  await nextTick()
  modelPanel.value?.scrollIntoView({ block: 'start' })
  modelPanel.value?.focus({ preventScroll: true })
}
const catalog = computed(() => query.document ? entries(query.document) : [])
const filtered = computed(() => filterEntries(catalog.value, search.value, group.value))
const selected = computed(() => filtered.value.find(item => item.operation.operationId === selectedId.value) ?? filtered.value[0])
const requestType = computed(() => Object.keys(selected.value?.operation.requestBody?.content ?? {})[0])
const request = computed(() => requestType.value ? selected.value?.operation.requestBody?.content[requestType.value] : undefined)
const schema = computed(() => query.document?.components.schemas[selectedSchema.value])
function fields(input?: ApiSchema) { return input && query.document ? resolveSchema(query.document, input) : null }
function releaseDownload() { if (downloadUrl.value) URL.revokeObjectURL(downloadUrl.value); downloadUrl.value = '' }
watch(() => query.document, document => {
  releaseDownload()
  if (document) downloadUrl.value = URL.createObjectURL(new Blob([JSON.stringify(document, null, 2)], { type: 'application/json' }))
})
watch(() => [props.scopeKey, props.refreshVersion], () => { selectedSchema.value = ''; void query.load(props.scopeKey) }, { immediate: true })
onBeforeUnmount(() => { query.clear(); releaseDownload() })
</script>

<template>
  <section class="content api-reference">
    <div class="page-heading"><div><p class="eyebrow">INTEGRATION / API</p><h2>接口文档</h2><p>按当前部署的实际契约接入审批流程。</p></div><a v-if="downloadUrl" class="secondary api-download" :href="downloadUrl" download="agentflow-openapi.json">下载 OpenAPI JSON ↓</a></div>
    <div v-if="query.loading" class="panel api-empty" role="status">正在加载当前版本接口文档…</div>
    <div v-else-if="query.error" class="panel api-empty" role="alert"><p>{{ query.error }}</p><button class="secondary" @click="query.load(scopeKey)">重试加载</button></div>
    <template v-else-if="query.document">
      <div class="api-intro"><strong>OpenAPI {{ query.document.openapi }} · v{{ query.document.info.version }}</strong><p>{{ query.document.info.description }}</p><p>下方 curl 示例使用演示 Bearer 令牌；企业模式通过浏览器会话登录，并按契约携带 CSRF 和页面身份请求头。网络结果不明时，业务写入使用原幂等键和完全相同的请求重试；版本冲突需重新核对。</p></div>
      <div class="api-filters"><label>搜索接口<input v-model="search" type="search" placeholder="路径、方法或用途，例如 publish" /></label><label>接口分组<select v-model="group"><option value="">全部分组</option><option v-for="tag in query.document.tags" :key="tag.name">{{ tag.name }}</option></select></label><span>{{ filtered.length }} / {{ catalog.length }} 个操作</span></div>
      <div v-if="!filtered.length" class="panel api-empty"><strong>没有匹配的接口</strong><p>调整关键词或分组继续查找。</p><button class="secondary" @click="search = ''; group = ''">清空筛选</button></div>
      <div v-else class="api-layout">
        <nav class="panel api-directory" aria-label="接口目录"><button v-for="entry in filtered" :key="entry.operation.operationId" :aria-current="selected?.operation.operationId === entry.operation.operationId ? 'true' : undefined" @click="selectedId = entry.operation.operationId; selectedSchema = ''"><span class="api-method" :class="entry.method.toLowerCase()">{{ entry.method }}</span><span><strong>{{ entry.operation.summary }}</strong><code>{{ entry.path }}</code></span></button></nav>
        <article v-if="selected" class="panel api-detail" aria-label="接口详情">
          <div class="api-operation"><span class="api-method" :class="selected.method.toLowerCase()">{{ selected.method }}</span><code>{{ selected.path }}</code></div>
          <h3>{{ selected.operation.summary }}</h3><p>{{ selected.operation.description }}</p>
          <dl class="api-rules"><div><dt>访问权限</dt><dd>{{ selected.operation['x-access'] }}</dd></div><div><dt>幂等键</dt><dd>{{ selected.operation['x-idempotency'] ? '必需 · 成功请求可在 24 小时内按原键恢复' : '不需要' }}</dd></div></dl>
          <template v-if="selected.operation.parameters?.length"><h4>请求参数</h4><div class="api-table-wrap"><table><thead><tr><th>参数 / 位置</th><th>类型</th><th>说明</th></tr></thead><tbody><tr v-for="param in selected.operation.parameters" :key="param.in + param.name"><td><code>{{ param.name }}</code><small>{{ param.in }} · {{ param.required ? '必填' : '选填' }}</small></td><td>{{ schemaName(param.schema) }}</td><td>{{ param.description }}<small v-if="param.schema.enum">允许值：{{ param.schema.enum.join(' / ') }}</small></td></tr></tbody></table></div></template>
          <template v-if="request"><h4>请求正文 <button class="api-schema-link" @click="openSchema(schemaName(request.schema))">{{ schemaName(request.schema) }} ↗</button></h4><p>{{ selected.operation.requestBody?.required ? '必填' : '可省略' }} · application/json</p><div class="api-table-wrap"><table><thead><tr><th>字段</th><th>类型</th><th>约束</th></tr></thead><tbody><tr v-for="(field, name) in fields(request.schema)?.properties" :key="name"><td><code>{{ name }}</code><small>{{ fields(request.schema)?.required?.includes(String(name)) ? '必填' : '选填' }}</small></td><td>{{ schemaName(field) }}</td><td>{{ field.description ?? '详见模型定义' }}</td></tr></tbody></table></div></template>
          <h4>调用示例</h4><p>设置 <code>BASE_URL</code> 为部署根地址，<code>TOKEN</code> 为自己的登录令牌；路径中的花括号替换为实际标识。新写入使用唯一 <code>REQUEST_KEY</code>，版本值按实际读取结果填写。</p><pre aria-label="curl 调用示例">{{ curlExample(selected) }}</pre>
          <h4>响应</h4><div class="api-responses"><div v-for="(response, code) in selected.operation.responses" :key="code"><strong>{{ code }}</strong><span>{{ response.description }}</span><button v-if="response.content?.['application/json']" class="api-schema-link" @click="openSchema(schemaName(response.content['application/json'].schema).replace('[]', ''))">{{ schemaName(response.content['application/json'].schema) }} ↗</button><small v-if="response.headers?.['Idempotency-Replayed']">成功响应头 Idempotency-Replayed 表示是否为原请求回放。</small><small v-for="media in Object.keys(response.content ?? {}).filter(value => value !== 'application/json')" :key="media">文件响应：{{ media }}</small></div></div>
          <details ref="modelPanel" tabindex="-1" aria-label="数据模型浏览" class="api-schema-browser" :open="!!selectedSchema"><summary>数据模型</summary><label>选择模型<select v-model="selectedSchema"><option value="">请选择模型</option><option v-for="(_, name) in query.document.components.schemas" :key="name">{{ name }}</option></select></label><pre v-if="schema" aria-label="数据模型定义">{{ JSON.stringify(schema, null, 2) }}</pre></details>
        </article>
      </div>
    </template>
  </section>
</template>

<style scoped>
.api-reference{min-width:0}.api-download{display:inline-flex;text-decoration:none;align-items:center;white-space:nowrap;color:var(--ink)}.api-intro{border-left:3px solid var(--deep);padding:2px 0 2px 18px;margin-bottom:25px}.api-intro strong{font-size:12px;color:var(--deep)}.api-intro p,.api-detail p{font-size:12px;color:var(--muted);line-height:1.85;margin:9px 0}.api-filters{display:flex;gap:16px;align-items:end;margin:22px 0}.api-filters label{display:grid;gap:7px;font-size:12px}.api-filters label:first-child{flex:1}.api-filters input,.api-filters select,.api-schema-browser select{background:var(--paper,#fff);border:1px solid var(--line);border-radius:7px;padding:10px 12px;color:var(--ink);width:100%;min-width:0}.api-filters>span{font-size:11px;color:var(--muted);white-space:nowrap;padding-bottom:12px}.api-layout{display:grid;grid-template-columns:minmax(220px,300px) minmax(0,1fr);gap:20px;align-items:start}.api-directory{max-height:74vh;overflow:auto;padding:8px}.api-directory button{width:100%;display:flex;align-items:start;gap:10px;text-align:left;background:transparent;border:0;border-radius:6px;padding:14px 10px;color:var(--ink)}.api-directory button[aria-current=true]{background:var(--soft)}.api-directory button>span:last-child{min-width:0}.api-directory strong{display:block;font-size:12px;font-weight:600}.api-directory code{display:block;overflow-wrap:anywhere;font-size:10px;line-height:1.6;margin-top:7px;color:var(--muted)}.api-method{font:600 10px ui-monospace,monospace;padding:4px 5px;border-radius:4px;color:var(--deep);background:var(--soft);flex-shrink:0}.api-method.post{color:#855b20;background:#fbf0d8}.api-method.put{color:#385883;background:#eaf0fa}.api-detail{padding:25px;min-width:0}.api-operation{display:flex;gap:10px;align-items:center}.api-operation>code{font-size:12px;overflow-wrap:anywhere}.api-detail h3{font-size:21px;margin:18px 0 10px}.api-detail h4{font-size:13px;margin:27px 0 12px}.api-rules{font-size:12px;border-top:1px solid var(--line);border-bottom:1px solid var(--line);padding:8px 0;margin-top:20px}.api-rules>div{display:flex;gap:18px;padding:8px 0}.api-rules dt{color:var(--muted);flex-shrink:0;width:56px}.api-rules dd{margin:0;line-height:1.7}.api-table-wrap{overflow:auto}.api-table-wrap table{width:100%;border-collapse:collapse;table-layout:auto;font-size:11px;text-align:left}.api-table-wrap th{font-weight:500;color:var(--muted)}.api-table-wrap th,.api-table-wrap td{padding:11px 8px;border-bottom:1px solid var(--line);vertical-align:top;line-height:1.7}.api-table-wrap code{overflow-wrap:anywhere}.api-table-wrap small{display:block;color:var(--muted);font-size:10px;margin-top:4px}.api-detail pre{font:11px/1.75 ui-monospace,SFMono-Regular,Consolas,monospace;background:#f3f6f5;border:1px solid var(--line);border-radius:7px;padding:16px;overflow:auto;max-height:420px;color:#284039;white-space:pre-wrap;overflow-wrap:anywhere}.api-schema-link{border:0;background:transparent;color:var(--deep);font-size:11px;padding:0;text-decoration:underline;text-underline-offset:3px;overflow-wrap:anywhere}.api-responses>div{display:flex;gap:12px;flex-wrap:wrap;font-size:11px;line-height:1.8;padding:10px 0;border-bottom:1px solid var(--line)}.api-responses strong{font-family:ui-monospace,monospace}.api-responses span{flex:1;min-width:100px}.api-responses small{width:100%;color:var(--muted)}.api-schema-browser{margin-top:25px;font-size:12px}.api-schema-browser summary{cursor:pointer;padding:8px 0}.api-schema-browser label{display:grid;gap:8px;margin-top:12px}.api-empty{text-align:center;padding:45px 22px;font-size:13px}.api-empty p{color:var(--muted)}
@media(max-width:1050px){.api-layout{grid-template-columns:minmax(0,1fr)}.api-directory{max-height:240px}.api-detail{padding:22px}}@media(max-width:650px){.api-reference .page-heading{flex-direction:column;align-items:start;gap:15px}.api-filters{flex-wrap:wrap}.api-filters label:first-child{flex-basis:100%}.api-detail{padding:16px}.api-rules>div{gap:10px}.api-table-wrap table{min-width:390px}.api-operation{align-items:start}.api-filters>span{margin-left:auto}}
</style>
