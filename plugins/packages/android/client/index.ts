import { Context } from '@cordisjs/client'
import type {} from '@cordisjs/plugin-loader-webui/client'
import Debug from './debug.vue'

export default (ctx: Context) => {
  ctx.inject(['manager'], (ctx) => {
    ctx.client.router.slot({
      type: 'plugin-details',
      component: Debug,
      order: 0,
    })
  })
}
