import assert from 'node:assert/strict'
import { readFileSync, writeFileSync, mkdirSync, realpathSync } from 'node:fs'
import { resolve, relative } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import ts from 'typescript'

// 运行真实画布几何和序列化函数；合成快照只写入指定的本地验收目录。
const root = fileURLToPath(new URL('../', import.meta.url))
const output = resolve(process.argv[2]), input = resolve(process.argv[3])
assert.ok(!relative(realpathSync('/fyoung/tmp'), realpathSync(output)).startsWith('..'))
const modules = resolve(output, 'frontend-modules'); mkdirSync(modules)
writeFileSync(resolve(modules, 'package.json'), '{"type":"module"}')
for (const name of ['formSchema', 'serviceTasks', 'subprocessDesigner', 'designerGraph', 'designerLayout']) {
  writeFileSync(resolve(modules, name + '.js'), ts.transpileModule(readFileSync(resolve(root, 'src', name + '.ts'), 'utf8'),
    { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText)
}
const { loadDesignerNodes, serializeDesignerNodes } = await import(pathToFileURL(resolve(modules, 'designerGraph.js')))
const { serializeDesignerEdges, nodeRectangle } = await import(pathToFileURL(resolve(modules, 'designerLayout.js')))
const graph = JSON.parse(readFileSync(input, 'utf8')), nodes = loadDesignerNodes(graph.nodes)
const result = { ...graph, nodes: serializeDesignerNodes(nodes), edges: serializeDesignerEdges(nodes, graph.edges) }
writeFileSync(resolve(output, 'frontend-graph.json'), JSON.stringify(result, null, 2) + '\n')
writeFileSync(resolve(output, 'frontend-boxes.json'), JSON.stringify(Object.fromEntries(nodes.map(node => [node.id, nodeRectangle(node)])), null, 2) + '\n')
