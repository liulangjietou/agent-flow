import type { ConditionRow } from './conditionBuilder'

/** 仅供编辑器回显和说明使用；不执行条件，也不代替服务端校验。@author owlzhangfq@gmail.com */
export type ConditionExpression = { kind: 'comparison'; row: ConditionRow }
  | { kind: 'AND' | 'OR'; terms: ConditionExpression[] }
  | { kind: 'group'; term: ConditionExpression }
  | { kind: 'not'; term: ConditionExpression }

/** 无法完整识别时保留原文，不把部分解析结果当作有效条件。 */
export function parseConditionExpression(source: string): ConditionExpression | null {
  try { return new DisplayParser(source).parse() } catch { return null }
}

/** 与 v2 语法同界限的只读解析器，保留显式括号以判断能否回到平面配置。@author owlzhangfq@gmail.com */
class DisplayParser {
  private cursor = 0
  private comparisons = 0
  constructor(private source: string) {}

  /** 只有完整输入能生成说明。 */
  parse(): ConditionExpression {
    if (!this.source.trim() || this.source.length > 4000) throw new Error('Invalid condition length')
    const result = this.or(0)
    this.space()
    if (this.cursor !== this.source.length) throw new Error('Unexpected token')
    return result
  }

  private or(depth: number): ConditionExpression {
    const terms = [this.and(depth)]
    while (this.symbol('||') || this.word('OR')) terms.push(this.and(depth))
    return terms.length === 1 ? terms[0]! : { kind: 'OR', terms }
  }
  private and(depth: number): ConditionExpression {
    const terms = [this.unary(depth)]
    while (this.symbol('&&') || this.word('AND')) terms.push(this.unary(depth))
    return terms.length === 1 ? terms[0]! : { kind: 'AND', terms }
  }
  private unary(depth: number): ConditionExpression {
    if (depth > 16) throw new Error('Nesting limit exceeded')
    if (this.symbol('!')) return { kind: 'not', term: this.unary(depth + 1) }
    if (this.symbol('(')) {
      const term = this.or(depth + 1)
      if (!this.symbol(')')) throw new Error('Closing parenthesis required')
      return { kind: 'group', term }
    }
    if (++this.comparisons > 100) throw new Error('Comparison limit exceeded')
    this.space()
    const field = /^[a-zA-Z][a-zA-Z0-9_.]{0,63}(?![a-zA-Z0-9_.])/.exec(this.source.slice(this.cursor))?.[0]
    if (!field) throw new Error('Field required')
    this.cursor += field.length
    if (this.word('NOT_EXISTS')) return { kind: 'comparison', row: { field, operator: 'NOT_EXISTS', value: '' } }
    if (this.word('EXISTS')) return { kind: 'comparison', row: { field, operator: 'EXISTS', value: '' } }
    if (this.word('IN')) {
      if (!this.symbol('[')) throw new Error('Membership list required')
      const values: string[] = []
      do {
        this.space()
        if (!['"', "'"].includes(this.source[this.cursor] ?? '')) throw new Error('Quoted member required')
        values.push(this.literal())
        if (values.length > 50) throw new Error('Membership limit exceeded')
      } while (this.symbol(','))
      if (!this.symbol(']')) throw new Error('Closing bracket required')
      return { kind: 'comparison', row: { field, operator: 'IN', value: '', values } }
    }
    const operator = ['==', '!=', '>=', '<=', '>', '<'].find(token => this.symbol(token))
    if (!operator) throw new Error('Operator required')
    return { kind: 'comparison', row: { field, operator, value: this.literal() } }
  }
  private literal(): string {
    this.space()
    const quote = this.source[this.cursor]
    let value = ''
    if (quote === '"' || quote === "'") {
      this.cursor++
      let closed = false
      while (this.cursor < this.source.length) {
        const char = this.source[this.cursor++]!
        if (char === quote) { closed = true; break }
        if (char < ' ') throw new Error('Unescaped control character')
        if (char !== '\\') value += char
        else {
          const escape = this.source[this.cursor++]!
          const escapes: Record<string, string> = { '"': '"', "'": "'", '\\': '\\', '/': '/', n: '\n', r: '\r', t: '\t', b: '\b', f: '\f' }
          if (Object.prototype.hasOwnProperty.call(escapes, escape)) value += escapes[escape]
          else if (escape === 'u' && /^[0-9a-fA-F]{4}$/.test(this.source.slice(this.cursor, this.cursor + 4))) {
            value += String.fromCharCode(parseInt(this.source.slice(this.cursor, this.cursor + 4), 16)); this.cursor += 4
          } else throw new Error('Invalid escape')
        }
        if (value.length > 256) throw new Error('Literal limit exceeded')
      }
      if (!closed) throw new Error('Unclosed literal')
    } else {
      const start = this.cursor
      while (this.cursor < this.source.length && !/[\s)\]&,|]/.test(this.source[this.cursor]!)) this.cursor++
      value = this.source.slice(start, this.cursor)
      if (!/^(?:-?\d+(?:\.\d+)?|true|false)$/.test(value)) throw new Error('Quoted text required')
    }
    if (value.length > 256 || value.includes(';') || value.includes('${') || value.includes('#{')) throw new Error('Unsupported literal')
    return value
  }
  private space() { while (/[ \t\n\r\f\v\u001c-\u001f\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/.test(this.source[this.cursor] ?? '')) this.cursor++ }
  private symbol(token: string): boolean {
    this.space()
    if (!this.source.startsWith(token, this.cursor)) return false
    this.cursor += token.length; return true
  }
  private word(token: string): boolean {
    this.space()
    const end = this.cursor + token.length
    if (this.source.slice(this.cursor, end).toUpperCase() !== token || /[a-zA-Z0-9_.]/.test(this.source[end] ?? '')) return false
    this.cursor = end; return true
  }
}
