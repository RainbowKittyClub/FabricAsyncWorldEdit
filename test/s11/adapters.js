'use strict'

// S11 adapters suite (S10a): blocks of installed mods that declare their lifecycle to FAWE. Every
// FAWE route builds the same pattern as strict vanilla commands do, and the client must see the
// same cells and the same effect of the block, after removal, undo, redo and a restart. The
// vanilla-made rows are the reference; a deliberately wrong row proves the comparison can tell a
// missing block from a right one. A subject is one declaring mod's block and what a client sees of
// it: diagonal walls draw arm displays, a player-portals frame block gives light a stand-in cannot.
// The portal block of the same mod declares nothing and must still be refused.

const { canonical, probe, differences } = require('./common')

const Y = 80
const STONE = 'minecraft:stone'
// Blocks at (0,0), (1,1) and (2,0) of a 3x2 box: two diagonals, no block beside another.
const CELLS = [[0, 0], [1, 1], [2, 0]]
const box = (name, x1, z1) => ({ name, x1, y1: Y, z1, x2: x1 + 2, y2: Y, z2: z1 + 1 })
const corners = b => [b.x1, b.y1, b.z1, b.x2, b.y2, b.z2]
const cellAt = (b, [dx, dz]) => [b.x1 + dx, Y, b.z1 + dz]
const states = area => area.cells.map(cell => canonical(cell.state))
const shown = area => area.displays.map(({ at, shows }) => `${at}|${shows}`).sort()
const lights = area => area.cells.map(cell => `${cell.light}/${cell.lightAbove}`)
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b)

const SUBJECTS = [
  {
    key: 'walls',
    block: 'minecraft:cobblestone_wall',
    state: 'minecraft:cobblestone_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]',
    z: 7000,
    // The arm displays a client holds.
    look: area => [...states(area), ...shown(area)],
    shows: area => shown(area).length > 0
  },
  {
    key: 'frames',
    block: 'playerportals:void_infused_deepslate',
    state: 'playerportals:void_infused_deepslate',
    z: 7032,
    // The stand-in gives no light of its own; the server's reaches the client by Polymer's resend.
    look: area => [...states(area), ...lights(area)],
    shows: area => area.cells.some(cell => cell.light > 0)
  }
]
// Sixteen blocks apart: each row in its own chunk.
const rowsOf = subject => {
  const row = (label, x) => box(`${subject.key}-${label}`, x, subject.z)
  const rows = {
    mirror: row('mirror', 7000), native: row('native', 7016), staticMirror: row('staticMirror', 7032),
    coordinated: row('coordinated', 7048), apiNative: row('apiNative', 7064), apiBulk: row('apiBulk', 7080),
    wrong: row('wrong', 7096),
    // Plain glass in a section of its own: the one place a direct install is expected.
    control: row('control', 7104)
  }
  rows.view = [rows.mirror, rows.native, rows.staticMirror, rows.coordinated, rows.apiNative, rows.apiBulk, rows.wrong]
  return rows
}
// One lone portal block, away from every row, with no lit frame behind it.
const GHOST = [7040, Y, 7064]
const GHOST_STATE = 'playerportals:portal[axis=x,sealed=false]'
const AREAS = ['forceload add 6992 6992 7111 7015', 'forceload add 6992 7024 7111 7071']
const CLEAR = [`fill 6992 ${Y - 1} 6992 7111 ${Y + 3} 7015 minecraft:air`, `fill 6992 ${Y - 1} 7024 7111 ${Y + 3} 7071 minecraft:air`]
const VIEW = '7048.5 82 7004.5 180 20'

