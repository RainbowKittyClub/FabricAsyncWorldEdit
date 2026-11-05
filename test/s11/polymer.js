'use strict'

// S11 polymer suite: every route with Polymer and S7's fixture overlays installed, watched by a client
// with the server pack and one without. Each step is checked against an independent model, on the
// server and in both clients, and against a mirror row made with strict vanilla commands.

const fs = require('node:fs')
const path = require('node:path')
const { canonical, probe, differences, delta, holdsItem } = require('./common')
const { startClient } = require('../harness/native')

const repo = path.resolve(__dirname, '../..')
const NATIVE_STAGES = ['snapshot', 'admitted', 'prepared', 'authorized', 'published', 'reconciled', 'recorded']
const BULK_STAGES = ['ticket', ...NATIVE_STAGES]
const ROUTES = [['native', 'PLAYER'], ['native', 'API'], ['bulk', 'PLAYER'], ['bulk', 'API']]

const Y = 80
const WIDTH = 4
const row = (name, x1, z) => ({ name, x1, y1: Y, z1: z, x2: x1 + WIDTH - 1, y2: Y, z2: z })
// Rows 24 blocks apart, so no lamp lights another, each in its own chunk; anchors share a section
// with the row their edits run in and are never edited.
const NATIVE = row('native', 4096, 4100)
const NATIVE_MIRROR = row('nativeMirror', 4120, 4100)
const BULK = row('bulk', 4144, 4100)
const BULK_MIRROR = row('bulkMirror', 4168, 4100)
const SOURCE = row('source', 4120, 4124)
const NATIVE_ANCHOR = row('nativeAnchor', 4096, 4104)
const BULK_ANCHOR = row('bulkAnchor', 4144, 4104)
// Chunks 256,255 and 257,255: one native unit of 256 cells each.
const WIDE = { name: 'wide', x1: 4096, y1: 86, z1: 4080, x2: 4127, y2: 86, z2: 4095 }
const WIDE_CHUNKS = new Set(['256,255', '257,255'])
const PLAIN_DISPLAY = { name: 'plainDisplay', x1: 4130, y1: Y, z1: 4116, x2: 4130, y2: Y, z2: 4116 }
const AREA = [4080, 4064, 4191, 4143]
const VIEW = '4134.5 81 4110.5 180 20'

const OAK = 'minecraft:oak_log[axis=y]'
const STRIPPED = 'minecraft:stripped_oak_log[axis=y]'
const BIRCH = 'minecraft:birch_log[axis=y]'
const ACACIA = 'minecraft:acacia_log[axis=y]'
const LIT = 'minecraft:redstone_lamp[lit=true]'
const UNLIT = 'minecraft:redstone_lamp[lit=false]'
const STONE = 'minecraft:stone'
const AIR = 'minecraft:air'
const CHEST = 'minecraft:chest[facing=north,type=single,waterlogged=false]'
const CHEST_DATA = '{Items:[{Slot:0b,id:"minecraft:diamond",count:3}]}'
const SEED = [OAK, BIRCH, LIT, ACACIA]
const SOURCE_ROW = [OAK, ACACIA, CHEST, LIT]
const ANCHOR_ROW = Array(WIDTH).fill(OAK)
const fill = state => Array(WIDTH).fill(state)

// The fixture: these blocks carry a holder showing an emerald display, acacia's 0.25 up, and a lamp
// shows as blue wool to a client with the server pack and red wool to one without.
const HOLDERS = new Set(['oak_log', 'stripped_oak_log', 'birch_log', 'acacia_log', 'jungle_log', 'chest'].map(id => `minecraft:${id}`))
const EMERALD = 'minecraft:emerald_block'
const blockId = state => state.split('[')[0]
const seen = (state, pack) => blockId(state) === 'minecraft:redstone_lamp'
  ? (pack ? 'minecraft:blue_wool' : 'minecraft:red_wool') : state
// shows: what this client makes of an emerald block display.
const displaysOf = (states, shows) => states.flatMap((state, i) => HOLDERS.has(blockId(state))
  ? [`${i + 0.5},${blockId(state) === 'minecraft:acacia_log' ? 0.75 : 0.5},0.5|${shows}`] : []).sort()
