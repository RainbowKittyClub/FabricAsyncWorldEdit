#!/usr/bin/env node
'use strict'

const fs = require('node:fs')
const path = require('node:path')
const { parseArgs } = require('node:util')
const { GateError, hash, json, writeJson, safeRoot, verify, memory, Rig, tool } = require('./rig')
const { renderControl } = require('./native')
const { smoke } = require('./smoke')
const { controls } = require('./controls')

const repo = path.resolve(__dirname, '../..')
const { values, positionals } = parseArgs({ allowPositionals: true, options: {
  root: { type: 'string', default: '/mnt/WORKSPACE/scratchpad/fawe' },
  manifest: { type: 'string', default: path.join(repo, 'test/manifest.json') },
  workspace: { type: 'string', default: '/home/kgadberry/projects/minecraft-dev' },
  suite: { type: 'string', default: 'smoke' },
  profile: { type: 'string', default: 'reference' },
  seed: { type: 'string' },
  fault: { type: 'string' }
} })

function preflight (manifest) {
  safeRoot(values.root)
  if (values.seed && Number(values.seed) !== manifest.seed) throw new GateError('Seed differs from pinned manifest', 3)
  const resources = memory()
  if (resources.available < 8 * 1024 ** 3 || resources.swapUsed !== 0) throw new GateError('Insufficient unswapped resources', 2)
  for (const side of ['client', 'server', 'metadata', 'assetIndex']) verify(manifest.minecraft[side])
  for (const input of [...manifest.minecraft.libraries, ...manifest.loader.libraries]) verify(input)
  for (const [name, input] of Object.entries(manifest.helpers)) {
    const actual = path.join(values.workspace, 'scripts/loadtest/lib', `${name}.js`)
    if (hash(actual) !== input.sha256) throw new GateError(`Workspace helper changed: ${actual}`, 2)
  }
  if (!manifest.profiles[values.profile]) throw new GateError(`Profile not provisioned: ${values.profile}`, 2)
  for (const key of manifest.profiles[values.profile]) verify(manifest.availableDependencies[key])
  if (!/^eula=true\s*$/m.test(fs.readFileSync(verify(manifest.eula), 'utf8'))) {
    throw new GateError('Previously authorized EULA configuration is unavailable', 2)
  }
  for (const binary of ['java', 'javac', 'jar', 'Xvfb', 'xauth', 'vulkaninfo']) {
    try { tool('sh', ['-c', 'command -v "$1"', 'sh', binary]) } catch {
      throw new GateError(`${binary} missing; invoke through test/flake.nix`, 2)
    }
  }
  if (!process.env.VK_DRIVER_FILES || !fs.existsSync(process.env.VK_DRIVER_FILES)) {
    throw new GateError('Pinned software Vulkan ICD unavailable; invoke through test/flake.nix', 2)
  }
  if (!process.env.TMPDIR) throw new GateError('TMPDIR must name a writable private temporary root', 2)
  return resources
}

function xml (text) { return String(text).replace(/[<>&"']/g, c => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&apos;' })[c]) }

async function main () {
  const manifest = json(values.manifest)
  const resources = preflight(manifest)
  if (positionals[0] === 'preflight') {
    console.log(JSON.stringify({ status: 'capabilities-present-not-runtime-tested', resources,
      manifest: values.manifest, incompleteProfiles: manifest.unavailableProfiles }))
    return
  }
  if (positionals[0] !== 'run') throw new GateError('Use preflight or run; compare is not implemented', 2)
  if (!['render-control', 'smoke', 's1-controls'].includes(values.suite)) throw new GateError(`Required suite not implemented: ${values.suite}`, 2)
  const rig = new Rig(values.root, manifest, values.manifest)
  writeJson(path.join(rig.root, 'test-inputs.json'), Object.fromEntries(
    fs.readdirSync(__dirname).filter(file => /\.(js|py)$/.test(file))
      .map(file => [file, hash(path.join(__dirname, file))])))
  let failure
  try {
    if (values.suite === 'render-control') await renderControl(rig, repo)
    else if (values.suite === 's1-controls') await controls(rig, repo)
    else await smoke(rig, repo, values.workspace, values.profile, values.fault)
  } catch (error) { failure = error } finally {
    try { await rig.close() } catch (error) { failure = error }
    for (const entry of rig.children) {
      const log = fs.readFileSync(entry.log, 'utf8')
      if (/\/ERROR\]|Mixin apply failed|InvalidInjectionException|Failed to handle packet|Failed to load options|Couldn't save screenshot|Failed to load the built-in legacy/.test(log)) {
        failure ||= new GateError(`Runtime error in ${entry.log}`)
      }
    }
    const result = { suite: values.suite, profile: values.profile, accepted: false,
      status: failure ? 'failed' : 'passed-subset', exitCode: failure?.code || (failure ? 1 : 0),
      error: failure?.message, results: rig.results, fixtureRoot: rig.root,
      stagesComplete: [], missing: ['S3-S15 fast backend, integrations and release acceptance'] }
    writeJson(path.join(rig.root, 'results.json'), result)
    fs.writeFileSync(path.join(rig.root, 'junit.xml'), `<testsuite name="${xml(values.suite)}" tests="1" failures="${failure ? 1 : 0}"><testcase name="${xml(values.suite)}">${failure ? `<failure>${xml(failure.message)}</failure>` : ''}</testcase></testsuite>\n`)
    console.log(JSON.stringify(result))
  }
  if (failure) throw failure
}
main().catch(error => { console.error(error.stack); process.exitCode = error.code || 1 })
