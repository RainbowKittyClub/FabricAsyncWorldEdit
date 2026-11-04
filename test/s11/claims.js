'use strict'

// S11 claims suite: real commands and API sessions over OPAC claims at their defaults. The player
// owns some chunks and a stranger others; each case's expected world is computed here and compared
// before and after a restart.

const { probe, differences, delta } = require('./common')

const NATIVE_STAGES = ['snapshot', 'admitted', 'prepared', 'authorized', 'published', 'reconciled', 'recorded']
const BULK_STAGES = ['ticket', ...NATIVE_STAGES]
const ROUTES = [['native', 'PLAYER'], ['native', 'API'], ['bulk', 'PLAYER'], ['bulk', 'API']]
const AREA = 'forceload add 5952 5952 6047 6015'
// Chunk x 372 the player's, 373 the stranger's, 374 nobody's; chunk z 372 throughout.
const OWN = 372
const STRANGER = 373
const ROW = [5956, 80, 5956, 5995, 80, 5959]
const ROW_TOGGLE = [5975, 80, 5957]
const API_NATIVE = [5964, 82, 5956, 5971, 82, 5957]
const API_BULK = [5964, 84, 5956, 5971, 84, 5957]
const COORDINATED = [5964, 86, 5956, 5971, 86, 5957]
// The player's chunk 373,374 ringed by eight of the stranger's.
const RING_CENTRE = [373, 374]
const RING = [5964, 80, 5980, 5987, 80, 6003]
// Each passes to the stranger after the player's edit: undone in 375,372, redone in 375,373.
const UNDONE = [6004, 80, 5956, 6007, 80, 5959]
const REDONE = [6004, 80, 5972, 6007, 80, 5975]
// Water behind a wall in the player's chunk 376, a channel on into the stranger's 377: released
// once by the edit and once by vanilla, rows z 5956 and 5962.
const WATER_EDIT = 5956
const WATER_VANILLA = 5962

const inStranger = x => x >> 4 === STRANGER
const cellCount = ([x1, y1, z1, x2, y2, z2]) => (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1)
const ownCells = box => cellCount([box[0], box[1], box[2], STRANGER * 16 - 1, box[4], box[5]])

const CASES = [
  { name: 'adjacent-owners', box: ROW, expect: (x, y, z) => x === ROW_TOGGLE[0] && z === ROW_TOGGLE[2]
    ? ['minecraft:stone', 'minecraft:glass'] : inStranger(x) ? 'minecraft:air' : 'minecraft:glowstone' },
  { name: 'api-native', box: API_NATIVE, expect: x => inStranger(x) ? 'minecraft:air' : 'minecraft:birch_log[axis=y]' },
  { name: 'api-bulk', box: API_BULK, expect: x => inStranger(x) ? 'minecraft:air' : 'minecraft:redstone_lamp[lit=false]' },
  { name: 'coordinated', box: COORDINATED, expect: x => inStranger(x) ? 'minecraft:air' : 'minecraft:glowstone' },
  { name: 'ring', box: RING, expect: (x, y, z) => x >> 4 === RING_CENTRE[0] && z >> 4 === RING_CENTRE[1]
    ? 'minecraft:gold_block' : 'minecraft:air' },
  { name: 'undo-denied', box: UNDONE, expect: () => 'minecraft:diamond_block' },
  { name: 'redo-denied', box: REDONE, expect: () => 'minecraft:air' }
]

