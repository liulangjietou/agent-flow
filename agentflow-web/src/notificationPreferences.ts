/** 本人外部通知偏好；站内业务提醒始终开启。 */
export interface NotificationPreferences {
  inAppEnabled: true
  emailEnabled: boolean
  enterpriseImEnabled: boolean
  version: number
  updatedAt: string | null
}
export interface NotificationPreferencesInput {
  emailEnabled: boolean
  enterpriseImEnabled: boolean
  expectedVersion: number
}

/** 缺失或错误的设置不能被界面解释为默认关闭，更不能确认一次写入。 */
export function readNotificationPreferences(value: unknown): NotificationPreferences {
  const result = value as NotificationPreferences | null
  if (!result || Array.isArray(result) || result.inAppEnabled !== true
      || typeof result.emailEnabled !== 'boolean' || typeof result.enterpriseImEnabled !== 'boolean'
      || !Number.isSafeInteger(result.version) || result.version < 0
      || Object.keys(result).sort().join(',') !== 'emailEnabled,enterpriseImEnabled,inAppEnabled,updatedAt,version'
      || (result.version === 0 ? result.updatedAt !== null || result.emailEnabled || result.enterpriseImEnabled
        : typeof result.updatedAt !== 'string' || !Number.isFinite(Date.parse(result.updatedAt)))) {
    throw unreadable()
  }
  return result
}

/** 原键恢复只确认原回执；当前设置仍由后续只读查询取得。 */
export function validateNotificationPreferencesReceipt(value: unknown, input: NotificationPreferencesInput) {
  const result = readNotificationPreferences(value)
  if (result.emailEnabled !== input.emailEnabled || result.enterpriseImEnabled !== input.enterpriseImEnabled
      || (result.version !== input.expectedVersion && result.version !== input.expectedVersion + 1)) throw unreadable()
  return result
}

function unreadable() {
  return { status: 0, code: 'RESPONSE_UNREADABLE', message: '通知偏好响应不完整，请重新读取；保存结果未知时请恢复原操作。' }
}
