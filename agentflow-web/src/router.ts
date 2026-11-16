import { createRouter, createWebHistory } from 'vue-router'
import { workspacePages } from './workspaceNavigation'

/**
 * 页面与地址一一对应：刷新、深链和浏览器前进后退都能回到同一页面。
 * 页面内容仍由 App.vue 按当前页面渲染，路由只负责地址同步，权限由各页面自身判断。
 * @author owlzhangfq@gmail.com
 */
const RouteAnchor = { render: () => null }

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    // 根路径与未知路径回到待办，保留查询参数（如企业登录回跳的 authError）。
    { path: '/', redirect: to => ({ path: '/workbench', query: to.query, hash: to.hash }) },
    ...workspacePages.map(page => ({ path: '/' + page, name: page, component: RouteAnchor })),
    { path: '/:unknown(.*)*', redirect: to => ({ path: '/workbench', query: to.query }) }
  ]
})
