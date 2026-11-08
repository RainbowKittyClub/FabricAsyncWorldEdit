'use strict'

// Same-position reference comparisons avoid treating a weighted model's position-dependent art
// as a lifecycle failure. The supplied deployment contains Terrestria, not PPB's other optional mods.
const { canonical, probe } = require('./common')
const AREA = '7440 7440 7583 7551'
const Y = 80
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b)
const box = (name, x, z) => ({ name, x1: x, x2: x, y1: Y, y2: Y, z1: z, z2: z, padding: 2 })
const at = b => `${b.x1} ${Y} ${b.z1}`
const corners = b => `${at(b)} ${at(b)}`
const describe = area => ({ cells: area.cells.map(c => ({ state: canonical(c.state), blockEntity: c.blockEntity })),
  displays: area.displays.filter(d => d.at.split(',').map(Number).every(value => value >= -2 && value <= 3))
    .map(({ at, shows, item, text }) => ({ at, shows, item, text })).sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b))), drops: area.drops })
const rich = c => c.class.endsWith('SignBlock')
  ? '{front_text:{messages:["FAWE front A","FAWE front B","",""],color:"blue",has_glowing_text:1b},back_text:{messages:["FAWE back","","",""],color:"red"},is_waxed:1b}'
  : c.class.endsWith('ShelfBlock')
    ? '{Items:[{Slot:0b,id:"minecraft:diamond",count:3},{Slot:2b,id:"minecraft:emerald",count:7}],align_items_to_bottom:1b}' : ''

