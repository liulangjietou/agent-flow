// 前端静态检查：启用推荐规则发现真实缺陷，格式交由编辑器处理，不在此重复约束。
import js from '@eslint/js'
import globals from 'globals'
import tseslint from 'typescript-eslint'
import vue from 'eslint-plugin-vue'

export default [
  { ignores: ['dist/**', 'node_modules/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...vue.configs['flat/essential'],
  {
    files: ['**/*.vue'],
    languageOptions: { parserOptions: { parser: tseslint.parser } }
  },
  {
    files: ['**/*.{ts,vue}'],
    languageOptions: { globals: globals.browser }
  },
  {
    rules: {
      // 输入校验需要显式匹配控制字符，拒绝隐藏字符进入业务字段。
      'no-control-regex': 'off',
      // 现有配置编辑器以“父组件传入可编辑定义对象、子组件就地修改”方式协作，改为事件回传需单独重构。
      'vue/no-mutating-props': 'off',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', ignoreRestSiblings: true }]
    }
  },
  {
    files: ['scripts/**/*.mjs', 'tests/**/*.mjs', '*.config.{js,ts}'],
    languageOptions: { globals: globals.node },
    rules: {
      // 测试桩需要在回调中保存宿主对象引用，模拟浏览器事件目标。
      '@typescript-eslint/no-this-alias': 'off'
    }
  }
]
