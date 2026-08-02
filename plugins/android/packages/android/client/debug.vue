<template>
  <k-comment v-if="isCurrentPlugin" type="primary">
    <p class="debug-entry">
      <span>在 WebUI 中查看并触发 Android 插件注册的按钮。</span>
      <el-button type="primary" @click="open">调试</el-button>
    </p>
  </k-comment>

  <el-dialog v-model="visible" title="Cordis Android 调试" width="min(680px, 90vw)">
    <el-table v-loading="loading" :data="buttons" empty-text="暂无已注册的按钮">
      <el-table-column prop="label" label="按钮" min-width="160" />
      <el-table-column label="说明" min-width="240">
        <template #default="{ row }">
          {{ row.description || row.id }}
          <div v-if="row.enabled === false && row.disabledReason" class="disabled-reason">
            {{ row.disabledReason }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" align="right">
        <template #default="{ row }">
          <el-button
            type="primary"
            :disabled="row.enabled === false"
            :loading="triggering === row.id"
            @click="trigger(row.id)"
          >触发</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-dialog>
</template>

<script setup lang="ts">
import { message, send, useContext, useRpc } from '@cordisjs/client'
import { computed, ref } from 'vue'
import type { ButtonDef } from '../src'

interface DebugData {
  buttons: ButtonDef[]
}

interface ManagerContext {
  manager: {
    currentEntry?: { name: string }
  }
}

const ctx = useContext() as ReturnType<typeof useContext> & ManagerContext
const data = useRpc<DebugData>()
const visible = ref(false)
const loading = ref(false)
const triggering = ref<string>()
const buttons = ref<ButtonDef[]>([])
const isCurrentPlugin = computed(() => ctx.manager.currentEntry?.name === 'cordis-plugin-android')

async function refresh() {
  loading.value = true
  try {
    buttons.value = await send('android.debug.buttons')
  } finally {
    loading.value = false
  }
}

async function open() {
  visible.value = true
  buttons.value = data.value.buttons
  await refresh()
}

async function trigger(id: string) {
  triggering.value = id
  try {
    await send('android.debug.trigger', id)
    message.success('按钮已触发。')
  } catch (error) {
    message.error(error instanceof Error ? error.message : String(error))
  } finally {
    triggering.value = undefined
    await refresh()
  }
}
</script>

<style scoped>
.debug-entry {
  align-items: center;
  display: flex;
  gap: 1rem;
  justify-content: space-between;
}

.disabled-reason {
  color: var(--el-text-color-secondary);
  font-size: 0.875rem;
  margin-top: 0.25rem;
}
</style>