module.exports = ({ rig, check, paced }) => {
  const receipts = async (send, words) => JSON.parse(await send(`fawes10 receipts${words ? ` ${words}` : ''}`))
  const totals = list => list.reduce((sum, receipt) => ({ applied: sum.applied + receipt.applied,
    denied: sum.denied + receipt.denied }), { applied: 0, denied: 0 })
  const count = async (send, box, block) => JSON.parse(await send(`fawes9 count ${box.join(' ')} minecraft:${block}`)).count
  const holds = async (send, x, y, z, block) => (await send(`execute if block ${x} ${y} ${z} minecraft:${block}`)) === 'Test passed'
  const now = async send => (await probe(send, 'timeline 2147483647')).tick
  const verifyAll = async (send, suffix) => {
    for (const entry of CASES) {
      const wrong = await differences(send, entry)
      check(`${entry.name}${suffix}`, wrong.length === 0, wrong)
    }
  }

  return {
    async contract (rig, send, name, { client, chatCommand: sendChatCommand }) {
      const chatCommand = paced(send, client, sendChatCommand)
      const select = async (x1, y1, z1, x2, y2, z2) => {
        await chatCommand(`/pos1 ${x1},${y1},${z1}`, /First position set|Position already set/)
        await chatCommand(`/pos2 ${x2},${y2},${z2}`, /Second position set|Position already set/)
      }
      const claim = (who, chunkX, chunkZ) => send(`s6probe claim-at ${name} ${who} ${chunkX} ${chunkZ}`)
      const passTicks = async ticks => {
        const until = (await now(send)) + ticks
        await rig.wait(`${ticks} ticks`, async () => (await now(send)) >= until, 20000, [client.entry])
      }
      await send(AREA)
      await send('fill 5952 79 5952 6047 79 6015 minecraft:stone')
      await send('fill 5952 80 5952 6047 90 6015 minecraft:air')
      await claim('owner', OWN, 372)
      await claim('stranger', STRANGER, 372)
      await claim('none', 374, 372)
      await claim('owner', ...RING_CENTRE)
      for (let cx = 372; cx <= 374; cx++) {
        for (let cz = 373; cz <= 375; cz++) if (cx !== RING_CENTRE[0] || cz !== RING_CENTRE[1]) await claim('stranger', cx, cz)
      }
      for (const [cx, cz] of [[375, 372], [375, 373], [376, 372]]) await claim('owner', cx, cz)
      await claim('stranger', 377, 372)
      await send(`tp ${name} 5958.5 80 5966.5`)
      // The write oracle counts only inside this box.
      await probe(send, 'watch 5952 0 5952 6047 255 6015')
      const before = await probe(send, 'coverage')

      // Own, stranger and unclaimed chunks in one edit, a stranger cell flipped every tick meanwhile.
      await probe(send, `toggle ${ROW_TOGGLE.join(' ')}`)
      await receipts(send, 'clear')
      let since = await now(send)
      await select(...ROW)
      await chatCommand('/set glowstone', /Operation completed/)
      await probe(send, 'toggle off')
      const row = totals((await receipts(send)).receipts)
      const strangerCells = cellCount(ROW) / 40 * 16
      check('adjacent-owners-receipts', row.denied === strangerCells && row.applied === cellCount(ROW) - strangerCells, row)
      const rowUnits = (await probe(send, `timeline ${since}`)).publications.filter(entry => entry.chunkZ === 372)
      check('adjacent-owners-no-conflict', rowUnits.length === 3 && rowUnits.every(entry => !/CONFLICT|FAILED/.test(entry.outcome)),
        rowUnits.map(({ chunkX, outcome, applied }) => ({ chunkX, outcome, applied })))

      // The player's chunk inside the stranger's: only its own cells change.
      await select(...RING)
      await chatCommand('/set gold_block', /Operation completed/)

      // API sessions, native and coordinated, and the coordinated command, over own and stranger cells.
      await receipts(send, 'clear')
      const nativeId = (await probe(send, `api native ${name} minecraft:birch_log ${API_NATIVE.join(' ')}`)).id
      await rig.wait('API native edit', async () => (await probe(send, `api result ${nativeId}`)).done, 60000, [client.entry])
      const apiReceipts = (await receipts(send)).receipts
      check('api-native-denied', apiReceipts.every(receipt => receipt.source === 'API') &&
        totals(apiReceipts).denied === ownCells(API_NATIVE) && totals(apiReceipts).applied === ownCells(API_NATIVE),
      { ...totals(apiReceipts), sources: [...new Set(apiReceipts.map(receipt => receipt.source))] })
      const bulkId = (await probe(send, `api bulk ${name} minecraft:redstone_lamp ${API_BULK.join(' ')}`)).id
      await rig.wait('API bulk edit', async () => (await probe(send, `api result ${bulkId}`)).done, 60000, [client.entry])
      await select(...COORDINATED)
      await client.send({ action: 'command', command: 'fawe-coordinated set glowstone' })
      await rig.wait('coordinated edit', async () => await count(send, COORDINATED, 'glowstone') >= ownCells(COORDINATED),
        30000, [client.entry]).catch(() => {})

      // Redo after the chunk passed to the stranger: refused, the cells stay as undone.
      await select(...REDONE)
      await chatCommand('/set diamond_block', /Operation completed/)
      await chatCommand('/undo', /Undid/)
      await claim('stranger', 375, 373)
      await receipts(send, 'clear')
      await chatCommand('/redo', /Redid/)
      const redo = totals((await receipts(send)).receipts)
      check('redo-denied-receipts', redo.denied === cellCount(REDONE) && redo.applied === 0, redo)
      // Undo after the chunk passed to the stranger: refused, the edit stays.
      await select(...UNDONE)
      await chatCommand('/set diamond_block', /Operation completed/)
      await claim('stranger', 375, 372)
      await receipts(send, 'clear')
      await chatCommand('/undo', /Undid/)
      const undo = totals((await receipts(send)).receipts)
      check('undo-denied-receipts', undo.denied === cellCount(UNDONE) && undo.applied === 0, undo)

      // Callbacks across the border: the edit's released water ends where vanilla's does.
      await send('fill 6026 80 5955 6037 82 5963 minecraft:stone')
      for (const z of [WATER_EDIT, WATER_VANILLA]) {
        await send(`setblock 6029 81 ${z} minecraft:water`)
        await send(`fill 6031 81 ${z} 6036 81 ${z} minecraft:air`)
      }
      await chatCommand('/perf neighbors on', /set to|is already/)
      await select(6030, 81, WATER_EDIT, 6030, 81, WATER_EDIT)
      await chatCommand('/set air', /Operation completed/)
      await chatCommand('/perf neighbors off', /set to|is already/)
      await send(`setblock 6030 81 ${WATER_VANILLA} minecraft:air`)
      await passTicks(60)
      const released = [WATER_EDIT, WATER_VANILLA].map(async z => holds(send, 6031, 81, z, 'water'))
      check('callback-released', (await Promise.all(released)).every(Boolean), {})
      const across = []
      for (let x = 6032; x <= 6036; x++) {
        across.push({ x, edit: await holds(send, x, 81, WATER_EDIT, 'water'), vanilla: await holds(send, x, 81, WATER_VANILLA, 'water') })
      }
      check('callback-border-like-vanilla', across.every(cell => cell.edit === cell.vanilla), across)

      // Publication cost with OPAC: 16 chunks of 1024 cells, the player's, the stranger's, nobody's.
      await send('forceload add 6400 6400 6591 6463')
      await send('fill 6400 79 6400 6591 79 6463 minecraft:stone')
      for (let cz = 400; cz <= 403; cz++) {
        for (let cx = 400; cx <= 411; cx++) await claim(cx < 404 ? 'owner' : cx < 408 ? 'stranger' : 'none', cx, cz)
      }
      const cost = {}
      for (const [label, x] of [['own', 6400], ['stranger', 6464], ['unclaimed', 6528]]) {
        await send(`fill ${x} 80 6400 ${x + 63} 83 6463 minecraft:air`)
        await select(x, 80, 6400, x + 63, 83, 6463)
        const since = await now(send)
        await chatCommand('/set stone', /Operation completed/)
        const units = (await probe(send, `timeline ${since}`)).publications
          .filter(entry => entry.chunkZ >= 400 && entry.chunkZ <= 403 && entry.chunkX >> 2 === x >> 6)
        const ms = units.map(entry => entry.nanos / 1e6)
        cost[label] = { units: units.length, applied: units.reduce((sum, entry) => sum + entry.applied, 0),
          meanMs: ms.reduce((sum, value) => sum + value, 0) / (ms.length || 1), maxMs: Math.max(0, ...ms) }
      }
      // One full 4096-cell unit in the player's chunk 412,400: the largest single-tick step.
      await send('forceload add 6592 6400 6607 6415')
      await send('fill 6592 79 6400 6607 79 6415 minecraft:stone')
      await send('fill 6592 80 6400 6607 95 6415 minecraft:air')
      await claim('owner', 412, 400)
      await select(6592, 80, 6400, 6607, 95, 6415)
      const sinceFull = await now(send)
      await chatCommand('/set stone', /Operation completed/)
      const full = (await probe(send, `timeline ${sinceFull}`)).publications.filter(entry => entry.chunkX === 412)
      cost.ownFullUnit = { units: full.length, applied: full.reduce((sum, entry) => sum + entry.applied, 0),
        ms: full.map(entry => entry.nanos / 1e6) }
      check('opac-unit-cost', cost.own.units === 16 && cost.own.applied === 16384 && cost.stranger.applied === 0 &&
        cost.unclaimed.applied === 16384 && cost.ownFullUnit.units === 1 && cost.ownFullUnit.applied === 4096, cost)

      await verifyAll(send, '')
      const coverage = delta(before, await probe(send, 'coverage'))
      check('every-route-every-stage', ROUTES.every(([route, source]) =>
        (route === 'bulk' ? BULK_STAGES : NATIVE_STAGES).every(stage => coverage[`${route}.${source}.${stage}`] > 0)), coverage)
      const writes = await probe(send, 'writes')
      check('no-unguarded-writes', Object.keys(writes.counts).every(key => !/\.unguarded\.|offthread/.test(key)), writes)
      await send('save-all flush')
    },
    async restart (rig, send) {
      await send(AREA)
      await verifyAll(send, '-after-restart')
      // The comparison fails on a value planted against it, at exactly that cell.
      await send('setblock 5956 80 5956 minecraft:dirt')
      const planted = await differences(send, CASES[0])
      check('detects-known-wrong', planted.length === 1 && planted[0].x === 5956 && planted[0].actual === 'minecraft:dirt', planted)
      await send('setblock 5956 80 5956 minecraft:glowstone')
    }
  }
}
