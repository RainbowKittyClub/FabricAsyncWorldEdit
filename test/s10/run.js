'use strict'

// S10 history lane. Usage: run.js history-base. Six edits through ordinary WorldEdit commands and the
// coordinated command, then a restart; the same player rejoins and undoes and redoes all six, checking
// the world through vanilla commands and each replay's provenance through observed receipts.
// World checks are soft: every one is recorded and the run fails at the end, naming them all.

const fs = require('node:fs')
const path = require('node:path')
const { Rig, SingleLaneLease, admitResources, RESOURCE_POLICY, writeJson, verify, hash, tool, GateError } = require('../harness/rig')
const { smoke } = require('../harness/smoke')
const repo = path.resolve(__dirname, '../..')
const workspace = '/home/kgadberry/projects/minecraft-dev'
const manifestFile = path.join(__dirname, 'manifest.json')
const manifest = JSON.parse(fs.readFileSync(manifestFile))
const evidence = '/mnt/WORKSPACE/scratchpad/fawe-s10-evidence'
const profile = process.argv[2]
if (!['history-base'].includes(profile)) throw new Error('Unknown S10 profile')

const STAND = 'type=minecraft:armor_stand,tag=s10src'
// 64 units each (a 2x2 column over four chunks and 16 sections, 16 cells a unit), so a cancel or
// disconnect a second in lands part-way, and the replay, one native cell a tick, stays short.
const CANCEL = [4095, 64, 4095, 4096, 319, 4096]
const DISCONNECT = [4127, 64, 4095, 4128, 319, 4096]
const CELLS = 4 * 256
// Each edit's before and after, as vanilla checks; index matches the history entry, oldest first.
const EDITS = [
  { name: 'set', before: [['block', 4000, 80, 4000, 'minecraft:air'], ['block', 4002, 80, 4000, 'minecraft:air']],
    after: [['block', 4000, 80, 4000, 'minecraft:stone'], ['block', 4002, 80, 4000, 'minecraft:stone']] },
  { name: 'nbt-paste', before: [['block', 4008, 80, 4000, 'minecraft:chest[facing=south]'], ['items', 4008, 80, 4000, 'emerald', 2]],
    after: [['block', 4008, 80, 4000, 'minecraft:chest[facing=north]'], ['items', 4008, 80, 4000, 'diamond', 3]] },
  // The source stand stays on both sides: an undo that removed it would have undone the wrong entity.
  { name: 'entity-paste', before: [['entities', 4016, 80, 4000, 0], ['entities', 4012, 80, 4000, 1]],
    after: [['entities', 4016, 80, 4000, 1], ['entities', 4012, 80, 4000, 1]] },
  { name: 'move', before: [['block', 4020, 80, 4000, 'minecraft:gold_block'], ['block', 4024, 80, 4000, 'minecraft:air']],
    after: [['block', 4020, 80, 4000, 'minecraft:air'], ['block', 4024, 80, 4000, 'minecraft:gold_block']] },
  { name: 'biome', before: [['biome', 4028, 80, 4000, 'minecraft:plains']], after: [['biome', 4028, 80, 4000, 'minecraft:desert']] },
  { name: 'coordinated', before: [['block', 4034, 80, 4000, 'minecraft:air']], after: [['block', 4034, 80, 4000, 'minecraft:glass']] }
]

