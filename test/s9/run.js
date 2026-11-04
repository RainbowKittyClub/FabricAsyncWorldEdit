'use strict'

// S9 lanes: lighting, caches and the optimization policy. Usage: run.js <profile>, one of
// light-vanilla, vanilla-light-opt, full-opt (light and cache suite) or unverified (refusal only).
// Site A takes coordinated edits; site B, 64 blocks east, takes the same edits through vanilla fill
// as the control. Both straddle a chunk border at the same relative x.

const fs = require('node:fs')
const path = require('node:path')
const { Rig, SingleLaneLease, admitResources, RESOURCE_POLICY, writeJson, verify, hash, tool, GateError } = require('../harness/rig')
const { smoke } = require('../harness/smoke')
const repo = path.resolve(__dirname, '../..')
const workspace = '/home/kgadberry/projects/minecraft-dev'
const manifestFile = path.join(__dirname, 'manifest.json')
const manifest = JSON.parse(fs.readFileSync(manifestFile))
const evidence = '/mnt/WORKSPACE/scratchpad/fawe-s9-evidence'
const profile = process.argv[2]
if (!['light-vanilla', 'vanilla-light-opt', 'full-opt', 'unverified'].includes(profile)) throw new Error('Unknown S9 profile')
const OFFSET = 64
// Box interior plus roof layer; the shell spans x 3236..3259 across the 3248 chunk border.
const BOX = [3237, 65, 3235, 3258, 70, 3244]
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

function differences (a, b) {
  let count = 0
  let first = null
  for (const layer of ['block', 'sky']) {
    for (let index = 0; index < a[layer].length; index++) {
      if (a[layer][index] !== b[layer][index]) {
        count++
        first ??= { layer, index, a: a[layer][index], b: b[layer][index] }
      }
    }
  }
  return { count, first }
}

