import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

// 页面标识同时是路由名称：菜单中的每个页面都必须可路由，未知名称不能被当作页面。
const navigation = await import(process.env.AGENTFLOW_TEST_WORKSPACE_NAVIGATION)

test('every menu page is a routable workspace page and unknown names are rejected', () => {
  const pages = navigation.workspaceMenu.flatMap(group => group.items).map(item => item.page).filter(Boolean)
  assert.ok(pages.length > 0)
  for (const page of pages) assert.equal(navigation.isWorkspacePage(page), true, page)
  assert.equal(new Set(navigation.workspacePages).size, navigation.workspacePages.length)
  for (const value of ['', 'admin', 'designer/', null, undefined, 42]) assert.equal(navigation.isWorkspacePage(value), false)
})

test('router declares one named route per workspace page and keeps query on redirects', () => {
  const router = readFileSync(new URL('../src/router.ts', import.meta.url), 'utf8')
  assert.match(router, /workspacePages\.map\(page => \(\{ path: '\/' \+ page, name: page/)
  assert.match(router, /redirect: to => \(\{ path: '\/workbench', query: to\.query/)
  const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
  // 登录与恢复会话都经由同一落地页规则，深链只使用一次。
  assert.equal(app.match(/page\.value = landingPage\(\)/g)?.length, 2)
})
