'use strict'

// S11 adapters suite (S10a): blocks of installed mods that declare their lifecycle to FAWE (diagonal
// walls first). Every FAWE route builds the same pattern as strict vanilla commands do, and the
// client must see the same cells and the same virtual arm displays, after removal, undo, redo and a
// restart. The vanilla-made rows are the reference; a deliberately wrong row proves the comparison
// can tell a missing arm from a right one.

const { canonical, probe, differences } = require('./common')

const Y = 80
const WALL = 'minecraft:cobblestone_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]'
const STONE = 'minecraft:stone'
// Walls at (0,0), (1,1) and (2,0) of a 3x2 box: two diagonals, no wall beside another.
const CELLS = [[0, 0], [1, 1], [2, 0]]
const box = (name, x1, z1) => ({ name, x1, y1: Y, z1, x2: x1 + 2, y2: Y, z2: z1 + 1 })
const Z = 7000
// 16 blocks apart: each row in its own chunk.
const MIRROR = box('mirror', 7000, Z)
const NATIVE = box('native', 7016, Z)
const STATIC_MIRROR = box('staticMirror', 7032, Z)
const COORDINATED = box('coordinated', 7048, Z)
const API_NATIVE = box('apiNative', 7064, Z)
const API_BULK = box('apiBulk', 7080, Z)
const WRONG = box('wrong', 7096, Z)
// Plain glass in a section of its own: the one place a direct install is expected.
const CONTROL = box('control', 7104, Z)
const ROWS = [MIRROR, NATIVE, STATIC_MIRROR, COORDINATED, API_NATIVE, API_BULK, WRONG]
const AREA = 'forceload add 6992 6992 7111 7015'
const VIEW = '7048.5 82 7004.5 180 20'

const corners = b => [b.x1, b.y1, b.z1, b.x2, b.y2, b.z2]
const cellAt = (b, [dx, dz]) => [b.x1 + dx, Y, b.z1 + dz]
const shown = area => area.displays.map(({ at, shows }) => `${at}|${shows}`).sort()
const states = area => area.cells.map(cell => canonical(cell.state))
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b)