async function main () {
  fs.mkdirSync(evidence, { recursive: true })
  const recordFile = path.join(evidence, `s9-${profile}-${Date.now()}.json`)
  const report = { accepted: false, suite: 's9', profile, resourcePolicy: RESOURCE_POLICY, samples: [], checks: [] }
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
    report.sourceHashes = Object.fromEntries(['test/s9/run.js', 'test/s9/pin.py'].map(name => [name, hash(path.join(repo, name))]))
    lease = new SingleLaneLease()
    const admission = await admitResources({ samples: report.samples })
    rig = new Rig(`/mnt/WORKSPACE/scratchpad/fawe/fawe-fast-editing-20261029-s9-${profile}`, manifest, manifestFile, admission)
    report.admission = admission
    report.root = rig.root
    console.log(JSON.stringify({ root: rig.root }))
    const check = (name, condition, detail) => {
      report.checks.push({ name, passed: Boolean(condition), detail })
      if (!condition) throw new GateError(`${name}: ${JSON.stringify(detail)}`)
    }
    // Gson omits null fields, so an absent poi or refusal reads as null.
    const probe = async (send, words) => ({ poi: null, refusal: null, ...JSON.parse(await send(`fawes9 ${words}`)) })
    const lightAt = (send, dx) => probe(send, `light ${BOX[0] + dx} ${BOX[1]} ${BOX[2]} ${BOX[3] + dx} ${BOX[4]} ${BOX[5]}`)
    // Stable means three reads 600 ms apart agree; light engines run asynchronously.
    const settled = async (send, dx, label) => {
      const deadline = Date.now() + 60000
      let previous = await lightAt(send, dx)
      let agreeing = 0
      while (agreeing < 2) {
        if (Date.now() > deadline) throw new GateError(`Light never settled: ${label}`)
        await sleep(600)
        const now = await lightAt(send, dx)
        agreeing = differences(now, previous).count === 0 ? agreeing + 1 : 0
        previous = now
      }
      return previous
    }
    await smoke(rig, repo, workspace, profile, undefined, {
      jvmArgs: ['-Dfawe.experimental.coordinated=true'],
      async contract (rig, send, name, { client, chatCommand: sendChatCommand, expected }) {
        let lastCommandTick
        const clock = async () => {
          const match = (await send('time query gametime')).match(/^The game time is (\d+) tick\(s\)$/)
          if (!match) throw new GateError('Unexpected tick clock')
          return Number(match[1])
        }
        const chatCommand = async (command, response) => {
          if (lastCommandTick !== undefined) {
            await rig.wait('S9 command pacing', async () => await clock() >= lastCommandTick + 21, 10000, [client.entry])
          }
          lastCommandTick = await clock()
          await sendChatCommand(command, response)
        }
        const select = async (x1, y1, z1, x2, y2, z2) => {
          await chatCommand(`/pos1 ${x1},${y1},${z1}`, /First position set|Position already set/)
          await chatCommand(`/pos2 ${x2},${y2},${z2}`, /Second position set|Position already set/)
        }
        await send('forceload add 3200 3200 3343 3295')

        if (profile === 'unverified') {
          const verdict = await probe(send, 'optimizations')
          await send('fill 3240 80 3240 3243 80 3240 minecraft:air')
          await select(3240, 80, 3240, 3243, 80, 3240)
          await chatCommand('fawe-coordinated set stone', /unverified optimization mods: light engine phosphor has no verified light barrier/)
          await expected(3240, 80, 3240, 'minecraft:air')
          check('unverified-refused', /phosphor/.test(verdict.refusal || ''), verdict)
          rig.results.push({ gate: 's9-unverified-refusal', status: 'passed' })
          return
        }
        check('verified-combination', (await probe(send, 'optimizations')).refusal === null, await probe(send, 'optimizations'))

        // Light: each step runs on B first and settles; A's coordinated edit must then match B the moment
        // it reports complete, which holds only if completion waited for the light engine.
        for (const dx of [0, OFFSET]) await send(`fill ${3236 + dx} 64 3234 ${3259 + dx} 70 3245 minecraft:stone hollow`)
        const baseA = await settled(send, 0, 'shell A')
        const baseB = await settled(send, OFFSET, 'shell B')
        check('light-shell-baseline', differences(baseA, baseB).count === 0, differences(baseA, baseB))
        const steps = [
          ['glow-added', [3247, 67, 3239, 3248, 67, 3240], 'glowstone'],
          ['glow-removed', [3247, 67, 3239, 3248, 67, 3240], 'air'],
          ['roof-opened', [3244, 70, 3238, 3251, 70, 3241], 'air'],
          ['roof-closed', [3244, 70, 3238, 3251, 70, 3241], 'stone']]
        for (const [label, [x1, y1, z1, x2, y2, z2], block] of steps) {
          await send(`fill ${x1 + OFFSET} ${y1} ${z1} ${x2 + OFFSET} ${y2} ${z2} minecraft:${block}`)
          const control = await settled(send, OFFSET, `control ${label}`)
          await select(x1, y1, z1, x2, y2, z2)
          await chatCommand(`fawe-coordinated set ${block}`, /Coordinated edit complete: \d+ changes/)
          const atCompletion = await lightAt(send, 0)
          check(`light-${label}-at-completion`, differences(atCompletion, control).count === 0, differences(atCompletion, control))
        }

        // Lithium: a coordinated edit flips random ticking (dirt to grass) in a replaced section, then
        // grass spreads there under fast random ticks, flipping it again through vanilla.
        await send('fill 3264 79 3264 3271 79 3271 minecraft:dirt')
        await send('fill 3264 80 3264 3271 90 3271 minecraft:air')
        await select(3264, 79, 3264, 3265, 79, 3264)
        await chatCommand('fawe-coordinated set grass_block', /Coordinated edit complete: 2 changes/)
        await send('gamerule random_tick_speed 1000')
        await sleep(5000)
        await send('gamerule random_tick_speed 3')
        const grass = (await probe(send, 'count 3264 79 3264 3271 79 3271 minecraft:grass_block')).count
        check('random-ticks-after-section-install', grass > 2, { grass })

        // Path cache: a cached open cell turned to stone by a section install must read as blocked.
        await send('fill 3280 80 3264 3281 80 3264 minecraft:air')
        const openBefore = (await probe(send, 'pathtype 3281 80 3264')).type
        await select(3281, 80, 3264, 3281, 80, 3264)
        await chatCommand('fawe-coordinated set stone', /Coordinated edit complete: 1 changes/)
        const afterEdit = (await probe(send, 'pathtype 3281 80 3264')).type
        check('path-cache-invalidated', openBefore === 'OPEN' && afterEdit === 'BLOCKED', { openBefore, afterEdit })

        // POI: fawe-coordinated declares no side effects, so, as WorldEdit's own path under //perf none,
        // its bell registers no meeting POI. A vanilla bell beside it shows the probe sees POIs.
        await send('setblock 3284 80 3264 minecraft:air')
        await send('setblock 3286 80 3264 minecraft:bell')
        await rig.wait('vanilla bell POI', async () => (await probe(send, 'poi 3286 80 3264')).poi === 'minecraft:meeting', 10000, [client.entry])
        await select(3284, 80, 3264, 3284, 80, 3264)
        await chatCommand('fawe-coordinated set bell', /Coordinated edit complete: 1 changes/)
        await sleep(1500)
        check('poi-follows-declared-side-effects', (await probe(send, 'poi 3284 80 3264')).poi === null &&
          (await probe(send, 'poi 3286 80 3264')).poi === 'minecraft:meeting', {})
        rig.results.push({ gate: 's9-light-and-caches', status: 'passed', checks: report.checks.map(entry => entry.name) })
      },
      // After an unload and restart, site A's light still matches the control.
      async restart (rig, send) {
        if (profile === 'unverified') return
        await send('forceload add 3200 3200 3343 3295')
        const afterA = await settled(send, 0, 'A after restart')
        const afterB = await settled(send, OFFSET, 'B after restart')
        check('light-after-restart', differences(afterA, afterB).count === 0, differences(afterA, afterB))
        check('poi-after-restart', (await probe(send, 'poi 3284 80 3264')).poi === null &&
          (await probe(send, 'poi 3286 80 3264')).poi === 'minecraft:meeting', {})
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
