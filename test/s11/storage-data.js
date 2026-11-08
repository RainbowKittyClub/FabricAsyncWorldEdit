'use strict'

// Populated chiseled shelves and persistent crafting grids, including the vanilla blocks the mod
// changes. Ordinary strict setblock is the reference. All NBT reads and client observations are
// independent of the edit path; the public API caller copies full blocks, not just their state.
const { canonical, probe } = require('./common')
const Y = 80
const CASES = [
  { key: 'chiseled-planked', block: 'sensiblestorage:spruce_chiseled_bookshelf', z: 7304, shelf: true },
  { key: 'chiseled-vanilla', block: 'minecraft:chiseled_bookshelf', z: 7336, shelf: true },
  { key: 'grid-planked', block: 'sensiblestorage:spruce_crafting_table', z: 7368 },
  { key: 'grid-vanilla', block: 'minecraft:crafting_table', z: 7400 }
]
const state = (c, filled = true) => c.block + (c.shelf ? '[facing=north,' +
  Array.from({ length: 6 }, (_, slot) => `slot_${slot}_occupied=${filled && [0, 2, 5].includes(slot)}`).join(',') + ']' : '')
const item = (slot, id, count, title, extra = '') => `{Slot:${slot}b,id:"minecraft:${id}",count:${count},components:{"minecraft:custom_name":"${title}"${extra ? ',' + extra : ''}}}`
const nbt = c => c.shelf
  ? `{Items:[${item(0, 'book', 1, 'FAWE volume A')},${item(2, 'enchanted_book', 1, 'FAWE volume B')},${item(5, 'written_book', 1, 'FAWE volume C', '"minecraft:written_book_content":{title:"FAWE archive",author:"s11",generation:1,pages:["FAWE page one","FAWE page two"],resolved:true}')}],last_interacted_slot:5}`
  : `{Items:[${item(0, 'diamond', 3, 'FAWE grid A')},${item(4, 'gold_ingot', 7, 'FAWE grid B')},${item(8, 'stick', 12, 'FAWE grid C')}],facing:"east",CustomName:"FAWE workbench"}`
const rows = c => Object.fromEntries(['source', 'mirror', 'paste', 'wrong', 'apiNative', 'apiBulk', 'replay'].map((name, i) =>
  [name, { name, x1: 7304 + 12 * i, x2: 7304 + 12 * i, y1: Y, y2: Y, z1: c.z, z2: c.z, padding: 2 }]))
const at = b => `${b.x1} ${Y} ${b.z1}`
const corners = b => `${at(b)} ${at(b)}`
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b)
const payload = answer => answer.split('has the following block data: ')[1]
const contentsKeys = c => c.shelf ? ['Items', 'last_interacted_slot'] : ['Items', 'facing', 'CustomName']
// Slot sprite numbers depend on the shelf's position. Compare the occupied mask, not random art.
const displayed = (c, area) => area.displays.filter(d => d.item !== undefined).map(d => {
  const components = d.item.components || {}
  const slots = components['minecraft:custom_model_data']?.strings
  return { at: d.at, id: d.item.id, count: d.item.count,
    components: slots ? { ...components, 'minecraft:custom_model_data': { ...components['minecraft:custom_model_data'], strings: slots.map(Boolean) } } : components }
}).sort((a, b) => a.at.localeCompare(b.at))
const look = (c, area) => ({ states: area.cells.map(cell => canonical(cell.state)), displays: displayed(c, area) })