module.exports = ({ rig, check, paced }) => {
  let client, chatCommand, select

  // What the client holds for these rows, retried until the packets have all arrived.
  const view = async () => {
    const answer = await client.send({ action: 'region', boxes: ROWS })
    return answer.inWorld ? answer.boxes : null
  }
  const settle = async (name, send, { placed = [], removed = [], pairs = [], armed = [], bare = [] }) => {
    let last = { problems: ['never observed'] }
    try {
      await rig.wait(`${name} views settled`, async () => {
        const problems = []
        for (const b of placed) if ((await differences(send, { box: corners(b), expect: (x, y, z) =>
          CELLS.some(c => cellAt(b, c).join() === [x, y, z].join()) ? WALL : undefined })).length) problems.push(`server ${b.name} is not the wall pattern`)
        for (const b of removed) if ((await differences(send, { box: corners(b), expect: () => undefined })).length) problems.push(`server ${b.name} is not empty`)
        const boxes = await view()
        if (!boxes) problems.push('client is not in the world')
        else {
          for (const [a, b] of pairs) {
            if (!same(states(boxes[a.name]), states(boxes[b.name]))) problems.push(`${a.name} cells differ from ${b.name}`)
            if (!same(shown(boxes[a.name]), shown(boxes[b.name]))) problems.push(`${a.name} displays ${shown(boxes[a.name])} differ from ${b.name} ${shown(boxes[b.name])}`)
          }
          for (const b of armed) if (!shown(boxes[b.name]).length) problems.push(`${b.name} shows no arm`)
          for (const b of bare) if (shown(boxes[b.name]).length) problems.push(`${b.name} still shows ${shown(boxes[b.name])}`)
        }
        last = { problems, boxes }
        return problems.length === 0
      }, 40000, [client.entry])
    } catch (error) {
      if (!/^Timeout waiting for/.test(error.message)) throw error
    }
    check(name, last.problems.length === 0, last.problems.slice(0, 6))
    return last.boxes
  }

  const vanillaWalls = async (send, b) => {
    for (const c of CELLS) await send(`setblock ${cellAt(b, c).join(' ')} minecraft:cobblestone_wall`)
  }
  const stones = async (send, b) => {
    for (const c of CELLS) await send(`setblock ${cellAt(b, c).join(' ')} ${STONE}`)
  }
  const apiWalls = async (send, name, route, b) => {
    for (const c of CELLS) {
      const at = cellAt(b, c).join(' ')
      const id = (await probe(send, `api ${route} ${name} minecraft:cobblestone_wall ${at} ${at}`)).id
      await rig.wait(`API ${route} wall`, async () => (await probe(send, `api result ${id}`)).done, 60000, [client.entry])
      const result = await probe(send, `api result ${id}`)
      if (result.error) throw new Error(`API ${route} edit failed: ${result.error}`)
    }
  }

  return {
    async contract (rig_, send, name, { client: playerClient, chatCommand: sendChatCommand }) {
      client = playerClient
      chatCommand = paced(send, client, sendChatCommand)
      select = async b => {
        await chatCommand(`/pos1 ${b.x1},${b.y1},${b.z1}`, /First position set|Position already set/)
        await chatCommand(`/pos2 ${b.x2},${b.y2},${b.z2}`, /Second position set|Position already set/)
      }
      await send(AREA)
      await send(`fill 6992 ${Y - 1} 6992 7111 ${Y + 3} 7015 minecraft:air`)
      await send(`tp ${name} ${VIEW}`)

      // Which blocks FAWE edits is now each mod's to declare.
      const lifecycle = await probe(send, 'lifecycle minecraft:cobblestone_wall minecraft:oak_fence minecraft:glass_pane minecraft:stone')
      const byBlock = Object.fromEntries(lifecycle.blocks.map(entry => [entry.block, entry]))
      check('declared-blocks-supported', byBlock['minecraft:cobblestone_wall'].supported && byBlock['minecraft:oak_fence'].supported &&
        byBlock['minecraft:glass_pane'].supported && lifecycle.declared > 0, lifecycle)
      check('declared-blocks-stay-per-cell', !byBlock['minecraft:cobblestone_wall'].direct && byBlock['minecraft:stone'].direct, lifecycle)

      // The references first: strict vanilla commands, the lifecycle every route must reproduce.
      await vanillaWalls(send, MIRROR)
      await vanillaWalls(send, STATIC_MIRROR)
      for (const c of CELLS.slice(0, 2)) await send(`setblock ${cellAt(WRONG, c).join(' ')} minecraft:cobblestone_wall`)

      // A native command: stone stands where the walls go, and one //replace turns it into walls.
      await stones(send, NATIVE)
      await select(NATIVE)
      await chatCommand('/replace stone cobblestone_wall', /3 blocks have been replaced/)
      // Coordinated, API native and API bulk edits of the same cells. A declared block must never be
      // installed into a section directly, which would skip the mod's hooks; the control shows the
      // counter does move for plain blocks.
      const installs = async () => (await probe(send, 'statistics')).sections
      const start = await installs()
      await stones(send, COORDINATED)
      await select(COORDINATED)
      // The command's arguments are brigadier words, which cannot hold a namespace colon.
      await chatCommand('fawe-coordinated replace stone cobblestone_wall', /Coordinated edit complete: 3 changes/)
      const afterCoordinated = await installs()
      await apiWalls(send, name, 'native', API_NATIVE)
      await apiWalls(send, name, 'bulk', API_BULK)
      const afterApi = await installs()
      check('declared-blocks-never-installed-directly', afterCoordinated === start && afterApi === start, { start, afterCoordinated, afterApi })
      await select(CONTROL)
      await chatCommand('fawe-coordinated set glass', /Coordinated edit complete: 6 changes/)
      check('plain-blocks-control-installs-a-section', await installs() === start + 1, { start, now: await installs() })

      const fawe = [NATIVE, COORDINATED, API_NATIVE, API_BULK]
      const placed = await settle('placed-match-vanilla', send, {
        placed: [MIRROR, STATIC_MIRROR, ...fawe],
        pairs: [[NATIVE, MIRROR], ...fawe.map(b => [b, STATIC_MIRROR])], armed: [MIRROR, STATIC_MIRROR, ...fawe]
      })
      check('mirror-shows-arms', placed && shown(placed.mirror).length > 0, placed && shown(placed.mirror))
      check('comparison-detects-a-missing-arm', placed && !same(shown(placed.wrong), shown(placed.staticMirror)), placed && shown(placed.wrong))

      // Removal: a native //set air against vanilla's fill; the arms go with the walls.
      await select(NATIVE)
      await chatCommand('/set air', /Operation completed/)
      await send(`fill ${corners(MIRROR).join(' ')} minecraft:air`)
      await settle('removed-match-vanilla', send, { removed: [MIRROR, NATIVE], pairs: [[NATIVE, MIRROR]], bare: [NATIVE, MIRROR] })
      // Undo brings walls and arms back; redo takes them away again.
      await chatCommand('/undo', /Undid/)
      await settle('undo-restores-arms', send, { placed: [NATIVE], pairs: [[NATIVE, STATIC_MIRROR]], armed: [NATIVE] })
      await chatCommand('/redo', /Redid/)
      await settle('redo-removes-arms', send, { removed: [NATIVE], pairs: [[NATIVE, MIRROR]], bare: [NATIVE] })
      await send('save-all flush')
    },
    async restart (rig_, send, name, { client: restartedClient }) {
      client = restartedClient
      await send(AREA)
      await send(`tp ${name} ${VIEW}`)
      // Reloaded chunks rebuild the arms from the world; none may be lost or left over.
      const fawe = [COORDINATED, API_NATIVE, API_BULK]
      await settle('restart-arms-rebuilt', send, {
        placed: [STATIC_MIRROR, ...fawe], removed: [NATIVE, MIRROR],
        pairs: [[NATIVE, MIRROR], ...fawe.map(b => [b, STATIC_MIRROR])], armed: [STATIC_MIRROR, ...fawe], bare: [NATIVE, MIRROR]
      })
    }
  }
}
