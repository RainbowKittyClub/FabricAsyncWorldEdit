'use strict'

// S11 adapters suite (S10a): blocks of installed mods that declare their lifecycle to FAWE. Every
// FAWE route builds the same pattern as strict vanilla commands do, and the client must see the
// same cells and the same effect of the block, after removal, undo, redo and a restart. The
// vanilla-made rows are the reference; a deliberately wrong row proves the comparison can tell a
// missing block from a right one. A subject is one declaring mod's block and what a client sees of
// it: diagonal walls draw arm displays, a player-portals frame block gives light a stand-in cannot,
// a planked chest has a display holder; plain shelves retain their fallback or pack carrier and
// enchanting-power tag. A paste of a vanilla-made double chest with contents
// follows, since a half's holder looks for its partner. Blocks a mod leaves undeclared (the lit
// portal block) must still be refused. Populated shelves and crafting grids follow in storage-data.

const fs = require('node:fs')
const path = require('node:path')
const { startClient } = require('../harness/native')
const { canonical, probe, differences, holdsItem } = require('./common')

const Y = 80
const STONE = 'minecraft:stone'
// Blocks at (0,0), (1,1) and (2,0) of a 3x2 box: two diagonals, no block beside another.
const CELLS = [[0, 0], [1, 1], [2, 0]]
const box = (name, x1, z1, width = 3, depth = 2) => ({ name, x1, y1: Y, z1, x2: x1 + width - 1, y2: Y, z2: z1 + depth - 1 })
const corners = b => [b.x1, b.y1, b.z1, b.x2, b.y2, b.z2]
const cellAt = (b, [dx, dz]) => [b.x1 + dx, Y, b.z1 + dz]
const states = area => area.cells.map(cell => canonical(cell.state))
const shown = area => area.displays.map(({ at, shows }) => `${at}|${shows}`).sort()
const lights = area => area.cells.map(cell => `${cell.light}/${cell.lightAbove}`)
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b)
const arms = { look: area => [...states(area), ...shown(area)], shows: area => shown(area).length > 0 }

// Each subject owns a band of rows 32 blocks from the next; the player stands near its band.
const SUBJECTS = [
  {
    key: 'walls',
    block: 'minecraft:cobblestone_wall',
    state: 'minecraft:cobblestone_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]',
    z: 7000,
    ...arms
  },
  {
    key: 'frames',
    block: 'playerportals:void_infused_deepslate',
    state: 'playerportals:void_infused_deepslate',
    z: 7032,
    // The stand-in gives no light of its own; the server's reaches the client by Polymer's resend.
    look: area => [...states(area), ...lights(area)],
    shows: area => area.cells.some(cell => cell.light > 0)
  },
  {
    key: 'chests',
    block: 'sensiblestorage:oak_chest',
    state: 'sensiblestorage:oak_chest[facing=north,type=single,waterlogged=false]',
    z: 7064,
    // A chest's holder draws its base and lid as displays over the vanilla stand-in.
    ...arms
  },
  {
    key: 'bookshelves',
    block: 'sensiblestorage:spruce_bookshelf',
    state: 'sensiblestorage:spruce_bookshelf',
    z: 7192,
    pack: true,
    clipboard: true,
    look: area => [...states(area), ...shown(area)],
    shows: area => states(area).some(state => state !== 'minecraft:air')
  }
]
// A vanilla-made double chest with contents, pasted: left half at the west, facing north.
const DOUBLES = [
  { key: 'double-chest', block: 'sensiblestorage:oak_chest', z: 7096, items: [['diamond', 3], ['emerald', 2]] },
  { key: 'double-trapped-chest', block: 'sensiblestorage:oak_trapped_chest', z: 7128, items: [['iron_ingot', 2], ['gold_ingot', 7]] }
]
const bandOf = z => `forceload add 6992 ${z - 8} 7111 ${z + 23}`
const clearOf = z => `fill 6992 ${Y - 1} ${z - 8} 7111 ${Y + 3} ${z + 23} minecraft:air`
const viewOf = z => `7048.5 82 ${z + 4.5} 180 20`
// One lone portal block, away from every row, with no lit frame behind it.
const GHOST = [7040, Y, 7160]
const GHOST_STATE = 'playerportals:portal[axis=x,sealed=false]'
const BANDS = [...SUBJECTS.map(subject => subject.z), ...DOUBLES.map(double => double.z), GHOST[2]]

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
  subject.expect = b => (x, y, z) => CELLS.some(c => cellAt(b, c).join() === [x, y, z].join()) ? subject.state : undefined
  return rows
}
const doubleRows = double => {
  const row = (label, z) => box(`${double.key}-${label}`, 7000, z, 2, 1)
  const rows = { source: row('source', double.z), pasted: row('pasted', double.z + 4), wrong: row('wrong', double.z + 8) }
  rows.view = [rows.source, rows.pasted, rows.wrong]
  const half = type => `${double.block}[facing=north,type=${type},waterlogged=false]`
  double.expect = b => x => x === b.x1 ? half('left') : half('right')
  double.look = arms.look
  double.shows = arms.shows
  return rows
}

