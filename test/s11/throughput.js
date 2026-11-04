'use strict'

// S11a throughput suite: a session commits one chunk per unit, one unit per heartbeat. Units are
// read from the coordinator's publication timeline; world states are checked before and after a
// restart.

const { probe, differences } = require('./common')

const AREA = 'forceload add 5296 4992 5391 5087'
// 16 chunks of 16x4x16: one unit each.
const SET16 = [5296, 80, 4992, 5359, 83, 5055]
// One chunk of 5120 cells: over the 4096-cell unit limit.
const LARGE = [5360, 80, 4992, 5375, 99, 5007]
// Four such chunks: other chunks fill the heartbeats while one chunk's first unit relights.
const WIDE = [5296, 80, 5056, 5327, 99, 5087]
// A chunk with a block flipped every tick, outside the cells edited.
const TOGGLED = [5376, 80, 4992, 5383, 81, 4999]
const TOGGLE = [5390, 90, 5006]
const READ_BACK = [5385, 80, 5085]
// 64 cells of one chunk with neighbour updates on: one unit, its callbacks after its writes.
const ONE_CELL = [5328, 80, 5056, 5335, 80, 5063]
// Above SET16: 16 chunks with neighbour updates on, then undone.
const NEIGHBORS16 = [5296, 90, 4992, 5359, 91, 5055]
// Stone with two enclosed water sources; each loses the two wall cells east of it, one edit with
// neighbour updates on and one with them off.
const WALLS = 'fill 5344 80 5072 5355 82 5083 minecraft:stone'
const WATER_ON = [5346, 81, 5074]
const WATER_OFF = [5346, 81, 5080]
const OPENED_ON = [5347, 81, 5074, 5348, 81, 5074]
const OPENED_OFF = [5347, 81, 5080, 5348, 81, 5080]
// A stone pool; water is set along one edge of its floor with updates on.
const POOL = ['fill 5360 80 5072 5371 82 5083 minecraft:stone', 'fill 5361 80 5073 5370 81 5082 minecraft:air']
const POURED = [5361, 80, 5073, 5362, 80, 5073]
// A 16-chunk layer whose cell TARGET_TOGGLE is flipped every tick while the layer is set.
const TARGET16 = [5296, 95, 4992, 5359, 95, 5055]
const TARGET_TOGGLE = [5300, 95, 5000]

const chunksOf = ([x1, , z1, x2, , z2]) => {
  const out = new Set()
  for (let cx = x1 >> 4; cx <= x2 >> 4; cx++) for (let cz = z1 >> 4; cz <= z2 >> 4; cz++) out.add(`${cx},${cz}`)
  return out
}
const cellCount = ([x1, y1, z1, x2, y2, z2]) => (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1)