module.exports = ({ rig, check }) => {
  let send, name, client, watcher, chat, cases
  const references = new Map()
  const select = async b => {
    await chat(`/pos1 ${b.x1},${Y},${b.z1}`, /First position set|Position already set/)
    await chat(`/pos2 ${b.x1},${Y},${b.z1}`, /Second position set|Position already set/)
  }
  const move = async b => {
    await send(`tp ${name} ${b.x1 + 0.5} 82 ${b.z1 + 4.5} 180 20`)
    await send(`tp FaweShelfPack ${b.x1 + 0.5} 82 ${b.z1 + 4.5} 180 20`)
  }
  const snapshot = async (c, b) => {
    const views = {}
    for (const [label, observer] of [['plain', client], ['pack', watcher]]) {
      const area = (await observer.send({ action: 'region', boxes: [b] })).boxes?.[b.name]
      if (!area?.loaded) return null
      views[label] = describe(area)
    }
    return { data: c.blockEntity ? await send(`data get block ${at(b)}`) : null, views }
  }
  const expect = async (c, label, b, wanted) => {
    let seen
    let okay = false
    try {
      await rig.wait(`PPB ${c.id} ${label}`, async () => {
        seen = await snapshot(c, b)
        return (okay = seen !== null && same(seen, wanted))
      }, 25000, [client.entry, watcher.entry])
    } catch (error) { if (!/^Timeout waiting for/.test(error.message)) throw error }
    check(`ppb:${c.id}:${label}`, okay, okay ? undefined : { seen, wanted })
    if (!okay) throw new Error(`PPB parity failed: ${c.id} ${label}`)
  }
  const referenceAt = async (c, b) => {
    let reference, fingerprint, since
    await rig.wait('PPB reference fully received by both clients', async () => {
      reference = await snapshot(c, b)
      if (!reference || !Object.values(reference.views).every(v =>
        !['minecraft:air', 'minecraft:void_air', 'minecraft:cave_air'].includes(v.cells[0].state) &&
        (!c.blockEntity || v.cells[0].blockEntity?.includes(c.class.endsWith('SignBlock') ? 'FAWE front A' : 'minecraft:emerald')))) return false
      const next = JSON.stringify(reference)
      if (next !== fingerprint) { fingerprint = next; since = Date.now() }
      return Date.now() - since >= 500
    }, 30000, [client.entry, watcher.entry])
    return reference
  }
  const erase = async (c, b) => {
    if (c.class.endsWith('ShelfBlock')) await send(`data merge block ${at(b)} {Items:[]}`)
    await send(`setblock ${at(b)} minecraft:air strict`)
  }
  const api = async (route, source, b) => {
    const id = (await probe(send, `api-copy ${route} ${name} ${at(source)} ${corners(b)}`)).id
    await rig.wait('PPB API publication', async () => (await probe(send, `api result ${id}`)).done, 60000, [client.entry])
    const result = await probe(send, `api result ${id}`)
    if (result.error) throw new Error(result.error)
  }
  const primary = i => box('primary', 7456 + 12 * (i % 8), 7456 + 12 * Math.floor(i / 8))
  return {
    async contract (context) {
      ;({ send, name, client, watcher, chat } = context)
      await send(`forceload add ${AREA}`)
      await send('gamerule random_tick_speed 0')
      // Match strict vanilla commands: no placement correction or neighbour propagation. Other
      // S11 suites independently exercise validation/side-effect policy on the same engine build.
      await chat('/perf validation off', /set to|is already/)
      const catalog = (await probe(send, 'catalog terrestria')).blocks
      check('ppb:all-provisioned-blocks-declared', catalog.length === 263 && catalog.every(c => c.supported), { count: catalog.length })
      const families = new Map()
      for (const c of [...catalog].sort((a, b) => a.id.localeCompare(b.id))) if (!families.has(c.class)) families.set(c.class, c)
      cases = [...families.values()].sort((a, b) => Number(b.blockEntity) - Number(a.blockEntity) || a.class.localeCompare(b.class))
      check('ppb:all-provisioned-classes-covered', cases.length === 37, cases.map(c => ({ id: c.id, class: c.class })))
      for (const [i, c] of cases.entries()) {
        const b = primary(i)
        const source = box('source', b.x1 + 4, b.z1)
        await send(`fill ${b.x1 - 2} 79 ${b.z1 - 2} ${b.x1 + 10} 79 ${b.z1 + 6} minecraft:dirt strict`)
        await send(`fill ${b.x1 - 2} 80 ${b.z1 - 2} ${b.x1 + 10} 83 ${b.z1 + 6} minecraft:air strict`)
        await move(b)
        await send(`setblock ${at(b)} ${c.state}${rich(c)} strict`)
        check(`ppb:${c.id}:reference-is-real`, await send(`execute if block ${at(b)} ${c.state}`) === 'Test passed')
        const reference = await referenceAt(c, b)
        references.set(c.id, reference)
        if (c.blockEntity) check(`ppb:${c.id}:reference-has-rich-data`, reference.data.includes(c.class.endsWith('SignBlock') ? 'FAWE front A' : 'minecraft:emerald'), reference.data)
        await send(`setblock ${at(source)} ${c.state}${rich(c)} strict`)
        await select(source)
        await send(`tp ${name} ${source.x1 - 0.5} ${Y} ${source.z1 + 0.5}`)
        await chat('/copy', /blocks were copied|blocks affected/)
        await erase(c, b)
        await send(`tp ${name} ${b.x1 - 0.5} ${Y} ${b.z1 + 0.5}`)
        await chat('/paste', /clipboard has been pasted/)
        await chat('/clearclipboard', /Clipboard cleared/)
        await move(b)
        await expect(c, 'paste-matches-vanilla', b, reference)
        const sections = (await probe(send, 'statistics')).sections
        for (const route of ['native', 'bulk']) {
          await erase(c, b)
          await api(route, source, b)
          await expect(c, `api-${route}-matches-vanilla`, b, reference)
        }
        await select(b)
        await chat('fawe-coordinated set stone', /Coordinated edit complete: 1 changes/)
        await chat('/undo', /Undid/)
        await expect(c, 'coordinated-undo-matches-vanilla', b, reference)
        check(`ppb:${c.id}:no-direct-install`, (await probe(send, 'statistics')).sections === sections)
        await select(b)
        await chat('/set air', /Operation completed/)
        let empty
        await rig.wait('PPB removal received', async () => {
          empty = await snapshot({ blockEntity: false }, b)
          return empty && Object.values(empty.views).every(v => v.cells[0].state === 'minecraft:air' && !v.displays.length && !v.drops.length)
        }, 25000, [client.entry, watcher.entry])
        check(`ppb:${c.id}:removal-clears-the-model`, empty && Object.values(empty.views).every(v => v.cells[0].state === 'minecraft:air' && !v.displays.length && !v.drops.length), empty)
        await chat('/undo', /Undid/)
        await expect(c, 'undo-matches-vanilla', b, reference)
        await chat('/redo', /Redid/)
        await expect({ ...c, blockEntity: false }, 'redo-is-empty', b, empty)
        await chat('/undo', /Undid/)
        await expect(c, 'restored-for-restart', b, reference)
        await erase(c, source)
        const replay = box('replay', b.x1 + 8, b.z1)
        await send(`setblock ${at(replay)} ${c.state}${rich(c)} strict`)
        const replayReference = await referenceAt(c, replay)
        references.set(c.id + ':replay', replayReference)
        await select(replay)
        await chat('/set air', /Operation completed/)
        await send(`tp ${name} ${replay.x1 + 0.5} ${Y} ${replay.z1 + 2.5}`)
        await chat('ledger rollback action:fawe-block range:3 after:1h', /only applied actions were marked|No results for that search/)
        await move(b)
        await expect(c, 'ledger-rollback-matches-vanilla', replay, replayReference)
      }
      await send('save-all flush')
      await send(`forceload remove ${AREA}`)
      await send(`tp ${name} 1024.5 80 1024.5`)
      await send('tp FaweShelfPack 1024.5 80 1024.5')
      let unloaded = false
      try {
        await rig.wait('PPB actual chunk unload', async () => {
          const loaded = []
          for (const [i] of cases.entries()) {
            const b = primary(i)
            loaded.push((await probe(send, `block-info ${at(b)}`)).loaded)
            loaded.push((await probe(send, `block-info ${b.x1 + 8} ${Y} ${b.z1}`)).loaded)
          }
          return (unloaded = loaded.every(value => !value))
        }, 45000, [client.entry, watcher.entry])
      } catch (error) { if (!/^Timeout waiting for/.test(error.message)) throw error }
      check('ppb:chunks-actually-unloaded', unloaded)
      await send(`forceload add ${AREA}`)
      for (const [i, c] of cases.entries()) {
        await move(primary(i))
        await expect(c, 'chunk-reload-matches-vanilla', primary(i), references.get(c.id))
        await expect(c, 'replay-chunk-reload-matches-vanilla', box('replay', primary(i).x1 + 8, primary(i).z1), references.get(c.id + ':replay'))
      }
    },
    async restart (context) {
      ;({ send, name, client, watcher } = context)
      await send(`forceload add ${AREA}`)
      for (const [i, c] of cases.entries()) {
        await move(primary(i))
        await expect(c, 'restart-matches-vanilla', primary(i), references.get(c.id))
        await expect(c, 'replay-restart-matches-vanilla', box('replay', primary(i).x1 + 8, primary(i).z1), references.get(c.id + ':replay'))
      }
    }
  }
}
