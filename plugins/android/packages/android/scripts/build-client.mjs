import { build } from '@cordisjs/client/lib'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('..', import.meta.url))

await build(root)