module.exports = ({ rig, check, paced }) => {
  const storage = require('./storage-data')({ rig, check })
  let client, chatCommand, select, watcher
  const packName = 'FaweShelfPack'
  const repo = path.resolve(__dirname, '../..')
  const startWatcher = async label => {
    const port = Number(/^server-port=(\d+)$/m.exec(fs.readFileSync(path.join(rig.root, 'server/server.properties'), 'utf8'))[1])
    watcher = await startClient(rig, repo, { name: packName, port, pack: true, label })
  }

  // What the client holds for a band's rows, retried until the packets have all arrived.
  const view = async (rows, observer = client) => {
    const answer = await observer.send({ action: 'region', boxes: rows.view })
    return answer.inWorld ? answer.boxes : null
  }
  const settle = async (subject, rows, name, send, { placed = [], removed = [], pairs = [], armed = [], bare = [] }) => {
    const label = `${subject.key}:${name}`
    let last = { problems: ['never observed'] }
    try {
      await rig.wait(`${label} views settled`, async () => {
        const problems = []
        for (const b of placed) if ((await differences(send, { box: corners(b), expect: subject.expect(b) })).length) problems.push(`server ${b.name} is not the pattern`)
        for (const b of removed) if ((await differences(send, { box: corners(b), expect: () => undefined })).length) problems.push(`server ${b.name} is not empty`)
        const views = { plain: await view(rows) }
        if (subject.pack) views.pack = await view(rows, watcher)
        for (const [audience, boxes] of Object.entries(views)) {
          if (!boxes) { problems.push(`${audience} client is not in the world`); continue }
          // An unloaded box reads as air, which would pass every check of an empty row.
          for (const b of rows.view) if (!boxes[b.name]?.loaded) problems.push(`${audience} client lacks ${b.name}`)
          for (const [a, b] of pairs) {
            if (!same(subject.look(boxes[a.name]), subject.look(boxes[b.name]))) {
              problems.push(`${audience} ${a.name} ${JSON.stringify(subject.look(boxes[a.name])).slice(0, 220)} differs from ${b.name} ${JSON.stringify(subject.look(boxes[b.name])).slice(0, 220)}`)
            }
          }
          for (const b of armed) if (!subject.shows(boxes[b.name])) problems.push(`${b.name} shows nothing of the block`)
          for (const b of bare) if (subject.shows(boxes[b.name])) problems.push(`${b.name} still shows the block`)
        }
        last = { problems, boxes: views.plain }
        return problems.length === 0
      }, 40000, [client.entry, ...(subject.pack ? [watcher.entry] : [])])
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
    await send(`tp ${name} ${viewOf(subject.z)}`)
    if (subject.pack) {
      await startWatcher('shelves-pack')
      await send(`tp ${packName} ${viewOf(subject.z)}`)
    }

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
    if (subject.pack) {
      const packed = await view(rows, watcher)
      check(`${key}:pack-uses-a-distinct-carrier`, packed && placed &&
        !same(states(packed[mirror.name]), states(placed[mirror.name])) &&
        CELLS.every(c => canonical(placed[mirror.name].cells.find(cell => cell.at === `${c[0]},0,${c[1]}`).state) === 'minecraft:bookshelf'),
      packed && subject.look(packed[mirror.name]))
      check(`${key}:pack-comparison-detects-a-missing-block`, packed &&
        !same(subject.look(packed[wrong.name]), subject.look(packed[staticMirror.name])), packed && subject.look(packed[wrong.name]))
      const power = []
      for (const b of [mirror, ...fawe]) for (const c of CELLS) {
        power.push(await send(`execute if block ${cellAt(b, c).join(' ')} #minecraft:enchantment_power_provider`))
      }
      check(`${key}:all-routes-retain-enchanting-power`, power.every(answer => answer === 'Test passed'), power)
    }

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
    if (subject.clipboard) {
      for (const b of [staticMirror, native]) await send(`setblock ${b.x1 - 1} ${Y - 1} ${b.z1} minecraft:stone`)
      await select(staticMirror)
      await send(`tp ${name} ${staticMirror.x1 - 0.5} ${Y} ${staticMirror.z1 + 0.5}`)
      await chatCommand('/copy', /blocks were copied|blocks affected/)
      await send(`tp ${name} ${native.x1 - 0.5} ${Y} ${native.z1 + 0.5}`)
      await chatCommand('/paste', /clipboard has been pasted/)
      await chatCommand('/clearclipboard', /Clipboard cleared/)
      await send(`tp ${name} ${viewOf(subject.z)}`)
      await settle(subject, rows, 'pasted-match-vanilla', send, { placed: [native], pairs: [[native, staticMirror]], armed: [native] })
      await chatCommand('/undo', /Undid/)
      await settle(subject, rows, 'paste-undo-removes-the-block', send, { removed: [native], pairs: [[native, mirror]], bare: [native] })
      await chatCommand('/redo', /Redid/)
      await settle(subject, rows, 'paste-redo-restores-the-block', send, { placed: [native], pairs: [[native, staticMirror]], armed: [native] })
    }
    if (subject.pack) await rig.stop(watcher.entry)
  }

  // The contents a double chest's two halves hold, and what each half reports when asked.
  const contents = async (send, double, b) => {
    const [left, right] = double.items
    return {
      ok: await holdsItem(send, [b.x1, Y, b.z1], 0, left[0], left[1]) && await holdsItem(send, [b.x2, Y, b.z1], 0, right[0], right[1]),
      left: await send(`data get block ${b.x1} ${Y} ${b.z1} Items`),
      right: await send(`data get block ${b.x2} ${Y} ${b.z1} Items`)
    }
  }
  const checkContents = async (name, send, double, ...rows) => {
    const seen = []
    for (const b of rows) seen.push(await contents(send, double, b))
    check(name, seen.every(entry => entry.ok), seen)
  }

  // A double chest made the vanilla way, copied and pasted: both halves, their contents and their
  // holders must come out as the original has them, and survive removal, undo, redo and a restart.
  const doubleContract = async (double, send, name) => {
    const rows = doubleRows(double)
    const { source, pasted, wrong } = rows
    const key = double.key
    const [left, right] = double.items
    await send(`tp ${name} ${viewOf(double.z)}`)
    // The player stands on stone west of the source, and the same way four south to paste.
    await send(`fill 6998 ${Y - 1} ${double.z} 6999 ${Y - 1} ${double.z + 4} minecraft:stone`)
    await send(`setblock ${source.x1} ${Y} ${source.z1} ${double.block}[facing=north,type=left]{Items:[{Slot:0b,id:"minecraft:${left[0]}",count:${left[1]}}]} strict`)
    await send(`setblock ${source.x2} ${Y} ${source.z1} ${double.block}[facing=north,type=right]{Items:[{Slot:0b,id:"minecraft:${right[0]}",count:${right[1]}}]} strict`)
    await send(`setblock ${wrong.x1} ${Y} ${wrong.z1} ${double.block}[facing=north,type=single]{Items:[{Slot:0b,id:"minecraft:${left[0]}",count:${left[1]}}]} strict`)
    check(`${key}:source-is-a-double-chest`, (await differences(send, { box: corners(source), expect: double.expect(source) })).length === 0)
    await checkContents(`${key}:source-holds-its-contents`, send, double, source)

    await select(source)
    await send(`tp ${name} 6999.5 ${Y} ${double.z + 0.5}`)
    await chatCommand('/copy', /blocks were copied|blocks affected/)
    await send(`tp ${name} 6999.5 ${Y} ${double.z + 4.5}`)
    await chatCommand('/paste', /clipboard has been pasted/)
    await chatCommand('/clearclipboard', /Clipboard cleared/)
    await send(`tp ${name} ${viewOf(double.z)}`)

    const placed = await settle(double, rows, 'pasted-matches-source', send, {
      placed: [source, pasted], pairs: [[pasted, source]], armed: [source, pasted]
    })
    await checkContents(`${key}:pasted-contents-kept`, send, double, pasted)
    check(`${key}:comparison-detects-a-lone-half`, placed && !same(double.look(placed[wrong.name]), double.look(placed[source.name])), placed && double.look(placed[wrong.name]))

    await select(pasted)
    await chatCommand('/set air', /Operation completed/)
    await settle(double, rows, 'removed-leaves-nothing', send, { removed: [pasted], bare: [pasted] })
    await chatCommand('/undo', /Undid/)
    await settle(double, rows, 'undo-restores-the-pair', send, { placed: [pasted], pairs: [[pasted, source]], armed: [pasted] })
    await checkContents(`${key}:undo-restores-contents`, send, double, pasted)
    await chatCommand('/redo', /Redid/)
    await settle(double, rows, 'redo-removes-the-pair', send, { removed: [pasted], bare: [pasted] })
    // Back again for the restart, which rebuilds both rows' holders from the saved chunks.
    await chatCommand('/undo', /Undid/)
    await settle(double, rows, 'undo-again-restores-the-pair', send, { placed: [pasted], pairs: [[pasted, source]], armed: [pasted] })
  }

  return {
    clientOptions: { pack: false },
    async configureServer (serverDir) {
      fs.mkdirSync(path.join(serverDir, 'config/polymer'), { recursive: true })
      fs.writeFileSync(path.join(serverDir, 'config/polymer/auto-host.json'), JSON.stringify({ enabled: true, required: false,
        mod_override: false, resource_pack_status_dialog: false, allow_joining_after_generation_failure: false }))
    },
    async contract (rig_, send, name, { client: playerClient, chatCommand: sendChatCommand }) {
      client = playerClient
      chatCommand = paced(send, client, sendChatCommand)
      select = async b => {
        await chatCommand(`/pos1 ${b.x1},${b.y1},${b.z1}`, /First position set|Position already set/)
        await chatCommand(`/pos2 ${b.x2},${b.y2},${b.z2}`, /Second position set|Position already set/)
      }
      for (const z of BANDS) await send(bandOf(z))
      for (const z of BANDS) await send(clearOf(z))

      // Which blocks FAWE edits is each mod's to declare; a mod that declares nothing for a block
      // gets no support for it.
      const lifecycle = await probe(send, 'lifecycle minecraft:cobblestone_wall minecraft:oak_fence minecraft:glass_pane ' +
        'playerportals:void_infused_deepslate playerportals:portal sensiblestorage:oak_chest sensiblestorage:oak_trapped_chest ' +
        'sensiblestorage:spruce_crafting_table sensiblestorage:spruce_bookshelf sensiblestorage:spruce_chiseled_bookshelf ' +
        'minecraft:crafting_table minecraft:chiseled_bookshelf minecraft:stone minecraft:chest')
      const byBlock = Object.fromEntries(lifecycle.blocks.map(entry => [entry.block, entry]))
      const declared = ['minecraft:cobblestone_wall', 'minecraft:oak_fence', 'minecraft:glass_pane', 'playerportals:void_infused_deepslate',
        'sensiblestorage:oak_chest', 'sensiblestorage:oak_trapped_chest', 'sensiblestorage:spruce_bookshelf',
        'sensiblestorage:spruce_crafting_table', 'sensiblestorage:spruce_chiseled_bookshelf',
        'minecraft:crafting_table', 'minecraft:chiseled_bookshelf']
      const undeclared = ['playerportals:portal']
      check('declared-blocks-supported', declared.every(id => byBlock[id].supported) && lifecycle.declared > 0, lifecycle)
      check('declared-blocks-stay-per-cell', declared.every(id => !byBlock[id].direct) && byBlock['minecraft:stone'].direct, lifecycle)
      check('undeclared-blocks-unsupported', undeclared.every(id => !byBlock[id].supported && !byBlock[id].direct), lifecycle)
      // Inventories: the vanilla chest by default, a mod's chests by its declaration, nothing else.
      const containers = ['minecraft:chest', 'sensiblestorage:oak_chest', 'sensiblestorage:oak_trapped_chest',
        'sensiblestorage:spruce_crafting_table', 'sensiblestorage:spruce_chiseled_bookshelf',
        'minecraft:crafting_table', 'minecraft:chiseled_bookshelf']
      check('declared-containers', containers.every(id => byBlock[id].container) &&
        [...declared, ...undeclared].filter(id => !containers.includes(id)).every(id => !byBlock[id].container), lifecycle)

      for (const subject of SUBJECTS) await subjectContract(subject, send, name)
      for (const double of DOUBLES) await doubleContract(double, send, name)
      await startWatcher('storage-pack')
      await storage.contract({ send, name, client, watcher, chat: chatCommand })
      await rig.stop(watcher.entry)

      // A lit portal's interior has a record outside the chunk that undo cannot restore, so its
      // block stays undeclared: an edit that would replace it skips the cell and says so.
      await send(`tp ${name} ${viewOf(GHOST[2])}`)
      await send(`setblock ${GHOST.join(' ')} ${GHOST_STATE}`)
      await select({ x1: GHOST[0], y1: GHOST[1], z1: GHOST[2], x2: GHOST[0], y2: GHOST[1], z2: GHOST[2] })
      await chatCommand('/set stone', /Skipped 1 unsupported blocks?.*playerportals:portal/)
      const kept = await differences(send, { box: [...GHOST, ...GHOST], expect: () => GHOST_STATE })
      check('undeclared-portal-block-kept', kept.length === 0, kept)
      await send(`setblock ${GHOST.join(' ')} minecraft:air`)
      await send('save-all flush')
    },
    async restart (rig_, send, name, { client: restartedClient }) {
      client = restartedClient
      for (const z of BANDS) await send(bandOf(z))
      // Reloaded chunks rebuild what the blocks give from the world; none may be lost or left over.
      for (const subject of SUBJECTS) {
        const rows = rowsOf(subject)
        const { mirror, native, staticMirror, coordinated, apiNative, apiBulk } = rows
        const fawe = [coordinated, apiNative, apiBulk, ...(subject.clipboard ? [native] : [])]
        await send(`tp ${name} ${viewOf(subject.z)}`)
        if (subject.pack) {
          await startWatcher('shelves-pack-restart')
          await send(`tp ${packName} ${viewOf(subject.z)}`)
        }
        await settle(subject, rows, 'restart-rebuilt', send, {
          placed: [staticMirror, ...fawe], removed: subject.clipboard ? [mirror] : [native, mirror],
          pairs: [...(subject.clipboard ? [] : [[native, mirror]]), ...fawe.map(b => [b, staticMirror])],
          armed: [staticMirror, ...fawe], bare: subject.clipboard ? [mirror] : [native, mirror]
        })
        if (subject.pack) await rig.stop(watcher.entry)
      }
      for (const double of DOUBLES) {
        const rows = doubleRows(double)
        const { source, pasted } = rows
        await send(`tp ${name} ${viewOf(double.z)}`)
        await settle(double, rows, 'restart-rebuilt', send, {
          placed: [source, pasted], pairs: [[pasted, source]], armed: [source, pasted]
        })
        await checkContents(`${double.key}:restart-contents-kept`, send, double, pasted, source)
      }
      await startWatcher('storage-pack-restart')
      await storage.restart({ send, name, client, watcher })
      await rig.stop(watcher.entry)
    }
  }
}
