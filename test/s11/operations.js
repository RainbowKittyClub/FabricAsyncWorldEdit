'use strict'

// S11 operations suite: generation, a brush used by the client, fast mode, side-effect modes,
// entities, biomes and regeneration. Expectations are computed here and checked before and after
// a restart; the write oracle allows unguarded writes only for entities, the declared path.

const fs = require('node:fs')
const { probe, differences, delta } = require('./common')

const AREA = 'forceload add 5096 4996 5151 5035'
const NATIVE_STAGES = ['snapshot', 'admitted', 'prepared', 'authorized', 'published', 'reconciled', 'recorded']
const STAND = 'type=minecraft:armor_stand,tag=s11stand'
// A filled radius-1 sphere (WorldEdit adds half a block): every offset with dx²+dy²+dz² <= 2.
const inSphere = (cx, cy, cz) => (x, y, z) => (x - cx) ** 2 + (y - cy) ** 2 + (z - cz) ** 2 <= 2
const floor = y => y === 79 ? 'minecraft:stone' : undefined

const CASES = [
  { name: 'sphere', box: [5101, 79, 5001, 5103, 81, 5003], expect: (x, y, z) => inSphere(5102, 80, 5002)(x, y, z) ? 'minecraft:stone' : floor(y) },
  { name: 'walls', box: [5106, 80, 5000, 5109, 82, 5003], expect: (x, y, z) => x === 5106 || x === 5109 || z === 5000 || z === 5003 ? 'minecraft:glass' : undefined },
  { name: 'cylinder', box: [5113, 80, 5001, 5115, 81, 5003], expect: () => 'minecraft:oak_planks' },
  // The brush traces from the player's eyes straight down to the floor block it centres on.
  { name: 'brush', box: [5119, 78, 5001, 5121, 80, 5003], expect: (x, y, z) => inSphere(5120, 79, 5002)(x, y, z) ? 'minecraft:glass' : floor(y) },
  { name: 'fast', box: [5126, 80, 5000, 5127, 80, 5001], expect: () => 'minecraft:glowstone' },
  // Without the update side effect a placed water source schedules no fluid tick and never flows.
  { name: 'update-off', box: [5101, 80, 5010, 5103, 80, 5012], expect: (x, y, z) => x === 5102 && z === 5011 ? 'minecraft:water[level=0]' : undefined },
  // A lone left half is invalid: kept as written without validation, corrected to single with it.
  { name: 'validation-off', box: [5130, 80, 5002, 5130, 80, 5002], expect: () => 'minecraft:chest[facing=north,type=left,waterlogged=false]' },
  { name: 'validation-on', box: [5132, 80, 5002, 5132, 80, 5002], expect: () => 'minecraft:chest[facing=north,type=single,waterlogged=false]' },
  { name: 'regen', box: [5140, -64, 5000, 5141, -61, 5001], expect: (x, y, z) => y > -64 ? undefined : x === 5140 && z === 5000 ? 'minecraft:glass' : 'minecraft:bedrock' }
]

// With OPAC loaded regeneration is refused, so the glass placed in the region stays; bedrock outside
// the allowlist was never touched.
const REFUSED = { regen: (x, y, z) => y > -64 || (x === 5140 && z === 5000) ? 'minecraft:glass' : 'minecraft:bedrock' }

