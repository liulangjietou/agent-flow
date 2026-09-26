/** 随流程版本冻结的三类申请人站内文案。@author owlzhangfq@gmail.com */
export interface NotificationTexts { submitted: string; returned: string; approved: string }
export const notificationTextEvents = [
  { key: 'submitted', label: '申请提交', status: '申请已提交' },
  { key: 'returned', label: '申请退回', status: '申请已退回' },
  { key: 'approved', label: '申请批准', status: '申请已批准' }
] as const
export const NOTIFICATION_TEXT_LIMIT = 500

/** 历史定义没有文案时保持空配置；每次复制均使用独立对象。 */
export function copyNotificationTexts(value?: Partial<NotificationTexts> | null): NotificationTexts {
  return { submitted: value?.submitted ?? '', returned: value?.returned ?? '', approved: value?.approved ?? '' }
}

/** 模板文件入口拒绝未知事件、非文本和超限数据，避免导入时丢失配置。 */
export function readNotificationTexts(value: unknown): NotificationTexts {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('站内通知文案必须是对象。')
  const entries = Object.entries(value)
  if (entries.some(([key, text]) => !notificationTextEvents.some(event => event.key === key) || typeof text !== 'string'
      || text.length > NOTIFICATION_TEXT_LIMIT || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/.test(text))) {
    throw new Error('通知文案仅支持提交、退回、批准三类纯文本，每项最多 500 字符。')
  }
  return copyNotificationTexts(value as Partial<NotificationTexts>)
}
