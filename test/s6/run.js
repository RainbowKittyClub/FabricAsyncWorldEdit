'use strict'

const fs = require('node:fs')
const path = require('node:path')
const { Rig, SingleLaneLease, admitResources, RESOURCE_POLICY, writeJson, verify, hash, tool, GateError } = require('../harness/rig')
const { smoke } = require('../harness/smoke')
const repo = path.resolve(__dirname, '../..')
const workspace = '/home/kgadberry/projects/minecraft-dev'
const manifestFile = path.join(__dirname, 'manifest.json')
const manifest = JSON.parse(fs.readFileSync(manifestFile))
const evidence = path.join(repo, 'handoff/s6')
const suite = process.argv[2] || 'claims'
if (!['claims', 'history', 'edges', 's5'].includes(suite)) throw new Error('Expected claims, history, edges or s5 suite')
// --polymer: the same suite with the accepted Polymer bundle installed (S7 regression).
const polymer = process.argv.includes('--polymer')
if (polymer && suite === 's5') throw new Error('Use the harness s5 polymer profile instead')
const profile = suite === 's5' ? 'base' : polymer ? 'claims-polymer' : 'claims'

async function main () {
  const recordFile = path.join(evidence, `native-${Date.now()}.json`)
  const report = { accepted: false, completedStages: [], suite, profile, resourcePolicy: RESOURCE_POLICY, samples: [] }
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
    lease = new SingleLaneLease()
    const admission = await admitResources({ samples: report.samples })
    rig = new Rig('/mnt/WORKSPACE/scratchpad/fawe/fawe-fast-editing-20261029-s6', manifest, manifestFile, admission)
    report.admission = admission
    report.root = rig.root
    console.log(JSON.stringify({ root: rig.root }))
    await smoke(rig, repo, workspace, profile, undefined, suite === 's5' ? require('../harness/s5') : {
      jvmArgs: ['history', 'edges'].includes(suite) ? [] : ['-Dfawe.experimental.coordinated=true'],
      async configureServer (serverDir) {
        if (suite !== 'edges') return
        fs.mkdirSync(path.join(serverDir, 'config'), { recursive: true })
        // Dedicated exception fixtures; ordinary claims suites retain OPAC's unchanged defaults.
        fs.writeFileSync(path.join(serverDir, 'config/openpartiesandclaims-server.toml'),
          '[serverConfig.claims.protection]\n' +
          'forcedBlockProtectionExceptionList = ["minecraft:crafting_table", "break$minecraft:oak_planks"]\n' +
          'itemUseProtectionExceptionList = ["minecraft:firework_rocket", "minecraft:glass"]\n')
      },
      async contract (rig, send, name, { client, chatCommand: sendChatCommand, expected }) {
        // Non-op commands add 20 ticks of vanilla spam debt; pace without weakening that rule.
        let lastCommandTick
        const clock = async () => {
          const answer = await send('time query gametime')
          const match = answer.match(/^The game time is (\d+) tick\(s\)$/)
          if (!match) throw new GateError(`Unexpected tick clock: ${answer}`)
          return Number(match[1])
        }
        const chatCommand = async (command, response) => {
          if (lastCommandTick !== undefined) {
            await rig.wait('S6 command pacing', async () => await clock() >= lastCommandTick + 21, 10000, [client.entry])
          }
          lastCommandTick = await clock()
          await sendChatCommand(command, response)
        }
        await send('forceload add 3184 3184 3231 3231')
        await send(`deop ${name}`)
        if (suite === 'edges') {
          await send('forceload add 3232 3200 3263 3215')
          await send(`s6probe edges ${name}`)
          let state
          await rig.wait('native exceptions replay and failure cases', async () => {
            state = JSON.parse(await send('s6probe status'))
            return state.done
          }, 120000, [client.entry])
          writeJson(path.join(rig.root, 's6-native-edges.json'), state)
          if (state.failure || state.results.length !== 22) throw new GateError(`Native edges failed: ${JSON.stringify(state)}`)
          rig.results.push({ gate: 'native-exceptions-direct-replay-failures', status: 'passed', cases: state.results })
          await send(`op ${name}`)
          return
        }
        {
          await send(`s6probe native-history ${name}`)
          let historyState
          await rig.wait('bound native dynamic history', async () => {
            historyState = JSON.parse(await send('s6probe status'))
            return historyState.done
          }, 30000, [client.entry])
          writeJson(path.join(rig.root, 's6-native-history.json'), historyState)
          // Ten cases plus the unflushed-history case.
          if (historyState.failure || historyState.results.length !== 11) {
            throw new GateError(`Dynamic history/native refusals failed: ${JSON.stringify(historyState)}`)
          }
          await expected(3202, 80, 3200, 'minecraft:dirt')
          rig.results.push({ gate: 'bound-native-dynamic-history', status: 'passed', cases: historyState.results })
          await chatCommand('/pos1 3202,80,3200', /First position set/)
          await chatCommand('/pos2 3202,80,3200', /Second position set/)
          await chatCommand('/cut', /unsupported operation: cut/)
          await expected(3202, 80, 3200, 'minecraft:dirt')
          rig.results.push({ gate: 'native-cut-refused-before-source-removal', status: 'passed' })
          if (suite === 'history') {
            await send(`op ${name}`)
            return
          }
        }
        await send(`s6probe start ${name}`)
        let state
        await rig.wait('real OPAC API cases', async () => {
          state = JSON.parse(await send('s6probe status'))
          return state.done
        }, 120000, [client.entry])
        writeJson(path.join(rig.root, 's6-api.json'), state)
        if (state.failure || state.results.length !== 35) throw new GateError(`OPAC cases failed: ${JSON.stringify(state)}`)
        rig.results.push({ gate: 'real-opac-api-subset', status: 'passed', cases: state.results })
        await chatCommand('/pos1 3200,80,3200', /First position set/)
        await chatCommand('/pos2 3200,80,3200', /Second position set/)
        await send('setblock 3200 80 3200 minecraft:stone')
        await send('s6probe claim stranger')
        await chatCommand('fawe-coordinated set gold_block', /Coordinated edit failed after 0 recorded changes/)
        await expected(3200, 80, 3200, 'minecraft:stone')
        await send('s6probe claim owner')
        await chatCommand('fawe-coordinated set gold_block', /Coordinated edit complete: 1 changes/)
        await expected(3200, 80, 3200, 'minecraft:gold_block')
        rig.results.push({ gate: 'native-nonop-coordinated-command', status: 'passed', cases: 2 })
        // Record the required WNA assertion, then retain rendering/restart evidence before failing.
        await send('s6probe claim stranger')
        const nativeBefore = JSON.parse(await send('s6probe native-reset'))
        await chatCommand('/set diamond_block', /Operation completed|denied|protected|refused/)
        const deniedWorld = await send('execute if block 3200 80 3200 minecraft:gold_block')
        report.wnaBypassReproduced = deniedWorld !== 'Test passed'
        if (report.wnaBypassReproduced) await expected(3200, 80, 3200, 'minecraft:diamond_block')
        else {
          const facts = []
          // A player's own //set edits as that player.
          const observe = async (name, applied, denied, historyBefore, source = 'PLAYER', original) => {
            const state = JSON.parse(await send('s6probe native-status'))
            if (state.nativeReceipts.reduce((sum, receipt) => sum + receipt.applied, 0) !== applied ||
                state.nativeReceipts.reduce((sum, receipt) => sum + receipt.denied, 0) !== denied ||
                state.nativeReceipts.length !== 1 ||
                (historyBefore && JSON.stringify(state.historySizes) !== JSON.stringify(historyBefore.historySizes))) {
              throw new GateError(`Native history/receipt mismatch for ${name}: ${JSON.stringify(state)}`)
            }
            const receipt = state.nativeReceipts[0]
            if (receipt.source !== source || receipt.session === 'unknown' ||
                (original && (receipt.parent !== original.session || receipt.originalActor !== original.actor ||
                  receipt.actor !== original.actor || receipt.session === original.session))) {
              throw new GateError(`Native replay provenance mismatch for ${name}: ${JSON.stringify(receipt)}`)
            }
            facts.push({ name, receipts: state.nativeReceipts, historySizes: state.historySizes })
            return state
          }
          await observe('denied-set', 0, 1, nativeBefore)
          await send('s6probe claim owner')
          await send('s6probe native-reset')
          await chatCommand('/set diamond_block', /Operation completed/)
          await expected(3200, 80, 3200, 'minecraft:diamond_block')
          const written = await observe('owner-set', 1, 0)
          await send('s6probe claim stranger')
          await send('s6probe native-reset')
          await chatCommand('undo', /Undid/)
          await expected(3200, 80, 3200, 'minecraft:diamond_block')
          await observe('denied-actual-undo', 0, 1, written, 'UNDO', written.nativeReceipts[0])
          await send('s6probe claim owner')
          await chatCommand('redo', /Redid/)
          await send('s6probe native-reset')
          await chatCommand('undo', /Undid/)
          await expected(3200, 80, 3200, 'minecraft:gold_block')
          await observe('owner-actual-undo', 1, 0, written, 'UNDO', written.nativeReceipts[0])
          await send('s6probe native-reset')
          await send('s6probe claim stranger')
          await chatCommand('redo', /Redid/)
          await expected(3200, 80, 3200, 'minecraft:gold_block')
          await observe('denied-actual-redo', 0, 1, written, 'REDO', written.nativeReceipts[0])
          rig.results.push({ gate: 'native-current-claims-history-receipts', status: 'passed', cases: facts })
        }
        await send('s6probe borders')
        let borderState
        await rig.wait('native adjacent claim and inventory cases', async () => {
          borderState = JSON.parse(await send('s6probe status'))
          return borderState.done
        }, 30000, [client.entry])
        writeJson(path.join(rig.root, 's6-native-borders.json'), borderState)
        if (borderState.failure || borderState.results.length !== 6) {
          throw new GateError(`Native border cases failed: ${JSON.stringify(borderState)}`)
        }
        rig.results.push({ gate: 'native-borders-inventory', status: 'passed', cases: borderState.results })
        await send('fill 3203 79 3199 3217 79 3201 minecraft:stone')
        await send('fill 3204 80 3200 3205 80 3200 minecraft:oak_planks')
        await send(`tp ${name} 3204.5 81 3200.5`)
        await chatCommand('/pos1 3204,80,3200', /First position set/)
        await chatCommand('/pos2 3205,80,3200', /Second position set/)
        await chatCommand('/copy', /copied|2 blocks affected/i)
        await send(`tp ${name} 3215.5 81 3200.5`)
        const pasteBefore = JSON.parse(await send('s6probe native-reset'))
        await chatCommand('/paste', /clipboard has been pasted/)
        await expected(3215, 80, 3200, 'minecraft:oak_planks')
        await expected(3216, 80, 3200, 'minecraft:stone')
        const pasteAfter = JSON.parse(await send('s6probe status'))
        if (pasteAfter.nativeReceipts.length !== 2 ||
            pasteAfter.nativeReceipts.reduce((sum, receipt) => sum + receipt.applied, 0) !== 1 ||
            pasteAfter.nativeReceipts.reduce((sum, receipt) => sum + receipt.denied, 0) !== 1 ||
            pasteAfter.historySizes.length !== pasteBefore.historySizes.length + 1 ||
            pasteAfter.historySizes.at(-1) !== 1) throw new GateError('Border paste history/receipt mismatch')
        rig.results.push({ gate: 'ordinary-border-paste', status: 'passed', receipts: pasteAfter.nativeReceipts })
        await send(`op ${name}`)
      },
      async beforeStop (rig, send) {
        if (['history', 'edges'].includes(suite)) return
        await send('s6probe disconnect')
        let state
        await rig.wait('real actor disconnect before publication', async () => {
          state = JSON.parse(await send('s6probe status'))
          return state.disconnectDone
        }, 15000)
        writeJson(path.join(rig.root, 's6-disconnect.json'), state)
        if (state.failure || !state.results.some(result => result.case === 'actual-disconnect')) {
          throw new GateError(`Disconnected actor case failed: ${JSON.stringify(state)}`)
        }
        rig.results.push({ gate: 'real-disconnected-actor', status: 'passed', case: state.results.at(-1) })
      },
      async restart (rig, send) {
        await send('forceload add 3184 3184 3231 3231')
        if (suite === 'edges') {
          await send('forceload add 3232 3200 3263 3215')
          for (const x of [3200, 3232, 3248]) {
            if ((await send(`execute if block ${x} 80 3200 minecraft:gold_block`)) !== 'Test passed') {
              throw new GateError('Direct replay/native failure delta lost after restart')
            }
          }
          rig.results.push({ gate: 'native-failure-deltas-restart', status: 'passed' })
          return
        }
        if (suite === 'history') {
          if ((await send('execute if block 3202 80 3200 minecraft:dirt')) !== 'Test passed') {
            throw new GateError('Native history fixture changed after restart')
          }
          return
        }
        for (const [x, block] of [[3215, 'oak_planks'], [3216, 'stone']]) {
          if ((await send(`execute if block ${x} 80 3200 minecraft:${block}`)) !== 'Test passed') {
            throw new GateError('Border edit changed after restart')
          }
        }
        if (!/following block data: \[\]$/.test(await send('data get block 3208 80 3200 Items')) ||
            (await send('execute if data block 3224 80 3200 Items[{Slot:0b,id:"minecraft:diamond",count:7}]')) !== 'Test passed. Count: 1' ||
            (await send('s6probe border-saved')) !== '{"savedBorders":true}') {
          throw new GateError('Native inventory/biome border changed after restart')
        }
        const expected = report.wnaBypassReproduced ? 'diamond_block' : 'gold_block'
        const answer = await send(`execute if block 3200 80 3200 minecraft:${expected}`)
        if (answer !== 'Test passed') throw new GateError('Unexpected restart value')
        if ((await send('execute if block 3200 80 3201 minecraft:stone')) !== 'Test passed') {
          throw new GateError('Disconnected actor write appeared after restart')
        }
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
    if (report.wnaBypassReproduced) problem ||= new GateError('Required WNA protection failed: //set changed a foreign claim')
    report.error = problem?.stack
    report.status = problem ? 'failed' : 'passed-subset'
    writeJson(recordFile, report)
    console.log(JSON.stringify({ recordFile, ...report }))
  }
  if (problem) throw problem
}
main().catch(error => { console.error(error.stack); process.exitCode = Number.isInteger(error.code) ? error.code : 1 })