module.exports = ({ rig, check, paced }) => {
  let client, chatCommand, select

  // What the client holds for a subject's rows, retried until the packets have all arrived.
  const view = async rows => {
    const answer = await client.send({ action: 'region', boxes: rows.view })
    return answer.inWorld ? answer.boxes : null
  }
  const settle = async (subject, rows, name, send, { placed = [], removed = [], pairs = [], armed = [], bare = [] }) => {
    const label = `${subject.key}:${name}`
    let last = { problems: ['never observed'] }
    try {
      await rig.wait(`${label} views settled`, async () => {
        const problems = []
        for (const b of placed) if ((await differences(send, { box: corners(b), expect: (x, y, z) =>
          CELLS.some(c => cellAt(b, c).join() === [x, y, z].join()) ? subject.state : undefined })).length) problems.push(`server ${b.name} is not the pattern`)
        for (const b of removed) if ((await differences(send, { box: corners(b), expect: () => undefined })).length) problems.push(`server ${b.name} is not empty`)
        const boxes = await view(rows)
        if (!boxes) problems.push('client is not in the world')
        else {
          for (const [a, b] of pairs) {
            if (!same(subject.look(boxes[a.name]), subject.look(boxes[b.name]))) {
              problems.push(`${a.name} ${JSON.stringify(subject.look(boxes[a.name])).slice(0, 220)} differs from ${b.name} ${JSON.stringify(subject.look(boxes[b.name])).slice(0, 220)}`)
            }
          }
          for (const b of armed) if (!subject.shows(boxes[b.name])) problems.push(`${b.name} shows nothing of the block`)
          for (const b of bare) if (subject.shows(boxes[b.name])) problems.push(`${b.name} still shows the block`)
        }
        last = { problems, boxes }
        return problems.length === 0
      }, 40000, [client.entry])
    } catch (error) {
      if (!/^Timeout waiting for/.test(error.message)) throw error
    }
    check(label, last.problems.length === 0, last.problems.slice(0, 6))
    return last.boxes
  }

  const place = async (send, block, b) => {
    for (const c of CELLS) await send(`setblock ${cellAt(b, c).join(' ')} ${block}`)
  }
  const apiPlace = async (send, name, route, block, b) => {
    for (const c of CELLS) {
      const at = cellAt(b, c).join(' ')
      const id = (await probe(send, `api ${route} ${name} ${block} ${at} ${at}`)).id
      await rig.wait(`API ${route} block`, async () => (await probe(send, `api result ${id}`)).done, 60000, [client.entry])
      const result = await probe(send, `api result ${id}`)
      if (result.error) throw new Error(`API ${route} edit failed: ${result.error}`)
    }
  }

  // Every route against the vanilla reference, for one subject.
  const subjectContract = async (subject, send, name) => {
    const rows = rowsOf(subject)
    const { mirror, native, staticMirror, coordinated, apiNative, apiBulk, wrong, control } = rows
    const key = subject.key

    // The references first: strict vanilla commands, the lifecycle every route must reproduce.
    await place(send, subject.block, mirror)
    await place(send, subject.block, staticMirror)
    for (const c of CELLS.slice(0, 2)) await send(`setblock ${cellAt(wrong, c).join(' ')} ${subject.block}`)

    // A native command: stone stands where the blocks go, and one //replace turns it into them.
    await place(send, STONE, native)
    await select(native)
    await chatCommand(`/replace stone ${subject.block}`, /3 blocks have been replaced/)
    // Coordinated, API native and API bulk edits of the same cells. A declared block must never be
    // installed into a section directly, which would skip the mod's hooks; the control shows the
    // counter does move for plain blocks.
    const installs = async () => (await probe(send, 'statistics')).sections
    const start = await installs()
    await place(send, STONE, coordinated)
    await select(coordinated)
    // Quoted: the arguments are brigadier strings, and a namespace colon needs the quotes.
    await chatCommand(`fawe-coordinated replace stone "${subject.block}"`, /Coordinated edit complete: 3 changes/)
    const afterCoordinated = await installs()
    await apiPlace(send, name, 'native', subject.block, apiNative)
    await apiPlace(send, name, 'bulk', subject.block, apiBulk)
    const afterApi = await installs()
    check(`${key}:declared-blocks-never-installed-directly`, afterCoordinated === start && afterApi === start, { start, afterCoordinated, afterApi })
    await select(control)
    await chatCommand('fawe-coordinated set glass', /Coordinated edit complete: 6 changes/)
    check(`${key}:plain-blocks-control-installs-a-section`, await installs() === start + 1, { start, now: await installs() })

    const fawe = [native, coordinated, apiNative, apiBulk]
    const placed = await settle(subject, rows, 'placed-match-vanilla', send, {
      placed: [mirror, staticMirror, ...fawe],
      pairs: [[native, mirror], ...fawe.map(b => [b, staticMirror])], armed: [mirror, staticMirror, ...fawe]
    })
    check(`${key}:mirror-shows-the-block`, placed && subject.shows(placed[mirror.name]), placed && subject.look(placed[mirror.name]))
    check(`${key}:comparison-detects-a-missing-block`, placed && !same(subject.look(placed[wrong.name]), subject.look(placed[staticMirror.name])),
      placed && subject.look(placed[wrong.name]))

    // Removal: a native //set air against vanilla's fill; what the block gave goes with it.
    await select(native)
    await chatCommand('/set air', /Operation completed/)
    await send(`fill ${corners(mirror).join(' ')} minecraft:air`)
    await settle(subject, rows, 'removed-match-vanilla', send, { removed: [mirror, native], pairs: [[native, mirror]], bare: [native, mirror] })
    // Undo brings the blocks back; redo takes them away again.
    await chatCommand('/undo', /Undid/)
    await settle(subject, rows, 'undo-restores-the-block', send, { placed: [native], pairs: [[native, staticMirror]], armed: [native] })
    await chatCommand('/redo', /Redid/)
    await settle(subject, rows, 'redo-removes-the-block', send, { removed: [native], pairs: [[native, mirror]], bare: [native] })
  }

  return {
    async contract (rig_, send, name, { client: playerClient, chatCommand: sendChatCommand }) {
      client = playerClient
      chatCommand = paced(send, client, sendChatCommand)
      select = async b => {
        await chatCommand(`/pos1 ${b.x1},${b.y1},${b.z1}`, /First position set|Position already set/)
        await chatCommand(`/pos2 ${b.x2},${b.y2},${b.z2}`, /Second position set|Position already set/)
      }
      for (const area of AREAS) await send(area)
      for (const clear of CLEAR) await send(clear)
      await send(`tp ${name} ${VIEW}`)

      // Which blocks FAWE edits is each mod's to declare; a mod that declares nothing for a block
      // gets no support for it.
      const lifecycle = await probe(send, 'lifecycle minecraft:cobblestone_wall minecraft:oak_fence minecraft:glass_pane ' +
        'playerportals:void_infused_deepslate playerportals:portal minecraft:stone')
      const byBlock = Object.fromEntries(lifecycle.blocks.map(entry => [entry.block, entry]))
      check('declared-blocks-supported', byBlock['minecraft:cobblestone_wall'].supported && byBlock['minecraft:oak_fence'].supported &&
        byBlock['minecraft:glass_pane'].supported && byBlock['playerportals:void_infused_deepslate'].supported && lifecycle.declared > 0, lifecycle)
      check('declared-blocks-stay-per-cell', !byBlock['minecraft:cobblestone_wall'].direct && !byBlock['playerportals:void_infused_deepslate'].direct &&
        byBlock['minecraft:stone'].direct, lifecycle)
      check('undeclared-portal-block-unsupported', !byBlock['playerportals:portal'].supported && !byBlock['playerportals:portal'].direct, lifecycle)

      for (const subject of SUBJECTS) await subjectContract(subject, send, name)

      // A lit portal's interior has a record outside the chunk that undo cannot restore, so its
      // block stays undeclared: an edit that would replace it skips the cell and says so.
      await send(`setblock ${GHOST.join(' ')} ${GHOST_STATE}`)
      await select({ x1: GHOST[0], y1: GHOST[1], z1: GHOST[2], x2: GHOST[0], y2: GHOST[1], z2: GHOST[2] })
      await chatCommand('/set stone', /Skipped 1 unsupported blocks?.*playerportals:portal/)
      check('undeclared-portal-block-kept', (await differences(send, { box: [...GHOST, ...GHOST], expect: () => GHOST_STATE })).length === 0,
        await differences(send, { box: [...GHOST, ...GHOST], expect: () => GHOST_STATE }))
      await send(`setblock ${GHOST.join(' ')} minecraft:air`)
      await send('save-all flush')
    },
    async restart (rig_, send, name, { client: restartedClient }) {
      client = restartedClient
      for (const area of AREAS) await send(area)
      await send(`tp ${name} ${VIEW}`)
      // Reloaded chunks rebuild what the blocks give from the world; none may be lost or left over.
      for (const subject of SUBJECTS) {
        const rows = rowsOf(subject)
        const { mirror, native, staticMirror, coordinated, apiNative, apiBulk } = rows
        const fawe = [coordinated, apiNative, apiBulk]
        await settle(subject, rows, 'restart-rebuilt', send, {
          placed: [staticMirror, ...fawe], removed: [native, mirror],
          pairs: [[native, mirror], ...fawe.map(b => [b, staticMirror])], armed: [staticMirror, ...fawe], bare: [native, mirror]
        })
      }
    }
  }
}