module.exports = ({ rig, check }) => {
  const references = new Map()
  let client, watcher, send, name, chat
  const select = b => (async () => {
    await chat(`/pos1 ${b.x1},${Y},${b.z1}`, /First position set|Position already set/)
    await chat(`/pos2 ${b.x2},${Y},${b.z1}`, /Second position set|Position already set/)
  })()
  const data = async (c, b) => {
    const values = []
    for (const key of contentsKeys(c)) values.push(payload(await send(`data get block ${at(b)} ${key}`)))
    return values
  }
  const view = async (observer, list) => (await observer.send({ action: 'region', boxes: list })).boxes
  const step = async (c, label, list, { empty = [] } = {}) => {
    let detail = []
    try {
      await rig.wait(`${c.key} ${label}`, async () => {
        detail = []
        for (const b of list) {
          const air = empty.includes(b.name)
          if (await send(`execute if block ${at(b)} ${air ? 'minecraft:air' : state(c)}`) !== 'Test passed') detail.push(`${b.name} server state`)
          if (!air && !same(await data(c, b), references.get(c.key))) detail.push(`${b.name} contents ${JSON.stringify(await data(c, b))}`)
          if ((await probe(send, `block-info ${at(b)}`)).signal !== (air ? 0 : c.shelf ? 6 : 3)) detail.push(`${b.name} comparator signal`)
        }
        for (const [audience, observer] of [['plain', client], ['pack', watcher]]) {
          const observed = await view(observer, [rows(c).source, ...list])
          for (const b of list) {
            const area = observed?.[b.name]
            if (!area?.loaded) { detail.push(`${audience} lacks ${b.name}`); continue }
            if (area.drops.length) detail.push(`${audience} item drops at ${b.name}`)
            if (empty.includes(b.name)) {
              if (area.cells.some(cell => cell.state !== 'minecraft:air') || displayed(c, area).some(d => d.id)) detail.push(`${audience} ${b.name} not cleared`)
            } else if (!same(look(c, area), look(c, observed.source))) detail.push(`${audience} ${b.name} differs from source: ${JSON.stringify(look(c, area))}`)
          }
        }
        return detail.length === 0
      }, 40000, [client.entry, watcher.entry])
    } catch (error) { if (!/^Timeout waiting for/.test(error.message)) throw error }
    check(`${c.key}:${label}`, detail.length === 0, detail)
  }
  const moveView = async c => {
    await send(`tp ${name} 7340.5 82 ${c.z + 4.5} 180 20`)
    await send(`tp FaweShelfPack 7340.5 82 ${c.z + 4.5} 180 20`)
  }
  const labelAt = async (c, b, expected, label) => {
    await send(`tp ${name} ${b.x1 + 0.84} ${Y} ${b.z1 - 2} 0 23.5`)
    let seen = []
    try {
      await rig.wait('shelf hover label', async () => {
        const area = (await view(client, [b]))?.[b.name]
        seen = area?.displays.filter(d => d.text !== undefined).map(d => d.text) || []
        return expected ? seen.some(text => text.includes('FAWE volume A')) : seen.length === 0
      }, 15000, [client.entry])
    } catch (error) { if (!/^Timeout waiting for/.test(error.message)) throw error }
    check(`${c.key}:${label}`, expected ? seen.some(text => text.includes('FAWE volume A')) : seen.length === 0, seen)
    await moveView(c)
  }
  const apiCopy = async (route, source, target) => {
    const id = (await probe(send, `api-copy ${route} ${name} ${at(source)} ${corners(target)}`)).id
    await rig.wait('storage API copy', async () => (await probe(send, `api result ${id}`)).done, 60000, [client.entry])
    const result = await probe(send, `api result ${id}`)
    if (result.error) throw new Error(`Storage API copy: ${result.error}`)
  }
  return {
    async contract (context) {
      ;({ send, name, client, watcher, chat } = context)
      for (const c of CASES) {
        const r = rows(c)
        await send(`forceload add 7296 ${c.z - 8} 7391 ${c.z + 15}`)
        await send(`fill 7296 79 ${c.z - 4} 7391 79 ${c.z + 4} minecraft:stone strict`)
        await send(`fill 7296 80 ${c.z - 4} 7391 83 ${c.z + 4} minecraft:air strict`)
        await moveView(c)
        for (const b of [r.source, r.mirror, r.replay]) await send(`setblock ${at(b)} ${state(c)}${nbt(c)} strict`)
        await send(`setblock ${at(r.wrong)} ${state(c, false)}{Items:[]} strict`)
        references.set(c.key, await data(c, r.source))
        check(`${c.key}:source-holds-rich-data`, references.get(c.key).every(value => value !== undefined) &&
          references.get(c.key)[0].includes(c.shelf ? 'FAWE volume A' : 'FAWE grid A') &&
          references.get(c.key)[0].includes(c.shelf ? 'FAWE volume C' : 'FAWE grid C') &&
          (!c.shelf || references.get(c.key)[0].includes('FAWE page two')), references.get(c.key))
        await step(c, 'vanilla-reference', [r.mirror])
        const plain = (await view(client, [r.source, r.wrong]))
        const pack = (await view(watcher, [r.source, r.wrong]))
        check(`${c.key}:comparison-detects-empty-inventory`, !same(await data(c, r.wrong), references.get(c.key)) &&
          !same(look(c, pack.source), look(c, pack.wrong)), { source: look(c, pack.source), wrong: look(c, pack.wrong) })
        check(`${c.key}:displays-carry-contents`, c.shelf
          ? (c.block.startsWith('sensiblestorage:') ? displayed(c, pack.source).some(d => d.components['minecraft:custom_model_data']?.strings.filter(Boolean).length === 3) : true)
          : [plain, pack].every(v => displayed(c, v.source).filter(d => d.id).length === 3), { plain: look(c, plain.source), pack: look(c, pack.source) })

        // Paste overwrites a populated block of the same kind, so undo must restore distinct NBT.
        const beforeState = c.shelf ? state(c, false).replace('slot_1_occupied=false', 'slot_1_occupied=true') : state(c)
        await send(`setblock ${at(r.paste)} ${beforeState}{Items:[${item(c.shelf ? 1 : 7, c.shelf ? 'book' : 'emerald', 1, 'FAWE before')}],${c.shelf ? 'last_interacted_slot:1' : 'facing:"south",CustomName:"FAWE before"'}} strict`)
        const before = await data(c, r.paste)
        await select(r.source)
        await send(`tp ${name} ${r.source.x1 - 0.5} ${Y} ${c.z + 0.5}`)
        await chat('/copy', /blocks were copied|blocks affected/)
        await send(`tp ${name} ${r.paste.x1 - 0.5} ${Y} ${c.z + 0.5}`)
        await chat('/paste', /clipboard has been pasted/)
        await chat('/clearclipboard', /Clipboard cleared/)
        await moveView(c)
        await step(c, 'paste-keeps-data-and-views', [r.paste])
        if (c.shelf) await labelAt(c, r.paste, true, 'pasted-book-label')
        await chat('/undo', /Undid/)
        check(`${c.key}:paste-undo-restores-before-data`, same(await data(c, r.paste), before) &&
          await send(`execute if block ${at(r.paste)} ${beforeState}`) === 'Test passed', await data(c, r.paste))
        await chat('/redo', /Redid/)
        await step(c, 'paste-redo-restores-data-and-views', [r.paste])
        const sections = (await probe(send, 'statistics')).sections
        await apiCopy('native', r.source, r.apiNative)
        await apiCopy('bulk', r.source, r.apiBulk)
        check(`${c.key}:populated-blocks-stay-per-cell`, (await probe(send, 'statistics')).sections === sections)
        await step(c, 'api-copies-keep-data-and-views', [r.apiNative, r.apiBulk])
        await select(r.paste)
        await chat('/set air', /Operation completed/)
        await step(c, 'remove-clears-views-without-drops', [r.paste], { empty: ['paste'] })
        if (c.shelf) await labelAt(c, r.paste, false, 'removal-clears-label')
        await chat('/undo', /Undid/)
        await step(c, 'undo-restores-contents-and-views', [r.paste])
        await chat('/redo', /Redid/)
        await step(c, 'redo-clears-views-without-drops', [r.paste], { empty: ['paste'] })
        await chat('/undo', /Undid/)
        await step(c, 'restored-for-restart', [r.paste])

        // The replay row has exactly one FAWE change; nearby vanilla reference rows are excluded.
        await select(r.replay)
        await chat('/set air', /Operation completed/)
        await send(`tp ${name} ${r.replay.x1 + 0.5} ${Y} ${c.z + 0.5}`)
        const done = /only applied actions were marked|No results for that search/
        await chat('ledger rollback action:fawe-block range:3 after:1h', done)
        await moveView(c)
        await step(c, 'ledger-rollback-restores-data-and-views', [r.replay])
        await send(`tp ${name} ${r.replay.x1 + 0.5} ${Y} ${c.z + 0.5}`)
        await chat('ledger restore action:fawe-block range:3 after:1h', done)
        await moveView(c)
        await step(c, 'ledger-restore-clears-views', [r.replay], { empty: ['replay'] })
        await send(`tp ${name} ${r.replay.x1 + 0.5} ${Y} ${c.z + 0.5}`)
        await chat('ledger rollback action:fawe-block range:3 after:1h', done)
        await moveView(c)
        await step(c, 'ledger-restored-for-restart', [r.replay])
      }
      await send('save-all flush')
      for (const c of CASES) await send(`forceload remove 7296 ${c.z - 8} 7391 ${c.z + 15}`)
      await send(`tp ${name} 1024.5 80 1024.5`)
      await send('tp FaweShelfPack 1024.5 80 1024.5')
      let unloaded = false
      try {
        await rig.wait('storage chunks actually unloaded', async () => {
          const loaded = []
          for (const c of CASES) for (const b of Object.values(rows(c))) loaded.push((await probe(send, `block-info ${at(b)}`)).loaded)
          return (unloaded = loaded.every(value => !value))
        }, 45000, [client.entry, watcher.entry])
      } catch (error) { if (!/^Timeout waiting for/.test(error.message)) throw error }
      check('storage:chunks-actually-unloaded', unloaded)
      for (const c of CASES) {
        await send(`forceload add 7296 ${c.z - 8} 7391 ${c.z + 15}`)
        await moveView(c)
        const r = rows(c)
        await step(c, 'chunk-reload-keeps-data-and-views', [r.mirror, r.paste, r.apiNative, r.apiBulk, r.replay])
      }
    },
    async restart (context) {
      ;({ send, name, client, watcher } = context)
      for (const c of CASES) {
        const r = rows(c)
        await send(`forceload add 7296 ${c.z - 8} 7391 ${c.z + 15}`)
        await moveView(c)
        await step(c, 'restart-keeps-data-and-views', [r.mirror, r.paste, r.apiNative, r.apiBulk, r.replay])
        if (c.shelf) await labelAt(c, r.paste, true, 'restart-book-label')
      }
    }
  }
}
