import type { GraphNode } from './api'
import { readSubprocessBinding, writeSubprocessBinding, type SubprocessBinding } from './subprocessDesigner.js'

/** 节点期限保留原始文本，缺项与非法旧配置由校验明确报告。@author owlzhangfq@gmail.com */
export interface DesignerDeadline { calendarId?: string; calendarRevision?: string; workingMinutes?: string; escalationWorkingMinutes?: string; escalationRecipientRule?: string }
const deadlineProperties = { calendarId: 'deadlineCalendarId', calendarRevision: 'deadlineCalendarRevision', workingMinutes: 'deadlineWorkingMinutes', escalationWorkingMinutes: 'escalationWorkingMinutes', escalationRecipientRule: 'escalationRecipientRule' } as const

/** 两种设计视图读取相同属性，保留非法原文供校验且不共享可变引用。 */
export function readDesignerDeadline(properties: Record<string, string>): DesignerDeadline | undefined {
  const deadline: DesignerDeadline = {}
  for (const field of Object.keys(deadlineProperties) as Array<keyof DesignerDeadline>) {
    const value = properties[deadlineProperties[field]]
    if (value !== undefined) deadline[field] = value
  }
  return Object.keys(deadline).length ? deadline : undefined
}

/**
 * 画布节点保留来源属性和加载时展示位置，以区分真实修改与展示默认值。
 * @author owlzhangfq@gmail.com
 */
export interface DesignerNode {
  id: string; name: string; type: string; x: number; y: number; assigneeRule: string
  recipientRule?: string
  approvalMode?: string
  approvalPercentage?: string
  timerDelaySeconds?: string
  eventContractKey?: string
  eventContractVersion?: string
  subprocess?: SubprocessBinding
  deadline?: DesignerDeadline
  originalProperties?: Record<string, string>
  loadedPosition?: { x: number; y: number }
}

const DEFAULT_NODE_X = 40
const DEFAULT_NODE_Y = 180
const DEFAULT_NODE_SPACING = 180

/** 保留完整来源配置；没有持久化坐标时只在画布里补充展示位置。 */
export function loadDesignerNodes(nodes: GraphNode[]): DesignerNode[] {
  return nodes.map((node, index) => {
    const rawX = Number(node.properties.x ?? DEFAULT_NODE_X + index * DEFAULT_NODE_SPACING)
    const rawY = Number(node.properties.y ?? DEFAULT_NODE_Y)
    const x = Number.isFinite(rawX) && rawX >= 0 ? rawX : DEFAULT_NODE_X + index * DEFAULT_NODE_SPACING
    const y = Number.isFinite(rawY) && rawY >= 0 ? rawY : DEFAULT_NODE_Y
    return { id: node.id, name: node.name, type: node.type, x, y, assigneeRule: node.properties.assigneeRule ?? '', recipientRule: node.properties.recipientRule ?? '',
      deadline: readDesignerDeadline(node.properties),
      approvalMode: node.properties.approvalMode ?? 'SINGLE', approvalPercentage: node.properties.approvalPercentage,
      timerDelaySeconds: node.properties.timerDelaySeconds,
      eventContractKey: node.properties.eventContractKey, eventContractVersion: node.properties.eventContractVersion,
      subprocess: readSubprocessBinding(node.properties),
      originalProperties: { ...node.properties }, loadedPosition: { x, y } }
  })
}

/** 将画布节点转换为发布、保存、校验、模拟和比较共用的配置快照。 */
export function serializeDesignerNodes(nodes: DesignerNode[]): GraphNode[] {
  return nodes.map(node => {
    const properties = node.type === 'SUB_PROCESS' ? writeSubprocessBinding(node.originalProperties ?? {}, node.subprocess) : { ...node.originalProperties }
    if (node.type === 'EVENT_WAIT') {
      for (const key of ['eventContractKey', 'eventContractVersion'] as const) {
        if (node[key] !== undefined) properties[key] = node[key]
        else delete properties[key]
      }
    }
    if (node.type === 'TIMER_WAIT') {
      if (node.timerDelaySeconds !== undefined) properties.timerDelaySeconds = node.timerDelaySeconds
      else delete properties.timerDelaySeconds
    }
    if (node.type === 'COPY') {
      if (node.recipientRule) properties.recipientRule = node.recipientRule
      else delete properties.recipientRule
    }
    if (node.type === 'USER_TASK') {
      for (const field of Object.keys(deadlineProperties) as Array<keyof DesignerDeadline>) {
        const value = node.deadline?.[field]
        if (value !== undefined) properties[deadlineProperties[field]] = value
        else delete properties[deadlineProperties[field]]
      }
      if (node.assigneeRule) properties.assigneeRule = node.assigneeRule
      else delete properties.assigneeRule
      // 未修改的旧节点保留原属性，不能因展示默认值而制造版本差异。
      if (node.approvalMode && (node.approvalMode !== 'SINGLE' || properties.approvalMode !== undefined)) {
        properties.approvalMode = node.approvalMode
      }
      if (node.approvalPercentage !== undefined) properties.approvalPercentage = node.approvalPercentage
      else delete properties.approvalPercentage
    }
    for (const axis of ['x', 'y'] as const) {
      if (!node.loadedPosition || node[axis] !== node.loadedPosition[axis]) properties[axis] = String(node[axis])
    }
    return { id: node.id, name: node.name, type: node.type, properties }
  })
}
