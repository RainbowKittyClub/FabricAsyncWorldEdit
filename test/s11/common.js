'use strict'

// Shared S11 helpers: expectations are computed by the lane and compared with dumped world states.

// Properties sorted, so property order never decides a match.
function canonical (state) {
  const match = /^([^[]+)(?:\[(.*)\])?$/.exec(state)
  if (!match || !match[2]) return state
  return `${match[1]}[${match[2].split(',').sort().join(',')}]`
}

// Cells of a box, y then z then x ascending: the probe's dump order.
function cells ([x1, y1, z1, x2, y2, z2]) {
  const out = []
  for (let y = y1; y <= y2; y++) for (let z = z1; z <= z2; z++) for (let x = x1; x <= x2; x++) out.push([x, y, z])
  return out
}

const probe = async (send, words) => JSON.parse(await send(`fawes11 ${words}`))

// Cells that differ from the expectation, at most eight. expect returns a state, a list of allowed
// states, or undefined for air.
async function differences (send, { box, expect, dimension }) {
  const runs = (await probe(send, `dump ${box.join(' ')}${dimension ? ` ${dimension}` : ''}`)).runs
  const states = runs.flatMap(([state, run]) => Array(run).fill(canonical(state)))
  const wrong = []
  cells(box).forEach(([x, y, z], index) => {
    const wanted = expect(x, y, z) ?? 'minecraft:air'
    const allowed = (Array.isArray(wanted) ? wanted : [wanted]).map(canonical)
    if (!allowed.includes(states[index]) && wrong.length < 8) wrong.push({ x, y, z, actual: states[index], expected: allowed })
  })
  return wrong
}

// Counters that moved between two coverage snapshots.
const delta = (before, after) => Object.fromEntries(Object.entries(after)
  .map(([key, value]) => [key, value - (before[key] ?? 0)]).filter(([, value]) => value !== 0))

// A block entity holds this item stack in this slot.
const holdsItem = async (send, [x, y, z], slot, id, count) => /^Test passed/.test(await send(
  `execute if data block ${x} ${y} ${z} Items[{Slot:${slot}b,id:"minecraft:${id}",count:${count}}]`))

module.exports = { canonical, cells, probe, differences, delta, holdsItem }