const wideDisplays = (state, shows) => {
  if (!HOLDERS.has(blockId(state))) return []
  const out = []
  for (let dz = 0; dz <= WIDE.z2 - WIDE.z1; dz++) for (let dx = 0; dx <= WIDE.x2 - WIDE.x1; dx++) out.push(`${dx + 0.5},0.5,${dz + 0.5}|${shows}`)
  return out.sort()
}
const corners = box => [box.x1, box.y1, box.z1, box.x2, box.y2, box.z2]
const shown = view => view.displays.map(({ at, shows }) => `${at}|${shows}`).sort()
const ids = view => view.displays.map(({ id }) => id).sort((a, b) => a - b)
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b)

module.exports = ({ rig, check, paced }) => {
  const fixtureOutput = path.join(rig.root, 'polymer-fixture')
  const packName = 'fwp' + rig.token.replaceAll('-', '').slice(0, 12)
  const model = { native: fill(AIR), bulk: fill(AIR) }
  let wide = null
  let player, watcher
  // Per watcher, the display ids each anchor showed when first observed this session.
  let anchorIds = {}
  let sections = 0
  // Per watcher, how it shows a vanilla emerald block display.
  const reference = {}

  const rows = () => [[NATIVE, model.native], [NATIVE_MIRROR, model.native], [BULK, model.bulk],
    [BULK_MIRROR, model.bulk], [NATIVE_ANCHOR, ANCHOR_ROW], [BULK_ANCHOR, ANCHOR_ROW], [SOURCE, SOURCE_ROW]]
  const edited = new Set([NATIVE, NATIVE_MIRROR, BULK, BULK_MIRROR].map(box => box.name))
  const pairs = [[NATIVE, NATIVE_MIRROR], [BULK, BULK_MIRROR]]
  const watchers = () => [['plain', player, false], ['pack', watcher, true]]

  const serverProblems = async send => {
    const problems = []
    for (const [box, states] of rows()) {
      const wrong = await differences(send, { box: corners(box), expect: x => states[x - box.x1] })
      if (wrong.length) problems.push(`server ${box.name} ${JSON.stringify(wrong)}`)
      for (let i = 0; i < WIDTH; i++) {
        if (states[i] === CHEST && !await holdsItem(send, [box.x1 + i, Y, box.z1], 0, 'diamond', 3)) {
          problems.push(`server ${box.name}[${i}] lost its chest items`)
        }
      }
    }
    if (wide) {
      const wrong = await differences(send, { box: corners(WIDE), expect: () => wide })
      if (wrong.length) problems.push(`server wide ${JSON.stringify(wrong)}`)
    }
    return problems
  }

  const viewProblems = async () => {
    const problems = []
    const views = {}
    const boxes = [...rows().map(([box]) => box), ...(wide ? [WIDE] : [])]
    for (const [label, client, pack] of watchers()) {
      const view = await client.send({ action: 'region', boxes })
      if (!view.inWorld) {
        problems.push(`${label} is not in the world`)
        continue
      }
      views[label] = view.boxes
      for (const [box, states] of rows()) {
        const area = view.boxes[box.name]
        if (!area.loaded) {
          problems.push(`${label} lacks ${box.name}`)
          continue
        }
        area.cells.forEach((cell, i) => {
          const expected = seen(states[i], pack)
          if (canonical(cell.state) !== canonical(expected)) problems.push(`${label} ${box.name}[${i}] ${cell.state}, expected ${expected}`)
          if (states[i] === LIT && cell.lightAbove !== 14) problems.push(`${label} ${box.name}[${i}] light above ${cell.lightAbove}`)
          if ((cell.blockEntity !== undefined) !== (blockId(states[i]) === 'minecraft:chest')) {
            problems.push(`${label} ${box.name}[${i}] block entity ${cell.blockEntity !== undefined}`)
          }
        })
        if (edited.has(box.name) && !states.includes(LIT) && area.cells.some(cell => cell.lightAbove !== 0)) {
          problems.push(`${label} ${box.name} stale light ${area.cells.map(cell => cell.lightAbove)}`)
        }
        if (!same(shown(area), displaysOf(states, reference[label]))) {
          problems.push(`${label} ${box.name} displays ${shown(area)}, expected ${displaysOf(states, reference[label])}`)
        }
      }
      // A vanilla-made row is the reference for what the model leaves open, such as light beside a lamp.
      const comparable = area => area.cells.map(({ at, state, light, lightAbove, blockEntity }) => ({ at, state, light, lightAbove, blockEntity }))
      for (const [fawe, vanilla] of pairs) {
        if (!same(comparable(view.boxes[fawe.name]), comparable(view.boxes[vanilla.name]))) problems.push(`${label} ${fawe.name} cells differ from ${vanilla.name}`)
        if (!same(shown(view.boxes[fawe.name]), shown(view.boxes[vanilla.name]))) problems.push(`${label} ${fawe.name} displays differ from ${vanilla.name}`)
      }
      // Identities are taken from the first complete view of each anchor.
      for (const anchor of [NATIVE_ANCHOR, BULK_ANCHOR]) {
        const area = view.boxes[anchor.name]
        anchorIds[label] ??= {}
        if (!anchorIds[label][anchor.name] && same(shown(area), displaysOf(ANCHOR_ROW, reference[label]))) anchorIds[label][anchor.name] = ids(area)
        const first = anchorIds[label][anchor.name]
        if (first && !same(ids(area), first)) problems.push(`${label} ${anchor.name} holders replaced: ${ids(area)}`)
      }
      if (wide) {
        const area = view.boxes.wide
        if (!area.loaded) problems.push(`${label} lacks wide`)
        else if (!same(shown(area), wideDisplays(wide, reference[label]))) problems.push(`${label} wide shows ${area.displays.length} displays`)
        else if (area.cells.some(cell => canonical(cell.state) !== canonical(wide))) problems.push(`${label} wide cells`)
      }
    }
    return { problems, views }
  }

  // Chunk, light and entity packets arrive over several ticks: retry until every oracle holds.
  const settle = async (send, timeoutMs = 30000) => {
    let last = { problems: ['never observed'] }
    try {
      await rig.wait('polymer views settled', async () => {
        const server = await serverProblems(send)
        const view = await viewProblems()
        last = { problems: [...server, ...view.problems], views: view.views }
        return last.problems.length === 0
      }, timeoutMs, [player.entry, watcher.entry])
    } catch (error) {
      if (!/^Timeout waiting for/.test(error.message)) throw error
    }
    return last
  }
  // installs: whole-section installs this step should add; while Polymer is loaded only a Polymer
  // token allows one, so they count the edits that took Polymer's bulk route.
  const step = async (send, name, { installs = 0, timeoutMs } = {}) => {
    const result = await settle(send, timeoutMs)
    const now = (await probe(send, 'statistics')).sections
    const moved = now - sections
    sections = now
    check(`step-${name}`, result.problems.length === 0 && moved === installs,
      { problems: result.problems.slice(0, 12), sectionInstalls: moved, expectedInstalls: installs })
    return result.views ?? {}
  }
  const kept = (name, before, after, box) => check(name, watchers().every(([label]) =>
    before[label] && after[label] && same(ids(before[label][box.name]), ids(after[label][box.name])) &&
    ids(after[label][box.name]).length > 0),
  Object.fromEntries(watchers().map(([label]) => [label, [before[label] && ids(before[label][box.name]), after[label] && ids(after[label][box.name])]])))

  const setRow = async (send, box, states) => {
    for (let i = 0; i < WIDTH; i++) {
      const data = states[i] === CHEST ? CHEST_DATA : ''
      await send(`setblock ${box.x1 + i} ${Y} ${box.z1} ${states[i]}${data} strict`)
    }
  }
  const fillRow = (send, box, state, strict = true) =>
    send(`fill ${box.x1} ${Y} ${box.z1} ${box.x2} ${Y} ${box.z2} ${state}${strict ? ' strict' : ''}`)
  const publications = async (send, since) => {
    const own = (await probe(send, `timeline ${since}`)).publications.filter(entry => WIDE_CHUNKS.has(`${entry.chunkX},${entry.chunkZ}`))
    return { units: own.length, applied: own.reduce((sum, entry) => sum + entry.applied, 0),
      meanMs: own.length ? own.reduce((sum, entry) => sum + entry.nanos, 0) / own.length / 1e6 : 0,
      maxMs: Math.max(0, ...own.map(entry => entry.nanos / 1e6)) }
  }
  // A holder must look as a vanilla emerald display looks to the same client. The fixture's lamp
  // overlay moves the lamp's states to the end of Polymer's id table, so every later vanilla id
  // reaches every client shifted; the reference is observed, not assumed.
  const observeReference = async (send, suffix) => {
    const seenBy = {}
    await rig.wait('vanilla display data', async () => {
      for (const [label, client] of watchers()) {
        const area = (await client.send({ action: 'region', boxes: [PLAIN_DISPLAY] })).boxes?.[PLAIN_DISPLAY.name]
        seenBy[label] = area?.displays.map(display => display.shows) ?? []
      }
      return Object.values(seenBy).every(list => list.length === 1 && list[0] !== AIR)
    }, 30000, [player.entry, watcher.entry]).catch(() => {})
    for (const [label] of watchers()) reference[label] = seenBy[label]?.[0]
    const server = await send(`data get entity @e[type=minecraft:block_display,x=${PLAIN_DISPLAY.x1}.5,y=${Y}.5,` +
      `z=${PLAIN_DISPLAY.z1}.5,distance=..0.1,limit=1] block_state`)
    check(`vanilla-display-reference${suffix}`, /emerald_block/.test(server) && watchers().every(([label]) =>
      reference[label] && reference[label] !== AIR && reference[label] === reference.plain), { server, seen: seenBy })
  }
  const startWatcher = async (label, send) => {
    const port = Number(/^server-port=(\d+)$/m.exec(fs.readFileSync(path.join(rig.root, 'server/server.properties'), 'utf8'))[1])
    watcher = await startClient(rig, repo, { name: packName, port, pack: true, label })
    await send(`tp ${packName} ${VIEW}`)
  }

  return {
    jvmArgs: ['-Dfawe.experimental.coordinated=true', `-Dpolymer.bulk.output=${fixtureOutput}`],
    clientOptions: { pack: false },
    async configureServer (serverDir) {
      // S7's auto-host settings: the pack is offered to everyone and required of no one.
      fs.mkdirSync(path.join(serverDir, 'config/polymer'), { recursive: true })
      fs.writeFileSync(path.join(serverDir, 'config/polymer/auto-host.json'), JSON.stringify({ enabled: true, required: false,
        mod_override: false, resource_pack_status_dialog: false, allow_joining_after_generation_failure: false }))
      fs.mkdirSync(fixtureOutput, { recursive: true })
    },
    async login (rig, send) {
      await startWatcher('pack', send)
    },
    async contract (rig, send, name, { client, chatCommand: sendChatCommand }) {
      player = client
      const chatCommand = paced(send, client, sendChatCommand)
      const select = async box => {
        await chatCommand(`/pos1 ${box.x1},${box.y1},${box.z1}`, /First position set|Position already set/)
        await chatCommand(`/pos2 ${box.x2},${box.y2},${box.z2}`, /Second position set|Position already set/)
      }
      const view = async () => {
        await send(`tp ${name} ${VIEW}`)
        await send(`tp ${packName} ${VIEW}`)
      }
      const api = async (route, block, box) => {
        const id = (await probe(send, `api ${route} ${name} ${block} ${corners(box).join(' ')}`)).id
        await rig.wait(`API ${route} edit`, async () => (await probe(send, `api result ${id}`)).done, 60000, [client.entry])
        return probe(send, `api result ${id}`)
      }
      await send(`forceload add ${AREA.join(' ')}`)
      // A floor under each row, and under the cell south of it where the player stands to copy and paste.
      for (const box of [NATIVE, NATIVE_MIRROR, BULK, BULK_MIRROR, SOURCE]) {
        await send(`fill ${box.x1} 79 ${box.z1} ${box.x2} 79 ${box.z1 + 1} minecraft:stone strict`)
      }
      await probe(send, `watch ${AREA[0]} 0 ${AREA[1]} ${AREA[2]} 255 ${AREA[3]}`)
      const coverageBefore = await probe(send, 'coverage')
      sections = (await probe(send, 'statistics')).sections
      await view()

      // Seeded the vanilla way, so the oracles are proven before any FAWE edit.
      await setRow(send, SOURCE, SOURCE_ROW)
      for (const anchor of [NATIVE_ANCHOR, BULK_ANCHOR]) await setRow(send, anchor, ANCHOR_ROW)
      for (const box of [NATIVE, NATIVE_MIRROR, BULK, BULK_MIRROR]) await setRow(send, box, SEED)
      // 26.3 writes a block state as its string.
      await send(`summon minecraft:block_display ${PLAIN_DISPLAY.x1}.5 ${Y}.5 ${PLAIN_DISPLAY.z1}.5 {block_state:"${EMERALD}"}`)
      await observeReference(send, '')
      model.native = [...SEED]
      model.bulk = [...SEED]
      await step(send, 'seed')

      // Ordinary commands: native chunk units through Polymer's per-cell lifecycle, never a token.
      const native = async (command, response, mirror, states) => {
        await select(NATIVE)
        await chatCommand(command, response)
        await mirror()
        model.native = states
      }
      const DONE = /Operation completed/
      await native('/set stone', DONE, () => fillRow(send, NATIVE_MIRROR, STONE), fill(STONE))
      await step(send, 'native-set-stone')
      await native('/set oak_log', DONE, () => fillRow(send, NATIVE_MIRROR, 'minecraft:oak_log'), fill(OAK))
      const oak = await step(send, 'native-set-oak')
      // Polymer asks the holder whether it stays across a block change; the fixture's does.
      await native('/set stripped_oak_log', DONE, () => fillRow(send, NATIVE_MIRROR, 'minecraft:stripped_oak_log'), fill(STRIPPED))
      const stripped = await step(send, 'native-set-stripped')
      kept('native-holders-kept', oak, stripped, NATIVE)
      // Birch's holder ticks and stripped oak's does not, so each holder is replaced.
      await native('/replace stripped_oak_log birch_log', /blocks? (?:have been replaced|replaced)|Operation completed/,
        () => send(`fill ${NATIVE_MIRROR.x1} ${Y} ${NATIVE_MIRROR.z1} ${NATIVE_MIRROR.x2} ${Y} ${NATIVE_MIRROR.z2} minecraft:birch_log replace minecraft:stripped_oak_log strict`),
        fill(BIRCH))
      const birch = await step(send, 'native-replace-ticking')
      check('native-ticking-holders-replaced', watchers().every(([label]) => stripped[label] && birch[label] &&
        ids(birch[label].native).length === WIDTH && ids(birch[label].native).every(id => !ids(stripped[label].native).includes(id))),
      Object.fromEntries(watchers().map(([label]) => [label, [stripped[label] && ids(stripped[label].native), birch[label] && ids(birch[label].native)]])))
      // Lit lamps stay lit without neighbour updates; their light reaches each client by Polymer.
      await native('/set redstone_lamp[lit=true]', DONE, () => fillRow(send, NATIVE_MIRROR, LIT), fill(LIT))
      await step(send, 'native-lit-lamps')

      // WorldEdit's copy and paste against vanilla clone: an offset holder, a chest and a lit lamp.
      const stand = box => send(`tp ${name} ${box.x1}.5 ${Y} ${box.z1 + 1}.5 0 0`)
      await stand(SOURCE)
      await select(SOURCE)
      await chatCommand('/copy', /copied|\d+ blocks affected/)
      await stand(NATIVE)
      await chatCommand('/paste', /pasted|\d+ blocks affected/)
      await view()
      const cloneSource = () => send(`clone ${SOURCE.x1} ${Y} ${SOURCE.z1} ${SOURCE.x2} ${Y} ${SOURCE.z2} ${NATIVE_MIRROR.x1} ${Y} ${NATIVE_MIRROR.z1} strict`)
      await cloneSource()
      model.native = [...SOURCE_ROW]
      await step(send, 'native-paste')
      await chatCommand('/undo', /Undid/)
      await fillRow(send, NATIVE_MIRROR, LIT)
      model.native = fill(LIT)
      await step(send, 'native-undo')
      await chatCommand('/redo', /Redid/)
      await cloneSource()
      model.native = [...SOURCE_ROW]
      await step(send, 'native-redo')
      // The first two cells stacked once east, over the chest and the lamp.
      await select({ ...NATIVE, x2: NATIVE.x1 + 1 })
      await chatCommand('/stack 1 east', /blocks changed|Operation completed/)
      await send(`clone ${NATIVE_MIRROR.x1} ${Y} ${NATIVE_MIRROR.z1} ${NATIVE_MIRROR.x1 + 1} ${Y} ${NATIVE_MIRROR.z1} ${NATIVE_MIRROR.x1 + 2} ${Y} ${NATIVE_MIRROR.z1} strict`)
      model.native = [OAK, ACACIA, OAK, ACACIA]
      await step(send, 'native-stack')
      // Neighbour updates run after the unit is written; an unpowered lit lamp then turns itself off,
      // as one placed by a non-strict vanilla fill does.
      await chatCommand('/perf neighbors on', /set to|is already/)
      await native('/set redstone_lamp[lit=true]', DONE, () => fillRow(send, NATIVE_MIRROR, LIT, false), fill(UNLIT))
      await chatCommand('/perf neighbors off', /set to|is already/)
      await step(send, 'native-neighbors-unlit')

      // Two chunk units, each edit from or to air: 512 plain cells placed and removed, then 512
      // holders. Owner-thread time per unit, so the holder lifecycle's cost shows beside plain cells.
      const timed = {}
      for (const [label, block, state] of [['plain', 'stone', STONE], ['plain-removed', 'air', AIR],
        ['holders', 'oak_log', OAK], ['holders-removed', 'air', AIR]]) {
        const since = (await probe(send, 'timeline 2147483647')).tick
        await select(WIDE)
        await chatCommand(`/set ${block}`, DONE)
        wide = state
        await step(send, `wide-${label}`, { timeoutMs: 60000 })
        timed[label] = await publications(send, since)
      }
      check('wide-units', Object.values(timed).every(entry => entry.units === 2 && entry.applied === 512), timed)
      wide = null

      // The bulk route on the second row: coordinated and API bulk edits of plain blocks take a
      // Polymer token; an API session takes native units.
      await select(BULK)
      await chatCommand('fawe-coordinated set stone', /Coordinated edit complete: 4 changes/)
      await fillRow(send, BULK_MIRROR, STONE)
      model.bulk = fill(STONE)
      await step(send, 'coordinated-set-stone', { installs: 1 })
      await chatCommand('fawe-coordinated set oak_log', /Coordinated edit complete: 4 changes/)
      await fillRow(send, BULK_MIRROR, 'minecraft:oak_log')
      model.bulk = fill(OAK)
      const bulkOak = await step(send, 'coordinated-set-oak', { installs: 1 })
      await chatCommand('fawe-coordinated set stripped_oak_log', /Coordinated edit complete: 4 changes/)
      await fillRow(send, BULK_MIRROR, 'minecraft:stripped_oak_log')
      model.bulk = fill(STRIPPED)
      const bulkStripped = await step(send, 'coordinated-set-stripped', { installs: 1 })
      kept('coordinated-holders-kept', bulkOak, bulkStripped, BULK)
      const bulkLamp = await api('bulk', 'minecraft:redstone_lamp', BULK)
      await fillRow(send, BULK_MIRROR, UNLIT)
      model.bulk = fill(UNLIT)
      await step(send, 'api-bulk-lamps', { installs: 1 })
      const nativeAcacia = await api('native', 'minecraft:acacia_log', BULK)
      await fillRow(send, BULK_MIRROR, 'minecraft:acacia_log')
      model.bulk = fill(ACACIA)
      await step(send, 'api-native-acacia')
      check('api-results', !bulkLamp.error && !nativeAcacia.error, { bulkLamp, nativeAcacia })
      // A chest in the proposal sends the whole chunk per cell, never through a token.
      await setRow(send, BULK, [CHEST, ACACIA, ACACIA, ACACIA])
      await setRow(send, BULK_MIRROR, [CHEST, ACACIA, ACACIA, ACACIA])
      model.bulk = [CHEST, ACACIA, ACACIA, ACACIA]
      await step(send, 'bulk-chest-seeded')
      await chatCommand('fawe-coordinated set oak_log', /Coordinated edit complete: 4 changes/)
      await fillRow(send, BULK_MIRROR, 'minecraft:oak_log')
      model.bulk = fill(OAK)
      await step(send, 'coordinated-over-chest')

      // The fixture ignoring the recipient's pack must fail the view check, then recover. The row
      // holds unlit lamps, so lit ones are a change that reaches the clients.
      check('recipient-fault-armed', /bulk-ok recipientfault/.test(await send('bulktest recipientfault')), {})
      await native('/set redstone_lamp[lit=true]', DONE, () => fillRow(send, NATIVE_MIRROR, LIT), fill(LIT))
      const faulted = await settle(send, 10000)
      check('detects-recipient-fault', faulted.problems.length > 0 &&
        faulted.problems.every(problem => /^pack native(Mirror)?\[\d\] minecraft:red_wool, expected minecraft:blue_wool$/.test(problem)),
      faulted.problems.slice(0, 12))
      check('recipient-fault-restored', /bulk-ok recipientrestore/.test(await send('bulktest recipientrestore')), {})
      await native('/set stone', DONE, () => fillRow(send, NATIVE_MIRROR, STONE), fill(STONE))
      await step(send, 'after-recipient-fault')

      const coverage = delta(coverageBefore, await probe(send, 'coverage'))
      check('every-route-every-stage', ROUTES.every(([route, source]) =>
        (route === 'bulk' ? BULK_STAGES : NATIVE_STAGES).every(stage => coverage[`${route}.${source}.${stage}`] > 0)), coverage)
      const writes = await probe(send, 'writes')
      check('no-unguarded-writes', Object.keys(writes.counts).every(key => !/\.unguarded\.|offthread/.test(key)), writes)
      await send('save-all flush')
    },
    async beforeStop () {
      await rig.stop(watcher.entry)
    },
    async restart (rig, send, name, { client }) {
      player = client
      anchorIds = {}
      await send(`forceload add ${AREA.join(' ')}`)
      await startWatcher('pack-restart', send)
      await send(`tp ${name} ${VIEW}`)
      sections = (await probe(send, 'statistics')).sections
      await observeReference(send, '-after-restart')
      await step(send, 'after-restart', { timeoutMs: 60000 })
      // A value planted against the model is found at exactly that cell, on the server and in both views.
      await send(`setblock ${NATIVE.x1} ${Y} ${NATIVE.z1} minecraft:dirt strict`)
      const planted = await differences(send, { box: corners(NATIVE), expect: x => model.native[x - NATIVE.x1] })
      const seenPlanted = await settle(send, 10000)
      check('detects-known-wrong', planted.length === 1 && planted[0].x === NATIVE.x1 && planted[0].actual === 'minecraft:dirt' &&
        ['plain', 'pack'].every(label => seenPlanted.problems.includes(`${label} native[0] minecraft:dirt, expected ${STONE}`)),
      { planted, seen: seenPlanted.problems.slice(0, 12) })
      await send(`setblock ${NATIVE.x1} ${Y} ${NATIVE.z1} ${STONE} strict`)
      await step(send, 'after-planted')
      await rig.stop(watcher.entry)
    }
  }
}