module.exports = ({ rig, check, paced, claimed }) => {
  const cases = CASES.map(entry => claimed && REFUSED[entry.name] ? { ...entry, expect: REFUSED[entry.name] } : entry)
  const biome = async (send, x, y, z) => JSON.parse(await send(`fawes10 biome ${x} ${y} ${z}`)).biome
  const entities = async (send, x, y, z, extra = '') => {
    const answer = await send(`execute if entity @e[${STAND},x=${x},y=${y},z=${z},dx=0,dy=0,dz=0${extra}]`)
    const match = /^Test passed\. Count: (\d+)$/.exec(answer)
    return match ? Number(match[1]) : 0
  }
  const verifyAll = async (send, suffix) => {
    for (const entry of cases) {
      const wrong = await differences(send, entry)
      check(`${entry.name}${suffix}`, wrong.length === 0, wrong)
    }
    check(`biome-source${suffix}`, await biome(send, 5108, 80, 5020) === 'minecraft:desert', {})
    check(`biome-paste${suffix}`, await biome(send, 5108, 80, 5028) === 'minecraft:desert', {})
  }
  const gameTime = async send => Number(/(\d+)/.exec(await send('time query gametime'))[1])
  const ticks = async (send, client, count) => {
    const start = await gameTime(send)
    await rig.wait(`${count} ticks`, async () => await gameTime(send) >= start + count, 20000, [client.entry])
  }

  return {
    async contract (rig, send, name, { client, chatCommand: sendChatCommand }) {
      const chatCommand = paced(send, client, sendChatCommand)
      const select = async (x1, y1, z1, x2, y2, z2) => {
        await chatCommand(`/pos1 ${x1},${y1},${z1}`, /First position set|Position already set/)
        await chatCommand(`/pos2 ${x2},${y2},${z2}`, /Second position set|Position already set/)
      }
      const tp = (x, y, z, rotation = '') => send(`tp ${name} ${x + 0.5} ${y} ${z + 0.5}${rotation}`)
      const chatSince = offset => fs.readFileSync(client.entry.log, 'utf8').slice(offset)
      await send(AREA)
      await send('fill 5096 79 4996 5151 79 5035 minecraft:stone')
      await send('fill 5096 80 4996 5151 84 5035 minecraft:air')
      await probe(send, 'watch -5100 -64 -5100 5300 255 5300')

      // Generation, centred on the player.
      await tp(5102, 80, 5002)
      await chatCommand('/sphere stone 1', /blocks have been created/)
      await select(5106, 80, 5000, 5109, 82, 5003)
      await chatCommand('/walls glass', /blocks have been changed|Operation completed/)
      await tp(5114, 80, 5002)
      await chatCommand('/cyl oak_planks 1 2', /blocks have been created/)

      // A brush is a tool: the client's right-click edits as the player, through every native stage.
      await send(`item replace entity ${name} weapon.mainhand with minecraft:stick`)
      await chatCommand('/brush sphere glass 1', /Sphere brush shape equipped|Equipped brush sphere/)
      await tp(5120, 81, 5002, ' 0 90')
      await ticks(send, client, 5)
      const beforeBrush = await probe(send, 'coverage')
      await client.send({ action: 'use' })
      await rig.wait('brush applied', async () => (await differences(send, CASES[3])).length === 0, 30000, [client.entry])
      const brushed = delta(beforeBrush, await probe(send, 'coverage'))
      check('brush-route', Object.keys(brushed).length > 0 && Object.keys(brushed).every(key => key.startsWith('native.PLAYER.')) &&
        NATIVE_STAGES.every(stage => brushed[`native.PLAYER.${stage}`] > 0), brushed)
      await send(`item replace entity ${name} weapon.mainhand with minecraft:air`)

      // Fast mode applies the edit through the coordinator but keeps no history.
      const historyBefore = JSON.parse(await send(`fawes10 history ${name}`)).entries.length
      await chatCommand('/fast', /Fast mode enabled/)
      await select(5126, 80, 5000, 5127, 80, 5001)
      await chatCommand('/set glowstone', /Operation completed/)
      check('fast-no-history', JSON.parse(await send(`fawes10 history ${name}`)).entries.length === historyBefore, { historyBefore })
      await chatCommand('/fast', /Fast mode disabled/)

      // Side effects: without updates a water source stays put; with them it flows.
      await chatCommand('/perf update off', /set to|is already/)
      await select(5102, 80, 5011, 5102, 80, 5011)
      await chatCommand('/set water', /Operation completed/)
      await chatCommand('/perf update on', /set to|is already/)
      await select(5130, 80, 5011, 5130, 80, 5011)
      await chatCommand('/set water', /Operation completed/)
      await ticks(send, client, 40)
      check('update-on-flows', (await send('execute if block 5131 80 5011 minecraft:water')) === 'Test passed', {})
      // Updates are off by default in this WorldEdit; later cases run with the defaults.
      await chatCommand('/perf update off', /set to|is already/)
      // Validation corrects a state against its neighbours before the cell is submitted.
      await select(5130, 80, 5002, 5130, 80, 5002)
      await chatCommand('/set chest[facing=north,type=left]', /Operation completed/)
      await chatCommand('/perf validation on', /set to|is already/)
      await select(5132, 80, 5002, 5132, 80, 5002)
      await chatCommand('/set chest[facing=north,type=left]', /Operation completed/)
      await chatCommand('/perf validation off', /set to|is already/)

      // Entities: copied with the selection, removed again by undo, restored by redo.
      await send(`summon minecraft:armor_stand 5102.5 80 5020.5 {Tags:["s11stand"],NoGravity:1b,Invulnerable:1b,CustomName:"S11 Stand"}`)
      await select(5102, 80, 5020, 5102, 80, 5020)
      await tp(5101, 80, 5020)
      if (claimed) {
        // Entities have no per-cell footprint to authorize: the copy is refused and nothing moves.
        await chatCommand('/copy -e', /OPAC protected editing refuses unsupported operation: entity/)
        check('entity-copy-refused', await entities(send, 5102, 80, 5024) === 0 && await entities(send, 5102, 80, 5020) === 1, {})
      } else {
        await chatCommand('/copy -e', /blocks were copied|blocks affected/)
        await tp(5101, 80, 5024)
        await chatCommand('/paste -e', /clipboard has been pasted/)
        check('entity-pasted', await entities(send, 5102, 80, 5024, ',name="S11 Stand"') === 1 &&
          await entities(send, 5102, 80, 5020) === 1, {})
        await chatCommand('undo', /Undid/)
        check('entity-undo', await entities(send, 5102, 80, 5024) === 0 && await entities(send, 5102, 80, 5020) === 1, {})
        await chatCommand('redo', /Redid/)
        check('entity-redo', await entities(send, 5102, 80, 5024, ',name="S11 Stand"') === 1, {})
        await chatCommand('/clearclipboard', /Clipboard cleared/)
      }

      // Biomes: one whole 4x4x4 cell set, copied with -b and pasted with -b.
      await select(5108, 80, 5020, 5111, 83, 5023)
      await chatCommand('/setbiome minecraft:desert', /Biomes were changed/)
      await tp(5107, 80, 5020)
      await chatCommand('/copy -b', /blocks were copied|blocks affected/)
      await tp(5107, 80, 5028)
      await chatCommand('/paste -b', /clipboard has been pasted/)
      await chatCommand('/clearclipboard', /Clipboard cleared/)

      // Regeneration restores generated terrain; bedrock is outside the allowlist, so that cell is skipped.
      await send('fill 5140 -63 5000 5141 -61 5001 minecraft:glass')
      await send('setblock 5140 -64 5000 minecraft:glass')
      await select(5140, -64, 5000, 5141, -61, 5001)
      const regenOffset = fs.statSync(client.entry.log).size
      if (claimed) {
        await chatCommand('/regen', /OPAC protected editing refuses unsupported operation: regeneration/)
      } else {
        await chatCommand('/regen', /Region regenerated/)
        const skipped = /Skipped \d+ unsupported blocks?.*minecraft:bedrock at 5140,-64,5000/
        for (const end = Date.now() + 10000; !skipped.test(chatSince(regenOffset)) && Date.now() < end;) {
          await new Promise(resolve => setTimeout(resolve, 250))
        }
        check('regen-skip-reported', skipped.test(chatSince(regenOffset)),
          chatSince(regenOffset).split('\n').filter(line => /CHAT/.test(line)).slice(-3))
      }

      await verifyAll(send, '')
      const writes = await probe(send, 'writes')
      // Without claims the entity paste is the declared unguarded path; with them it is refused,
      // so no entity write happens at all.
      const entityWrites = Object.keys(writes.counts).some(key => key.startsWith('entity.unguarded'))
      check(claimed ? 'no-entity-writes' : 'only-entities-unguarded',
        Object.keys(writes.counts).every(key => !/^block(-entity)?\.unguarded|offthread/.test(key)) && entityWrites === !claimed, writes)
      await send('save-all flush')
    },
    async restart (rig, send) {
      await send(AREA)
      await verifyAll(send, '-after-restart')
    }
  }
}
