/** 工作空间已有页面标识；导航展示不授予业务权限。@author owlzhangfq@gmail.com */
export type WorkspacePage = 'organization' | 'webhooks' | 'audit' | 'transfer' | 'calendars' | 'guide' | 'examples' | 'operations' | 'api' | 'notifications' | 'started' | 'drafts' | 'handled' | 'workbench' | 'designer' | 'templates' | 'applications' | 'assist' | 'expense' | 'system'

/** 同一份菜单供桌面侧栏与窄屏抽屉使用。@author owlzhangfq@gmail.com */
export interface WorkspaceMenuItem { label: string; icon: string; page?: WorkspacePage; access?: 'manage' | 'inspect' }

export const workspaceMenu: { label: string; items: WorkspaceMenuItem[] }[] = [
  { label: '个人工作', items: [
    { page: 'guide', label: '开始使用', icon: '◎', access: 'inspect' },
    { page: 'workbench', label: '待我审批', icon: '◉' },
    { page: 'started', label: '我发起', icon: '↗' },
    { page: 'drafts', label: '我的草稿', icon: '▧' },
    { page: 'handled', label: '已办记录', icon: '✓' },
    { page: 'notifications', label: '消息中心', icon: '◌' },
    { page: 'applications', label: '申请记录', icon: '↗' }
  ] },
  { label: '流程与业务', items: [
    { page: 'designer', label: '流程管理', icon: '⌘' },
    { page: 'templates', label: '模板中心', icon: '▤', access: 'manage' },
    { page: 'assist', label: 'Agent 助理', icon: '✦' },
    { page: 'expense', label: '费用报销', icon: '▣' }
  ] },
  { label: '管理与集成', items: [
    { page: 'api', label: '接口文档', icon: '⌁' },
    { page: 'webhooks', label: '集成投递', icon: '↗', access: 'inspect' },
    { page: 'audit', label: '操作审计', icon: '≡', access: 'inspect' },
    { page: 'operations', label: '审批运营', icon: '▥', access: 'inspect' },
    { page: 'organization', label: '组织与人员', icon: '◫', access: 'inspect' },
    { page: 'calendars', label: '工作日历', icon: '▦', access: 'inspect' },
    { page: 'system', label: '系统自检', icon: '◈', access: 'inspect' }
  ] }
]
