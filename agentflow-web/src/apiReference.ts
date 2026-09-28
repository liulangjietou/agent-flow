/** JSON Schema 的文档视图；原始契约下载保留全部标准字段。@author owlzhangfq@gmail.com */
export interface ApiSchema { $ref?: string; type?: string; description?: string; enum?: unknown[]; anyOf?: ApiSchema[]; items?: ApiSchema; properties?: Record<string, ApiSchema>; required?: string[]; [key: string]: unknown }
/** 接口参数与响应来自服务端契约。@author owlzhangfq@gmail.com */
export interface ApiParameter { name: string; in: string; required?: boolean; description?: string; schema: ApiSchema }
/** 一个实际 HTTP 操作。@author owlzhangfq@gmail.com */
export interface ApiOperation {
  operationId: string; tags: string[]; summary: string; description: string; 'x-access': string; 'x-idempotency': boolean
  parameters?: ApiParameter[]; security?: unknown[]
  requestBody?: { required: boolean; content: Record<string, { schema: ApiSchema; example?: unknown }> }
  responses: Record<string, { description: string; content?: Record<string, { schema: ApiSchema }>; headers?: Record<string, { description: string; schema: ApiSchema }> }>
}
/** 随服务发布的 OpenAPI 契约。@author owlzhangfq@gmail.com */
export interface ApiDocument { openapi: string; info: { title: string; version: string; description: string }; paths: Record<string, Record<string, ApiOperation>>; components: { schemas: Record<string, ApiSchema> }; tags: Array<{ name: string }> }
/** 可搜索的目录条目。@author owlzhangfq@gmail.com */
export interface ApiEntry { path: string; method: string; operation: ApiOperation }

export function entries(document: ApiDocument): ApiEntry[] {
  return Object.entries(document.paths).flatMap(([path, methods]) => Object.entries(methods).map(([method, operation]) => ({ path, method: method.toUpperCase(), operation })))
}
export function filterEntries(items: ApiEntry[], search: string, group: string): ApiEntry[] {
  const query = search.trim().toLocaleLowerCase()
  return items.filter(item => (!group || item.operation.tags.includes(group)) &&
    `${item.method} ${item.path} ${item.operation.summary} ${item.operation.description}`.toLocaleLowerCase().includes(query))
}
export function schemaName(schema: ApiSchema): string {
  if (schema.$ref) return schema.$ref.split('/').pop() ?? 'object'
  if (schema.anyOf) return schema.anyOf.map(schemaName).join(' | ')
  if (schema.type === 'array') return `${schemaName(schema.items ?? {})}[]`
  return schema.type ?? '任意 JSON'
}
export function resolveSchema(document: ApiDocument, schema: ApiSchema): ApiSchema {
  return schema.$ref ? document.components.schemas[schemaName(schema)] ?? schema : schema
}

/** 示例令牌始终为环境变量占位符，绝不读取当前会话或业务数据。 */
export function curlExample(entry: ApiEntry): string {
  const operation = entry.operation
  const query = (operation.parameters ?? []).filter(p => p.in === 'query' && p.required)
    .map(p => `${encodeURIComponent(p.name)}=0`).join('&')
  const lines = [`curl --request ${entry.method} "$BASE_URL${entry.path}${query ? '?' + query : ''}"`]
  if (operation.security?.length !== 0) lines.push('  --header "Authorization: Bearer $TOKEN"')
  if (operation['x-idempotency']) lines.push('  --header "Idempotency-Key: $REQUEST_KEY"')
  if (operation.parameters?.some(parameter => parameter.in === 'header' && parameter.name === 'X-Application-Version')) {
    lines.push('  --header "X-Application-Version: $APPLICATION_VERSION"')
  }
  if (operation.requestBody?.content['application/octet-stream']) {
    lines.push('  --header "Content-Type: application/octet-stream"')
    lines.push('  --data-binary "@$FILE_PATH"')
  }
  if (operation.responses['200']?.content?.['application/octet-stream']) lines.push('  --output "$OUTPUT_FILE"')
  const body = operation.requestBody?.content['application/json']
  if (body?.example !== undefined) {
    lines.push('  --header "Content-Type: application/json"')
    lines.push(`  --data-raw '${JSON.stringify(body.example, null, 2).replace(/'/g, "'\"'\"'")}'`)
  }
  return lines.join(' \\\n')
}

/** 只读契约查询，刷新或离开页面后迟到响应不能恢复旧内容。@author owlzhangfq@gmail.com */
export class ApiReferenceQuery {
  document: ApiDocument | null = null
  loading = false
  error = ''
  private generation = 0
  private controller: AbortController | null = null
  constructor(private fetchDocument: (signal: AbortSignal) => Promise<ApiDocument>) {}
  clear() {
    this.generation++; this.controller?.abort(); this.controller = null
    this.document = null; this.loading = false; this.error = ''
  }
  async load(scope: string) {
    this.clear()
    if (!scope) return
    const generation = this.generation
    const controller = new AbortController(); this.controller = controller; this.loading = true
    let expired = false
    const timer = setTimeout(() => { expired = true; controller.abort() }, 12_000)
    try {
      const document = await this.fetchDocument(controller.signal)
      if (generation === this.generation && !expired) this.document = document
    } catch (error) {
      if (generation === this.generation) this.error = expired ? '接口文档加载超时，请重试。' : (error as { message?: string })?.message ?? '接口文档加载失败，请重试。'
    } finally {
      clearTimeout(timer)
      if (generation === this.generation) { this.loading = false; this.controller = null }
    }
  }
}
