'use strict'

// S11 content suite: inventories, an NBT-only change, multi-block structures, waterlogged and
// ticking blocks, schematics (a round trip, and a legacy file the data fixer must upgrade) and a
// dimension taller than the overworld. Expectations are computed here, before and after restart.

const fs = require('node:fs')
const path = require('node:path')
const zlib = require('node:zlib')
const { execFileSync } = require('node:child_process')
const { probe, differences, holdsItem } = require('./common')
const nbt = require('./nbt')

const AREA = 'forceload add 5196 4996 5251 5011'
const TALL = 's11:tall'
const CHEST = 'minecraft:chest[facing=north,type=single,waterlogged=false]'
const CASES = [
  { name: 'nbt-only', box: [5201, 80, 5004, 5201, 80, 5004], expect: () => CHEST },
  { name: 'inventories', box: [5205, 80, 5004, 5206, 80, 5004], expect: (x) => x === 5205 ? 'minecraft:barrel[facing=up,open=false]'
    : 'minecraft:trapped_chest[facing=north,type=single,waterlogged=false]' },
  { name: 'double-chest', box: [5210, 80, 5004, 5211, 80, 5004], expect: (x) => `minecraft:chest[facing=north,type=${x === 5210 ? 'left' : 'right'},waterlogged=false]` },
  { name: 'waterlogged', box: [5216, 81, 5004, 5216, 81, 5004], expect: () => 'minecraft:chest[facing=north,type=single,waterlogged=true]' },
  { name: 'water-source', box: [5221, 81, 5000, 5221, 81, 5000], expect: () => 'minecraft:water[level=0]' },
  { name: 'furnace', box: [5226, 80, 5004, 5226, 80, 5004], expect: () => ['minecraft:furnace[facing=north,lit=false]', 'minecraft:furnace[facing=north,lit=true]'] },
  // A door is outside the lifecycle allowlist: both halves skipped and reported, the target left empty.
  { name: 'door-skipped', box: [5230, 80, 5004, 5230, 81, 5004], expect: () => undefined },
  { name: 'door-source', box: [5230, 80, 5000, 5230, 81, 5000], expect: (x, y) => `minecraft:oak_door[facing=north,half=${y === 80 ? 'lower' : 'upper'},hinge=left,open=false,powered=false]` },
  { name: 'schematic-round-trip', box: [5234, 80, 5004, 5235, 80, 5004], expect: (x) => x === 5234 ? 'minecraft:furnace[facing=east,lit=false]' : 'minecraft:barrel[facing=up,open=false]' },
  { name: 'schematic-legacy', box: [5239, 80, 5004, 5239, 80, 5004], expect: () => CHEST },
  { name: 'tall-below', box: [0, -127, 0, 1, -126, 1], expect: () => 'minecraft:glass', dimension: TALL },
  { name: 'tall-above', box: [0, 380, 0, 1, 381, 1], expect: () => 'minecraft:glass', dimension: TALL }
]
// Stacks each block entity holds afterwards: [cell, slot, item, count].
const ITEMS = [
  [[5201, 80, 5004], 0, 'diamond', 3],
  [[5205, 80, 5004], 4, 'apple', 5],
  [[5206, 80, 5004], 0, 'gold_ingot', 7],
  [[5210, 80, 5004], 0, 'iron_ingot', 2],
  [[5216, 81, 5004], 0, 'stick', 1],
  [[5234, 80, 5004], 1, 'coal', 3],
  [[5235, 80, 5004], 0, 'stick', 9],
  // Stored as 1.20.4 `Count:3b`; only the data fixer turns it into `count`, else the stack reads as 1.
  [[5239, 80, 5004], 0, 'diamond', 3]
]

// A one-chest Sponge v3 schematic at DataVersion 3700 (1.20.4), with its items in the old format.
function legacySchematic () {
  const item = [['Slot', ['byte', 0]], ['id', ['string', 'minecraft:diamond']], ['Count', ['byte', 3]]]
  const blockEntity = [['Pos', ['intArray', [0, 0, 0]]], ['Id', ['string', 'minecraft:chest']],
    ['Data', ['compound', [['Items', ['list', ['compound', [item]]]]]]]]
  return zlib.gzipSync(nbt.root('', [['Schematic', ['compound', [
    ['Version', ['int', 3]], ['DataVersion', ['int', 3700]],
    ['Width', ['short', 1]], ['Height', ['short', 1]], ['Length', ['short', 1]],
    ['Offset', ['intArray', [0, 0, 0]]],
    ['Blocks', ['compound', [
      ['Palette', ['compound', [[CHEST, ['int', 0]]]]],
      ['Data', ['byteArray', [0]]],
      ['BlockEntities', ['list', ['compound', [blockEntity]]]]
    ]]]
  ]]]]))
}

