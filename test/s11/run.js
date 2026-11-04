'use strict'

// S11 lanes. Usage: run.js <suite> <profile>, e.g. run.js functional base. Each suite module
// returns smoke-harness hooks; every check is recorded and the run fails at the end, naming them.

const fs = require('node:fs')
const path = require('node:path')
const { Rig, SingleLaneLease, admitResources, RESOURCE_POLICY, writeJson, verify, hash, tool, GateError } = require('../harness/rig')
const { smoke } = require('../harness/smoke')
const repo = path.resolve(__dirname, '../..')
const workspace = '/home/kgadberry/projects/minecraft-dev'
const manifestFile = path.join(__dirname, 'manifest.json')
const manifest = JSON.parse(fs.readFileSync(manifestFile))
const evidence = '/mnt/WORKSPACE/scratchpad/fawe-s11-evidence'
const [suite, base] = process.argv.slice(2)
const SUITES = { functional: './functional', operations: './operations', content: './content', throughput: './throughput' }
if (!SUITES[suite]) throw new Error(`Unknown S11 suite ${suite}`)
const profile = `${suite}-${base}`
if (!manifest.profiles[profile]) throw new Error(`Unknown S11 profile ${profile}`)

async function main () {
  fs.mkdirSync(evidence, { recursive: true })
  const recordFile = path.join(evidence, `s11-${profile}-${Date.now()}.json`)
  const report = { accepted: false, suite: 's11', profile, resourcePolicy: RESOURCE_POLICY, samples: [], checks: [] }
  let rig, lease, problem
  try {
    for (const side of ['client', 'server', 'metadata', 'assetIndex']) verify(manifest.minecraft[side])
    for (const input of [...manifest.minecraft.libraries, ...manifest.loader.libraries]) verify(input)
    for (const [name, input] of Object.entries(manifest.helpers)) {
      if (hash(path.join(workspace, 'scripts/loadtest/lib', `${name}.js`)) !== input.sha256) throw new GateError('Helper drift', 2)
    }
    for (const key of manifest.profiles[profile]) verify(manifest.availableDependencies[key])
    if (!/^eula=true\s*$/m.test(fs.readFileSync(verify(manifest.eula), 'utf8'))) throw new GateError('Authorized EULA missing', 2)
    for (const binary of ['java', 'javac', 'jar', 'Xvfb', 'xauth', 'vulkaninfo']) tool('sh', ['-c', 'command -v "$1"', 'sh', binary])
    if (!process.env.TMPDIR || !process.env.VK_DRIVER_FILES || !fs.existsSync(process.env.VK_DRIVER_FILES)) throw new GateError('Use pinned Nix shell', 2)
    report.sourceHashes = Object.fromEntries(['test/s11/run.js', `test/s11/${suite}.js`, 'test/s11/common.js', 'test/s11/pin.py', 'test/harness/smoke.js']
      .map(name => [name, hash(path.join(repo, name))]))
    lease = new SingleLaneLease()
    const admission = await admitResources({ samples: report.samples })
    rig = new Rig(`/mnt/WORKSPACE/scratchpad/fawe/fawe-fast-editing-20261029-s11-${profile}`, manifest, manifestFile, admission)
    report.admission = admission
    report.root = rig.root
    console.log(JSON.stringify({ root: rig.root }))
    const check = (name, condition, detail) => {
      report.checks.push({ name, passed: Boolean(condition), detail })
    }
    let pacedClock
    // One chat command a second at most: the client's command rate stays under vanilla's spam limit.
    const paced = (send, client, sendChatCommand) => async (command, response) => {
      const clock = async () => {
        const match = (await send('time query gametime')).match(/^The game time is (\d+) tick\(s\)$/)
        if (!match) throw new GateError('Unexpected tick clock')
        return Number(match[1])
      }
      if (pacedClock !== undefined) {
        await rig.wait('S11 command pacing', async () => await clock() >= pacedClock + 21, 10000, [client.entry])
      }
      pacedClock = await clock()
      await sendChatCommand(command, response)
    }
    const hooks = require(SUITES[suite])({ rig, check, paced, GateError })
    await smoke(rig, repo, workspace, profile, undefined, {
      jvmArgs: ['-Dfawe.experimental.coordinated=true'],
      restartClient: true,
      ...hooks,
      async restart (...args) {
        await hooks.restart(...args)
        const failed = report.checks.filter(entry => !entry.passed).map(entry => entry.name)
        if (failed.length) throw new GateError(`S11 ${profile} checks failed: ${failed.join(', ')}`)
        rig.results.push({ gate: `s11-${profile}`, status: 'passed', checks: report.checks.map(entry => entry.name) })
      }
    })
  } catch (error) { problem = error } finally {
    if (rig) {
      try { await rig.close() } catch (error) { problem ||= error }
      report.results = rig.results
      report.resources = rig.resourceReport()
      for (const entry of rig.children) {
        if (/\/ERROR\]|Mixin apply failed|InvalidInjectionException|Failed to handle packet|Failed to load options/.test(fs.readFileSync(entry.log, 'utf8'))) {
          problem ||= new GateError(`Runtime error in ${entry.log}`)
        }
      }
      report.processes = rig.children.map(({ child, ...entry }) => entry)
    }
    if (!rig || rig.children.every(entry => entry.stopped)) lease?.close()
    report.leaseReleased = !lease || lease.closed
    report.error = problem?.stack
    report.status = problem ? 'failed' : 'passed-subset'
    writeJson(recordFile, report)
    console.log(JSON.stringify({ recordFile, status: report.status, error: report.error }))
  }
  if (problem) throw problem
}

main().catch(error => { console.error(error.stack); process.exitCode = Number.isInteger(error.code) ? error.code : 1 })
