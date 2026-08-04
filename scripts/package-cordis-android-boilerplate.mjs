#!/usr/bin/env node

import { execFileSync } from 'node:child_process'
import { chmodSync, existsSync, lstatSync, mkdtempSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { cp, mkdir, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const packages = [
  { name: 'cordis-plugin-android', directory: 'android', id: 'android' },
  { name: 'cordis-plugin-android-test', directory: 'android-test', id: 'android-test' },
]
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')

async function main(argv = process.argv.slice(2)) {
  const args = parseArgs(argv)
  const input = resolve(repoRoot, args.input || 'result')
  const output = resolve(repoRoot, args.output || 'ci-bootstrap-assets')
  const workspace = resolve(repoRoot, 'boilerplate')
  const zip = join(output, 'assets/bootstrap/boilerplate.zip')

  if (!existsSync(join(input, 'assets/bootstrap/boilerplate.zip'))) throw new Error(`missing bootstrap assets: ${input}`)
  await rm(output, { recursive: true, force: true })
  await mkdir(output, { recursive: true })
  await cp(join(input, 'assets'), join(output, 'assets'), { recursive: true, dereference: true })
  makeWritable(output)

  run('corepack', ['yarn', 'install', '--no-immutable'], workspace)
  run('corepack', ['yarn', 'build'], workspace)

  const temporary = mkdtempSync(join(tmpdir(), 'cordis-android-boilerplate-'))
  try {
    run('unzip', ['-q', zip, '-d', temporary], repoRoot)
    for (const plugin of packages) {
      const source = join(workspace, 'packages', plugin.directory)
      await copyPackage(source, join(temporary, 'packages', plugin.directory))
      await copyRuntime(source, join(temporary, 'node_modules', plugin.name))
    }
    patchPackageJson(join(temporary, 'package.json'))
    patchAppConfig(join(temporary, 'app.yml'))
    await rm(zip)
    run('zip', ['-q', '-9', '-r', zip, '.'], temporary)
    writeFileSync(
      join(output, 'assets/bootstrap/boilerplate.txt'),
      `${readFileSync(join(output, 'assets/bootstrap/boilerplate.txt'), 'utf8').trimEnd()}\nandroid_test_plugins=${packages.map(plugin => plugin.name).join(',')}\n`,
    )
  } finally {
    await rm(temporary, { recursive: true, force: true })
  }
}

function parseArgs(argv) {
  const result = {}
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index]
    const value = argv[index + 1]
    if (!key?.startsWith('--') || !value) throw new Error(`invalid argument: ${key || ''}`)
    result[key.slice(2)] = value
  }
  return result
}

async function copyPackage(source, target) {
  await rm(target, { recursive: true, force: true })
  await cp(source, target, {
    recursive: true,
    filter: path => !['node_modules', 'lib', 'dist', '.tsbuildinfo'].includes(path.slice(source.length + 1).split('/')[0]),
  })
}

async function copyRuntime(source, target) {
  await rm(target, { recursive: true, force: true })
  await mkdir(target, { recursive: true })
  for (const name of ['package.json', 'README.md', 'lib']) {
    if (existsSync(join(source, name))) await cp(join(source, name), join(target, name), { recursive: true })
  }
  if (existsSync(join(source, 'dist'))) await cp(join(source, 'dist'), join(target, 'dist'), { recursive: true })
}

function patchPackageJson(path) {
  const json = JSON.parse(readFileSync(path, 'utf8'))
  json.dependencies = { ...json.dependencies }
  for (const plugin of packages) json.dependencies[plugin.name] = `file:packages/${plugin.directory}`
  writeFileSync(path, `${JSON.stringify(json, null, 2)}\n`)
}

function patchAppConfig(path) {
  let content = readFileSync(path, 'utf8').trimEnd()
  for (const plugin of packages) {
    if (!content.includes(`name: ${plugin.name}`)) content += `\n- id: ${plugin.id}\n  name: ${plugin.name}`
  }
  writeFileSync(path, `${content}\n`)
}

function makeWritable(path) {
  const stat = lstatSync(path)
  chmodSync(path, stat.mode | 0o200)
  if (stat.isDirectory()) for (const name of readdirSync(path)) makeWritable(join(path, name))
}

function run(command, args, cwd) {
  execFileSync(command, args, { cwd, stdio: 'inherit' })
}

await main()
