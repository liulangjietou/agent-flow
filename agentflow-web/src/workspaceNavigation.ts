import type { WorkspaceIconName } from './workspaceIcons'

/** 工作空间已有页面标识；导航展示不授予业务权限。@author owlzhangfq@gmail.com */
export type WorkspacePage = 'expense-reports' | 'account-mappings' | 'expense-configuration' | 'organization' | 'proxies' | 'webhooks' | 'audit' | 'transfer' | 'calendars' | 'guide' | 'examples' | 'operations' | 'api' | 'notifications' | 'started' | 'drafts' | 'handled' | 'workbench' | 'designer' | 'templates' | 'applications' | 'assist' | 'expense' | 'cashier' | 'system'

/** 同一份菜单供桌面侧栏与窄屏抽屉使用。@author owlzhangfq@gmail.com */
export interface WorkspaceMenuItem { label: string; icon: WorkspaceIconName; page?: WorkspacePage; access?: 'manage' | 'inspect' | 'cashier' | 'finance-config' | 'finance' }

export const workspaceMenu: { label: string; items: WorkspaceMenuItem[] }[] = [
  { label: '个人工作', items: [
    { page: 'guide', label: '开始使用', icon: 'guide', access: 'inspect' },
    { page: 'workbench', label: '待我审批', icon: 'inbox' },
    { page: 'started', label: '我发起', icon: 'send' },
    { page: 'drafts', label: '我的草稿', icon: 'draft' },
    { page: 'handled', label: '已办记录', icon: 'check' },
    { page: 'notifications', label: '消息中心', icon: 'bell' },
    { page: 'applications', label: '申请记录', icon: 'records' }
  ] },
  { label: '流程与业务', items: [
    { page: 'designer', label: '流程管理', icon: 'flow' },
    { page: 'templates', label: '模板中心', icon: 'template', access: 'manage' },
    { page: 'assist', label: 'Agent 助理', icon: 'spark' },
    { page: 'expense', label: '财务申请', icon: 'wallet' },
    { page: 'expense-reports', label: '费用财务报表', icon: 'chart', access: 'finance' },
    { page: 'cashier', label: '出纳付款', icon: 'payment', access: 'cashier' }
  ] },
  { label: '管理与集成', items: [
    { page: 'account-mappings', label: '科目映射', icon: 'mapping', access: 'finance-config' },
    { page: 'expense-configuration', label: '费用制度', icon: 'policy', access: 'finance-config' },
    { page: 'api', label: '接口文档', icon: 'code' },
    { page: 'webhooks', label: '集成投递', icon: 'integration', access: 'inspect' },
    { page: 'audit', label: '操作审计', icon: 'audit', access: 'inspect' },
    { page: 'operations', label: '审批运营', icon: 'chart', access: 'inspect' },
    { page: 'organization', label: '组织与人员', icon: 'people', access: 'inspect' },
    { page: 'proxies', label: '审批代理', icon: 'mapping', access: 'inspect' },
    { page: 'calendars', label: '工作日历', icon: 'calendar', access: 'inspect' },
    { page: 'system', label: '系统自检', icon: 'system', access: 'inspect' }
  ] }
]

/** 面包屑与导航使用同一个页面名称，辅助页面保留各自的标题。 */
export function workspacePageLabel(page: WorkspacePage): string {
  if (page === 'transfer') return '模板文件'
  if (page === 'examples') return '示例数据'
  return workspaceMenu.flatMap(group => group.items).find(item => item.page === page)?.label ?? '审批工作台'
}
