<script setup lang="ts">
import WorkspaceIcon from '../components/WorkspaceIcon.vue'
import type { AuthOptions } from '../api'

/**
 * 登录页：只负责展示与收集输入，登录、恢复会话等流程仍由 App 统一编排。
 * @author owlzhangfq@gmail.com
 */
defineProps<{ authLoading: boolean; authOptions: AuthOptions | null; busy: boolean; notice: string }>()
const emit = defineEmits<{ login: []; 'enterprise-login': []; retry: [] }>()
const tenantId = defineModel<string>('tenantId', { required: true })
const username = defineModel<string>('username', { required: true })
const password = defineModel<string>('password', { required: true })
</script>

<template>
  <section class="login-screen">
    <div class="login-layout">
      <div class="login-story">
        <div class="brand"><div class="brand-mark" aria-hidden="true">AF</div><div><strong>agentflow</strong><small>审批与业务协同</small></div></div>
        <p class="eyebrow">AGENTFLOW / WORKSPACE</p>
        <h1>让每一次审批<br /><em>都有依据。</em></h1>
        <p class="login-copy">申请、审批与财务记录，在同一工作空间有序流转。</p>
        <ol class="login-journey" aria-label="审批流程">
          <li><WorkspaceIcon name="draft" /><strong>填写申请</strong><span>材料与业务事实</span></li>
          <li><WorkspaceIcon name="flow" /><strong>审批流转</strong><span>明确每一步责任</span></li>
          <li><WorkspaceIcon name="check" /><strong>记录归档</strong><span>结果有迹可循</span></li>
        </ol>
      </div>
      <form class="login-card" aria-labelledby="login-title" @submit.prevent="emit('login')">
        <h2 id="login-title">登录工作空间</h2>
        <p class="login-intro">继续处理你的申请与审批。</p>
        <p v-if="authLoading" role="status">正在读取登录配置…</p>
        <template v-else-if="authOptions?.mode === 'DEMO'">
          <label>租户空间<input v-model="tenantId" autocomplete="organization" placeholder="demo" /></label>
          <label>用户名<input v-model="username" autocomplete="username" placeholder="admin" /></label>
          <label>密码<input v-model="password" autocomplete="current-password" type="password" placeholder="请输入密码" /></label>
          <button class="primary wide" :disabled="busy" :aria-busy="busy">{{ busy ? '正在登录…' : '进入工作台' }}<WorkspaceIcon v-if="!busy" name="arrow" /></button>
          <details class="login-demo"><summary>查看演示账号</summary><p>租户 demo；账号 admin / manager / finance / cashier / alice，密码 demo。</p></details>
        </template>
        <template v-else-if="authOptions?.mode === 'OIDC'">
          <p class="login-copy">使用企业账号登录，租户空间和权限由管理员分配。</p>
          <button type="button" class="primary wide" :disabled="busy" @click="emit('enterprise-login')">使用企业账号登录 ↗</button>
        </template>
        <template v-else>
          <p role="status">{{ authOptions?.mode === 'UNCONFIGURED' ? '尚未配置登录服务，请联系管理员。' : '无法读取登录配置，请检查服务连接后重试。' }}</p>
          <button type="button" class="primary wide" @click="emit('retry')">重新检查</button>
        </template>
        <p v-if="notice" class="login-message" role="status">{{ notice }}</p>
      </form>
    </div>
  </section>
</template>