module.exports = ({ rig, check, paced }) => {
  const verifyAll = async (send, suffix) => {
    for (const entry of CASES) {
      const wrong = await differences(send, entry)
      check(`${entry.name}${suffix}`, wrong.length === 0, wrong)
    }
    for (const [cell, slot, item, count] of ITEMS) {
      check(`items-${cell.join(',')}${suffix}`, await holdsItem(send, cell, slot, item, count), { slot, item, count })
    }
    check(`nbt-only-replaced${suffix}`, !/^Test passed/.test(await send('execute if data block 5201 80 5004 Items[{id:"minecraft:emerald"}]')), {})
  }

  return {
    // Fixtures that must exist before the world is created.
    async configureServer (serverDir) {
      const schematics = path.join(serverDir, 'config/worldedit/schematics')
      fs.mkdirSync(schematics, { recursive: true })
      fs.writeFileSync(path.join(schematics, 's11legacy.schem'), legacySchematic())
      const pack = path.join(serverDir, 'world/datapacks/s11')
      fs.mkdirSync(path.join(pack, 'data/s11/dimension_type'), { recursive: true })
      fs.mkdirSync(path.join(pack, 'data/s11/dimension'), { recursive: true })
      fs.writeFileSync(path.join(pack, 'pack.mcmeta'), JSON.stringify({ pack: { description: 'S11 taller dimension', min_format: 121, max_format: 121 } }))
      // The overworld's own type, from the pinned server jar, with only its height changed.
      const type = JSON.parse(execFileSync('unzip', ['-p', rig.manifest.minecraft.server.path, 'data/minecraft/dimension_type/overworld.json']))
      Object.assign(type, { min_y: -128, height: 512, logical_height: 512 })
      fs.writeFileSync(path.join(pack, 'data/s11/dimension_type/tall.json'), JSON.stringify(type))
      fs.writeFileSync(path.join(pack, 'data/s11/dimension/tall.json'), JSON.stringify({ type: TALL,
        generator: { type: 'minecraft:flat', settings: { layers: [{ block: 'minecraft:bedrock', height: 1 }], biome: 'minecraft:plains' } } }))
    },
    async contract (rig, send, name, { client, chatCommand: sendChatCommand }) {
      const chatCommand = paced(send, client, sendChatCommand)
      const select = async (x1, y1, z1, x2, y2, z2) => {
        await chatCommand(`/pos1 ${x1},${y1},${z1}`, /First position set|Position already set/)
        await chatCommand(`/pos2 ${x2},${y2},${z2}`, /Second position set|Position already set/)
      }
      const tp = (x, y, z) => send(`tp ${name} ${x + 0.5} ${y} ${z + 0.5}`)
      // Copy a source box standing on the empty cell west of it, paste standing the same way four south.
      const copyPaste = async (box, west, reply = /clipboard has been pasted/) => {
        await select(...box)
        await tp(west, box[1] === 81 ? 80 : box[1], box[2])
        await chatCommand('/copy', /blocks were copied|blocks affected/)
        await tp(west, box[1] === 81 ? 80 : box[1], box[2] + 4)
        await chatCommand('/paste', reply)
        await chatCommand('/clearclipboard', /Clipboard cleared/)
      }
      await send(AREA)
      await send('fill 5196 79 4996 5251 79 5011 minecraft:stone')
      await send('fill 5196 80 4996 5251 84 5011 minecraft:air')
      await probe(send, 'watch -5100 -128 -5100 5300 383 5300')

      // NBT only: the same chest state, other contents.
      await send('setblock 5201 80 5000 minecraft:chest[facing=north]{Items:[{Slot:0b,id:"minecraft:diamond",count:3}]}')
      await send('setblock 5201 80 5004 minecraft:chest[facing=north]{Items:[{Slot:0b,id:"minecraft:emerald",count:2}]}')
      await copyPaste([5201, 80, 5000, 5201, 80, 5000], 5200)

      await send('setblock 5205 80 5000 minecraft:barrel[facing=up]{Items:[{Slot:4b,id:"minecraft:apple",count:5}]}')
      await send('setblock 5206 80 5000 minecraft:trapped_chest[facing=north]{Items:[{Slot:0b,id:"minecraft:gold_ingot",count:7}]}')
      await copyPaste([5205, 80, 5000, 5206, 80, 5000], 5204)

      // A double chest: strict placement keeps both halves paired; the pair must survive the paste.
      await send('setblock 5210 80 5000 minecraft:chest[facing=north,type=left]{Items:[{Slot:0b,id:"minecraft:iron_ingot",count:2}]} strict')
      await send('setblock 5211 80 5000 minecraft:chest[facing=north,type=right] strict')
      check('double-chest-source', (await differences(send, { ...CASES[2], box: [5210, 80, 5000, 5211, 80, 5000] })).length === 0, {})
      await copyPaste([5210, 80, 5000, 5211, 80, 5000], 5209)

      // Waterlogged and water cells sit inside glass shells, so no water flows out of the fixture.
      for (const z of [5000, 5004]) await send(`fill 5215 80 ${z - 1} 5217 82 ${z + 1} minecraft:glass hollow`)
      await send('setblock 5216 81 5000 minecraft:chest[facing=north,waterlogged=true]{Items:[{Slot:0b,id:"minecraft:stick",count:1}]}')
      await copyPaste([5216, 81, 5000, 5216, 81, 5000], 5214)
      await send('fill 5220 80 4999 5222 82 5001 minecraft:glass hollow')
      await select(5221, 81, 5000, 5221, 81, 5000)
      await chatCommand('/set water', /Operation completed/)

      // A pasted furnace keeps ticking: its copy smelts.
      await send('setblock 5226 80 5000 minecraft:furnace[facing=north]{Items:[{Slot:0b,id:"minecraft:raw_iron",count:4},{Slot:1b,id:"minecraft:coal",count:2}]}')
      await copyPaste([5226, 80, 5000, 5226, 80, 5000], 5225)
      let smelted = false
      for (const end = Date.now() + 30000; !smelted && Date.now() < end;) {
        smelted = /^Test passed/.test(await send('execute if data block 5226 80 5004 Items[{Slot:2b,id:"minecraft:iron_ingot"}]'))
        if (!smelted) await new Promise(resolve => setTimeout(resolve, 500))
      }
      check('furnace-ticks', smelted, {})

      await send('setblock 5230 80 5000 minecraft:oak_door[facing=north,half=lower] strict')
      await send('setblock 5230 81 5000 minecraft:oak_door[facing=north,half=upper] strict')
      await copyPaste([5230, 80, 5000, 5230, 81, 5000], 5229, /Skipped 2 unsupported blocks/)

      // Schematics: saved and loaded back, and a legacy file the data fixer upgrades.
      await send('setblock 5234 80 5000 minecraft:furnace[facing=east]{Items:[{Slot:1b,id:"minecraft:coal",count:3}]}')
      await send('setblock 5235 80 5000 minecraft:barrel[facing=up]{Items:[{Slot:0b,id:"minecraft:stick",count:9}]}')
      await select(5234, 80, 5000, 5235, 80, 5000)
      await tp(5233, 80, 5000)
      await chatCommand('/copy', /blocks were copied|blocks affected/)
      await chatCommand('schem save s11round', /s11round\S* saved/)
      await chatCommand('/clearclipboard', /Clipboard cleared/)
      await chatCommand('schem load s11round', /s11round\S* loaded/)
      await tp(5233, 80, 5004)
      await chatCommand('/paste', /clipboard has been pasted/)
      await chatCommand('schem load s11legacy', /s11legacy\S* loaded/)
      await tp(5239, 80, 5004)
      await chatCommand('/paste', /clipboard has been pasted/)
      await chatCommand('/clearclipboard', /Clipboard cleared/)

      // A dimension from y=-128 to 383: edits below and above the overworld's range.
      await send(`execute in ${TALL} run forceload add 0 0 15 15`)
      await send(`execute in ${TALL} run fill 0 -1 0 3 -1 3 minecraft:stone`)
      await send(`execute in ${TALL} run tp ${name} 2.5 0 2.5`)
      await send('fawes10 receipts clear')
      await select(0, -127, 0, 1, -126, 1)
      const lowSelection = await probe(send, `selection ${name}`)
      check('tall-selection-bounds', lowSelection.volume === 8, lowSelection)
      await chatCommand('/set glass', /Operation completed/)
      await select(0, 380, 0, 1, 381, 1)
      await chatCommand('/set glass', /Operation completed/)
      // Every cell's receipt names the dimension it was written in, and nothing is left pending.
      const tallReceipts = JSON.parse(await send('fawes10 receipts')).receipts
      const statistics = await probe(send, 'statistics')
      // Two edits of one chunk each: two units, eight cells apiece.
      check('tall-receipts-in-tall', tallReceipts.length === 2 &&
        tallReceipts.reduce((sum, receipt) => sum + receipt.applied, 0) === 16 &&
        tallReceipts.every(receipt => receipt.dimension === TALL),
        { dimensions: [...new Set(tallReceipts.map(receipt => receipt.dimension))], count: tallReceipts.length,
          overworld: (await probe(send, 'dump 0 -64 0 1 -63 1')).runs, overworldTop: (await probe(send, 'dump 0 318 0 1 319 1')).runs })
      check('tall-nothing-pending', statistics.pending === 0, statistics)
      await send(`execute in minecraft:overworld run tp ${name} 5200.5 80 5008.5`)
      // The client leaves its world for a loading screen; later client requests need it back.
      const lastState = () => fs.readFileSync(client.events, 'utf8').trim().split('\n')
        .map(line => JSON.parse(line)).filter(event => event.state).at(-1)?.state
      const start = Date.now()
      await rig.wait('client back in its world', async () => Date.now() - start > 2000 && lastState() === 'world',
        30000, [client.entry])

      await verifyAll(send, '')
      const writes = await probe(send, 'writes')
      check('no-unguarded-writes', Object.keys(writes.counts).every(key => !/\.unguarded\.|offthread/.test(key)), writes)
      await send('save-all flush')
    },
    async restart (rig, send) {
      await send(AREA)
      await send(`execute in ${TALL} run forceload add 0 0 15 15`)
      await verifyAll(send, '-after-restart')
    }
  }
}