async function main () {
  fs.mkdirSync(evidence, { recursive: true })
  const recordFile = path.join(evidence, `s10-${profile}-${Date.now()}.json`)
  const report = { accepted: false, suite: 's10', profile, resourcePolicy: RESOURCE_POLICY, samples: [], checks: [] }
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
    report.sourceHashes = Object.fromEntries(['test/s10/run.js', 'test/s10/pin.py', 'test/harness/smoke.js']
      .map(name => [name, hash(path.join(repo, name))]))
    lease = new SingleLaneLease()
    const admission = await admitResources({ samples: report.samples })
    rig = new Rig(`/mnt/WORKSPACE/scratchpad/fawe/fawe-fast-editing-20261029-s10-${profile}`, manifest, manifestFile, admission)
    report.admission = admission
    report.root = rig.root
    console.log(JSON.stringify({ root: rig.root }))
    const check = (name, condition, detail) => {
      report.checks.push({ name, passed: Boolean(condition), detail })
    }
    const probe = async (send, words) => JSON.parse(await send(`fawes10 ${words}`))
    const count = async (send, box, block) => JSON.parse(await send(`fawes9 count ${box.join(' ')} minecraft:${block}`)).count
    // Chat after a log offset, for messages that arrive after the command's own reply.
    const chatSince = (client, offset) => fs.readFileSync(client.entry.log, 'utf8').slice(offset)
    // One vanilla check of an EDITS entry: true when the world holds it.
    const holds = async (send, [kind, x, y, z, value, count]) => {
      switch (kind) {
        case 'block': return (await send(`execute if block ${x} ${y} ${z} ${value}`)) === 'Test passed'
        // The stored cell: vanilla's `execute if biome` reads through BiomeManager's zoom, which
        // jitters into neighbouring cells, so one changed cell can read as its neighbour.
        case 'biome': return (await probe(send, `biome ${x} ${y} ${z}`)).biome === value
        case 'items': return /^Test passed/.test(await send(
          `execute if data block ${x} ${y} ${z} Items[{Slot:0b,id:"minecraft:${value}",count:${count}}]`))
        case 'entities': {
          const answer = await send(`execute if entity @e[${STAND},x=${x},y=${y},z=${z},dx=0,dy=0,dz=0]`)
          return value === 0 ? /^Test failed/.test(answer) : answer === `Test passed. Count: ${value}`
        }
      }
      throw new GateError(`Unknown check kind ${kind}`)
    }
    const state = async (send, label, expectations) => {
      const failed = []
      for (const expectation of expectations) if (!await holds(send, expectation)) failed.push(expectation)
      check(label, failed.length === 0, failed)
    }
    let pacedClock
    const paced = (rig, send, client, sendChatCommand) => async (command, response) => {
      const clock = async () => {
        const match = (await send('time query gametime')).match(/^The game time is (\d+) tick\(s\)$/)
        if (!match) throw new GateError('Unexpected tick clock')
        return Number(match[1])
      }
      if (pacedClock !== undefined) {
        await rig.wait('S10 command pacing', async () => await clock() >= pacedClock + 21, 10000, [client.entry])
      }
      pacedClock = await clock()
      await sendChatCommand(command, response)
    }
    let origins
    await smoke(rig, repo, workspace, profile, undefined, {
      jvmArgs: ['-Dfawe.experimental.coordinated=true'],
      restartClient: true,
      async contract (rig, send, name, { client, chatCommand: sendChatCommand }) {
        const chatCommand = paced(rig, send, client, sendChatCommand)
        const select = async (x1, y1, z1, x2, y2, z2) => {
          await chatCommand(`/pos1 ${x1},${y1},${z1}`, /First position set|Position already set/)
          await chatCommand(`/pos2 ${x2},${y2},${z2}`, /Second position set|Position already set/)
        }
        await send('forceload add 3984 3984 4063 4031')
        await send('fill 3996 79 3996 4040 79 4004 minecraft:stone')
        await send('fill 3996 80 3996 4040 84 4004 minecraft:air')
        await send('setblock 4006 80 4000 minecraft:chest[facing=north]{Items:[{Slot:0b,id:"minecraft:diamond",count:3}]}')
        await send('setblock 4008 80 4000 minecraft:chest[facing=south]{Items:[{Slot:0b,id:"minecraft:emerald",count:2}]}')
        await send(`summon minecraft:armor_stand 4012.5 80 4000.5 {Tags:["s10src"],NoGravity:1b,Invulnerable:1b}`)
        await send('fill 4020 80 4000 4021 80 4000 minecraft:gold_block')
        await send('forceload add 4080 4080 4143 4111')

        // Cancel: the report, the world and the history agree on what was written, undo and redo
        // replay exactly that, and nothing is left to replay a second time.
        await select(...CANCEL)
        await chatCommand('fawe-coordinated set glass', /Coordinated edit admitted/)
        const offset = fs.statSync(client.entry.log).size
        await chatCommand('fawe-coordinated cancel', /Coordinated edit cancelled \(/)
        const cancelText = chatSince(client, offset)
        const match = cancelText.match(/Coordinated edit cancelled \(cancelled by [^)]+\) after (\d+) recorded changes/)
        const reported = match ? Number(match[1]) : -1
        const glass = await count(send, CANCEL, 'glass')
        check('cancel-partial', reported > 0 && reported < CELLS && glass === reported,
          { reported, glass, chat: cancelText.split('\n').filter(line => /CHAT/.test(line)).slice(-3) })
        const cancelEntry = (await probe(send, `history ${name}`)).entries.at(-1)
        check('cancel-history-exact', cancelEntry.size === reported && cancelEntry.operation !== 'null', cancelEntry)
        await chatCommand('undo', /Undid/)
        check('cancel-undo-exact', await count(send, CANCEL, 'glass') === 0, {})
        await chatCommand('redo', /Redid/)
        check('cancel-redo-exact', await count(send, CANCEL, 'glass') === reported, {})
        await chatCommand('redo', /Nothing left to redo/)
        check('cancel-no-second-replay', await count(send, CANCEL, 'glass') === reported, {})

        for (const edit of EDITS) await state(send, `before-${edit.name}`, edit.before)

        await select(4000, 80, 4000, 4002, 80, 4000)
        await chatCommand('/set stone', /Operation completed/)
        await send(`tp ${name} 4006.5 80 4000.5`)
        await select(4006, 80, 4000, 4006, 80, 4000)
        await chatCommand('/copy', /copied|blocks affected/i)
        await send(`tp ${name} 4008.5 80 4000.5`)
        await chatCommand('/paste', /clipboard has been pasted/)
        await send(`tp ${name} 4012.5 80 4000.5`)
        await select(4012, 80, 4000, 4012, 80, 4000)
        await chatCommand('/copy -e', /copied|blocks affected/i)
        await send(`tp ${name} 4016.5 80 4000.5`)
        await chatCommand('/paste -e', /clipboard has been pasted/)
        await select(4020, 80, 4000, 4021, 80, 4000)
        await chatCommand('/move 4 east', /moved|Operation completed/i)
        await select(4028, 80, 4000, 4029, 80, 4001)
        await chatCommand('/setbiome minecraft:desert', /Biomes were changed|Operation completed/)
        await select(4034, 80, 4000, 4034, 80, 4000)
        await chatCommand('fawe-coordinated set glass', /Coordinated edit complete: 1 changes/)
        for (const edit of EDITS) await state(send, `after-${edit.name}`, edit.after)
        // The smoke edits come first in the same player's history; the lane's six are the last six.
        const all = (await probe(send, `history ${name}`)).entries
        origins = all.slice(-EDITS.length)
        check('history-before-restart', all.length >= EDITS.length && origins.every(entry =>
          entry.type === 'DiskStorageHistory' && entry.operation !== 'null'), all)
        await send('save-all flush')
      },
      // Disconnect: once part of a large edit is written, the harness stops the client and then the
      // server; the edit's history must still hold exactly what was written.
      async beforeStop (rig, send, name, { client, chatCommand: sendChatCommand }) {
        const chatCommand = paced(rig, send, client, sendChatCommand)
        await chatCommand(`/pos1 ${DISCONNECT.slice(0, 3).join(',')}`, /First position set/)
        await chatCommand(`/pos2 ${DISCONNECT.slice(3).join(',')}`, /Second position set/)
        await chatCommand('fawe-coordinated set stone', /Coordinated edit admitted/)
        await rig.wait('disconnect leg part-written', async () => await count(send, DISCONNECT, 'stone') > 0,
          30000, [client.entry])
      },
      async restart (rig, send, name, { client, chatCommand: sendChatCommand }) {
        const chatCommand = paced(rig, send, client, sendChatCommand)
        await send('forceload add 3984 3984 4063 4031')
        await send('forceload add 4080 4080 4143 4111')
        const stone = await count(send, DISCONNECT, 'stone')
        check('disconnect-partial', stone > 0 && stone < CELLS, { stone })
        await chatCommand('undo', /Undid/)
        check('disconnect-undo-exact', await count(send, DISCONNECT, 'stone') === 0, {})
        const reloaded = (await probe(send, `history ${name}`)).entries.slice(-EDITS.length - 1, -1)
        check('history-survives-restart', reloaded.length === EDITS.length &&
          reloaded.every((entry, index) => entry.type === 'DiskStorageHistory' && entry.index === origins[index].index),
        { origins, reloaded })
        check('provenance-survives-restart', reloaded.length === origins.length &&
          reloaded.every((entry, index) => entry.operation !== 'null' && entry.operation === origins[index].operation &&
            entry.actor === origins[index].actor), { origins, reloaded })
        for (let index = EDITS.length - 1; index >= 0; index--) {
          await probe(send, 'receipts clear')
          await chatCommand('undo', /Undid/)
          await state(send, `undo-${EDITS[index].name}`, EDITS[index].before)
          const receipts = (await probe(send, 'receipts')).receipts
          // Entity creation never reaches the coordinator, so that undo has no receipts.
          if (EDITS[index].name !== 'entity-paste') {
            check(`undo-provenance-${EDITS[index].name}`, receipts.length > 0 && receipts.every(receipt =>
              receipt.source === 'UNDO' && receipt.parent === origins[index]?.operation &&
              receipt.originalActor === origins[index]?.actor), receipts)
          }
        }
        for (const edit of EDITS) {
          await chatCommand('redo', /Redid/)
          await state(send, `redo-${edit.name}`, edit.after)
        }
        await chatCommand('redo', /Redid/)
        check('disconnect-redo-exact', await count(send, DISCONNECT, 'stone') === stone, {})
        const failed = report.checks.filter(entry => !entry.passed).map(entry => entry.name)
        if (failed.length) throw new GateError(`S10 history checks failed: ${failed.join(', ')}`)
        rig.results.push({ gate: 's10-history', status: 'passed', checks: report.checks.map(entry => entry.name) })
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
