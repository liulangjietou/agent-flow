import { mkdtempSync, readFileSync, writeFileSync, existsSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'
import ts from 'typescript'

// 测试产物只写入约定的临时目录，不改变应用构建配置。
const root = fileURLToPath(new URL('../', import.meta.url))
const output = mkdtempSync('/fyoung/tmp/agentflow-web-requests-')
writeFileSync(resolve(output, 'package.json'), '{"type":"module"}')
for (const name of ['api', 'pendingWrites', 'formSchema', 'templateCenter', 'unsavedConfirmation']) {
  const path = resolve(root, `src/${name}.ts`)
  if (!existsSync(path)) continue
  const source = readFileSync(path, 'utf8').replace('import.meta.env.VITE_API_BASE', 'undefined')
  writeFileSync(resolve(output, `${name}.js`), ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext }
  }).outputText)
}
const result = spawnSync(process.execPath, ['--test', resolve(root, 'tests/requests.test.mjs'), resolve(root, 'tests/forms.test.mjs'), resolve(root, 'tests/templates.test.mjs'), resolve(root, 'tests/confirmation.test.mjs')], {
  env: { ...process.env, AGENTFLOW_TEST_API: resolve(output, 'api.js'), AGENTFLOW_TEST_FORMS: resolve(output, 'formSchema.js'), AGENTFLOW_TEST_TEMPLATES: resolve(output, 'templateCenter.js'), AGENTFLOW_TEST_CONFIRMATION: resolve(output, 'unsavedConfirmation.js') }, stdio: 'inherit'
})
process.exitCode = result.status ?? 1