module.exports = ({ rig, check, paced }) => {
  const CASES = [
    { name: 'set16-undone', box: SET16, expect: () => undefined },
    { name: 'large', box: LARGE, expect: () => 'minecraft:glass' },
    { name: 'wide', box: WIDE, expect: () => 'minecraft:oak_planks' },
    { name: 'one-cell', box: ONE_CELL, expect: () => 'minecraft:stone' },
    { name: 'neighbors16-undone', box: NEIGHBORS16, expect: () => undefined },
    { name: 'toggled-target', box: TARGET16, expect: (x, y, z) => x === TARGET_TOGGLE[0] && z === TARGET_TOGGLE[2]
      ? ['minecraft:stone', 'minecraft:glass'] : 'minecraft:oak_planks' },
    { name: 'toggled', box: TOGGLED, expect: () => 'minecraft:glowstone' },
    { name: 'read-back', box: [...READ_BACK, ...READ_BACK], expect: () => 'minecraft:diamond_block' }
  ]
  const verifyAll = async (send, suffix) => {
    for (const entry of CASES) {
      const wrong = await differences(send, entry)
      check(`${entry.name}${suffix}`, wrong.length === 0, wrong)
    }
  }
  // The publications into these chunks since a tick, and a summary of them.
  const publications = async (send, since, box) => {
    const chunks = chunksOf(box)
    const all = (await probe(send, `timeline ${since}`)).publications
    const own = all.filter(entry => chunks.has(`${entry.chunkX},${entry.chunkZ}`))
    const ticks = own.map(entry => entry.tick).sort((a, b) => a - b)
    return {
      own,
      units: own.length,
      applied: own.reduce((sum, entry) => sum + entry.applied, 0),
      outcomes: [...new Set(own.map(entry => entry.outcome))],
      // Consecutive: one publication on every tick from the first to the last.
      consecutive: ticks.length > 0 && new Set(ticks).size === ticks.length && ticks.at(-1) - ticks[0] + 1 === ticks.length,
      ticks: ticks.length ? [ticks[0], ticks.at(-1)] : [],
      maxMs: Math.max(0, ...own.map(entry => entry.nanos / 1e6)),
      meanMs: own.length ? own.reduce((sum, entry) => sum + entry.nanos, 0) / own.length / 1e6 : 0
    }
  }
  const now = async send => (await probe(send, 'timeline 2147483647')).tick
  // Finalization after publication between two timing samples, in milliseconds.
  const lags = (before, after) => {
    const units = after.finished - before.finished
    const mean = key => units ? (after[key] - before[key]) / units / 1e6 : 0
    return { finished: units, finishMeanMs: mean('finishNanos'), historyMeanMs: mean('historyNanos'),
      maintenanceMeanMs: mean('maintenanceNanos'), maxFinishMs: after.maxFinishNanos / 1e6 }
  }
  // Waits for the units themselves; with `lenient`, a shortfall is left to the check.
  const settled = async (send, client, since, box, units, lenient) => {
    const waiting = rig.wait(`${units} units in ${box.join(',')}`, async () =>
      (await publications(send, since, box)).units >= units, 60000, [client.entry])
    await (lenient ? waiting.catch(() => {}) : waiting)
    return publications(send, since, box)
  }
  const passTicks = async (send, client, count) => {
    const until = (await now(send)) + count
    await rig.wait(`${count} ticks`, async () => (await now(send)) >= until, 20000, [client.entry])
  }
  const holds = async (send, [x, y, z], block) =>
    (await send(`execute if block ${x} ${y} ${z} minecraft:${block}`)) === 'Test passed'

  return {
    async contract (rig, send, name, { client, chatCommand: sendChatCommand }) {
      const chatCommand = paced(send, client, sendChatCommand)
      const select = async (x1, y1, z1, x2, y2, z2) => {
        await chatCommand(`/pos1 ${x1},${y1},${z1}`, /First position set|Position already set/)
        await chatCommand(`/pos2 ${x2},${y2},${z2}`, /Second position set|Position already set/)
      }
      await send(AREA)
      await send('fill 5296 79 4992 5391 79 5087 minecraft:stone')
      await send('fill 5296 80 4992 5391 99 5087 minecraft:air')
      await send(`tp ${name} 5380.5 80 5080.5`)

      // 16 chunks: 16 units on 16 consecutive ticks, nothing waiting at any heartbeat between.
      await select(...SET16)
      let since = await now(send)
      let timing = await probe(send, 'timing')
      await chatCommand('/set stone', /Operation completed/)
      // The reply waits for the edit: no unit publishes after the tick it was seen.
      const replied = await now(send)
      const set = await settled(send, client, since, SET16, 16)
      const afterSet = await probe(send, 'timing')
      const idle = afterSet.idleWithPending - timing.idleWithPending
      const unitsAfterReply = set.own.filter(entry => entry.tick > replied).length
      check('set16-reply-after-units', unitsAfterReply === 0, { replied, ticks: set.ticks, unitsAfterReply })
      check('set16-units', set.units === 16 && set.applied === cellCount(SET16) && set.outcomes.join() === 'COMPLETE',
        { ...set, own: undefined })
      check('set16-consecutive', set.consecutive && idle === 0, { ticks: set.ticks, idle, ...lags(timing, afterSet) })
      check('set16-world', (await differences(send, { box: SET16, expect: () => 'minecraft:stone' })).length === 0, {})
      check('publication-time-recorded', set.own.every(entry => entry.nanos > 0),
        { maxMs: set.maxMs, meanMs: set.meanMs, cellsPerUnit: cellCount(SET16) / 16 })

      // Undo is a session too: the same 16 units.
      since = await now(send)
      await chatCommand('undo', /Undid/)
      const undo = await settled(send, client, since, SET16, 16)
      check('undo16-units', undo.units === 16 && undo.applied === cellCount(SET16) && undo.consecutive &&
        undo.outcomes.join() === 'COMPLETE', { ...undo, own: undefined })

      // Over 4096 cells in one chunk: two units of it, the later admitted once the earlier finished.
      await select(...LARGE)
      since = await now(send)
      timing = await probe(send, 'timing')
      await chatCommand('/set glass', /Operation completed/)
      const large = await settled(send, client, since, LARGE, 2)
      const afterLarge = await probe(send, 'timing')
      check('large-units', large.units === 2 && large.applied === cellCount(LARGE) &&
        large.outcomes.join() === 'COMPLETE', { ...large, own: undefined, ...lags(timing, afterLarge) })

      // Four such chunks: 8 units on 8 consecutive ticks.
      await select(...WIDE)
      since = await now(send)
      timing = await probe(send, 'timing')
      await chatCommand('/set oak_planks', /Operation completed/)
      const wide = await settled(send, client, since, WIDE, 8)
      const afterWide = await probe(send, 'timing')
      check('wide-consecutive', wide.units === 8 && wide.applied === cellCount(WIDE) && wide.consecutive &&
        afterWide.idleWithPending === timing.idleWithPending && wide.outcomes.join() === 'COMPLETE',
      { ...wide, own: undefined, idle: afterWide.idleWithPending - timing.idleWithPending, ...lags(timing, afterWide) })

      // Neighbour updates on: one unit per chunk as without them, the callbacks after its writes.
      await chatCommand('/perf neighbors on', /set to|is already/)
      await select(...ONE_CELL)
      since = await now(send)
      timing = await probe(send, 'timing')
      await chatCommand('/set stone', /Operation completed/)
      const oneCell = await settled(send, client, since, ONE_CELL, 1)
      const afterOneCell = await probe(send, 'timing')
      check('neighbors-one-unit', oneCell.units === 1 && oneCell.applied === 64 && oneCell.outcomes.join() === 'COMPLETE',
        { ...oneCell, own: undefined, ...lags(timing, afterOneCell) })
      await select(...NEIGHBORS16)
      since = await now(send)
      await chatCommand('/set glass', /Operation completed/)
      const neighbors16 = await settled(send, client, since, NEIGHBORS16, 16)
      check('neighbors16-consecutive', neighbors16.units === 16 && neighbors16.applied === cellCount(NEIGHBORS16) &&
        neighbors16.consecutive && neighbors16.outcomes.join() === 'COMPLETE', { ...neighbors16, own: undefined })
      since = await now(send)
      await chatCommand('undo', /Undid/)
      const neighbors16Undo = await settled(send, client, since, NEIGHBORS16, 16)
      check('neighbors16-undo-consecutive', neighbors16Undo.units === 16 && neighbors16Undo.consecutive &&
        neighbors16Undo.applied === cellCount(NEIGHBORS16), { ...neighbors16Undo, own: undefined })

      // Water learns of a removed wall only through a neighbour update.
      await send(WALLS)
      await send(`setblock ${WATER_ON.join(' ')} minecraft:water`)
      await send(`setblock ${WATER_OFF.join(' ')} minecraft:water`)
      await select(...OPENED_ON)
      await chatCommand('/set air', /Operation completed/)
      await chatCommand('/perf neighbors off', /set to|is already/)
      await select(...OPENED_OFF)
      await chatCommand('/set air', /Operation completed/)
      await passTicks(send, client, 40)
      check('neighbors-wake-water', await holds(send, OPENED_ON.slice(3), 'water'), {})
      check('no-neighbors-water-stays', await holds(send, OPENED_OFF.slice(0, 3), 'air'), {})

      // Water set with updates on schedules its own flow, from every cell of the unit.
      for (const command of POOL) await send(command)
      await chatCommand('/perf update on', /set to|is already/)
      await select(...POURED)
      await chatCommand('/set water', /Operation completed/)
      await chatCommand('/perf update off', /set to|is already/)
      await passTicks(send, client, 40)
      check('update-water-flows', await holds(send, [POURED[3] + 2, POURED[4], POURED[5]], 'water') &&
        await holds(send, [POURED[0], POURED[1], POURED[2] + 2], 'water'), {})

      // A target cell flipped every tick: overwritten as found, never a conflict.
      await probe(send, `toggle ${TARGET_TOGGLE.join(' ')}`)
      await select(...TARGET16)
      since = await now(send)
      await client.send({ action: 'command', command: '/set oak_planks' })
      const target16 = await settled(send, client, since, TARGET16, 16, true)
      await probe(send, 'toggle off')
      check('toggled-target-no-conflict', target16.units === 16 && target16.applied === cellCount(TARGET16) &&
        target16.outcomes.join() === 'COMPLETE', { ...target16, own: undefined })

      // A block changing every tick elsewhere in the chunk is not a conflict.
      await probe(send, `toggle ${TOGGLE.join(' ')}`)
      await select(...TOGGLED)
      since = await now(send)
      await chatCommand('/set glowstone', /Operation completed/)
      const toggled = await settled(send, client, since, TOGGLED, 1)
      await probe(send, 'toggle off')
      check('toggled-no-conflict', toggled.units === 1 && toggled.applied === cellCount(TOGGLED) &&
        toggled.outcomes.join() === 'COMPLETE', { ...toggled, own: undefined })

      // An unflushed write is read back by its own session while the world still holds air.
      const id = (await probe(send, `ryw ${name} ${READ_BACK.join(' ')}`)).id
      await rig.wait('read-back session', async () => (await probe(send, `api result ${id}`)).done, 60000, [client.entry])
      const readBack = await probe(send, `api result ${id}`)
      check('reads-own-writes', !readBack.error && readBack.session === 'minecraft:diamond_block' &&
        readBack.sessionFull === 'minecraft:diamond_block' && readBack.world === 'minecraft:air' &&
        readBack.flushed === 'minecraft:diamond_block', readBack)

      await verifyAll(send, '')
      await send('save-all flush')
    },
    async restart (rig, send) {
      await send(AREA)
      await verifyAll(send, '-after-restart')
    }
  }
}
