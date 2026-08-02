import { Context } from '@cordisjs/client'
import Debug from './debug.vue'

export default (ctx: Context) => {
  ctx.inject(['manager'], (ctx) => {
    ctx.slot({
      type: 'plugin-details',
      component: Debug,
      order: 0,
    })
  })
}
