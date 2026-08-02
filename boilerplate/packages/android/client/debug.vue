<template>
  <k-comment v-if="isCurrentPlugin" type="primary">
    <p>在 WebUI 中查看并触发 Android 插件注册的按钮。</p>
    <el-table :data="data.buttons" empty-text="暂无已注册的按钮">
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
  </k-comment>
</template>

<script setup lang="ts">
import { message, useContext, useRpc } from '@cordisjs/client'
import type {} from '@cordisjs/plugin-loader-webui/client'
import { computed, ref } from 'vue'
import type { ButtonDef } from '../src'

interface DebugData {
  buttons: ButtonDef[]
}

const ctx = useContext()
const data = useRpc<DebugData>()
const triggering = ref<string>()
const isCurrentPlugin = computed(() => ctx.manager.currentEntry?.name === 'cordis-plugin-android')

async function trigger(id: string) {
  triggering.value = id
  try {
    await data.value.trigger(id)
    message.success('按钮已触发。')
  } catch (error) {
    message.error(error instanceof Error ? error.message : String(error))
  } finally {
    triggering.value = undefined
  }
}
</script>

<style scoped>
.disabled-reason {
  color: var(--el-text-color-secondary);
  font-size: 0.875rem;
  margin-top: 0.25rem;
}
</style>
