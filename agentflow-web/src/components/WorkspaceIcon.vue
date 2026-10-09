<script setup lang="ts">
import { workspaceIcons, type WorkspaceIconName } from '../workspaceIcons'

defineProps<{ name: WorkspaceIconName }>()
</script>

<template>
  <svg class="workspace-icon" width="20" height="20" viewBox="0 0 24 24" fill="none"
    stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"
    aria-hidden="true" focusable="false"><path :d="workspaceIcons[name]" /></svg>
</template>

<style scoped>
.workspace-icon { display: inline-block; flex-shrink: 0; vertical-align: middle; }
</style>
